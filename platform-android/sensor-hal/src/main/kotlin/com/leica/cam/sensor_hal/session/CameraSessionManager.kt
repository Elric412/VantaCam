package com.leica.cam.sensor_hal.session

import android.hardware.camera2.CameraAccessException
import com.leica.cam.common.Logger
import com.leica.cam.common.result.LeicaResult
import com.leica.cam.common.result.PipelineStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Lifecycle orchestrator for opening, configuring, capturing, and closing the
 * active camera session with retry and idempotent lifecycle guards.
 */
class CameraSessionManager(
    private val stateMachine: CameraSessionStateMachine,
    private val cameraController: CameraController,
    private val cameraSelector: CameraSelector,
    private val retryPolicy: RetryPolicy = RetryPolicy.default,
) {
    private val mutex = Mutex()

    suspend fun openSession(): LeicaResult<Unit> {
        return mutex.withLock {
            if (stateMachine.currentState() != CameraSessionState.CLOSED) {
                Logger.d(TAG, "openSession ignored because state=${stateMachine.currentState()}")
                return@withLock LeicaResult.Success(Unit)
            }

            stateMachine.transition(CameraSessionEvent.OPEN_REQUESTED)
            runCatching {
                retryWithBackoff("openSession") {
                    val selectedCameraId = cameraSelector.selectCameraId(cameraController.availableCameraIds())
                    cameraController.openCamera(selectedCameraId)
                    stateMachine.transition(CameraSessionEvent.OPENED)
                    cameraController.configureSession(selectedCameraId)
                    stateMachine.transition(CameraSessionEvent.CONFIGURED)
                }
            }.fold(
                onSuccess = { LeicaResult.Success(Unit) },
                onFailure = { throwable ->
                    if (throwable is CancellationException) throw throwable
                    Logger.e(TAG, "openSession failed; closing partial camera session", throwable)
                    closeAfterFailedOpen()
                    LeicaResult.Failure.Hardware(errorCode = -1, message = throwable.message ?: "Camera open failed", cause = throwable)
                },
            )
        }
    }

    suspend fun capture(): LeicaResult<Unit> {
        return mutex.withLock {
            if (stateMachine.currentState() != CameraSessionState.IDLE) {
                Logger.d(TAG, "capture ignored because state=${stateMachine.currentState()}")
                return@withLock LeicaResult.Failure.Pipeline(PipelineStage.SESSION, "Capture requested in non-idle session state")
            }

            stateMachine.transition(CameraSessionEvent.CAPTURE_REQUESTED)
            stateMachine.transition(CameraSessionEvent.CAPTURE_STARTED)
            val captureResult = runCatching { cameraController.capture() }.getOrElse { throwable ->
                if (throwable is CancellationException) throw throwable
                LeicaResult.Failure.Hardware(errorCode = -1, message = throwable.message ?: "Capture crashed", cause = throwable)
            }
            when (captureResult) {
                is LeicaResult.Success -> {
                    stateMachine.transition(CameraSessionEvent.PROCESSING_COMPLETED)
                    LeicaResult.Success(Unit)
                }
                is LeicaResult.Failure -> {
                    stateMachine.transition(CameraSessionEvent.ERROR)
                    captureResult
                }
            }
        }
    }

    suspend fun closeSession() {
        mutex.withLock {
            val currentState = stateMachine.currentState()
            if (currentState == CameraSessionState.CLOSED) {
                return
            }

            if (currentState != CameraSessionState.CLOSING) {
                stateMachine.transition(CameraSessionEvent.CLOSE_REQUESTED)
            }
            cameraController.closeCamera()
            stateMachine.transition(CameraSessionEvent.CLOSED)
        }
    }

    private suspend fun closeAfterFailedOpen() {
        runCatching { cameraController.closeCamera() }
        when (stateMachine.currentState()) {
            CameraSessionState.CLOSED -> Unit
            CameraSessionState.CLOSING -> stateMachine.transition(CameraSessionEvent.CLOSED)
            else -> {
                runCatching { stateMachine.transition(CameraSessionEvent.ERROR) }
                if (stateMachine.currentState() == CameraSessionState.CLOSING) {
                    stateMachine.transition(CameraSessionEvent.CLOSED)
                }
            }
        }
    }

    private suspend fun retryWithBackoff(operation: String, block: suspend () -> Unit) {
        var attempts = 0
        while (true) {
            try {
                block()
                return
            } catch (exception: CameraAccessException) {
                attempts += 1
                if (attempts > retryPolicy.maxRetries) {
                    stateMachine.transition(CameraSessionEvent.ERROR)
                    throw exception
                }
                val backoff = retryPolicy.backoffForAttempt(attempts)
                Logger.e(TAG, "$operation failed, retry #$attempts after ${backoff}ms", exception)
                delay(backoff)
            } catch (exception: CameraDisconnectedException) {
                attempts += 1
                if (attempts > retryPolicy.maxRetries) {
                    stateMachine.transition(CameraSessionEvent.ERROR)
                    throw exception
                }
                val backoff = retryPolicy.backoffForAttempt(attempts)
                Logger.e(TAG, "$operation disconnected, retry #$attempts after ${backoff}ms", exception)
                delay(backoff)
            }
        }
    }

    private companion object {
        private const val TAG = "CameraSessionManager"
    }
}

/** Abstraction over low-level Camera2 interactions for testability. */
interface CameraController {
    fun availableCameraIds(): List<String>
    suspend fun openCamera(cameraId: String)
    suspend fun configureSession(cameraId: String)
    suspend fun capture(): LeicaResult<Unit>
    suspend fun closeCamera()
}

/** Camera selector policy for device/lens routing decisions. */
fun interface CameraSelector {
    fun selectCameraId(cameraIds: List<String>): String
}

/** Represents camera disconnection errors from low-level camera runtime. */
class CameraDisconnectedException(message: String) : RuntimeException(message)

/** Exponential backoff policy with deterministic schedule. */
data class RetryPolicy(
    val maxRetries: Int,
    val backoffScheduleMs: List<Long>,
) {
    fun backoffForAttempt(attempt: Int): Long = backoffScheduleMs[(attempt - 1).coerceAtMost(backoffScheduleMs.lastIndex)]

    companion object {
        val default = RetryPolicy(
            maxRetries = 3,
            backoffScheduleMs = listOf(100L, 400L, 1_600L),
        )
    }
}
