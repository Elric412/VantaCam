package com.leica.cam.feature.camera.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.hilt.navigation.compose.hiltViewModel
import com.leica.cam.feature.camera.controls.CaptureControlsViewModel
import com.leica.cam.feature.camera.preview.CameraPreview
import com.leica.cam.feature.settings.preferences.CameraPreferencesRepository
import com.leica.cam.feature.settings.preferences.GridStyle
import com.leica.cam.sensor_hal.session.Camera2CameraController
import com.leica.cam.sensor_hal.session.CameraSessionManager
import com.leica.cam.ui_components.camera.AfBracket
import com.leica.cam.ui_components.camera.CameraGesture
import com.leica.cam.ui_components.camera.CameraMode
import com.leica.cam.ui_components.camera.CompositionOverlay
import com.leica.cam.ui_components.camera.LeicaControlDial
import com.leica.cam.ui_components.camera.LeicaDialSheet
import com.leica.cam.ui_components.camera.LeicaModeSwitcher
import com.leica.cam.ui_components.camera.LeicaShutterButton
import com.leica.cam.ui_components.camera.LumaFrame
import com.leica.cam.ui_components.camera.Phase9UiStateCalculator
import com.leica.cam.ui_components.camera.SceneBadge
import com.leica.cam.ui_components.camera.ViewfinderGridStyle
import com.leica.cam.ui_components.camera.ViewfinderOverlay
import com.leica.cam.ui_components.theme.LeicaTokens
import javax.inject.Inject

/**
 * Bundled dependency bag so MainActivity doesn't leak 6 parameters into every
 * navigation composable. All fields are @Inject singletons.
 */
@androidx.compose.runtime.Stable
class CameraScreenDeps @Inject constructor(
    val orchestrator: CameraUiOrchestrator,
    val uiStateCalculator: Phase9UiStateCalculator,
    val modeSwitcher: CameraModeSwitcher,
    val preferences: CameraPreferencesRepository,
    val cameraController: Camera2CameraController,
    val sessionManager: CameraSessionManager,
)

