package com.leica.cam.feature.camera.controls

import com.leica.cam.feature.camera.ui.ProCaptureRequest
import com.leica.cam.feature.camera.ui.ProModeController
import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureControlsViewModelTest {
    @Test
    fun `test format labels`() {
        var state = CaptureControlsUiState(shutterUs = 4_000L, exposureEv = 0.5f)
        assertEquals("1/250", state.shutterLabel)
        assertEquals("+0.5", state.evLabel)
        
        state = CaptureControlsUiState(shutterUs = 1_000_000L, exposureEv = -1.5f)
        assertEquals("1.0s", state.shutterLabel)
        assertEquals("-1.5", state.evLabel)
    }
}