package com.leica.cam.feature.camera.ui

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.leica.cam.ui_components.camera.AfBracket
import com.leica.cam.ui_components.camera.CameraGesture
import com.leica.cam.ui_components.camera.CameraMode
import com.leica.cam.ui_components.camera.LeicaControlDial
import com.leica.cam.ui_components.camera.LeicaModeSwitcher
import com.leica.cam.ui_components.camera.LeicaShutterButton
import com.leica.cam.ui_components.camera.LumaFrame
import com.leica.cam.ui_components.camera.Phase9UiStateCalculator
import com.leica.cam.ui_components.camera.SceneBadge
import com.leica.cam.ui_components.camera.ViewfinderOverlay
import com.leica.cam.ui_components.theme.LeicaBlack

@Composable
fun CameraScreen(
    orchestrator: CameraUiOrchestrator,
    uiStateCalculator: Phase9UiStateCalculator,
    modeSwitcher: CameraModeSwitcher
) {
    // State driven by orchestrator and calculator (simulated)
    var currentMode by remember { mutableStateOf(modeSwitcher.currentMode()) }
    var overlayState by remember {
        mutableStateOf(
            uiStateCalculator.buildOverlayState(
                lumaFrame = LumaFrame(1, 1, byteArrayOf(0)),
                afBracket = AfBracket(0.5f, 0.5f, 0.1f, false),
                faces = emptyList(),
                shotQualityScore = 0.8f,
                horizonTiltDegrees = 0.5f,
                sceneBadge = SceneBadge("AUTO", 0.9f)
            )
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LeicaBlack)
    ) {
        // Viewfinder Area
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 200.dp)
                .background(Color.DarkGray)
        ) {
            ViewfinderOverlay(state = overlayState)

            // Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("BATT 85%", color = Color.White, style = MaterialTheme.typography.labelSmall)
                Text(overlayState.sceneBadge.label.uppercase(), color = Color.White, style = MaterialTheme.typography.labelSmall)
                Text("SD 12.4GB", color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
        }

        // Bottom Controls
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(LeicaBlack)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Manual Dials (Simplified)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                LeicaControlDial("ISO", "200", {})
                LeicaControlDial("Shutter", "1/250", {})
                LeicaControlDial("EV", "+0.0", {})
                LeicaControlDial("WB", "AUTO", {})
            }

            // Mode Switcher
            LeicaModeSwitcher(
                modes = CameraMode.entries,
                selectedMode = currentMode,
                onModeSelected = {
                    modeSwitcher.setMode(it)
                    currentMode = modeSwitcher.currentMode()
                }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Shutter Button
            LeicaShutterButton(onClick = {
                orchestrator.handleGesture(CameraGesture.Tap(0.5f, 0.5f), 1.0f)
            })
        }
    }
}
