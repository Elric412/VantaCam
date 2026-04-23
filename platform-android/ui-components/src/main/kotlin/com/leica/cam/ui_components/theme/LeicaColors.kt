package com.leica.cam.ui_components.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Leica brand palette. Extended from the previous 5-colour palette to a full
 * 3-tier token system: brand, surface ramp, content ramp, semantic.
 *
 * Only [LeicaRed] and [LeicaBlack] are "signature" and must not be adjusted.
 * Every other token is design-system mutable.
 */
object LeicaPalette {
    // Brand — sacred
    val Red = Color(0xFFE4002B)
    val Black = Color(0xFF0A0A0A)
    val White = Color(0xFFFFFFFF)

    // Surface ramp (dark)
    val Surface0 = Color(0xFF000000)
    val Surface1 = Color(0xFF0A0A0A)
    val Surface2 = Color(0xFF141414)
    val Surface3 = Color(0xFF1C1C1C)
    val Surface4 = Color(0xFF242424)
    val SurfaceTranslucent = Color(0xCC0A0A0A)

    // Content ramp
    val Content0 = Color(0xFFFFFFFF)
    val Content1 = Color(0xFFE8E8E8)
    val Content2 = Color(0xFFB4B4B4)
    val Content3 = Color(0xFF7A7A7A)
    val Content4 = Color(0xFF4A4A4A)

    // Semantic
    val Success = Color(0xFF4CAF50)
    val Warning = Color(0xFFFFB300)
    val Error = Color(0xFFE4002B) // reuse LeicaRed for destructive
    val Info = Color(0xFF80DEEA)

    // Overlay stroke tokens for HUD
    val OverlayStroke = Color(0xCCFFFFFF)
    val OverlayStrokeMuted = Color(0x66FFFFFF)
    val FocusLocked = Color(0xFFFFC400)
}

@Immutable
data class LeicaColorScheme(
    val brand: Color = LeicaPalette.Red,
    val background: Color = LeicaPalette.Surface1,
    val surface: Color = LeicaPalette.Surface2,
    val surfaceElevated: Color = LeicaPalette.Surface3,
    val surfaceTranslucent: Color = LeicaPalette.SurfaceTranslucent,
    val onBackground: Color = LeicaPalette.Content0,
    val onSurface: Color = LeicaPalette.Content1,
    val onSurfaceMuted: Color = LeicaPalette.Content2,
    val onSurfaceDisabled: Color = LeicaPalette.Content4,
    val success: Color = LeicaPalette.Success,
    val warning: Color = LeicaPalette.Warning,
    val error: Color = LeicaPalette.Error,
    val info: Color = LeicaPalette.Info,
    val overlayStroke: Color = LeicaPalette.OverlayStroke,
    val overlayStrokeMuted: Color = LeicaPalette.OverlayStrokeMuted,
    val focusLocked: Color = LeicaPalette.FocusLocked,
)

// Back-compat shims so existing call-sites that import LeicaRed/LeicaBlack/LeicaWhite/LeicaGray/LeicaDarkGray keep compiling.
val LeicaRed = LeicaPalette.Red
val LeicaBlack = LeicaPalette.Black
val LeicaWhite = LeicaPalette.White
val LeicaGray = LeicaPalette.Content2
val LeicaDarkGray = LeicaPalette.Surface3