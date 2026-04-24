package com.leica.cam.feature.camera.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.HdrOn
import androidx.compose.material.icons.filled.Lens
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.leica.cam.feature.camera.controls.CaptureControlsViewModel
import com.leica.cam.feature.camera.preview.CameraPreview
import com.leica.cam.feature.camera.preview.SessionCommandBus
import com.leica.cam.feature.settings.preferences.CameraFacing
import com.leica.cam.feature.settings.preferences.CameraPreferencesRepository
import com.leica.cam.feature.settings.preferences.FlashMode
import com.leica.cam.feature.settings.preferences.GridStyle
import com.leica.cam.imaging_pipeline.api.UserHdrMode
import com.leica.cam.sensor_hal.session.CaptureFlashMode
import com.leica.cam.sensor_hal.session.Camera2CameraController
import com.leica.cam.sensor_hal.session.CameraSessionManager
import com.leica.cam.ui_components.camera.AfBracket
import com.leica.cam.ui_components.camera.CameraGesture
import com.leica.cam.ui_components.camera.CameraMode
import com.leica.cam.ui_components.camera.CompositionOverlay
import com.leica.cam.ui_components.camera.LeicaModeSwitcher
import com.leica.cam.ui_components.camera.LeicaShutterButton
import com.leica.cam.ui_components.camera.LumaFrame
import com.leica.cam.ui_components.camera.Phase9UiStateCalculator
import com.leica.cam.ui_components.camera.SceneBadge
import com.leica.cam.ui_components.camera.ViewfinderGridStyle
import com.leica.cam.ui_components.camera.ViewfinderOverlay
import com.leica.cam.ui_components.theme.LeicaRed
import com.leica.cam.ui_components.theme.LeicaTokens
import javax.inject.Inject
import kotlinx.coroutines.launch

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
    val sessionCommandBus: SessionCommandBus,
)

@Composable
fun CameraScreen(
    deps: CameraScreenDeps,
    onOpenGallery: () -> Unit = {},
    controlsVm: CaptureControlsViewModel = hiltViewModel(),
) {
    val tokens = LeicaTokens.colors
    val spacing = LeicaTokens.spacing
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    val preferences by deps.preferences.state.collectAsState()

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

    var currentZoom by rememberSaveable { mutableStateOf(preferences.currentZoom) }

    Box(modifier = Modifier.fillMaxSize().background(tokens.background)) {

        // Real preview & viewfinder
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 220.dp)
        ) {
            CameraPreview(
                modifier = Modifier.fillMaxSize(),
                controller = deps.cameraController,
                sessionManager = deps.sessionManager,
                commandBus = deps.sessionCommandBus,
            )

            ViewfinderOverlay(
                state = overlayState,
                modifier = Modifier.fillMaxSize(),
            )

            // Zoom control pill
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = spacing.m)
                    .background(tokens.surfaceTranslucent, CircleShape)
                    .padding(horizontal = spacing.m, vertical = spacing.s),
                horizontalArrangement = Arrangement.spacedBy(spacing.m)
            ) {
                listOf(0.6f, 1f, 2f).forEach { zoom ->
                    Text(
                        text = if (zoom == 1f) "1x" else zoom.toString(),
                        color = if (currentZoom == zoom) tokens.onBackground else tokens.onSurfaceMuted,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.clickable {
                            currentZoom = zoom
                            deps.preferences.update { it.copy(currentZoom = zoom) }
                            deps.cameraController.setZoomRatio(zoom)
                        }
                    )
                }
            }

            // Wand filter icon
            IconButton(
                onClick = { },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = spacing.m)
                    .background(tokens.surfaceTranslucent, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.AutoFixHigh,
                    contentDescription = "Wand",
                    tint = tokens.onBackground
                )
            }
        }

        // Top HUD
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.l, vertical = spacing.m)
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                val next = preferences.flashMode.next()
                deps.preferences.update { it.copy(flashMode = next) }
                deps.cameraController.setFlashMode(
                    when (next) {
                        FlashMode.OFF -> CaptureFlashMode.OFF
                        FlashMode.ON -> CaptureFlashMode.ON
                        FlashMode.AUTO -> CaptureFlashMode.AUTO
                    },
                )
            }) {
                Icon(Icons.Default.FlashOn, contentDescription = "Flash", tint = tokens.onBackground)
            }
            IconButton(onClick = {
                val next = when (preferences.hdr.mode) {
                    UserHdrMode.OFF -> UserHdrMode.ON
                    UserHdrMode.ON -> UserHdrMode.SMART
                    UserHdrMode.SMART -> UserHdrMode.PRO_XDR
                    UserHdrMode.PRO_XDR -> UserHdrMode.OFF
                }
                deps.preferences.update { it.copy(hdr = it.hdr.copy(mode = next)) }
            }) {
                Icon(Icons.Default.HdrOn, contentDescription = "HDR", tint = tokens.onBackground)
            }
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(LeicaRed, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Camera,
                    contentDescription = "Leica",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(onClick = { }) {
                Icon(Icons.Default.Lens, contentDescription = "Lens", tint = tokens.onBackground)
            }
            IconButton(onClick = {
                deps.preferences.update {
                    it.copy(grid = it.grid.copy(showHorizonGuide = !it.grid.showHorizonGuide))
                }
            }) {
                Icon(Icons.Default.Menu, contentDescription = "Menu", tint = tokens.onBackground)
            }
        }

        // Bottom chrome
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(tokens.surfaceTranslucent)
                .padding(bottom = spacing.xl, top = spacing.m),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LeicaModeSwitcher(
                modes = CameraMode.entries,
                selectedMode = currentMode,
                onModeSelected = {
                    deps.modeSwitcher.setMode(it)
                    currentMode = deps.modeSwitcher.currentMode()
                },
            )

            Spacer(modifier = Modifier.height(spacing.l))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.xl),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onOpenGallery) {
                    Icon(
                        imageVector = Icons.Default.PhotoLibrary,
                        contentDescription = "Gallery",
                        tint = tokens.onBackground,
                        modifier = Modifier.size(32.dp)
                    )
                }

                LeicaShutterButton(onClick = {
                    deps.orchestrator.handleGesture(CameraGesture.Tap(0.5f, 0.5f), 1.0f)
                    coroutineScope.launch {
                        val captureResult = deps.sessionManager.capture()
                        if (captureResult is com.leica.cam.common.result.LeicaResult.Failure) {
                            android.widget.Toast.makeText(
                                context,
                                "Capture failed: ${captureResult.message}",
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                })

                IconButton(onClick = {
                    val nextFacing = preferences.cameraFacing.toggled()
                    deps.preferences.update { it.copy(cameraFacing = nextFacing) }
                    coroutineScope.launch {
                        deps.sessionCommandBus.send(com.leica.cam.feature.camera.preview.SessionCommand.Close)
                        val switched = deps.cameraController.switchCameraFacing(nextFacing == CameraFacing.FRONT)
                        if (switched is com.leica.cam.common.result.LeicaResult.Failure) {
                            android.widget.Toast.makeText(context, switched.message, android.widget.Toast.LENGTH_SHORT).show()
                        }
                        deps.sessionCommandBus.send(com.leica.cam.feature.camera.preview.SessionCommand.Open)
                    }
                }) {
                    Icon(
                        imageVector = Icons.Default.Cameraswitch,
                        contentDescription = "Switch Camera",
                        tint = tokens.onBackground,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }
}
