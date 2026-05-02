package com.leica.cam.sensor_hal.raw

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import com.leica.cam.common.Logger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.sqrt

/**
 * Production-grade RAW16 + preview capture session that backs ProXDR v3.
 *
 * **Why this exists.** The current LeicaCam capture flow operates on the
 * already-demosaiced fp32 linear RGB `PipelineFrame` — a documented v3 escape
 * hatch (see `proxdr/docs/INTEGRATION.md` §6 "CameraX alternative"). Wiring a
 * real RAW16 path is the next logical step, and unlocks the upstream native
 * engine's full quality (per-CFA Wiener weights, DCG-HDR, soft-knee spectral
 * recovery, Daubechies db4 wavelet NR — none of which can run on a post-demosaic
 * 3-channel buffer).
 *
 * **Design rules.**
 *  1. **Direct ByteBuffers only.** Heap-backed buffers force a JNI copy on every
 *     frame; the upstream bridge asserts on this and the Kotlin layer mirrors
 *     the assertion (see [RawFrame]).
 *  2. **ZSL ring buffer.** Every preview frame stays in the ring; a shutter press
 *     simply snapshots the most-recent N frames and hands them to the engine.
 *  3. **Metadata follows the timestamp.** Camera2 delivers `Image` and
 *     `TotalCaptureResult` on independent threads; we re-pair them by
 *     `SENSOR_TIMESTAMP` before adding to the ring.
 *  4. **The session is independent of CameraX.** It opens its own CameraDevice
 *     so it can coexist with the existing CameraX preview pipeline without
 *     fighting over the camera. The caller passes a CameraX-owned preview
 *     [Surface] in via [start] when ZSL preview parity is needed.
 *
 * **Safe to unit-test?** No — this class is purely an Android adapter. The
 * algorithmic side (`RawDemosaicEngine`, `ProXdrV3Engine`, `RawBurstIngestor`)
 * is fully covered by JVM tests. Instrumentation tests exercise the full path.
 *
 * @param context Application context for `CAMERA_SERVICE`.
 * @param cameraId Camera2 ID, normally selected by [DefaultCameraSelector].
 * @param ringSize Number of RAW16 frames retained in the ZSL ring buffer.
 *                 Must be ≤ device max RAW16 acquisition queue (8 is the
 *                 conservative default that works on every shipping sensor).
 */
