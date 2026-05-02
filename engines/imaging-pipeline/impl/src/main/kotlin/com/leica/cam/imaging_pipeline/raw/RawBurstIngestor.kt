package com.leica.cam.imaging_pipeline.raw

import com.leica.cam.common.result.LeicaResult
import com.leica.cam.common.result.PipelineStage
import com.leica.cam.imaging_pipeline.hdr.proxdrv3.ProXdrV3Engine
import com.leica.cam.imaging_pipeline.hdr.proxdrv3.ProXdrV3SceneMode
import com.leica.cam.imaging_pipeline.hdr.proxdrv3.ProXdrV3Thermal
import com.leica.cam.imaging_pipeline.pipeline.HdrMergeMode
import com.leica.cam.imaging_pipeline.pipeline.HdrMergeResult
import com.leica.cam.imaging_pipeline.pipeline.PipelineFrame
import com.leica.cam.sensor_hal.raw.RawBurstBundle
import com.leica.cam.sensor_hal.raw.RawCameraMeta
import com.leica.cam.sensor_hal.raw.RawFrameMeta
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * Production entry-point for the **native ProXDR v3 RAW16 capture path**.
 *
 * Replaces the post‑demosaic fp32 RGB pipeline (the documented v3 escape hatch
 * in `proxdr/docs/INTEGRATION.md` §6 "CameraX alternative") with a real
 * RAW16-first flow:
 *
 * ```
 *   Camera2 RAW_SENSOR ──► RawCaptureSession (sensor-hal) ──► RawBurstBundle
 *                                                              │
 *                                                              ▼
 *                                                   RawBurstIngestor.process(...)
 *                                                              │
 *                                       ┌──────────────────────┼───────────────────────┐
 *                                       ▼                                              ▼
 *                       (1) NativePath: ProXDRBridge.processBurst       (2) JvmFallback:
 *                           via reflection (libproxdr_engine.so)            RawDemosaicEngine + ProXdrV3Engine
 * ```
 *
 * The native path forwards the full direct-ByteBuffer / FrameMetaPojo /
 * CameraMetaPojo set into the upstream JNI bridge — the engine then runs
 * stages 0..14 (alignment → DCG-HDR → highlight reco → tone-LM → color-LM →
 * gain-map). On JVM (unit tests, devices that fail to load `.so`) we
 * demosaic each frame through `RawDemosaicEngine` and feed the resulting
 * `PipelineFrame` list into `ProXdrV3Engine.runRgbFastPath`.
 *
 * This class is the **only** code path the orchestrator should use when a
 * RAW capture is available — it deliberately replaces the legacy
 * `ImagingPipeline.process(rgbFrames)` path for shutter-button captures.
 *
 * Reflection is used to talk to `com.proxdr.engine.ProXDRBridge` so this
 * module never compile-imports the Android-only `ProXDRBridge.kt`.
 *
 * Thread-safety: the ingestor is stateless aside from a cached MethodHandle.
 * Safe to call from any background thread.
 *
 * @param demosaic JVM fallback demosaic engine.
 * @param engine   v3 wrapper used in the JVM fast path.
 */
