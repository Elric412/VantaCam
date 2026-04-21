package com.leica.cam.ui_components.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.leica.cam.ui_components.theme.LeicaRed
import com.leica.cam.ui_components.theme.LeicaWhite
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val RULE_OF_THIRDS_FIRST = 1f / 3f
private const val RULE_OF_THIRDS_SECOND = 2f / 3f
private const val GOLDEN_FIRST = 0.382f
private const val GOLDEN_SECOND = 0.618f
private const val GRID_ALPHA = 0.55f
private const val CENTER_MARK_ALPHA = 0.9f
private const val HORIZON_HALF_LENGTH_DP = 96
private const val CENTER_MARK_LENGTH_DP = 12

/**
 * Composition grid overlay for the viewfinder.
 *
 * Rule-of-thirds lines are drawn at 1/3 and 2/3 of each axis. Golden-ratio
 * lines are drawn at 0.382 and 0.618 of each axis.
 */
@Composable
fun GridOverlay(
    composition: CompositionOverlay,
    horizonTiltDegrees: Float,
    horizonLevelLocked: Boolean,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val strokeWidth = 1.dp.toPx()
        val gridColor = LeicaWhite.copy(alpha = GRID_ALPHA)
        gridFractions(composition.gridStyle).forEach { fraction ->
            drawLine(
                color = gridColor,
                start = Offset(size.width * fraction, 0f),
                end = Offset(size.width * fraction, size.height),
                strokeWidth = strokeWidth,
            )
            drawLine(
                color = gridColor,
                start = Offset(0f, size.height * fraction),
                end = Offset(size.width, size.height * fraction),
                strokeWidth = strokeWidth,
            )
        }
        if (composition.showCenterMark) {
            drawCenterMark(strokeWidth)
        }
        if (composition.showHorizonGuide) {
            drawHorizonGuide(horizonTiltDegrees, horizonLevelLocked)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCenterMark(strokeWidth: Float) {
    val centerX = size.width / 2f
    val centerY = size.height / 2f
    val markLength = CENTER_MARK_LENGTH_DP.dp.toPx()
    val color = LeicaWhite.copy(alpha = CENTER_MARK_ALPHA)
    drawLine(
        color = color,
        start = Offset(centerX - markLength, centerY),
        end = Offset(centerX + markLength, centerY),
        strokeWidth = strokeWidth,
    )
    drawLine(
        color = color,
        start = Offset(centerX, centerY - markLength),
        end = Offset(centerX, centerY + markLength),
        strokeWidth = strokeWidth,
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHorizonGuide(
    horizonTiltDegrees: Float,
    horizonLevelLocked: Boolean,
) {
    val centerX = size.width / 2f
    val centerY = size.height / 2f
    val halfLength = HORIZON_HALF_LENGTH_DP.dp.toPx()
    val radians = horizonTiltDegrees * PI.toFloat() / 180f
    val deltaX = cos(radians) * halfLength
    val deltaY = sin(radians) * halfLength
    drawLine(
        color = if (horizonLevelLocked) LeicaRed else LeicaWhite,
        start = Offset(centerX - deltaX, centerY - deltaY),
        end = Offset(centerX + deltaX, centerY + deltaY),
        strokeWidth = 2.dp.toPx(),
    )
}

private fun gridFractions(style: ViewfinderGridStyle): List<Float> = when (style) {
    ViewfinderGridStyle.OFF -> emptyList()
    ViewfinderGridStyle.RULE_OF_THIRDS -> listOf(RULE_OF_THIRDS_FIRST, RULE_OF_THIRDS_SECOND)
    ViewfinderGridStyle.GOLDEN_RATIO -> listOf(GOLDEN_FIRST, GOLDEN_SECOND)
}
