package com.leica.cam.ui_components.motion

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalView
import android.view.HapticFeedbackConstants

object LeicaHaptics {
    @Composable
    fun rememberShutterHaptic(): () -> Unit {
        val view = LocalView.current
        return {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    @Composable
    fun rememberDialTickHaptic(): () -> Unit {
        val view = LocalView.current
        return {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }
}