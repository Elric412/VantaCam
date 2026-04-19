package com.leica.cam.common.result

/**
 * Sealed result type for all Leica Cam pipeline operations.
 * Failures are typed — callers know what failed and why.
 * No checked exceptions cross module boundaries.
 */
sealed class LeicaResult<out T> {
    data class Success<out T>(val value: T) : LeicaResult<T>()

    sealed class Failure : LeicaResult<Nothing>() {
        abstract val message: String
        open val cause: Throwable? = null

        /** A stage in the imaging pipeline failed with a typed cause. */
        data class Pipeline(
            val stage: PipelineStage,
            override val message: String,
            override val cause: Throwable? = null,
        ) : Failure()

        /** A hardware error with an Android Camera2 error code. */
        data class Hardware(
            val errorCode: Int,
            override val message: String,
            override val cause: Throwable? = null,
        ) : Failure()

        /**
         * A recoverable failure that carries a fallback suspend lambda.
         * The fallback must be idempotent and must not itself produce Recoverable.
         */
        data class Recoverable(
            override val message: String,
            val fallback: suspend () -> LeicaResult<Nothing>,
            override val cause: Throwable? = null,
        ) : Failure()
    }

    // ── Functional operators ───────────────────────────────────────────

    inline fun <R> map(transform: (T) -> R): LeicaResult<R> = when (this) {
        is Success -> Success(transform(value))
        is Failure -> this
    }

    inline fun <R> flatMap(transform: (T) -> LeicaResult<R>): LeicaResult<R> = when (this) {
        is Success -> transform(value)
        is Failure -> this
    }

    inline fun getOrElse(default: (Failure) -> LeicaResult<@UnsafeVariance T>): LeicaResult<T> =
        if (this is Success) this else default(this as Failure)

    fun getOrThrow(): T = when (this) {
        is Success -> value
        is Failure -> throw LeicaPipelineException(message, cause)
    }

    inline fun <R> fold(onSuccess: (T) -> R, onFailure: (Failure) -> R): R = when (this) {
        is Success -> onSuccess(value)
        is Failure -> onFailure(this)
    }

    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure
}

class LeicaPipelineException(message: String, cause: Throwable? = null) : Exception(message, cause)
