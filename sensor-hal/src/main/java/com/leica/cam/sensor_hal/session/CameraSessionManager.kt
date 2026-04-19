package com.leica.cam.sensor_hal.session

import android.hardware.camera2.CameraAccessException
import com.leica.cam.common.Logger
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Lifecycle orchestrator for opening, configuring, and closing camera sessions with retry.
 */
class CameraSessionManager(
    private val stateMachine: CameraSessionStateMachine,
    private val cameraController: CameraController,
    private val cameraSelector: CameraSelector,
    private val retryPolicy: RetryPolicy = RetryPolicy.default,
) {
    private val mutex = Mutex()

    suspend fun openSession() {
        mutex.withLock {
            stateMachine.transition(CameraSessionEvent.OPEN_REQUESTED)
            retryWithBackoff("openSession") {
                val selectedCameraId = cameraSelector.selectCameraId(cameraController.availableCameraIds())
                cameraController.openCamera(selectedCameraId)
                stateMachine.transition(CameraSessionEvent.OPENED)
                cameraController.configureSession(selectedCameraId)
                stateMachine.transition(CameraSessionEvent.CONFIGURED)
            }
        }
    }

    suspend fun capture() {
        mutex.withLock {
            stateMachine.transition(CameraSessionEvent.CAPTURE_REQUESTED)
            cameraController.capture()
            stateMachine.transition(CameraSessionEvent.CAPTURE_STARTED)
            stateMachine.transition(CameraSessionEvent.PROCESSING_COMPLETED)
        }
    }

    suspend fun closeSession() {
        mutex.withLock {
            stateMachine.transition(CameraSessionEvent.CLOSE_REQUESTED)
            cameraController.closeCamera()
            stateMachine.transition(CameraSessionEvent.CLOSED)
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
    suspend fun capture()
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
