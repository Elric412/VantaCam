package com.leica.cam.feature.camera

import com.leica.cam.common.result.LeicaResult
import com.leica.cam.nativeimagingcore.CaptureMetadata
import com.leica.cam.nativeimagingcore.FrameHandle
import com.leica.cam.nativeimagingcore.AdvancedHdrConfig
import com.leica.cam.nativeimagingcore.HyperToneWbConfig
import com.leica.cam.nativeimagingcore.ImagingRuntimeOrchestrator
import com.leica.cam.nativeimagingcore.LutDescriptor
import com.leica.cam.nativeimagingcore.NativeGpuBackend
import com.leica.cam.nativeimagingcore.NativeSessionConfig
import com.leica.cam.nativeimagingcore.ProcessingMode
import com.leica.cam.nativeimagingcore.ProcessingRequest

/**
 * Camera feature entrypoint to native imaging runtime.
 * Keeps Kotlin orchestration lightweight and delegates heavy work to NDK core.
 */
class NativeImagingRuntimeFacade(
    private val orchestrator: ImagingRuntimeOrchestrator,
) {
    fun startSession(sessionId: String): LeicaResult<Unit> {
        val config = NativeSessionConfig(sessionId = sessionId)
        return orchestrator.start(config)
    }

    fun submitPreviewFrame(frameId: Long, hardwareBufferHandle: Long, width: Int, height: Int): LeicaResult<Unit> {
        val frame = FrameHandle(
            frameId = frameId,
            hardwareBufferHandle = hardwareBufferHandle,
            width = width,
            height = height,
            format = IMAGE_FORMAT_PRIVATE,
        )
        val metadata = CaptureMetadata(
            timestampNs = System.nanoTime(),
            exposureTimeNs = 0L,
            iso = 0,
            focusDistanceDiopters = 0f,
            sensorGain = 1f,
            thermalLevel = 0,
        )
        val queued = orchestrator.submitFrame(frame, metadata)
        if (queued is LeicaResult.Failure) {
            return queued
        }
        return orchestrator.requestProcessing(
            ProcessingRequest(requestId = frameId, mode = ProcessingMode.PREVIEW),
        )
    }

    fun stopSession(): LeicaResult<Unit> = orchestrator.shutdown()

    fun configureRealtimeColorPipeline(
        hdrConfig: AdvancedHdrConfig,
        wbConfig: HyperToneWbConfig,
        lut: LutDescriptor,
        gpuBackend: NativeGpuBackend = NativeGpuBackend.VULKAN,
    ): LeicaResult<Unit> {
        val hdr = orchestrator.configureAdvancedHdr(hdrConfig)
        if (hdr is LeicaResult.Failure) return hdr

        val wb = orchestrator.configureHyperToneWb(wbConfig)
        if (wb is LeicaResult.Failure) return wb

        val lutRegistered = orchestrator.registerLut(lut)
        if (lutRegistered is LeicaResult.Failure) return lutRegistered

        val activeLut = orchestrator.activateLut(lut.lutId)
        if (activeLut is LeicaResult.Failure) return activeLut

        return orchestrator.configureGpuBackend(gpuBackend)
    }

    private companion object {
        const val IMAGE_FORMAT_PRIVATE = 34
    }
}
