package com.leica.cam.common.logging

/** JVM-pure default logger for non-Android modules and tests. */
class NoOpLeicaLogger : LeicaLogger {
    override fun debug(tag: String, message: String) = Unit
    override fun info(tag: String, message: String) = Unit
    override fun warn(tag: String, message: String, cause: Throwable?) = Unit
    override fun error(tag: String, message: String, cause: Throwable?) = Unit
}
