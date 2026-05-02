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
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.HdrOff
import androidx.compose.material.icons.filled.HdrOn
import androidx.compose.material.icons.filled.Lens
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.leica.cam.capture.orchestrator.CaptureProcessingOrchestrator
import com.leica.cam.capture.orchestrator.CaptureRequest
import com.leica.cam.capture.orchestrator.HdrCaptureMode
import com.leica.cam.common.result.LeicaResult
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
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
    val captureOrchestrator: CaptureProcessingOrchestrator,
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

            // Wand filter icon — cycles through AI-assisted scene enhancements.
            // On press, requests a new AI scene classification so the smart-imaging
            // overlay reflects the latest scene context (e.g. portrait → skin-anchor WB).
            IconButton(
                onClick = {
                    coroutineScope.launch {
                        deps.orchestrator.handleGesture(
                            CameraGesture.Tap(0.5f, 0.5f),
                            currentZoom,
                        )
                    }
                },
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
            // Flash button — icon and tint reflect the current mode.
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
                AnimatedContent(
                    targetState = preferences.flashMode,
                    transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) },
                    label = "FlashIconAnim",
                ) { flashMode ->
                    val (flashIcon: ImageVector, flashTint) = when (flashMode) {
                        FlashMode.OFF  -> Icons.Default.FlashOff  to tokens.onSurfaceMuted
                        FlashMode.ON   -> Icons.Default.FlashOn   to Color(0xFFFFD600)   // amber
                        FlashMode.AUTO -> Icons.Default.FlashAuto to Color(0xFFFFD600)
                    }
                    Icon(flashIcon, contentDescription = "Flash: ${flashMode.name}", tint = flashTint)
                }
            }
            // HDR button — badge label shows current mode.
            IconButton(onClick = {
                val next = when (preferences.hdr.mode) {
                    UserHdrMode.OFF     -> UserHdrMode.ON
                    UserHdrMode.ON      -> UserHdrMode.SMART
                    UserHdrMode.SMART   -> UserHdrMode.PRO_XDR
                    UserHdrMode.PRO_XDR -> UserHdrMode.OFF
                }
                deps.preferences.update { it.copy(hdr = it.hdr.copy(mode = next)) }
            }) {
                AnimatedContent(
                    targetState = preferences.hdr.mode,
                    transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) },
                    label = "HdrIconAnim",
                ) { hdrMode ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        val (hdrIcon: ImageVector, hdrTint) = when (hdrMode) {
                            UserHdrMode.OFF     -> Icons.Default.HdrOff to tokens.onSurfaceMuted
                            UserHdrMode.ON      -> Icons.Default.HdrOn  to tokens.onBackground
                            UserHdrMode.SMART   -> Icons.Default.HdrOn  to Color(0xFF4FC3F7)  // light-blue
                            UserHdrMode.PRO_XDR -> Icons.Default.HdrOn  to Color(0xFFFFD600)  // gold
                        }
                        Icon(hdrIcon, contentDescription = "HDR: ${hdrMode.name}", tint = hdrTint)
                        Text(
                            text = when (hdrMode) {
                                UserHdrMode.OFF     -> "OFF"
                                UserHdrMode.ON      -> "ON"
                                UserHdrMode.SMART   -> "SMART"
                                UserHdrMode.PRO_XDR -> "PRO"
                            },
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = androidx.compose.ui.unit.TextUnit(
                                    8f, androidx.compose.ui.unit.TextUnitType.Sp
                                )
                            ),
                            color = hdrTint,
                        )
                    }
                }
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
            // Lens icon — cycles through available focal-length lenses on the device.
            // The zoom steps (0.6x ultrawide → 1x main → 2x tele) mirror the zoom pill
            // below; pressing cycles forward and updates the controller + persisted pref.
            IconButton(onClick = {
                val zoomSteps = listOf(0.6f, 1f, 2f)
                val nextZoom = zoomSteps[(zoomSteps.indexOf(currentZoom).takeIf { it >= 0 }
                    ?.let { (it + 1) % zoomSteps.size } ?: 1)]
                currentZoom = nextZoom
                deps.preferences.update { it.copy(currentZoom = nextZoom) }
                deps.cameraController.setZoomRatio(nextZoom)
            }) {
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
                        val pipelineResult = runCatching {
                            // ── Full LUMO Capture Pipeline ───────────────────────
                            // Build a CaptureRequest from current UI state (HDR mode,
                            // colour profile, tone config) and run the complete
                            // photon-to-pixel processing chain. This path can still
                            // fail on devices whose ZSL/RAW frame feed is warming up,
                            // so failures always fall back to the already-bound
                            // CameraX still capture below instead of crashing the UI.
                            val hdrMode = when (preferences.hdr.mode) {
                                UserHdrMode.OFF     -> HdrCaptureMode.OFF
                                UserHdrMode.ON      -> HdrCaptureMode.ON
                                UserHdrMode.SMART   -> HdrCaptureMode.SMART
                                UserHdrMode.PRO_XDR -> HdrCaptureMode.PRO_XDR
                            }
                            deps.captureOrchestrator.processCapture(CaptureRequest(hdrMode = hdrMode))
                        }.getOrElse { throwable ->
                            if (throwable is CancellationException) throw throwable
                            LeicaResult.Failure.Pipeline(
                                stage = com.leica.cam.common.result.PipelineStage.SESSION,
                                message = throwable.message ?: "Capture pipeline crashed before producing an image",
                                cause = throwable,
                            )
                        }

                        when (pipelineResult) {
                            is LeicaResult.Success -> {
                                val result = pipelineResult.value
                                android.widget.Toast.makeText(
                                    context,
                                    "Captured: ${result.sceneAnalysis.sceneLabel} " +
                                        "(${result.captureLatencyMs}ms)",
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            }
                            is LeicaResult.Failure -> {
                                val fallbackResult = deps.sessionManager.capture()
                                val msg = if (fallbackResult is LeicaResult.Failure) {
                                    "Capture failed: ${fallbackResult.message}"
                                } else {
                                    "Captured (basic mode)"
                                }
                                android.widget.Toast.makeText(
                                    context, msg, android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    }
                })

                IconButton(onClick = {
                    val nextFacing = preferences.cameraFacing.toggled()
                    deps.preferences.update { it.copy(cameraFacing = nextFacing) }
                    coroutineScope.launch {
                        deps.cameraController.setPreferredCameraFacing(nextFacing == CameraFacing.FRONT)
                        deps.sessionManager.closeSession()
                        val reopened = deps.sessionManager.openSession()
                        if (reopened is com.leica.cam.common.result.LeicaResult.Failure) {
                            android.widget.Toast.makeText(context, reopened.message, android.widget.Toast.LENGTH_SHORT).show()
                        }
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
