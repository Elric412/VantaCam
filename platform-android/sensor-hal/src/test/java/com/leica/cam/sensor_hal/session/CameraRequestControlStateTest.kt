package com.leica.cam.sensor_hal.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraRequestControlStateTest {
    @Test
    fun `manual exposure becomes active when iso or shutter is set`() {
        val state = CameraRequestControlState()
            .withIso(400)
            .withShutterUs(8_000L)

        assertTrue(state.usesManualExposure)
        assertEquals(400, state.manualIso)
        assertEquals(8_000L, state.manualShutterUs)
    }

    @Test
    fun `exposure compensation rounds to nearest supported step and clamps to range`() {
        val state = CameraRequestControlState(exposureCompensationEv = 1.2f)

        assertEquals(4, state.exposureCompensationIndex(stepEv = 0.3333f, supportedRange = -12..12))
        assertEquals(12, state.copy(exposureCompensationEv = 9f).exposureCompensationIndex(stepEv = 0.3333f, supportedRange = -12..12))
    }

    @Test
    fun `white balance gains bias blue for warm scenes and red for cool scenes`() {
        val warm = WhiteBalanceGains.fromKelvin(3_000)
        val cool = WhiteBalanceGains.fromKelvin(7_500)

        assertTrue(warm.blue > warm.red)
        assertTrue(cool.red > cool.blue)
    }
}
