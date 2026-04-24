package com.leica.cam.common

/**
 * Legacy static logger shim — kept for binary compatibility only.
 *
 * **DEPRECATED:** New code must inject [com.leica.cam.common.logging.LeicaLogger]
 * via Hilt instead of calling this singleton directly.  The injectable version
 * is testable (no global state) and satisfies the "no static Log calls" rule.
 *
 * Removal target: once all call-sites are migrated, delete this file and the
 * matching proguard keep rule.
 */
@Deprecated(
    message = "Inject LeicaLogger (com.leica.cam.common.logging.LeicaLogger) instead of using this singleton.",
    replaceWith = ReplaceWith(
        "logger.debug(tag, message)",
        "com.leica.cam.common.logging.LeicaLogger",
    ),
)
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
