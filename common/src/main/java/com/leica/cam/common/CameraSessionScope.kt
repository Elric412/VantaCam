package com.leica.cam.common

import javax.inject.Scope

/**
 * Scope for objects tied to the lifecycle of an active camera session.
 */
@Scope
@Retention(AnnotationRetention.RUNTIME)
annotation class CameraSessionScope
