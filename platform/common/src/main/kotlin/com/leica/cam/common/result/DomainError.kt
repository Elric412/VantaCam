package com.leica.cam.common.result

/**
 * Typed domain error hierarchy for the LUMO imaging platform.
 *
 * Every fallible function must return [LeicaResult<T>] with a typed failure
 * from this hierarchy. Untyped exceptions crossing module boundaries are
 * forbidden by the Dimension 2 code flow specification.
 *
 * Usage:
 * ```kotlin
 * fun align(frames: List<Frame>): LeicaResult<AlignedFrames> {
 *     if (frames.isEmpty()) return LeicaResult.Failure.Pipeline(
 *         PipelineStage.ALIGNMENT,
 *         "Frame list must not be empty",
 *     )
 *     // ...
 * }
 * ```
 *
 * Exhaustive `when` is enforced: all callers must handle every [Failure] subtype.
 * Adding a new [Failure] subtype without updating callers is a compile error.
 */
sealed class DomainError(
    open val message: String,
    open val cause: Throwable? = null,
) {
    // ── Pipeline processing errors ───────────────────────────────────

    /** A stage in the imaging pipeline failed with a typed cause. */
    data class PipelineError(
        val stage: PipelineStage,
        override val message: String,
        override val cause: Throwable? = null,
    ) : DomainError(message, cause)

    /** Input validation failed before processing could begin. */
    data class ValidationError(
        val field: String,
        override val message: String,
    ) : DomainError(message)

    // ── Hardware errors ──────────────────────────────────────────────

    /** A Camera2 API error with the Android error code. */
    data class HardwareError(
        val errorCode: Int,
        override val message: String,
        override val cause: Throwable? = null,
    ) : DomainError(message, cause)

    /** Sensor-specific error (e.g., unsupported sensor, missing calibration). */
    data class SensorError(
        val sensorId: String,
        override val message: String,
    ) : DomainError(message)

    // ── Resource errors ──────────────────────────────────────────────

    /** An AI model failed to load or execute. */
    data class ModelError(
        val modelName: String,
        override val message: String,
        override val cause: Throwable? = null,
    ) : DomainError(message, cause)

    /** GPU compute dispatch failed. */
    data class GpuError(
        val shaderName: String,
        override val message: String,
        override val cause: Throwable? = null,
    ) : DomainError(message, cause)

    /** Thermal throttling requires graceful degradation. */
    data class ThermalError(
        val gpuTempCelsius: Float,
        override val message: String,
    ) : DomainError(message)

    // ── Recoverable errors ───────────────────────────────────────────

    /**
     * A recoverable failure that carries a fallback suspend lambda.
     * The fallback must be idempotent and must not itself produce [Recoverable].
     */
    data class Recoverable(
        override val message: String,
        val fallback: suspend () -> LeicaResult<Nothing>,
        override val cause: Throwable? = null,
    ) : DomainError(message, cause)

    // ── Timeout ──────────────────────────────────────────────────────

    /** Processing exceeded the allowed time budget. */
    data class TimeoutError(
        val budgetMs: Long,
        val actualMs: Long,
        override val message: String,
    ) : DomainError(message)
}
