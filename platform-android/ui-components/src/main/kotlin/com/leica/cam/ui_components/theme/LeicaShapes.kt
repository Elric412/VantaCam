package com.leica.cam.ui_components.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Leica firmware feel: rectangular chrome (0dp), but soft corners on
 * interactive surfaces (dial sheets, pills) so touch targets read as
 * pressable rather than rigid chrome.
 */
val LeicaShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)