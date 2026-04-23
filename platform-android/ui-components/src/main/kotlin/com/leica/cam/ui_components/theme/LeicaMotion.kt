package com.leica.cam.ui_components.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Motion tokens. Durations in ms. Easings chosen to match Material-3's
 * "emphasized" set but tuned slightly faster for a camera-app feel.
 *
 * Use these — never hard-code duration/easing in a screen.
 */
@Immutable
data class LeicaMotion(
    val fast: Int = 120,
    val standard: Int = 220,
    val slow: Int = 360,
    val shutter: Int = 90,
    val enter: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f),
    val exit: Easing = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f),
    val standardEasing: Easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f),
)

val LocalLeicaMotion = staticCompositionLocalOf { LeicaMotion() }