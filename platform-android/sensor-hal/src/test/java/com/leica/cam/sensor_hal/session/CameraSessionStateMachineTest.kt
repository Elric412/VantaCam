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

    @Test
    fun `capture error recovers back to idle so next shutter press is possible`() {
        val machine = CameraSessionStateMachine()

        machine.transition(CameraSessionEvent.OPEN_REQUESTED)
        machine.transition(CameraSessionEvent.OPENED)
        machine.transition(CameraSessionEvent.CONFIGURED)
        machine.transition(CameraSessionEvent.CAPTURE_REQUESTED)
        machine.transition(CameraSessionEvent.CAPTURE_STARTED)
        machine.transition(CameraSessionEvent.ERROR)

        assertEquals(CameraSessionState.IDLE, machine.currentState())
    }

    @Test
    fun `lifecycle close is valid while opening and configuring`() {
        val opening = CameraSessionStateMachine()
        opening.transition(CameraSessionEvent.OPEN_REQUESTED)
        opening.transition(CameraSessionEvent.CLOSE_REQUESTED)
        opening.transition(CameraSessionEvent.CLOSED)
        assertEquals(CameraSessionState.CLOSED, opening.currentState())

        val configuring = CameraSessionStateMachine()
        configuring.transition(CameraSessionEvent.OPEN_REQUESTED)
        configuring.transition(CameraSessionEvent.OPENED)
        configuring.transition(CameraSessionEvent.CLOSE_REQUESTED)
        configuring.transition(CameraSessionEvent.CLOSED)
        assertEquals(CameraSessionState.CLOSED, configuring.currentState())
    }

    @Test(expected = IllegalStateException::class)
    fun `throws on invalid transition`() {
        val machine = CameraSessionStateMachine()
        machine.transition(CameraSessionEvent.CAPTURE_REQUESTED)
    }
}
