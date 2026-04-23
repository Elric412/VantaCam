package com.leica.cam.nativeimagingcore

import com.leica.cam.common.result.LeicaResult

interface INativeImagingOrchestrator {
    fun start(config: NativeSessionConfig): LeicaResult<Unit>
    fun submitFrame(frame: FrameHandle, metadata: CaptureMetadata): LeicaResult<Unit>
    fun configureAdvancedHdr(config: AdvancedHdrConfig): LeicaResult<Unit>
    fun configureHyperToneWb(config: HyperToneWbConfig): LeicaResult<Unit>
    fun registerLut(descriptor: LutDescriptor): LeicaResult<Unit>
    fun activateLut(lutId: String): LeicaResult<Unit>
    fun configureGpuBackend(backend: NativeGpuBackend): LeicaResult<Unit>
    fun requestProcessing(request: ProcessingRequest): LeicaResult<Unit>
    fun shutdown(): LeicaResult<Unit>
}
