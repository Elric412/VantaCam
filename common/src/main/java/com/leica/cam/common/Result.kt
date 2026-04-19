package com.leica.cam.common

/** Strongly typed result wrapper for camera pipeline operations. */
sealed interface LeicaResult<out T> {
    data class Success<T>(val value: T) : LeicaResult<T>
    data class Failure(val message: String, val cause: Throwable? = null) : LeicaResult<Nothing>
}
