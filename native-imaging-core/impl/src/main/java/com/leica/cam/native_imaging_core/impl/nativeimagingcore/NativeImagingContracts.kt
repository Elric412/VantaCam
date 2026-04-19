package com.leica.cam.nativeimagingcore

import com.leica.cam.common.result.LeicaResult
import com.leica.cam.common.result.PipelineStage
import java.nio.ShortBuffer

enum class SessionState {
    CREATED,
    WARM,
    RUNNING,
    DRAINING,
    CLOSED,
}

enum class ProcessingMode {
    PREVIEW,
    CAPTURE,
    HDR_BURST,
}

enum class Recoverability {
    RECOVERABLE,
    SESSION_RECOVERABLE,
    FATAL,
}

enum class DeviceTier {
    TIER_A,
    TIER_B,
    TIER_C,
}

enum class NativeGpuBackend {
    VULKAN,
    OPENGL_ES_3_0,
    CPU_FALLBACK,
}

data class NativeSessionConfig(
    val sessionId: String,
    val previewQueueCapacity: Int = 4,
    val captureQueueCapacity: Int = 16,
    val maxWorkers: Int = Runtime.getRuntime().availableProcessors().coerceAtMost(8),
)

data class CaptureMetadata(
    val timestampNs: Long,
    val exposureTimeNs: Long,
    val iso: Int,
    val focusDistanceDiopters: Float,
    val sensorGain: Float,
    val thermalLevel: Int,
)

data class FrameHandle(
    val frameId: Long,
    val hardwareBufferHandle: Long,
    val width: Int,
    val height: Int,
    val format: Int,
)

data class NativePhotonBufferHandle(
    val handle: Long,
    val width: Int,
    val height: Int,
    val channels: Int,
    val bitDepthBits: Int = 16,
)

data class NativePhotonPlane(
    val channel: Int,
    val data: ShortBuffer,
)

data class ProcessingRequest(
    val requestId: Long,
    val mode: ProcessingMode,
)

data class NativeError(
    val category: Recoverability,
    val message: String,
)

data class NativeResult(
    val requestId: Long,
    val outputHandle: Long,
    val completedAtNs: Long,
    val droppedPreviewFrames: Int,
    val warnings: List<String> = emptyList(),
)

data class RuntimeTelemetry(
    val p95PreviewLatencyMs: Double,
    val p95CaptureLatencyMs: Double,
    val nativeHeapBytes: Long,
    val thermalLevel: Int,
)

data class AdvancedHdrConfig(
    val exposures: Int = 3,
    val shadowBoost: Float = 0.22f,
    val highlightProtection: Float = 0.28f,
    val localContrast: Float = 0.16f,
)

data class HyperToneWbConfig(
    val targetKelvin: Float,
    val tintBias: Float,
    val strength: Float,
)

data class LutDescriptor(
    val lutId: String,
    val gridSize: Int,
    val payload: FloatArray,
)

internal fun SessionState.canTransitionTo(next: SessionState): Boolean {
    return when (this) {
        SessionState.CREATED -> next == SessionState.WARM || next == SessionState.CLOSED
        SessionState.WARM -> next == SessionState.RUNNING || next == SessionState.CLOSED
        SessionState.RUNNING -> next == SessionState.DRAINING || next == SessionState.CLOSED
        SessionState.DRAINING -> next == SessionState.CLOSED
        SessionState.CLOSED -> false
    }
}

internal fun illegalTransition(current: SessionState, next: SessionState): LeicaResult.Failure {
    return LeicaResult.Failure.Pipeline(PipelineStage.SESSION, "Illegal session state transition from $current to $next")
}
