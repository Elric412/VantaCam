package com.leica.cam.common

import android.util.Log

/** Centralized logger with stable tag formatting for all modules. */
object Logger {
    fun d(tag: String, message: String) = Log.d("LeicaCam/$tag", message)

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e("LeicaCam/$tag", message, throwable)
    }
}
