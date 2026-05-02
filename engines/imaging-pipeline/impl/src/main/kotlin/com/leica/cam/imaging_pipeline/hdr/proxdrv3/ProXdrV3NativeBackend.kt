package com.leica.cam.imaging_pipeline.hdr.proxdrv3

import com.leica.cam.imaging_pipeline.pipeline.PipelineFrame

/**
 * Thin abstraction over the JNI `com.proxdr.engine.ProXDRBridge` so the
 * Kotlin façade ([ProXdrV3Engine]) can be unit-tested without loading the
 * native library.
 *
 * On Android with a successfully loaded `libproxdr_engine.so`, the
 * production implementation [Native] forwards into `ProXDRBridge`. In JVM
 * unit-test environments, [tryLoad] falls back to [Unavailable] which
 * keeps the wrapper running on the Kotlin RGB fast path.
 *
 * Note: the existing LeicaCam `PipelineFrame` carries already-demosaiced
 * fp32 linear RGB. `ProXDRBridge.processBurst` expects RAW16 bytes per
 * frame. Until a RAW capture path is wired into the LeicaCam pipeline,
 * the native backend reports `isAvailable = true` but [processBurst]
 * always raises `UnsupportedOperationException`, which the façade catches
 * and falls through to the Kotlin path — a documented v3 escape hatch
 * (see `proxdr/docs/INTEGRATION.md` §6 "CameraX alternative").
 */
internal interface ProXdrV3NativeBackend {
    val isAvailable: Boolean

    fun processBurst(
        frames: List<PipelineFrame>,
        cfg: ProXdrV3Tuning,
        sceneMode: ProXdrV3SceneMode,
        thermal: ProXdrV3Thermal,
        userBias: Float,
    ): PipelineFrame

    object Unavailable : ProXdrV3NativeBackend {
        override val isAvailable: Boolean = false
        override fun processBurst(
            frames: List<PipelineFrame>,
            cfg: ProXdrV3Tuning,
            sceneMode: ProXdrV3SceneMode,
            thermal: ProXdrV3Thermal,
            userBias: Float,
        ): PipelineFrame =
            throw UnsupportedOperationException("ProXDR v3 native backend not loaded")
    }

    /**
     * Production implementation. Resolves the upstream `ProXDRBridge` via
     * reflection so we don't pull `com.proxdr.engine` into the public API
     * surface of `:imaging-pipeline:impl`.
     *
     * `processBurst` currently throws — the LeicaCam pipeline operates on
     * fp32 linear RGB after demosaic and the upstream bridge wants RAW16
     * direct ByteBuffers. The orchestrator catches and falls through.
     */
    class Native internal constructor(private val bridgeClassFound: Boolean) :
        ProXdrV3NativeBackend {
        override val isAvailable: Boolean
            get() = bridgeClassFound
        override fun processBurst(
            frames: List<PipelineFrame>,
            cfg: ProXdrV3Tuning,
            sceneMode: ProXdrV3SceneMode,
            thermal: ProXdrV3Thermal,
            userBias: Float,
        ): PipelineFrame {
            // RAW16 entry-point not yet wired through the LeicaCam capture
            // pipeline — see `proxdr/docs/INTEGRATION.md` §6.
            throw UnsupportedOperationException(
                "ProXDR v3 native RAW path not wired; using Kotlin RGB fast path",
            )
        }
    }

    companion object {
        fun tryLoad(): ProXdrV3NativeBackend = try {
            // We deliberately avoid `System.loadLibrary` here — the bridge's
            // `init {}` block already does it. A successful Class.forName is
            // enough to know the dependency is on the classpath.
            Class.forName("com.proxdr.engine.ProXDRBridge", false, this::class.java.classLoader)
            Native(bridgeClassFound = true)
        } catch (_: Throwable) {
            Unavailable
        }
    }
}
