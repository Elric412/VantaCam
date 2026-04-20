package com.leica.cam.common.logging

/**
 * Injectable logger interface. No module may use static Log.d calls directly.
 * Default Android implementation uses android.util.Log.
 * Test implementation uses println.
 */
interface LeicaLogger {
    fun debug(tag: String, message: String)
    fun info(tag: String, message: String)
    fun warn(tag: String, message: String, cause: Throwable? = null)
    fun error(tag: String, message: String, cause: Throwable? = null)
}
