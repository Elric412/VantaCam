package com.leica.cam.feature.camera.ui

import com.leica.cam.common.result.LeicaResult
import com.leica.cam.ui_components.camera.AfBracket
import com.leica.cam.ui_components.camera.CameraGesture
import com.leica.cam.ui_components.camera.CameraMode
import com.leica.cam.ui_components.camera.FaceBox
import com.leica.cam.ui_components.camera.LumaFrame
import com.leica.cam.ui_components.camera.Phase9UiStateCalculator
import com.leica.cam.ui_components.camera.SceneBadge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraUiOrchestratorPhase9Test {
    private val calculator = Phase9UiStateCalculator()

    @Test
    fun `overlay state contains histogram and horizon lock`() {
        val y = ByteArray(16) { i -> (i * 16).toByte() }
        val overlay = calculator.buildOverlayState(
            lumaFrame = LumaFrame(4, 4, y),
            afBracket = AfBracket(0.5f, 0.5f, 0.2f, locked = true),
            faces = listOf(FaceBox(0.2f, 0.2f, 0.5f, 0.6f, 0.9f)),
            shotQualityScore = 1.2f,
            horizonTiltDegrees = 0.5f,
            sceneBadge = SceneBadge("Portrait", 1.2f),
        )

        assertEquals(16, overlay.luminanceHistogram.sum())
        assertTrue(overlay.horizonLevelLocked)
        assertEquals(1f, overlay.shotQualityScore)
        assertEquals(1f, overlay.sceneBadge.confidence)
    }

    @Test
    fun `gesture driven mode switching rotates through all 11 camera modes`() {
        val switcher = CameraModeSwitcher(CameraMode.entries, initialMode = CameraMode.AUTO)
        val orchestrator = CameraUiOrchestrator(calculator, switcher)

        val result = orchestrator.handleGesture(
            gesture = CameraGesture.HorizontalSwipe(deltaNorm = 0.4f),
            currentZoomRatio = 1f,
        )

        assertTrue(result is LeicaResult.Success)
        assertEquals(CameraMode.NIGHT, (result as LeicaResult.Success).value)
    }

    @Test
    fun `post capture editor exposes at least 40 tools and clamps intensity`() {
        val editor = PostCaptureEditor()
        val start = editor.startSession()

        assertTrue(start.toolIntensities.size >= 40)

        val updated = editor.updateIntensity(start, "exposure", 10f)
        assertTrue(updated is LeicaResult.Success)
        val state = (updated as LeicaResult.Success).value
        assertEquals(1f, state.toolIntensities.getValue("exposure"))
        assertTrue(state.canUndo)
    }

    @Test
    fun `pro mode request clamps to safe ranges`() {
        val controller = ProModeController()

        val request = controller.buildManualRequest(
            iso = 99_999,
            shutterUs = 99_999_999L,
            whiteBalanceKelvin = 30_000,
            focusDistanceNorm = 3f,
            exposureCompensationEv = -10f,
        )

        assertEquals(6400, request.iso)
        assertEquals(30_000_000L, request.shutterUs)
        assertEquals(12_000, request.whiteBalanceKelvin)
        assertEquals(1f, request.focusDistanceNorm)
        assertEquals(-5f, request.exposureCompensationEv)
    }
}