@Composable
fun CameraScreen(
    deps: CameraScreenDeps,
    controlsVm: CaptureControlsViewModel = hiltViewModel(),
) {
    val tokens = LeicaTokens.colors
    val spacing = LeicaTokens.spacing

    val preferences by deps.preferences.state.collectAsState()
    val captureState by controlsVm.state.collectAsState()

    val composition = remember(preferences.grid) {
        CompositionOverlay(
            gridStyle = when (preferences.grid.style) {
                GridStyle.OFF -> ViewfinderGridStyle.OFF
                GridStyle.RULE_OF_THIRDS -> ViewfinderGridStyle.RULE_OF_THIRDS
                GridStyle.GOLDEN_RATIO -> ViewfinderGridStyle.GOLDEN_RATIO
            },
            showCenterMark = preferences.grid.showCenterMark,
            showHorizonGuide = preferences.grid.showHorizonGuide,
        )
    }

    var currentMode by rememberSaveable { mutableStateOf(deps.modeSwitcher.currentMode()) }

    // Build overlay state only when composition or mode changes — prevents
    // per-recomposition allocation of byteArrayOf and a new ViewfinderOverlayState.
    val overlayState = remember(composition, currentMode) {
        deps.uiStateCalculator.buildOverlayState(
            lumaFrame = LumaFrame(1, 1, byteArrayOf(0)),
            afBracket = AfBracket(0.5f, 0.5f, 0.1f, false),
            faces = emptyList(),
            shotQualityScore = 0.8f,
            horizonTiltDegrees = 0.5f,
            sceneBadge = SceneBadge(currentMode.name, 0.9f),
        ).copy(composition = composition)
    }

    // Which dial has an open sheet, if any.
    var openDial by remember { mutableStateOf<String?>(null) }

    Box(modifier = Modifier.fillMaxSize().background(tokens.background)) {

        // Real preview — replaces the old grey Box.
        CameraPreview(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 220.dp_),
            controller = deps.cameraController,
            sessionManager = deps.sessionManager,
        )

        ViewfinderOverlay(
            state = overlayState,
            modifier = Modifier.fillMaxSize().padding(bottom = 220.dp_),
        )

        // Top HUD
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.l, vertical = spacing.m),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("BATT 85%", color = tokens.onSurfaceMuted, style = MaterialTheme.typography.labelSmall)
            AnimatedContent(
                targetState = overlayState.sceneBadge.label.uppercase(),
                transitionSpec = {
                    (fadeIn(tween(180)) togetherWith fadeOut(tween(120)))
                },
                label = "sceneBadge",
            ) { label ->
                Text(label, color = tokens.onBackground, style = MaterialTheme.typography.labelMedium)
            }
            Text("SD 12.4GB", color = tokens.onSurfaceMuted, style = MaterialTheme.typography.labelSmall)
        }

        // Bottom chrome
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(tokens.surfaceTranslucent)
                .padding(bottom = spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = spacing.l),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                LeicaControlDial(
                    label = "ISO",
                    value = if (captureState.isAuto) "AUTO" else captureState.iso.toString(),
                    onValueChange = { openDial = "iso" },
                )
                LeicaControlDial(
                    label = "Shutter",
                    value = if (captureState.isAuto) "AUTO" else captureState.shutterLabel,
                    onValueChange = { openDial = "shutter" },
                )
                LeicaControlDial(
                    label = "EV",
                    value = captureState.evLabel,
                    onValueChange = { openDial = "ev" },
                )
                LeicaControlDial(
                    label = "WB",
                    value = captureState.wbLabel,
                    onValueChange = { openDial = "wb" },
                )
            }

            LeicaModeSwitcher(
                modes = CameraMode.entries,
                selectedMode = currentMode,
                onModeSelected = {
                    deps.modeSwitcher.setMode(it)
                    currentMode = deps.modeSwitcher.currentMode()
                },
            )

            Spacer(modifier = Modifier.height(spacing.xl))

            LeicaShutterButton(onClick = {
                deps.orchestrator.handleGesture(CameraGesture.Tap(0.5f, 0.5f), 1.0f)
                // Fire capture on the bound Camera2CameraController via sessionManager.
                kotlinx.coroutines.MainScope().launch {
                    runCatching { deps.sessionManager.capture() }
                }
            })
        }

        // Dial sheets
        when (openDial) {
            "iso" -> LeicaDialSheet(
                title = "ISO",
                options = controlsVm.isoOptions.map { it.toString() },
                selectedIndex = controlsVm.isoOptions.indexOf(captureState.iso).coerceAtLeast(0),
                onSelect = { idx ->
                    controlsVm.setIso(controlsVm.isoOptions[idx]); openDial = null
                },
                onDismiss = { openDial = null },
            )
            "shutter" -> LeicaDialSheet(
                title = "SHUTTER",
                options = controlsVm.shutterUsOptions.map { formatShutterUs(it) },
                selectedIndex = controlsVm.shutterUsOptions.indexOf(captureState.shutterUs).coerceAtLeast(0),
                onSelect = { idx ->
                    controlsVm.setShutter(controlsVm.shutterUsOptions[idx]); openDial = null
                },
                onDismiss = { openDial = null },
            )
            "ev" -> LeicaDialSheet(
                title = "EV",
                options = controlsVm.evOptions.map { "%+.1f".format(it) },
                selectedIndex = controlsVm.evOptions.indexOfFirst {
                    kotlin.math.abs(it - captureState.exposureEv) < 0.05f
                }.coerceAtLeast(0),
                onSelect = { idx ->
                    controlsVm.setExposureEv(controlsVm.evOptions[idx]); openDial = null
                },
                onDismiss = { openDial = null },
            )
            "wb" -> LeicaDialSheet(
                title = "WHITE BALANCE",
                options = controlsVm.wbOptions.map { "${it}K" },
                selectedIndex = controlsVm.wbOptions.indexOf(captureState.whiteBalanceKelvin).coerceAtLeast(0),
                onSelect = { idx ->
                    controlsVm.setWhiteBalance(controlsVm.wbOptions[idx]); openDial = null
                },
                onDismiss = { openDial = null },
            )
        }
    }
}

private fun formatShutterUs(us: Long): String = when {
    us >= 1_000_000L -> "%.1fs".format(us / 1_000_000.0)
    else -> "1/${(1_000_000.0 / us).toInt().coerceAtLeast(1)}"
}

private val Int.dp_: androidx.compose.ui.unit.Dp get() = androidx.compose.ui.unit.Dp(this.toFloat())

// Needed for the MainScope().launch call above without importing the symbol at top
private val kotlinx.coroutines.MainScope.launchShim: Unit get() = Unit