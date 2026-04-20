package com.leica.cam.common

import com.leica.cam.common.result.DomainError
import com.leica.cam.common.result.LeicaResult
import com.leica.cam.common.result.PipelineStage
import kotlin.coroutines.cancellation.CancellationException

/**
 * Structured coroutine-safe error handling utilities.
 *
 * Dimension 2 compliance:
 * - [CancellationException] is NEVER caught and suppressed — always rethrown.
 * - All other exceptions are wrapped in typed [LeicaResult.Failure] instances.
 * - No raw `try/catch(Exception)` blocks anywhere in pipeline code.
 *
 * Usage:
 * ```kotlin
 * val result = runPipelineCatching(PipelineStage.FUSION) {
 *     expensiveFusionOperation()
 * }
 * ```
 */

/**
 * Execute a suspend block with structured error handling.
 *
 * - [CancellationException] is rethrown (cooperative cancellation).
 * - All other exceptions are caught and wrapped as [LeicaResult.Failure.Pipeline].
 *
 * @param stage Pipeline stage identifier for error attribution
 * @param block Suspend block to execute
 * @return [LeicaResult.Success] with the block's return value, or
 *         [LeicaResult.Failure.Pipeline] on any non-cancellation exception
 */
inline fun <T> runPipelineCatching(
    stage: PipelineStage,
    block: () -> T,
): LeicaResult<T> {
    return try {
        LeicaResult.Success(block())
    } catch (e: CancellationException) {
        // NEVER suppress CancellationException — this violates structured concurrency.
        // Swallowing it prevents coroutine scope cancellation from propagating,
        // which causes resource leaks and zombie coroutines.
        throw e
    } catch (e: Exception) {
        LeicaResult.Failure.Pipeline(
            stage = stage,
            message = "${stage.name} failed: ${e.message ?: "unknown error"}",
            cause = e,
        )
    }
}

/**
 * Suspend variant of [runPipelineCatching] for suspend blocks.
 */
suspend inline fun <T> runPipelineCatchingSuspend(
    stage: PipelineStage,
    crossinline block: suspend () -> T,
): LeicaResult<T> {
    return try {
        LeicaResult.Success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        LeicaResult.Failure.Pipeline(
            stage = stage,
            message = "${stage.name} failed: ${e.message ?: "unknown error"}",
            cause = e,
        )
    }
}

/**
 * Chain multiple [LeicaResult] operations with early return on failure.
 *
 * Usage:
 * ```kotlin
 * val result = alignResult
 *     .then(PipelineStage.FUSION) { aligned -> merge(aligned) }
 *     .then(PipelineStage.DENOISE) { merged -> denoise(merged) }
 *     .then(PipelineStage.TONE) { denoised -> tonemap(denoised) }
 * ```
 */
inline fun <T, R> LeicaResult<T>.then(
    stage: PipelineStage,
    transform: (T) -> R,
): LeicaResult<R> = when (this) {
    is LeicaResult.Success -> runPipelineCatching(stage) { transform(value) }
    is LeicaResult.Failure -> this
}

/**
 * Require a non-null value or return a typed validation failure.
 *
 * Usage:
 * ```kotlin
 * val profile = capability.sensorProfile.requireOrFail("sensorProfile") ?: return it
 * ```
 */
inline fun <T : Any> T?.requireOrFail(
    fieldName: String,
    stage: PipelineStage = PipelineStage.INGEST,
): LeicaResult<T> = when (this) {
    null -> LeicaResult.Failure.Pipeline(
        stage = stage,
        message = "Required field '$fieldName' is null",
    )
    else -> LeicaResult.Success(this)
}
