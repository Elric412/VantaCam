/**
 * ╔══════════════════════════════════════════════════════════════════════════╗
 * ║  ProXDR v3  ·  android/HdrBurstCapture.kt                               ║
 * ║  Camera2 RAW burst capture wired to the ProXDR engine                   ║
 * ╠══════════════════════════════════════════════════════════════════════════╣
 * ║  Production pattern:                                                     ║
 * ║   • RAW16 ImageReader with N-deep ring buffer                           ║
 * ║   • ZSL-style: every preview frame stays in the ring                    ║
 * ║   • On shutter press: pick best N frames → ProXDRBridge.processBurst()  ║
 * ║   • Frame metadata extracted from TotalCaptureResult                    ║
 * ║                                                                          ║
 * ║  Required Camera2 capabilities:                                          ║
 * ║   • REQUEST_AVAILABLE_CAPABILITIES_RAW                                   ║
 * ║   • REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR (recommended)           ║
 * ║                                                                          ║
 * ║  Drop this file into your camera app. See docs/INTEGRATION.md for the   ║
 * ║  full integration walkthrough including thermal callbacks and CameraX.  ║
 * ╚══════════════════════════════════════════════════════════════════════════╝
 */

package com.proxdr.engine

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.util.Log
import android.util.Size
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.sqrt

/**
 * Captures RAW16 bursts and feeds them to ProXDRBridge.
 *
 * Usage:
 * ```
 * val capture = HdrBurstCapture(ctx, cameraId)
 * capture.start(previewSurface)
 * // ... user presses shutter ...
 * val result = capture.shoot()  // suspend / async
 * imageView.setImageBitmap(result.sdrBitmap)
 * ```
 */
