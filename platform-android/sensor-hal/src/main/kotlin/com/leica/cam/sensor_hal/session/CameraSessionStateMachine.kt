package com.leica.cam.sensor_hal.session

import com.leica.cam.common.Logger
import java.time.Clock
import java.time.Instant

/**
 * Finite-state machine that governs camera session lifecycle.
 */
class CameraSessionStateMachine(
    private val clock: Clock = Clock.systemUTC(),
) {
    private var currentState: CameraSessionState = CameraSessionState.CLOSED

    fun currentState(): CameraSessionState = currentState

    fun transition(event: CameraSessionEvent): CameraTransitionRecord {
        val nextState = transitionTable[currentState]?.get(event)
            ?: throw IllegalStateException("Invalid transition: $currentState -> $event")
        val record = CameraTransitionRecord(
            timestamp = Instant.now(clock),
            previousState = currentState,
            event = event,
            newState = nextState,
        )
        Logger.d(TAG, "Transition ${record.previousState} --${record.event}--> ${record.newState} @ ${record.timestamp}")
        currentState = nextState
        return record
    }

    private companion object {
        private const val TAG = "CameraSessionStateMachine"

        private val transitionTable: Map<CameraSessionState, Map<CameraSessionEvent, CameraSessionState>> =
            mapOf(
                CameraSessionState.CLOSED to mapOf(
                    CameraSessionEvent.OPEN_REQUESTED to CameraSessionState.OPENING,
                ),
                CameraSessionState.OPENING to mapOf(
                    CameraSessionEvent.OPENED to CameraSessionState.CONFIGURING,
                    CameraSessionEvent.CLOSE_REQUESTED to CameraSessionState.CLOSING,
                    CameraSessionEvent.ERROR to CameraSessionState.CLOSING,
                ),
                CameraSessionState.CONFIGURING to mapOf(
                    CameraSessionEvent.CONFIGURED to CameraSessionState.IDLE,
                    CameraSessionEvent.CLOSE_REQUESTED to CameraSessionState.CLOSING,
                    CameraSessionEvent.ERROR to CameraSessionState.CLOSING,
                ),
                CameraSessionState.IDLE to mapOf(
                    CameraSessionEvent.CAPTURE_REQUESTED to CameraSessionState.CAPTURING,
                    CameraSessionEvent.PAUSE_REQUESTED to CameraSessionState.PAUSED,
                    CameraSessionEvent.CLOSE_REQUESTED to CameraSessionState.CLOSING,
                ),
                CameraSessionState.CAPTURING to mapOf(
                    CameraSessionEvent.CAPTURE_STARTED to CameraSessionState.PROCESSING,
                    CameraSessionEvent.CLOSE_REQUESTED to CameraSessionState.CLOSING,
                    CameraSessionEvent.ERROR to CameraSessionState.IDLE,
                ),
                CameraSessionState.PROCESSING to mapOf(
                    CameraSessionEvent.PROCESSING_COMPLETED to CameraSessionState.IDLE,
                    CameraSessionEvent.CLOSE_REQUESTED to CameraSessionState.CLOSING,
                    CameraSessionEvent.ERROR to CameraSessionState.IDLE,
                ),
                CameraSessionState.PAUSED to mapOf(
                    CameraSessionEvent.RESUME_REQUESTED to CameraSessionState.IDLE,
                    CameraSessionEvent.CLOSE_REQUESTED to CameraSessionState.CLOSING,
                ),
                CameraSessionState.CLOSING to mapOf(
                    CameraSessionEvent.CLOSED to CameraSessionState.CLOSED,
                ),
            )
    }
}

/** Valid camera session lifecycle states. */
enum class CameraSessionState {
    CLOSED,
    OPENING,
    CONFIGURING,
    IDLE,
    CAPTURING,
    PROCESSING,
    PAUSED,
    CLOSING,
}

/** Triggers that drive state transitions. */
enum class CameraSessionEvent {
    OPEN_REQUESTED,
    OPENED,
    CONFIGURED,
    CAPTURE_REQUESTED,
    CAPTURE_STARTED,
    PROCESSING_COMPLETED,
    PAUSE_REQUESTED,
    RESUME_REQUESTED,
    CLOSE_REQUESTED,
    CLOSED,
    ERROR,
}

/** Auditable transition record for diagnostics and analytics. */
data class CameraTransitionRecord(
    val timestamp: Instant,
    val previousState: CameraSessionState,
    val event: CameraSessionEvent,
    val newState: CameraSessionState,
)