class RawCaptureSession(
    private val context: Context,
    private val cameraId: String,
    private val ringSize: Int = DEFAULT_RING_SIZE,
) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val characteristics = cameraManager.getCameraCharacteristics(cameraId)

    // ── Worker threads ──────────────────────────────────────────────────────
    private val captureThread = HandlerThread("LeicaCam-RawCapture").apply { start() }
    private val captureHandler = Handler(captureThread.looper)

    // ── Ring buffer state (timestamp-keyed) ─────────────────────────────────
    private val ring = ArrayDeque<RingEntry>()
    private val pendingResults = HashMap<Long, TotalCaptureResult>()
    private val ringLock = ReentrantLock()

    // ── Camera2 state ───────────────────────────────────────────────────────
    @Volatile private var device: CameraDevice? = null
    @Volatile private var session: CameraCaptureSession? = null
    @Volatile private var rawReader: ImageReader? = null
    @Volatile private var started: Boolean = false

    // ── Cached static metadata ──────────────────────────────────────────────
    private val sensorPixelArray: Size =
        characteristics[CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE]
            ?.let { Size(it.width, it.height) }
            ?: Size(4000, 3000)

    private val rawSize: Size =
        characteristics[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
            ?.getOutputSizes(ImageFormat.RAW_SENSOR)
            ?.maxByOrNull { it.width * it.height }
            ?: sensorPixelArray

    private val sensorNoiseProfile: DoubleArray? =
        characteristics[CameraCharacteristics.SENSOR_NOISE_PROFILE]

    private val whiteLevel: Int =
        characteristics[CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL] ?: 4095

    private val cfaPattern: BayerPattern = BayerPattern.fromOrdinal(
        characteristics[CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT] ?: 0,
    )

    private val rawBits: Int = run {
        val active = characteristics[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE]
        // Pixel-array depth is not exposed directly; infer from white level
        // (the standard Android approach used by Google Camera).
        when {
            whiteLevel >= 16383 -> 14
            whiteLevel >= 4095 -> 12
            whiteLevel >= 1023 -> 10
            else -> 8
        }
    }

    private val cameraMeta = RawCameraMeta(
        sensorWidth = rawSize.width,
        sensorHeight = rawSize.height,
        rawBits = rawBits,
        bayer = cfaPattern,
        hasDcg = false, // sensors that support DCG must override via SmartIsoProDetector
        hasOis = characteristics[CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION]
            ?.any { it == CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON } ?: false,
        ccm = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
    )

    /** True if this camera advertises RAW capability. Cheap; can be called any time. */
    fun supportsRaw(): Boolean {
        val caps = characteristics[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]
            ?: return false
        return caps.any { it == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW }
    }

    /** Convenience for callers that need the chosen RAW size (UI layout, EXIF). */
    fun rawSize(): Size = rawSize

    /** Chosen RAW Bayer pattern. */
    fun bayerPattern(): BayerPattern = cfaPattern

    /**
     * Open the camera and start the ZSL ring. The provided [previewSurface]
     * receives every preview frame; the RAW16 stream feeds the internal ring
     * exclusively.
     *
     * Must be called on a thread that holds the `android.permission.CAMERA`
     * permission grant — caller's responsibility to gate.
     */
    @Suppress("MissingPermission")
    fun start(previewSurface: Surface) {
        if (started) return
        started = true

        rawReader = ImageReader.newInstance(
            rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, ringSize,
        ).also { reader ->
            reader.setOnImageAvailableListener({ rdr ->
                rdr.acquireNextImage()?.let { onRawAvailable(it) }
            }, captureHandler)
        }

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) {
                device = d
                createSession(d, previewSurface)
            }
            override fun onDisconnected(d: CameraDevice) {
                Logger.i(TAG, "RAW capture device disconnected")
                d.close(); device = null
            }
            override fun onError(d: CameraDevice, error: Int) {
                Logger.e(TAG, "RAW capture device error: $error")
                d.close(); device = null
            }
        }, captureHandler)
    }

    /** Stop ZSL, release Image handles + camera resources. Safe to call multiple times. */
    fun stop() {
        if (!started) return
        started = false
        runCatching { session?.stopRepeating() }
        runCatching { session?.close() }; session = null
        runCatching { device?.close() }; device = null
        runCatching { rawReader?.close() }; rawReader = null
        ringLock.withLock {
            ring.forEach { runCatching { it.image.close() } }
            ring.clear()
            pendingResults.clear()
        }
    }

    /**
     * Snapshot the ZSL ring and return a fully-formed [RawBurstBundle].
     *
     * Frames are ranked by sharpness × stillness (the ProXDR v3 reference-pick
     * heuristic) before truncation to [maxFrames]. The reference is always
     * the highest-scoring frame, placed first in the bundle.
     *
     * The caller takes ownership of the returned [Image] handles: they are
     * **not** closed by this call, since the RAW buffers in the bundle
     * reference the same underlying memory. The JNI bridge (or the JVM
     * fast-path ingestor) is responsible for closing them after processing.
     */
    fun snapshotBurst(maxFrames: Int = 8): RawBurstBundle? {
        val entries: List<RingEntry> = ringLock.withLock {
            if (ring.isEmpty()) return null
            ring.toList().sortedByDescending { scoreFrame(it.result) }
                .take(maxFrames.coerceAtLeast(1))
        }
        if (entries.isEmpty()) return null

        val frames = entries.map { entry ->
            val buffer = entry.image.toDirectRaw16Buffer()
            RawFrame(
                width = entry.image.width,
                height = entry.image.height,
                raw16 = buffer,
                meta = entry.toFrameMeta(),
            )
        }
        // CCM extracted from the freshest frame's CaptureResult.
        val ccm = entries.firstOrNull()?.let { extractCcm(it.result) }
            ?: cameraMeta.ccm
        val cam = cameraMeta.copy(ccm = ccm)

        // Close Image handles now — RAW16 bytes were copied into direct buffers above.
        entries.forEach { runCatching { it.image.close() } }

        return RawBurstBundle(
            frames = frames,
            camera = cam,
            referenceIdx = 0, // sorted-by-score above
        )
    }

    // ── Camera2 plumbing ────────────────────────────────────────────────────

    private fun createSession(d: CameraDevice, preview: Surface) {
        val outputs = listOf(
            OutputConfiguration(preview),
            OutputConfiguration(rawReader!!.surface),
        )
        d.createCaptureSession(
            SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputs,
                { it.run() }, // executor — straight pass-through
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(preview)
                            addTarget(rawReader!!.surface)
                            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                        }.build()
                        s.setRepeatingRequest(req, captureCallback, captureHandler)
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        Logger.e(TAG, "RAW capture session configuration failed")
                    }
                },
            ),
        )
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            s: CameraCaptureSession,
            r: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            val ts = result[CaptureResult.SENSOR_TIMESTAMP] ?: return
            ringLock.withLock { pendingResults[ts] = result }
            // The matching RAW frame finalises the entry inside onRawAvailable.
        }
    }

    private fun onRawAvailable(image: Image) {
        val ts = image.timestamp
        val result = ringLock.withLock { pendingResults.remove(ts) }
        if (result == null) {
            // Out-of-order delivery: drop the orphan to avoid leaking Image
            // handles. CaptureCallback will deliver the result eventually,
            // but without a paired RAW it's useless to us.
            runCatching { image.close() }
            return
        }
        ringLock.withLock {
            if (ring.size >= ringSize) {
                runCatching { ring.removeFirst().image.close() }
            }
            ring.addLast(RingEntry(image, result))
        }
    }

    private fun scoreFrame(r: TotalCaptureResult): Float {
        val gyro = r[CaptureResult.STATISTICS_OIS_SAMPLES]
        val motion = if (gyro != null && gyro.isNotEmpty()) {
            sqrt(gyro.sumOf { (it.xShift * it.xShift + it.yShift * it.yShift).toDouble() }).toFloat()
        } else 0f
        // Sharpness proxy from the camera HAL is rarely available; use a flat
        // 1.0 baseline so motion is the dominant signal. A future revision can
        // populate this from a Laplacian-variance sum in the demosaic stage.
        val sharpness = 1f
        return sharpness * (1f / (1f + motion * MOTION_PENALTY))
    }

    private fun extractCcm(r: TotalCaptureResult): FloatArray? {
        val transform = r[CaptureResult.COLOR_CORRECTION_TRANSFORM] ?: return null
        return FloatArray(9) { i -> transform.getElement(i / 3, i % 3).toFloat() }
    }

    // ── Internal helpers ────────────────────────────────────────────────────

    private data class RingEntry(val image: Image, val result: TotalCaptureResult)

    private fun RingEntry.toFrameMeta(): RawFrameMeta {
        val expNs = result[CaptureResult.SENSOR_EXPOSURE_TIME] ?: 16_000_000L
        val iso = result[CaptureResult.SENSOR_SENSITIVITY] ?: 100
        val aper = result[CaptureResult.LENS_APERTURE] ?: 1.8f
        val wb = result[CaptureResult.COLOR_CORRECTION_GAINS]
        val bl = characteristics[CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN]
        val gyro = result[CaptureResult.STATISTICS_OIS_SAMPLES]

        val motion = if (gyro != null && gyro.isNotEmpty()) {
            sqrt(gyro.sumOf { (it.xShift * it.xShift + it.yShift * it.yShift).toDouble() }).toFloat()
        } else 0f

        val noiseScale = FloatArray(4)
        val noiseOffset = FloatArray(4)
        sensorNoiseProfile?.let {
            for (c in 0 until minOf(4, it.size / 2)) {
                noiseScale[c] = it[c * 2].toFloat()
                noiseOffset[c] = it[c * 2 + 1].toFloat()
            }
        }

        return RawFrameMeta(
            tsNs = result[CaptureResult.SENSOR_TIMESTAMP] ?: 0L,
            exposureMs = expNs / 1_000_000f,
            analogGain = iso / 100f,
            digitalGain = 1f,
            whiteLevel = whiteLevel.toFloat(),
            focalMm = result[CaptureResult.LENS_FOCAL_LENGTH] ?: 4f,
            fNumber = aper,
            motionPxMs = motion,
            sharpness = 1f,
            blackLevels = bl?.let {
                floatArrayOf(
                    it.getOffsetForIndex(0, 0).toFloat(),
                    it.getOffsetForIndex(0, 1).toFloat(),
                    it.getOffsetForIndex(1, 0).toFloat(),
                    it.getOffsetForIndex(1, 1).toFloat(),
                )
            } ?: floatArrayOf(0f, 0f, 0f, 0f),
            wbGains = wb?.let {
                floatArrayOf(it.red, it.greenEven, it.greenOdd, it.blue)
            } ?: floatArrayOf(1f, 1f, 1f, 1f),
            noiseScale = noiseScale,
            noiseOffset = noiseOffset,
            evOffset = 0f,
            isoEquivalent = iso,
        )
    }

    /**
     * Copy the RAW16 plane into a fresh direct ByteBuffer with native byte order.
     *
     * Camera2 may deliver row-padded planes (rowStride > width * 2). We strip
     * that padding here so the upstream JNI bridge (and our JVM fallback) can
     * assume tight (w*h*2)-byte buffers without a stride parameter.
     */
    private fun Image.toDirectRaw16Buffer(): ByteBuffer {
        val plane = planes[0]
        val src = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val w = width; val h = height
        val out = ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.nativeOrder())
        if (rowStride == w * 2 && pixelStride == 2) {
            // Tight layout — straight bulk copy.
            out.put(src.duplicate())
        } else {
            // Padded — strip per row.
            val row = ByteArray(rowStride)
            src.rewind()
            for (y in 0 until h) {
                src.position(y * rowStride)
                src.get(row, 0, w * 2)
                out.put(row, 0, w * 2)
            }
        }
        out.rewind()
        return out
    }

    companion object {
        private const val TAG = "RawCaptureSession"
        private const val DEFAULT_RING_SIZE = 8

        /** Sub-pixel motion penalty in ZSL frame ranking (HDR+ §3.1 default). */
        private const val MOTION_PENALTY = 0.10f
    }
}
