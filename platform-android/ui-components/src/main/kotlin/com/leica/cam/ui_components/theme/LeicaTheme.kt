package com.leica.cam.ui_components.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

private val LeicaDarkColorScheme = darkColorScheme(
    primary = LeicaPalette.Red,
    onPrimary = LeicaPalette.White,
    secondary = LeicaPalette.Content1,
    onSecondary = LeicaPalette.Black,
    background = LeicaPalette.Surface1,
    onBackground = LeicaPalette.Content0,
    surface = LeicaPalette.Surface2,
    onSurface = LeicaPalette.Content0,
    surfaceVariant = LeicaPalette.Surface3,
    onSurfaceVariant = LeicaPalette.Content2,
    error = LeicaPalette.Error,
    onError = LeicaPalette.White,
)

val LocalLeicaColors = staticCompositionLocalOf { LeicaColorScheme() }

/**
 * The single entry point for the Leica design system. Always dark — the
 * app is a camera HUD. Exposes tokens via CompositionLocals so screens can
 * read spacing/motion/elevation via [LeicaTokens] without re-plumbing.
 */
@Composable
fun LeicaTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalLeicaColors provides LeicaColorScheme(),
        LocalLeicaSpacing provides LeicaSpacing(),
        LocalLeicaMotion provides LeicaMotion(),
        LocalLeicaElevation provides LeicaElevation(),
    ) {
        MaterialTheme(
            colorScheme = LeicaDarkColorScheme,
            typography = LeicaTypography,
            shapes = LeicaShapes,
            content = content,
        )
    }
}

/** Token accessors. Prefer `LeicaTokens.spacing.l` over Material defaults. */
object LeicaTokens {
    val colors: LeicaColorScheme
        @Composable get() = LocalLeicaColors.current
    val spacing: LeicaSpacing
        @Composable get() = LocalLeicaSpacing.current
    val motion: LeicaMotion
        @Composable get() = LocalLeicaMotion.current
    val elevation: LeicaElevation
        @Composable get() = LocalLeicaElevation.current
}