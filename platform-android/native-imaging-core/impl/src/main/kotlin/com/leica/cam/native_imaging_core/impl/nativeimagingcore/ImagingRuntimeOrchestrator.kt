package com.leica.cam.nativeimagingcore

import com.leica.cam.common.ThermalState
import com.leica.cam.common.result.LeicaResult
import com.leica.cam.common.result.PipelineStage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Kotlin orchestration shell around the native runtime.
 * Uses bounded channels to enforce backpressure and preserve zero-jank behavior.
 */
class ImagingRuntimeOrchestrator(

    private val bridge: NativeImagingBridge,
    private val callbackDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : INativeImagingOrchestrator {
    private val ingestQueue = Channel<FrameTask>(capacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val processingQueue = Channel<ProcessingRequest>(capacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val runtimeScope = CoroutineScope(SupervisorJob() + callbackDispatcher)
    private var workerJob: Job? = null
    private val latestThermalLevel = AtomicInteger(0)
    private val highThermalFrameModulo = AtomicInteger(0)
    private val nonCriticalWarmupTriggered = AtomicBoolean(false)

    override fun start(config: NativeSessionConfig): LeicaResult<Unit> {
        val created = bridge.createSession(config)
        if (created is LeicaResult.Failure) return created
        bridge.warmSession()
        bridge.startRunning()
        startWorker()
        maybeWarmNonCriticalModulesAsync()
        return LeicaResult.Success(Unit)
    }

    override fun submitFrame(frame: FrameHandle, metadata: CaptureMetadata): LeicaResult<Unit> {
        latestThermalLevel.set(metadata.thermalLevel)
        val result = ingestQueue.trySend(FrameTask(frame, metadata))
        val enqueued = result.isSuccess
        return if (enqueued) LeicaResult.Success(Unit) else LeicaResult.Failure.Pipeline(PipelineStage.SMART_IMAGING, "Ingest queue saturated")
    }

    override fun configureAdvancedHdr(config: AdvancedHdrConfig): LeicaResult<Unit> = bridge.configureAdvancedHdr(config)

    override fun configureHyperToneWb(config: HyperToneWbConfig): LeicaResult<Unit> = bridge.configureHyperToneWb(config)

    override fun registerLut(descriptor: LutDescriptor): LeicaResult<Unit> = bridge.registerLut(descriptor)

    override fun activateLut(lutId: String): LeicaResult<Unit> = bridge.setActiveLut(lutId)

    override fun configureGpuBackend(backend: NativeGpuBackend): LeicaResult<Unit> = bridge.setGpuBackend(backend)

    override fun requestProcessing(request: ProcessingRequest): LeicaResult<Unit> {
        val throttledRequest = thermalAwareRequest(request) ?: return LeicaResult.Success(Unit)
        val result = processingQueue.trySend(throttledRequest)
        val enqueued = result.isSuccess
        return if (enqueued) LeicaResult.Success(Unit) else LeicaResult.Failure.Pipeline(PipelineStage.SMART_IMAGING, "Processing queue saturated")
    }

    override fun shutdown(): LeicaResult<Unit> {
        workerJob?.cancel()
        bridge.beginDrain()
        return bridge.closeSession()
    }

    private fun startWorker() {
        workerJob = runtimeScope.launch {
            while (true) {
                val ingest = ingestQueue.receive()
                bridge.queueFrame(ingest.frame, ingest.metadata)

                val processRequest = processingQueue.receive()
                bridge.requestProcess(processRequest)
                bridge.release(ingest.frame.hardwareBufferHandle)
                val result = bridge.pollResult(timeoutMs = 0L)
                if (result is LeicaResult.Success) {
                    val value = result.value
                    if (value != null) {
                        bridge.release(value.outputHandle)
                    }
                }
            }
        }
    }

    private fun thermalAwareRequest(request: ProcessingRequest): ProcessingRequest? {
        val thermal = latestThermalLevel.get()
        return when {
            thermal >= ThermalState.FRAME_DROP_CUTOFF.androidOrdinal -> {
                val frameIndex = highThermalFrameModulo.incrementAndGet()
                if (frameIndex % HIGH_THERMAL_DROP_MODULO == 0) request else null
            }
            thermal >= ThermalState.MULTI_FRAME_CUTOFF.androidOrdinal && request.mode == ProcessingMode.HDR_BURST -> {
                request.copy(mode = ProcessingMode.CAPTURE)
            }
            else -> {
                highThermalFrameModulo.set(0)
                request
            }
        }
    }

    private fun maybeWarmNonCriticalModulesAsync() {
        if (!nonCriticalWarmupTriggered.compareAndSet(false, true)) return
        runtimeScope.launch {
            bridge.prefetchNonCriticalModules()
        }
    }

    private companion object {
        private const val HIGH_THERMAL_DROP_MODULO = 2
    }
}

private data class FrameTask(
    val frame: FrameHandle,
    val metadata: CaptureMetadata,
)