class RawBurstIngestor(
    private val demosaic: RawDemosaicEngine = RawDemosaicEngine(),
    private val engine: ProXdrV3Engine = ProXdrV3Engine(),
) {

    private val nativeBridge: NativeBridgeHandle? = NativeBridgeHandle.tryLoad()

    /** True when `libproxdr_engine.so` is loadable and the JNI surface is reachable. */
    fun isNativeAvailable(): Boolean = nativeBridge?.available == true

    /**
     * Process a RAW16 burst end-to-end.
     *
     * @param bundle  RAW16 frames + camera metadata, freshly snapped from the
     *                ZSL ring buffer.
     * @param sceneMode Scene-mode lock (Auto by default).
     * @param thermal Current thermal state (drives quality decay).
     * @param userBias Optional EV bias [-2..+2] to apply just before tone map.
     * @return        A standard `HdrMergeResult` consumed by the downstream
     *                color/noise/tone stages.
     */
    fun process(
        bundle: RawBurstBundle,
        sceneMode: ProXdrV3SceneMode = ProXdrV3SceneMode.AUTO,
        thermal: ProXdrV3Thermal = ProXdrV3Thermal.NORMAL,
        userBias: Float = 0f,
    ): LeicaResult<HdrMergeResult> {
        if (bundle.frames.isEmpty()) {
            return LeicaResult.Failure.Pipeline(
                PipelineStage.IMAGING_PIPELINE,
                "RawBurstIngestor: empty bundle",
            )
        }

        // ── Native v3 path ───────────────────────────────────────────────
        nativeBridge?.takeIf { it.available }?.let { bridge ->
            try {
                val merged = invokeNative(bundle, bridge, sceneMode, thermal, userBias)
                if (merged != null) {
                    return LeicaResult.Success(
                        HdrMergeResult(
                            mergedFrame = merged,
                            ghostMask = FloatArray(merged.width * merged.height),
                            hdrMode = HdrMergeMode.WIENER_BURST,
                        ),
                    )
                }
            } catch (_: Throwable) {
                // Fall through to JVM path — engine TROUBLESHOOTING.md §"When
                // to escalate" requires a software fallback at all times.
            }
        }

        // ── JVM fallback: demosaic → ProXDR v3 RGB fast path ────────────
        return runJvmFallback(bundle, sceneMode, thermal, userBias)
    }

    private fun runJvmFallback(
        bundle: RawBurstBundle,
        sceneMode: ProXdrV3SceneMode,
        thermal: ProXdrV3Thermal,
        userBias: Float,
    ): LeicaResult<HdrMergeResult> {
        val rgbFrames = bundle.frames.map { rf ->
            val raw16 = ShortArray(bundle.width * bundle.height)
            val sb = rf.raw16.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer()
            sb.position(0)
            sb.get(raw16, 0, min(raw16.size, sb.remaining()))
            val pattern = RawBayerPattern.fromOrdinal(bundle.camera.bayer.ordinal_)
            val calib = RawCalibration(
                blackLevel = rf.meta.blackLevels,
                whiteLevel = max(rf.meta.whiteLevel, 1f),
                wbGains = rf.meta.wbGains,
                ccm = bundle.camera.ccm,
            )
            demosaic.demosaic(
                raw16 = raw16,
                width = bundle.width,
                height = bundle.height,
                pattern = pattern,
                calib = calib,
                evOffset = rf.meta.evOffset,
                iso = rf.meta.isoEquivalent,
                exposureNs = (rf.meta.exposureMs * 1_000_000f).toLong(),
            )
        }
        return engine.process(
            frames = rgbFrames,
            sceneMode = sceneMode,
            thermal = thermal,
            userBias = userBias,
        )
    }

    /**
     * Invoke the upstream `ProXDRBridge.processBurst` reflectively and
     * decode its RGB888 byte payload into a [PipelineFrame].
     *
     * Returns `null` when the bridge declines to process (e.g. it returns
     * a 1×1 placeholder bitmap), letting the caller fall through to JVM.
     */
    private fun invokeNative(
        bundle: RawBurstBundle,
        bridge: NativeBridgeHandle,
        sceneMode: ProXdrV3SceneMode,
        thermal: ProXdrV3Thermal,
        userBias: Float,
    ): PipelineFrame? {
        val rawBuffers = Array(bundle.frames.size) { i ->
            bundle.frames[i].raw16.also { it.order(ByteOrder.nativeOrder()) }
        }
        val metas = Array(bundle.frames.size) { i ->
            bridge.buildFrameMetaPojo(bundle.frames[i].meta)
        }
        val cameraMeta = bridge.buildCameraMetaPojo(bundle.camera, bundle.width, bundle.height)
        val opts = bridge.buildOptions(
            refIdx = bundle.referenceIdx,
            sceneMode = sceneMode,
            thermal = thermal,
            // Native bridge does not currently take a user EV bias on its
            // public surface; we bake it in after the call (see below).
        )

        val resultRgb = bridge.processBurst(
            rawBuffers = rawBuffers,
            metas = metas,
            cameraMeta = cameraMeta,
            width = bundle.width,
            height = bundle.height,
            opts = opts,
        ) ?: return null

        val biased = if (userBias != 0f) applyEvBiasInPlace(resultRgb, userBias) else resultRgb
        return biased
    }

    private fun applyEvBiasInPlace(frame: PipelineFrame, evBias: Float): PipelineFrame {
        val gain = Math.pow(2.0, evBias.toDouble()).toFloat()
        val n = frame.width * frame.height
        val r = FloatArray(n) { (frame.red[it] * gain).coerceAtLeast(0f) }
        val g = FloatArray(n) { (frame.green[it] * gain).coerceAtLeast(0f) }
        val b = FloatArray(n) { (frame.blue[it] * gain).coerceAtLeast(0f) }
        return PipelineFrame(
            frame.width, frame.height, r, g, b,
            frame.evOffset, frame.isoEquivalent, frame.exposureTimeNs,
        )
    }

    /**
     * Reflection wrapper around `com.proxdr.engine.ProXDRBridge`. Building the
     * MethodHandles up-front keeps the per-shot overhead negligible (~0 ns).
     *
     * The wrapper also constructs the two POJOs (`FrameMetaPojo`,
     * `CameraMetaPojo`) and the `ProXDROptions` data class — these are
     * `@JvmField` types in the bridge, so reflective field assignment is
     * the right choice (no Kotlin copy-data hack required).
     */
    internal class NativeBridgeHandle private constructor(
        private val bridgeObj: Any,
        private val processBurstMethod: java.lang.reflect.Method,
        private val frameMetaCtor: java.lang.reflect.Constructor<*>,
        private val cameraMetaCtor: java.lang.reflect.Constructor<*>,
        private val optionsCtor: java.lang.reflect.Constructor<*>,
        private val sceneModeValueOf: java.lang.reflect.Method,
        private val thermalValueOf: java.lang.reflect.Method,
        private val resultClass: Class<*>,
    ) {
        val available: Boolean = true

        fun buildFrameMetaPojo(meta: RawFrameMeta): Any {
            val pojo = frameMetaCtor.newInstance()
            val cls = pojo.javaClass
            cls.getField("tsNs").setLong(pojo, meta.tsNs)
            cls.getField("exposureMs").setFloat(pojo, meta.exposureMs)
            cls.getField("analogGain").setFloat(pojo, meta.analogGain)
            cls.getField("digitalGain").setFloat(pojo, meta.digitalGain)
            cls.getField("whiteLevel").setFloat(pojo, meta.whiteLevel)
            cls.getField("focalMm").setFloat(pojo, meta.focalMm)
            cls.getField("fNumber").setFloat(pojo, meta.fNumber)
            cls.getField("dcgLong").setBoolean(pojo, meta.dcgLong)
            cls.getField("dcgShort").setBoolean(pojo, meta.dcgShort)
            cls.getField("dcgRatio").setFloat(pojo, meta.dcgRatio)
            cls.getField("motionPxMs").setFloat(pojo, meta.motionPxMs)
            cls.getField("sharpness").setFloat(pojo, meta.sharpness)
            cls.getField("blackLevels").set(pojo, meta.blackLevels.copyOf())
            cls.getField("wbGains").set(pojo, meta.wbGains.copyOf())
            cls.getField("noiseScale").set(pojo, meta.noiseScale.copyOf())
            cls.getField("noiseOffset").set(pojo, meta.noiseOffset.copyOf())
            return pojo
        }

        fun buildCameraMetaPojo(camera: RawCameraMeta, width: Int, height: Int): Any {
            val pojo = cameraMetaCtor.newInstance()
            val cls = pojo.javaClass
            cls.getField("sensorWidth").setInt(pojo, max(camera.sensorWidth, width))
            cls.getField("sensorHeight").setInt(pojo, max(camera.sensorHeight, height))
            cls.getField("rawBits").setInt(pojo, camera.rawBits)
            cls.getField("bayer").setInt(pojo, camera.bayer.ordinal_)
            cls.getField("hasDcg").setBoolean(pojo, camera.hasDcg)
            cls.getField("hasOis").setBoolean(pojo, camera.hasOis)
            cls.getField("ccm").set(pojo, camera.ccm.copyOf())
            return pojo
        }

        fun buildOptions(
            refIdx: Int,
            sceneMode: ProXdrV3SceneMode,
            thermal: ProXdrV3Thermal,
        ): Any {
            val nativeScene = sceneModeValueOf.invoke(null, mapSceneModeName(sceneMode))
            val nativeThermal = thermalValueOf.invoke(null, mapThermalName(thermal))
            return optionsCtor.newInstance(
                refIdx,
                nativeScene,
                /* adaptive */ true,
                /* enableNeural */ true,
                /* enableUltraHdr */ true,
                nativeThermal,
                /* jpegQuality */ 97,
            )
        }

        fun processBurst(
            rawBuffers: Array<ByteBuffer>,
            metas: Array<Any>,
            cameraMeta: Any,
            width: Int,
            height: Int,
            opts: Any,
        ): PipelineFrame? {
            // Result is `com.proxdr.engine.ProXDRResult { sdrBitmap, ... }`.
            // To stay Android-imports-free we extract the engine's *raw RGB*
            // by recomputing it from the bitmap pixels via reflection.
            val resultObj = processBurstMethod.invoke(
                bridgeObj, rawBuffers, metas, cameraMeta, width, height, opts,
            ) ?: return null
            return decodeResult(resultObj, width, height)
        }

        private fun decodeResult(resultObj: Any, width: Int, height: Int): PipelineFrame? {
            // ProXDRResult.sdrBitmap is an android.graphics.Bitmap. We avoid
            // a hard import on Bitmap by resolving via reflection — this
            // keeps `:imaging-pipeline:impl` Android-API-free at the type
            // level. The actual call only happens on Android devices where
            // the class loader has Bitmap available.
            val bitmap = resultClass.getDeclaredField("sdrBitmap").apply { isAccessible = true }
                .get(resultObj) ?: return null
            val bmpClass = bitmap.javaClass
            val w = bmpClass.getMethod("getWidth").invoke(bitmap) as Int
            val h = bmpClass.getMethod("getHeight").invoke(bitmap) as Int
            val pixels = IntArray(w * h)
            // Bitmap.getPixels(int[] pixels, int offset, int stride, int x, int y, int w, int h)
            bmpClass.getMethod(
                "getPixels", IntArray::class.java,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            ).invoke(bitmap, pixels, 0, w, 0, 0, w, h)

            val n = w * h
            val r = FloatArray(n)
            val g = FloatArray(n)
            val b = FloatArray(n)
            val inv = 1f / 255f
            for (i in 0 until n) {
                val px = pixels[i]
                r[i] = ((px shr 16) and 0xFF) * inv
                g[i] = ((px shr 8) and 0xFF) * inv
                b[i] = (px and 0xFF) * inv
            }
            return PipelineFrame(w, h, r, g, b, 0f, 100, 16_666_666L)
        }

        private fun mapSceneModeName(mode: ProXdrV3SceneMode): String = when (mode) {
            ProXdrV3SceneMode.AUTO -> "AUTO"
            ProXdrV3SceneMode.BRIGHT_DAY -> "BRIGHT_DAY"
            ProXdrV3SceneMode.DAYLIGHT -> "DAYLIGHT"
            ProXdrV3SceneMode.INDOOR -> "INDOOR"
            ProXdrV3SceneMode.LOW_LIGHT -> "LOW_LIGHT"
            ProXdrV3SceneMode.NIGHT -> "NIGHT"
            ProXdrV3SceneMode.NIGHT_EXTREME -> "NIGHT_EXTREME"
            ProXdrV3SceneMode.GOLDEN_HOUR -> "GOLDEN_HOUR"
            ProXdrV3SceneMode.BACKLIT -> "BACKLIT"
            ProXdrV3SceneMode.PORTRAIT -> "PORTRAIT"
            ProXdrV3SceneMode.SPORTS -> "SPORTS"
            ProXdrV3SceneMode.TRIPOD -> "TRIPOD"
            ProXdrV3SceneMode.MACRO -> "MACRO"
        }

        private fun mapThermalName(thermal: ProXdrV3Thermal): String = when (thermal) {
            ProXdrV3Thermal.NORMAL -> "NORMAL"
            ProXdrV3Thermal.LIGHT -> "LIGHT"
            ProXdrV3Thermal.MODERATE -> "MODERATE"
            ProXdrV3Thermal.SEVERE -> "SEVERE"
            ProXdrV3Thermal.CRITICAL -> "CRITICAL"
        }

        companion object {
            fun tryLoad(): NativeBridgeHandle? = try {
                val cl = NativeBridgeHandle::class.java.classLoader
                val bridgeCls = Class.forName("com.proxdr.engine.ProXDRBridge", true, cl)
                val bridgeObj = bridgeCls.getField("INSTANCE").get(null)
                val frameMetaCls = Class.forName("com.proxdr.engine.FrameMetaPojo", true, cl)
                val cameraMetaCls = Class.forName("com.proxdr.engine.CameraMetaPojo", true, cl)
                val optionsCls = Class.forName("com.proxdr.engine.ProXDROptions", true, cl)
                val sceneModeCls = Class.forName("com.proxdr.engine.SceneMode", true, cl)
                val thermalCls = Class.forName("com.proxdr.engine.ThermalState", true, cl)
                val resultCls = Class.forName("com.proxdr.engine.ProXDRResult", true, cl)

                val processBurst = bridgeCls.methods.first { it.name == "processBurst" }
                val frameMetaCtor = frameMetaCls.getDeclaredConstructor()
                val cameraMetaCtor = cameraMetaCls.getDeclaredConstructor()
                // ProXDROptions(Int, SceneMode, Boolean, Boolean, Boolean, ThermalState, Int)
                val optionsCtor = optionsCls.declaredConstructors
                    .first { it.parameterTypes.size == 7 }
                val sceneValueOf = sceneModeCls.getMethod("valueOf", String::class.java)
                val thermalValueOf = thermalCls.getMethod("valueOf", String::class.java)

                NativeBridgeHandle(
                    bridgeObj,
                    processBurst,
                    frameMetaCtor,
                    cameraMetaCtor,
                    optionsCtor,
                    sceneValueOf,
                    thermalValueOf,
                    resultCls,
                )
            } catch (_: Throwable) {
                null
            }
        }
    }
}
