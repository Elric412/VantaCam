package com.leica.cam.common

/** Centralized logger with stable tag formatting for all modules. */
object Logger {
    private val delegate = java.util.logging.Logger.getLogger("LeicaCam")

    fun d(tag: String, message: String) {
        delegate.fine("LeicaCam/$tag: $message")
    }

    fun i(tag: String, message: String) {
        delegate.info("LeicaCam/$tag: $message")
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        delegate.severe(buildString {
            append("LeicaCam/")
            append(tag)
            append(": ")
            append(message)
            throwable?.let {
                append(" | ")
                append(it.message ?: it::class.java.simpleName)
            }
        })
    }
}