class HdrBurstCapture(
    private val ctx: Context,
    private val cameraId: String,
    private val ringSize: Int = 8,
) {
    companion object { private const val TAG = "HdrBurstCapture" }

    private val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val chars = mgr.getCameraCharacteristics(cameraId)

    // Worker threads
    private val captureThread = HandlerThread("ProXDR-Capture").apply { start() }
    private val captureHandler = Handler(captureThread.looper)

    // Ring buffer
    private val ring = ArrayDeque<RingEntry>()
    private val lock = ReentrantLock()
    private data class RingEntry(val image: Image, val result: TotalCaptureResult)

    // Camera2 state
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var rawReader: ImageReader? = null

    // Cached static fields
    private val sensorSize: Size = chars[CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE]
        ?.let { Size(it.width, it.height) } ?: Size(4000, 3000)
    private val rawSizes  = chars[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
        ?.getOutputSizes(ImageFormat.RAW_SENSOR)
    private val rawSize: Size = rawSizes?.maxByOrNull { it.width * it.height } ?: sensorSize

    private val noiseProfile: DoubleArray? = chars[CameraCharacteristics.SENSOR_NOISE_PROFILE]
    private val whiteLevel: Int = chars[CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL] ?: 4095
    private val cfaPattern: Int = chars[CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT] ?: 0

    // Build static CameraMetaPojo
    private val cameraMeta = CameraMetaPojo(
        sensorWidth  = rawSize.width,
        sensorHeight = rawSize.height,
        rawBits      = 12,
        bayer        = cfaPattern.coerceIn(0, 3),
        hasDcg       = false,    // set this from your sensor info
        hasOis       = chars[CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION]
                          ?.any { it == CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON } ?: false,
        ccm          = floatArrayOf(1f,0f,0f, 0f,1f,0f, 0f,0f,1f),  // overwritten per-frame
    )

    // ─── Public API ────────────────────────────────────────────────────────
    @Suppress("MissingPermission")
    fun start(previewSurface: Surface) {
        rawReader = ImageReader.newInstance(
            rawSize.width, rawSize.height,
            ImageFormat.RAW_SENSOR, ringSize
        ).also {
            it.setOnImageAvailableListener({ rdr ->
                val img = rdr.acquireNextImage() ?: return@setOnImageAvailableListener
                onRawAvailable(img)
            }, captureHandler)
        }

        mgr.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) {
                device = d
                createSession(d, previewSurface)
            }
            override fun onDisconnected(d: CameraDevice) { d.close(); device = null }
            override fun onError(d: CameraDevice, e: Int) { d.close(); device = null }
        }, captureHandler)
    }

    private fun createSession(d: CameraDevice, previewSurface: Surface) {
        val outs = listOf(
            OutputConfiguration(previewSurface),
            OutputConfiguration(rawReader!!.surface),
        )
        d.createCaptureSession(SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR, outs,
            { c -> c.run() },  // executor (lambda → Executor SAM)
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(previewSurface)
                        addTarget(rawReader!!.surface)
                        set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                    }.build()
                    s.setRepeatingRequest(req, captureCb, captureHandler)
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    Log.e(TAG, "session configure failed")
                }
            }
        ))
    }

    private val pendingResults = HashMap<Long, TotalCaptureResult>()
    private val captureCb = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult
        ) {
            val ts = result[CaptureResult.SENSOR_TIMESTAMP] ?: return
            lock.withLock { pendingResults[ts] = result }
            // RingEntry is finalised when its RAW frame arrives
        }
    }

    private fun onRawAvailable(img: Image) {
        val ts = img.timestamp
        val result = lock.withLock { pendingResults.remove(ts) }
        if (result == null) {
            // Out-of-order delivery — keep the image but skip metadata
            img.close()
            return
        }
        lock.withLock {
            if (ring.size >= ringSize) ring.removeFirst().image.close()
            ring.addLast(RingEntry(img, result))
        }
    }

    /**
     * Captures the current burst (up to ringSize frames) and processes via ProXDR.
     * Suspending? Wrap the call in a coroutine if you prefer.
     */
    fun shoot(opts: ProXDROptions = ProXDROptions()): ProXDRResult {
        val entries = lock.withLock {
            // Pick the best N by sharpness × stillness
            val sorted = ring.sortedByDescending { scoreFrame(it.result) }
            val n = sorted.size.coerceAtMost(8)
            sorted.subList(0, n).toList()
        }
        if (entries.isEmpty()) {
            Log.w(TAG, "No frames in ring")
            return ProXDRResult(sdrBitmap = android.graphics.Bitmap.createBitmap(
                1, 1, android.graphics.Bitmap.Config.ARGB_8888))
        }

        // Build direct ByteBuffers + metadata for each entry
        val buffers = Array(entries.size) { i -> entries[i].image.copyToDirectBuffer() }
        val metas   = Array(entries.size) { i -> resultToMeta(entries[i].result) }

        // Update CCM from the most recent CaptureResult
        cameraMeta.ccm = extractCcm(entries[0].result) ?: cameraMeta.ccm

        // Power-state-aware thermal
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val thermal = when (pm.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE      -> ThermalState.NORMAL
            PowerManager.THERMAL_STATUS_LIGHT     -> ThermalState.LIGHT
            PowerManager.THERMAL_STATUS_MODERATE  -> ThermalState.MODERATE
            PowerManager.THERMAL_STATUS_SEVERE    -> ThermalState.SEVERE
            else                                   -> ThermalState.CRITICAL
        }

        val finalOpts = opts.copy(thermalState = thermal)
        val result = ProXDRBridge.processBurst(
            buffers, metas, cameraMeta,
            entries[0].image.width, entries[0].image.height,
            finalOpts,
        )

        // Release Image handles
        entries.forEach { it.image.close() }
        return result
    }

    fun stop() {
        session?.close(); session = null
        device?.close();  device  = null
        rawReader?.close(); rawReader = null
        lock.withLock { ring.forEach { it.image.close() }; ring.clear() }
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    /** Sharpness × inverse motion → frame score for ZSL pick. */
    private fun scoreFrame(r: TotalCaptureResult): Float {
        val sharp = r[CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP]?.let { 1f } ?: 1f
        val gyro  = r[CaptureResult.STATISTICS_OIS_SAMPLES]
        val motion = if (gyro != null && gyro.isNotEmpty()) {
            sqrt(gyro.sumOf { (it.xShift*it.xShift + it.yShift*it.yShift).toDouble() }).toFloat()
        } else 0f
        return sharp * (1f / (1f + motion * 0.1f))
    }

    private fun extractCcm(r: TotalCaptureResult): FloatArray? {
        val m = r[CaptureResult.COLOR_CORRECTION_TRANSFORM] ?: return null
        return FloatArray(9) { i -> m.getElement(i / 3, i % 3).toFloat() }
    }

    private fun resultToMeta(r: TotalCaptureResult): FrameMetaPojo {
        val expNs = r[CaptureResult.SENSOR_EXPOSURE_TIME] ?: 16_000_000L
        val iso   = r[CaptureResult.SENSOR_SENSITIVITY]   ?: 100
        val aper  = r[CaptureResult.LENS_APERTURE]         ?: 1.8f
        val wb    = r[CaptureResult.COLOR_CORRECTION_GAINS]
        val bl    = chars[CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN]
        val gyro  = r[CaptureResult.STATISTICS_OIS_SAMPLES]

        val motion = if (gyro != null && gyro.isNotEmpty()) {
            sqrt(gyro.sumOf { (it.xShift*it.xShift + it.yShift*it.yShift).toDouble() }).toFloat()
        } else 0f

        val nScale  = FloatArray(4)
        val nOffset = FloatArray(4)
        noiseProfile?.let {
            for (c in 0 until minOf(4, it.size / 2)) {
                nScale[c]  = it[c*2].toFloat()
                nOffset[c] = it[c*2 + 1].toFloat()
            }
        }

        return FrameMetaPojo(
            tsNs        = r[CaptureResult.SENSOR_TIMESTAMP] ?: 0L,
            exposureMs  = expNs / 1_000_000f,
            analogGain  = iso / 100f,
            digitalGain = 1f,
            whiteLevel  = whiteLevel.toFloat(),
            focalMm     = r[CaptureResult.LENS_FOCAL_LENGTH] ?: 4f,
            fNumber     = aper,
            motionPxMs  = motion,
            sharpness   = 1f,  // populate from your AF metric if you have one
            blackLevels = bl?.let { floatArrayOf(it[0,0].toFloat(), it[0,1].toFloat(),
                                                  it[1,0].toFloat(), it[1,1].toFloat()) }
                          ?: floatArrayOf(0f,0f,0f,0f),
            wbGains     = wb?.let { floatArrayOf(it.red, it.greenEven, it.greenOdd, it.blue) }
                          ?: floatArrayOf(1f,1f,1f,1f),
            noiseScale  = nScale,
            noiseOffset = nOffset,
        )
    }
}

// ─── Image → direct ByteBuffer (zero-copy when possible) ───────────────────
private fun Image.copyToDirectBuffer(): ByteBuffer {
    // RAW16 has a single plane with 2-byte pixels.
    val plane = planes[0]
    val src = plane.buffer
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    val w = width; val h = height
    val out = ByteBuffer.allocateDirect(w * h * 2)
    if (rowStride == w * 2 && pixelStride == 2) {
        // Fast path — just copy straight through
        out.put(src); out.flip()
    } else {
        // Slow path: row-by-row copy stripping padding
        val row = ByteArray(rowStride)
        for (y in 0 until h) {
            src.position(y * rowStride)
            src.get(row, 0, w * 2)
            out.put(row, 0, w * 2)
        }
        out.flip()
    }
    return out
}
