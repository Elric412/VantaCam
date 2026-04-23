package com.leica.cam.ui_components.motion

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import com.leica.cam.ui_components.theme.LeicaMotion

object LeicaTransitions {
    fun sheetEnter(motion: LeicaMotion): EnterTransition =
        slideInVertically(tween(motion.standard, easing = motion.enter)) { it } +
            fadeIn(tween(motion.standard))

    fun sheetExit(motion: LeicaMotion): ExitTransition =
        slideOutVertically(tween(motion.fast, easing = motion.exit)) { it } +
            fadeOut(tween(motion.fast))

    fun hudFadeEnter(motion: LeicaMotion): EnterTransition = fadeIn(tween(motion.standard))
    fun hudFadeExit(motion: LeicaMotion): ExitTransition = fadeOut(tween(motion.fast))
}