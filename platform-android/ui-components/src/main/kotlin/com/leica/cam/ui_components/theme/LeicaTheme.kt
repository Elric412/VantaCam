package com.leica.cam.ui_components.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes

// Leica Signature Colors
val LeicaRed = Color(0xFFE4002B)
val LeicaBlack = Color(0xFF131313)
val LeicaWhite = Color(0xFFFFFFFF)
val LeicaGray = Color(0xFFC6C6C6)
val LeicaDarkGray = Color(0xFF1F1F1F)

private val DarkColorScheme = darkColorScheme(
    primary = LeicaWhite,
    secondary = LeicaRed,
    tertiary = LeicaGray,
    background = LeicaBlack,
    surface = LeicaDarkGray,
    onPrimary = LeicaBlack,
    onSecondary = LeicaWhite,
    onBackground = LeicaWhite,
    onSurface = LeicaWhite
)

private val LightColorScheme = lightColorScheme(
    primary = LeicaBlack,
    secondary = LeicaRed,
    tertiary = Color.Gray,
    background = LeicaWhite,
    surface = Color(0xFFF5F5F5),
    onPrimary = LeicaWhite,
    onSecondary = LeicaWhite,
    onBackground = LeicaBlack,
    onSurface = LeicaBlack
)

val LeicaTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 0.5.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp
    )
)

val LeicaShapes = Shapes(
    small = RoundedCornerShape(0.dp),
    medium = RoundedCornerShape(0.dp),
    large = RoundedCornerShape(0.dp)
)

@Composable
fun LeicaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = LeicaTypography,
        shapes = LeicaShapes,
        content = content
    )
}
