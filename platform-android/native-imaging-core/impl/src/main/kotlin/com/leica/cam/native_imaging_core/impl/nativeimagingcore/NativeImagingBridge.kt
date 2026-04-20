package com.leica.cam.nativeimagingcore


import com.leica.cam.common.result.LeicaResult
import com.leica.cam.common.result.PipelineStage
import java.nio.ByteBuffer
import java.nio.ShortBuffer
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NativeImagingBridge @Inject constructor() {
    private val sessionState = AtomicReference(SessionState.CLOSED)
    private var nativeSessionHandle: Long = 0

    fun createSession(config: NativeSessionConfig): LeicaResult<Unit> {
        if (!nativeLibraryLoaded) return LeicaResult.Failure.Pipeline(PipelineStage.SESSION, "native_imaging_core library is not loaded")
        nativeSessionHandle = nativeCreateSession(
            config.sessionId,
            config.previewQueueCapacity,
            config.captureQueueCapacity,
            config.maxWorkers,
        )
        return if (nativeSessionHandle != 0L) {
            sessionState.set(SessionState.CREATED)
            LeicaResult.Success(Unit)
        } else {
            LeicaResult.Failure.Pipeline(PipelineStage.SESSION, "Unable to create native imaging session")
        }
    }

    fun warmSession(): LeicaResult<Unit> = transition(SessionState.WARM)
    fun startRunning(): LeicaResult<Unit> = transition(SessionState.RUNNING)
    fun beginDrain(): LeicaResult<Unit> = transition(SessionState.DRAINING)

    fun closeSession(): LeicaResult<Unit> {
        if (!nativeLibraryLoaded) return LeicaResult.Failure.Pipeline(PipelineStage.SESSION, "native_imaging_core library is not loaded")
        val result = transition(SessionState.CLOSED)
        if (nativeSessionHandle != 0L) {
            nativeDestroySession(nativeSessionHandle)
            nativeSessionHandle = 0
        }
        return result
    }

    fun queueFrame(frameHandle: FrameHandle, captureMetadata: CaptureMetadata): LeicaResult<Unit> {
        if (!nativeLibraryLoaded) return LeicaResult.Failure.Pipeline(PipelineStage.SMART_IMAGING, "native_imaging_core library is not loaded")
        if (sessionState.get() != SessionState.RUNNING) {
            return LeicaResult.Failure.Pipeline(PipelineStage.SMART_IMAGING, "Session must be RUNNING before queueFrame")
        }
        val accepted = nativeQueueFrame(
            nativeSessionHandle,
            frameHandle.frameId,
            frameHandle.hardwareBufferHandle,
            frameHandle.width,
            frameHandle.height,
            frameHandle.format,
            captureMetadata.timestampNs,
            captureMetadata.exposureTimeNs,
            captureMetadata.iso,
            captureMetadata.focusDistanceDiopters,
            captureMetadata.sensorGain,
            captureMetadata.thermalLevel,
        )
        return if (accepted) LeicaResult.Success(Unit) else LeicaResult.Failure.Pipeline(PipelineStage.SMART_IMAGING, "Native ingest queue full")
    }

    fun queueBurst(frameHandles: LongArray, metadataBlob: ByteBuffer): LeicaResult<Unit> {
        if (!nativeLibraryLoaded) return LeicaResult.Failure.Pipeline(PipelineStage.SMART_IMAGING, "native_imaging_core library is not loaded")
        if (sessionState.get() != SessionState.RUNNING) {
            return LeicaResult.Failure.Pipeline(PipelineStage.SMART_IMAGING, "Session must be RUNNING before queueBurst")
        }
        val accepted = nativeQueueBurst(nativeSessionHandle, frameHandles, metadataBlob)
        return if (accepted) LeicaResult.Success(Unit) else LeicaResult.Failure.Pipeline(PipelineStage.SMART_IMAGING, "Burst queue rejected")
    }

    fun allocatePhotonBuffer(
        width: Int,
        height: Int,
        channels: Int,
        bitDepthBits: Int = 16,
    ): LeicaResult<NativePhotonBufferHandle> {
        if (!nativeLibraryLoaded) return LeicaResult.Failure.Pipeline(PipelineStage.SMART_IMAGING, "native_imaging_core library is not loaded")
        if (width <= 0 || height <= 0 || channels <= 0) {
            return LeicaResult.Failure.Pipeline(PipelineStage.SMART_IMAGING, "Photon buffer dimensions must be positive")
        }
        val sanitizedBitDepth = bitDepthBits.coerceIn(10, 16)
        val handle = nativeAllocatePhotonBuffer(width, height, channels, sanitizedBitDepth)
        return if (handle == 0L) {
            LeicaResult.Failure.Pipeline(PipelineStage.SMART_IMAGING, "Unable to allocate native photon buffer")
        } else {
            LeicaResult.Success(
                NativePhotonBufferHandle(
                    handle = handle,
                    width = width,
                    height = height,
                    channels = channels,
                    bitDepthBits = sanitizedBitDepth,
                ),
            )
        }
    }

    fun fillPhotonChannel(
        bufferHandle: NativePhotonBufferHandle,
        plane: NativePhotonPlane,
        offset: Int = 0,
    ): LeicaResult<Unit> {
        if (bufferHandle.handle == 0L) return LeicaResult.Failure.Pipeline(PipelineStage.SMART_IMAGING, "Native photon buffer handle is closed")
        if (!plane.data.isDirect) return LeicaResult.Failure.Pipeline(PipelineStage.SMART_IMAGING, "Photon channel data must be a direct ShortBuffer")
        if (offset < 0) return LeicaResult.Failure.Pipeline(PipelineStage.SMART_IMAGING, "Photon channel offset must be >= 0")
        if (plane.channel !in 0 until bufferHandle.channels) {
            return LeicaResult.Failure.Pipeline(PipelineStage.SMART_IMAGING, "Photon channel index is out of bounds")
        }
        val accepted = nativeFillChannel(
            bufferHandle.handle,
            plane.channel,
            plane.data,
            offset,
            plane.data.remaining(),
        )
        return if (accepted) LeicaResult.Success(Unit) else LeicaResult.Failure.Pipeline(PipelineStage.SMART_IMAGING, "nativeFillChannel rejected channel payload")
    }

    fun freePhotonBuffer(bufferHandle: NativePhotonBufferHandle) {
        if (!nativeLibraryLoaded) return
        nativeFreePhotonBuffer(bufferHandle.handle)
    }

    fun configureAdvancedHdr(config: AdvancedHdrConfig): LeicaResult<Unit> {
        if (!nativeLibraryLoaded) return LeicaResult.Failure.Pipeline(PipelineStage.SMART_IMAGING, "native_imaging_core library is not loaded")
        val configured = nativeConfigureAdvancedHdr(
            nativeSessionHandle,
            config.exposures,
            config.shadowBoost,
            config.highlightProtection,
            config.localContrast,
        )
        return if (configured) LeicaResult.Success(Unit) else LeicaResult.Failure.Pipeline(PipelineStage.SMART_IMAGING, "Advanced HDR configuration rejected")
    }

    fun configureHyperToneWb(config: HyperToneWbConfig): LeicaResult<Unit> {
        if (!nativeLibraryLoaded) return LeicaResult.Failure.Pipeline(PipelineStage.SMART_IMAGING, "native_imaging_core library is not loaded")
        val configured = nativeConfigureHyperToneWb(
            nativeSessionHandle,
            config.targetKelvin,
            config.tintBias,
            config.strength,
        )
        return if (configured) LeicaResult.Success(Unit) else LeicaResult.Failure.Pipeline(PipelineStage.SMART_IMAGING, "HyperTone WB configuration rejected")
    }

    fun registerLut(descriptor: LutDescriptor): LeicaResult<Unit> {
        if (!nativeLibraryLoaded) return LeicaResult.Failure.Pipeline(PipelineStage.SMART_IMAGING, "native_imaging_core library is not loaded")
        val configured = nativeRegisterLut(
            nativeSessionHandle,
            descriptor.lutId,
            descriptor.gridSize,
            descriptor.payload,
        )
        return if (configured) LeicaResult.Success(Unit) else LeicaResult.Failure.Pipeline(PipelineStage.SMART_IMAGING, "3D LUT registration rejected")
    }

    fun setActiveLut(lutId: String): LeicaResult<Unit> {
        if (!nativeLibraryLoaded) return LeicaResult.Failure.Pipeline(PipelineStage.SMART_IMAGING, "native_imaging_core library is not loaded")
        val configured = nativeSetActiveLut(nativeSessionHandle, lutId)
        return if (configured) LeicaResult.Success(Unit) else LeicaResult.Failure.Pipeline(PipelineStage.SMART_IMAGING, "Unable to set active 3D LUT")
    }

    fun setGpuBackend(backend: NativeGpuBackend): LeicaResult<Unit> {
        if (!nativeLibraryLoaded) return LeicaResult.Failure.Pipeline(PipelineStage.SMART_IMAGING, "native_imaging_core library is not loaded")
        val configured = nativeSetGpuBackend(nativeSessionHandle, backend.ordinal)
        return if (configured) LeicaResult.Success(Unit) else LeicaResult.Failure.Pipeline(PipelineStage.SMART_IMAGING, "GPU backend configuration rejected")
    }

    fun requestProcess(request: ProcessingRequest): LeicaResult<Unit> {
        if (!nativeLibraryLoaded) return LeicaResult.Failure.Pipeline(PipelineStage.SMART_IMAGING, "native_imaging_core library is not loaded")
        val accepted = nativeRequestProcess(nativeSessionHandle, request.requestId, request.mode.ordinal)
        return if (accepted) LeicaResult.Success(Unit) else LeicaResult.Failure.Pipeline(PipelineStage.SMART_IMAGING, "Native process request rejected")
    }

    fun pollResult(timeoutMs: Long): LeicaResult<NativeResult?> {
        if (!nativeLibraryLoaded) return LeicaResult.Failure.Pipeline(PipelineStage.SMART_IMAGING, "native_imaging_core library is not loaded")
        val result = nativePollResult(nativeSessionHandle, timeoutMs)
        return LeicaResult.Success(result)
    }

    fun prefetchNonCriticalModules(): LeicaResult<Unit> {
        if (!nativeLibraryLoaded) return LeicaResult.Failure.Pipeline(PipelineStage.SMART_IMAGING, "native_imaging_core library is not loaded")
        val warmed = nativePrefetchNonCriticalModules(nativeSessionHandle)
        return if (warmed) LeicaResult.Success(Unit) else LeicaResult.Failure.Pipeline(PipelineStage.SMART_IMAGING, "Unable to prefetch non-critical modules")
    }

    fun release(handle: Long) {
        if (!nativeLibraryLoaded) return
        nativeRelease(nativeSessionHandle, handle)
    }

    fun sessionState(): SessionState = sessionState.get()

    private fun transition(next: SessionState): LeicaResult<Unit> {
        val current = sessionState.get()
        if (!current.canTransitionTo(next)) {
            return illegalTransition(current, next)
        }
        sessionState.set(next)
        return LeicaResult.Success(Unit)
    }

    private external fun nativeCreateSession(
        sessionId: String,
        previewQueueCapacity: Int,
        captureQueueCapacity: Int,
        maxWorkers: Int,
    ): Long

    private external fun nativeQueueFrame(
        sessionHandle: Long,
        frameId: Long,
        hardwareBufferHandle: Long,
        width: Int,
        height: Int,
        format: Int,
        timestampNs: Long,
        exposureTimeNs: Long,
        iso: Int,
        focusDistanceDiopters: Float,
        sensorGain: Float,
        thermalLevel: Int,
    ): Boolean

    private external fun nativeQueueBurst(
        sessionHandle: Long,
        burstFrameHandles: LongArray,
        metadataBlob: ByteBuffer,
    ): Boolean

    private external fun nativeAllocatePhotonBuffer(
        width: Int,
        height: Int,
        channels: Int,
        bitDepthBits: Int,
    ): Long

    private external fun nativeFillChannel(
        handle: Long,
        channel: Int,
        shortBuffer: ShortBuffer,
        offset: Int,
        length: Int,
    ): Boolean

    private external fun nativeFreePhotonBuffer(handle: Long)

    private external fun nativeRequestProcess(
        sessionHandle: Long,
        requestId: Long,
        mode: Int,
    ): Boolean

    private external fun nativePollResult(sessionHandle: Long, timeoutMs: Long): NativeResult?

    private external fun nativePrefetchNonCriticalModules(sessionHandle: Long): Boolean

    private external fun nativeRelease(sessionHandle: Long, handle: Long)

    private external fun nativeConfigureAdvancedHdr(
        sessionHandle: Long,
        exposures: Int,
        shadowBoost: Float,
        highlightProtection: Float,
        localContrast: Float,
    ): Boolean

    private external fun nativeConfigureHyperToneWb(
        sessionHandle: Long,
        targetKelvin: Float,
        tintBias: Float,
        strength: Float,
    ): Boolean

    private external fun nativeRegisterLut(
        sessionHandle: Long,
        lutId: String,
        gridSize: Int,
        payload: FloatArray,
    ): Boolean

    private external fun nativeSetActiveLut(
        sessionHandle: Long,
        lutId: String,
    ): Boolean

    private external fun nativeSetGpuBackend(
        sessionHandle: Long,
        backend: Int,
    ): Boolean

    private external fun nativeDestroySession(sessionHandle: Long)

    companion object {
        private val nativeLibraryLoaded = runCatching {
            System.loadLibrary("native_imaging_core")
        }.isSuccess

        init {
            // Library loading is eager but non-fatal for pure JVM tests.
        }
    }
}
