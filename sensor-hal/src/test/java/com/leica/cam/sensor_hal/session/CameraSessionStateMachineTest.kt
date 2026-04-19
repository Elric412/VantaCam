package com.leica.cam.sensor_hal.session

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraSessionStateMachineTest {
    @Test
    fun `transitions through happy path`() {
        val machine = CameraSessionStateMachine()

        machine.transition(CameraSessionEvent.OPEN_REQUESTED)
        machine.transition(CameraSessionEvent.OPENED)
        machine.transition(CameraSessionEvent.CONFIGURED)
        machine.transition(CameraSessionEvent.CAPTURE_REQUESTED)
        machine.transition(CameraSessionEvent.CAPTURE_STARTED)
        machine.transition(CameraSessionEvent.PROCESSING_COMPLETED)
        machine.transition(CameraSessionEvent.CLOSE_REQUESTED)
        machine.transition(CameraSessionEvent.CLOSED)

        assertEquals(CameraSessionState.CLOSED, machine.currentState())
    }

    @Test(expected = IllegalStateException::class)
    fun `throws on invalid transition`() {
        val machine = CameraSessionStateMachine()
        machine.transition(CameraSessionEvent.CAPTURE_REQUESTED)
    }
}
