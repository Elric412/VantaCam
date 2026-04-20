package com.leica.cam.sensor_hal.autofocus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridAutoFocusEngineTest {
    private val engine = HybridAutoFocusEngine()

    @Test
    fun `prefers pdaf when pdaf reliability is high`() {
        val decision = engine.evaluate(
            AutoFocusInput(
                pdafPhaseError = 0.05f,
                contrastMetric = 0.2f,
                neuralSubjectConfidence = 0.3f,
            ),
        )

        assertEquals(FocusMode.PDAF_PRIMARY, decision.focusMode)
    }

    @Test
    fun `triggers sweep for low confidence scenes`() {
        val decision = engine.evaluate(
            AutoFocusInput(
                pdafPhaseError = 0.95f,
                contrastMetric = 0.18f,
                neuralSubjectConfidence = 0.05f,
            ),
        )

        assertTrue(decision.shouldTriggerFullSweep)
    }
}
