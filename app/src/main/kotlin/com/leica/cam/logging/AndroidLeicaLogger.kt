package com.leica.cam.logging

import android.util.Log
import com.leica.cam.common.logging.LeicaLogger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidLeicaLogger @Inject constructor() : LeicaLogger {
    override fun debug(tag: String, message: String) { Log.d(tag, message) }
    override fun info(tag: String, message: String) { Log.i(tag, message) }
    override fun warn(tag: String, message: String, cause: Throwable?) {
        if (cause == null) Log.w(tag, message) else Log.w(tag, message, cause)
    }
    override fun error(tag: String, message: String, cause: Throwable?) {
        if (cause == null) Log.e(tag, message) else Log.e(tag, message, cause)
    }
}
