package com.leica.cam.ui_components.camera

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leica.cam.ui_components.theme.LeicaRed
import com.leica.cam.ui_components.theme.LeicaWhite

@Composable
fun LeicaShutterButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = com.leica.cam.ui_components.motion.LeicaHaptics.rememberShutterHaptic()
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val innerCircleScale by animateFloatAsState(
        targetValue = if (isPressed) 0.9f else 1.0f,
        label = "ShutterButtonScale"
    )

    Box(
        modifier = modifier
            .size(80.dp)
            .semantics {
                contentDescription = "Take Photo"
                role = Role.Button
            }
            .background(Color.Transparent, CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = { haptic(); onClick() }
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                color = Color.White,
                radius = size.minDimension / 2,
                style = Stroke(width = 4.dp.toPx())
            )
            drawCircle(
                color = LeicaRed,
                radius = (size.minDimension / 2 - 8.dp.toPx()) * innerCircleScale
            )
        }
    }
}

@Composable
fun LeicaModeSwitcher(
    modes: List<CameraMode>,
    selectedMode: CameraMode,
    onModeSelected: (CameraMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    LazyRow(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        contentPadding = PaddingValues(horizontal = 16.dp)
    ) {
        itemsIndexed(modes) { _, mode ->
            val isSelected = mode == selectedMode
            Text(
                text = mode.name,
                color = if (isSelected) LeicaRed else Color.White,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .semantics {
                        contentDescription = "Mode ${mode.name}${if (isSelected) ", selected" else ""}"
                        role = Role.Button
                    }
                    .clickable { onModeSelected(mode) }
            )
        }
    }
}

@Composable
fun ViewfinderOverlay(
    state: ViewfinderOverlayState,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        GridOverlay(
            composition = state.composition,
            horizonTiltDegrees = state.horizonTiltDegrees,
            horizonLevelLocked = state.horizonLevelLocked,
        )

        // AF Bracket
        Box(
            modifier = Modifier
                .offset(
                    x = (state.afBracket.centerX * 300).dp,
                    y = (state.afBracket.centerY * 500).dp
                )
                .size(state.afBracket.size.dp * 100)
                .background(Color.Transparent)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(
                    color = if (state.afBracket.locked) Color.Yellow else Color.White,
                    style = Stroke(width = 1.dp.toPx())
                )
            }
        }

        // Face Boxes
        state.faces.forEach { face ->
            Box(
                modifier = Modifier
                    .offset(x = (face.left * 300).dp, y = (face.top * 500).dp)
                    .size(((face.right - face.left) * 300).dp, ((face.bottom - face.top) * 500).dp)
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawRect(color = Color.White.copy(alpha = 0.5f), style = Stroke(width = 1.dp.toPx()))
                }
            }
        }
    }
}

@Composable
fun LeicaControlDial(
    label: String,
    value: String,
    onValueChange: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = com.leica.cam.ui_components.theme.LeicaTokens.colors
    val spacing = com.leica.cam.ui_components.theme.LeicaTokens.spacing

    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1.0f,
        label = "dialScale",
    )

    Column(
        modifier = modifier
            .padding(spacing.s)
            .semantics(mergeDescendants = true) { role = Role.Button }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onValueChange,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = tokens.onSurfaceMuted,
        )
        androidx.compose.animation.AnimatedContent(
            targetState = value,
            transitionSpec = {
                androidx.compose.animation.fadeIn() androidx.compose.animation.togetherWith androidx.compose.animation.fadeOut()
            },
            label = "dialValue",
        ) { v ->
            Text(
                text = v,
                style = MaterialTheme.typography.titleMedium,
                color = tokens.onBackground,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.graphicsLayerScale(scale),
            )
        }
    }
}

private fun Modifier.graphicsLayerScale(s: Float): Modifier =
    this.then(androidx.compose.ui.graphics.graphicsLayer { scaleX = s; scaleY = s })
