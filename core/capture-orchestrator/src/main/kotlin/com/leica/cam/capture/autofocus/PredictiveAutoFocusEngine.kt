package com.leica.cam.capture.autofocus

import com.leica.cam.sensor_hal.autofocus.FocusMode
import kotlin.math.abs

/**
 * Predictive Auto-Focus Engine with Kalman filter for motion-aware focus prediction.
 *
 * This engine extends the HybridAutoFocusEngine by adding temporal prediction:
 * - Kalman filter tracks focus position over time for moving subjects
 * - Predicts where focus should be at capture time (shutter-release delay compensation)
 * - Adapts filter gain based on scene motion characteristics
 * - Provides sub-frame focus position estimation for burst captures
 *
 * The Kalman state vector is [position, velocity] where:
 * - position = normalized focus distance (0.0 = infinity, 1.0 = macro)
 * - velocity = rate of focus change per millisecond
 *
 * Reference: Implementation.md — Advanced Autofocus System
 */
class PredictiveAutoFocusEngine {

    // ── Kalman Filter State ──────────────────────────────────────────
    private var statePosition: Float = 0.5f
    private var stateVelocity: Float = 0f
    private var errorCovarianceP00: Float = 1f
    private var errorCovarianceP01: Float = 0f
    private var errorCovarianceP10: Float = 0f
    private var errorCovarianceP11: Float = 1f
    private var lastTimestampMs: Long = 0L
    private var isInitialized: Boolean = false

    // ── History for confidence smoothing ─────────────────────────────
    private val confidenceHistory = ArrayDeque<Float>(CONFIDENCE_HISTORY_SIZE)
    private val focusHistory = ArrayDeque<FocusObservation>(FOCUS_HISTORY_SIZE)

    /**
     * Predict focus state at capture time given current AF sensor readings.
     *
     * @param currentConfidence Fused confidence from HybridAutoFocusEngine [0..1]
     * @param focusMode Current focus mode (PDAF, Contrast, Hybrid)
     * @param timestampMs System time at which AF reading was taken
     * @param shutterDelayMs Expected delay from AF reading to actual capture (default 25ms)
     * @return Predicted focus state with confidence and position
     */
    fun predict(
        currentConfidence: Float,
        focusMode: FocusMode,
        timestampMs: Long,
        shutterDelayMs: Float = DEFAULT_SHUTTER_DELAY_MS,
    ): PredictedFocusState {
        val dt = if (isInitialized) {
            ((timestampMs - lastTimestampMs).coerceIn(1L, MAX_DT_MS)).toFloat()
        } else {
            DEFAULT_DT_MS
        }

        // Observation: map confidence to a pseudo-position measurement
        val observedPosition = confidenceToPosition(currentConfidence, focusMode)

        // ── Kalman Predict Step ──────────────────────────────────────
        // State prediction: x_pred = F * x
        val predictedPosition = statePosition + stateVelocity * dt
        val predictedVelocity = stateVelocity * VELOCITY_DECAY

        // Covariance prediction: P_pred = F * P * F^T + Q
        val newP00 = errorCovarianceP00 + dt * (errorCovarianceP10 + errorCovarianceP01) +
            dt * dt * errorCovarianceP11 + PROCESS_NOISE_POSITION
        val newP01 = errorCovarianceP01 + dt * errorCovarianceP11
        val newP10 = errorCovarianceP10 + dt * errorCovarianceP11
        val newP11 = errorCovarianceP11 + PROCESS_NOISE_VELOCITY

        // ── Kalman Update Step ───────────────────────────────────────
        // Innovation: y = z - H * x_pred (H = [1, 0])
        val innovation = observedPosition - predictedPosition

        // Innovation covariance: S = H * P_pred * H^T + R
        val measurementNoise = adaptiveMeasurementNoise(currentConfidence, focusMode)
        val innovationCovariance = newP00 + measurementNoise

        // Kalman gain: K = P_pred * H^T / S
        val gain0 = newP00 / innovationCovariance
        val gain1 = newP10 / innovationCovariance

        // State update: x = x_pred + K * y
        statePosition = (predictedPosition + gain0 * innovation).coerceIn(0f, 1f)
        stateVelocity = predictedVelocity + gain1 * innovation

        // Covariance update: P = (I - K * H) * P_pred
        errorCovarianceP00 = (1f - gain0) * newP00
        errorCovarianceP01 = (1f - gain0) * newP01
        errorCovarianceP10 = newP10 - gain1 * newP00
        errorCovarianceP11 = newP11 - gain1 * newP01

        lastTimestampMs = timestampMs
        isInitialized = true

        // Record observation history
        recordObservation(currentConfidence, observedPosition, timestampMs)

        // ── Forward Prediction to Capture Time ───────────────────────
        val capturePosition = (statePosition + stateVelocity * shutterDelayMs).coerceIn(0f, 1f)
        val captureConfidence = computeSmoothedConfidence(currentConfidence)

        // ── Motion Detection ─────────────────────────────────────────
        val isSubjectMoving = abs(stateVelocity) > MOTION_VELOCITY_THRESHOLD
        val motionMagnitude = abs(stateVelocity) * 1000f // per-second velocity

        return PredictedFocusState(
            predictedPosition = capturePosition,
            predictedConfidence = captureConfidence,
            currentPosition = statePosition,
            velocity = stateVelocity,
            isSubjectMoving = isSubjectMoving,
            motionMagnitude = motionMagnitude,
            focusMode = focusMode,
            kalmanGain = gain0,
        )
    }

    /**
     * Reset the Kalman filter state — called on camera switch or scene change.
     */
    fun reset() {
        statePosition = 0.5f
        stateVelocity = 0f
        errorCovarianceP00 = 1f
        errorCovarianceP01 = 0f
        errorCovarianceP10 = 0f
        errorCovarianceP11 = 1f
        lastTimestampMs = 0L
        isInitialized = false
        confidenceHistory.clear()
        focusHistory.clear()
    }

    // ──────────────────────────────────────────────────────────────────
    // Internal Helpers
    // ──────────────────────────────────────────────────────────────────

    private fun confidenceToPosition(confidence: Float, mode: FocusMode): Float {
        // Map AF confidence to a normalized focus position estimate.
        // High confidence = focused position is reliable.
        // Low confidence = position is uncertain, use last known good.
        val modeWeight = when (mode) {
            FocusMode.PDAF_PRIMARY -> 0.95f     // PDAF is most position-accurate
            FocusMode.CONTRAST_PRIMARY -> 0.80f  // Contrast AF has slight lag
            FocusMode.HYBRID_SWEEP -> 0.60f      // Sweep mode is exploratory
        }
        return (statePosition + (confidence - 0.5f) * modeWeight * POSITION_SENSITIVITY)
            .coerceIn(0f, 1f)
    }

    private fun adaptiveMeasurementNoise(confidence: Float, mode: FocusMode): Float {
        // Lower confidence → higher measurement noise → trust prediction more
        val baseNoise = when (mode) {
            FocusMode.PDAF_PRIMARY -> MEASUREMENT_NOISE_PDAF
            FocusMode.CONTRAST_PRIMARY -> MEASUREMENT_NOISE_CONTRAST
            FocusMode.HYBRID_SWEEP -> MEASUREMENT_NOISE_HYBRID
        }
        val confidenceFactor = (1.1f - confidence).coerceIn(0.1f, 2f)
        return baseNoise * confidenceFactor
    }

    private fun computeSmoothedConfidence(currentConfidence: Float): Float {
        if (confidenceHistory.isEmpty()) return currentConfidence.coerceIn(0f, 1f)

        // IIR temporal smoothing to avoid flickering confidence
        val smoothed = confidenceHistory.average().toFloat() * TEMPORAL_SMOOTH_ALPHA +
            currentConfidence * (1f - TEMPORAL_SMOOTH_ALPHA)
        return smoothed.coerceIn(0f, 1f)
    }

    private fun recordObservation(confidence: Float, position: Float, timestampMs: Long) {
        if (confidenceHistory.size >= CONFIDENCE_HISTORY_SIZE) {
            confidenceHistory.removeFirst()
        }
        confidenceHistory.addLast(confidence)

        if (focusHistory.size >= FOCUS_HISTORY_SIZE) {
            focusHistory.removeFirst()
        }
        focusHistory.addLast(FocusObservation(position, confidence, timestampMs))
    }

    private data class FocusObservation(
        val position: Float,
        val confidence: Float,
        val timestampMs: Long,
    )

    companion object {
        // ── Kalman Filter Constants ──────────────────────────────────
        private const val PROCESS_NOISE_POSITION = 0.001f
        private const val PROCESS_NOISE_VELOCITY = 0.0001f
        private const val MEASUREMENT_NOISE_PDAF = 0.01f
        private const val MEASUREMENT_NOISE_CONTRAST = 0.05f
        private const val MEASUREMENT_NOISE_HYBRID = 0.1f
        private const val VELOCITY_DECAY = 0.98f

        // ── Timing Constants ─────────────────────────────────────────
        private const val DEFAULT_SHUTTER_DELAY_MS = 25f
        private const val DEFAULT_DT_MS = 33f  // ~30 fps
        private const val MAX_DT_MS = 200L     // Reject stale readings

        // ── Tuning Constants ─────────────────────────────────────────
        private const val POSITION_SENSITIVITY = 0.15f
        private const val MOTION_VELOCITY_THRESHOLD = 0.0005f
        private const val TEMPORAL_SMOOTH_ALPHA = 0.7f

        // ── Buffer Sizes ─────────────────────────────────────────────
        private const val CONFIDENCE_HISTORY_SIZE = 8
        private const val FOCUS_HISTORY_SIZE = 16
    }
}

/**
 * Predicted focus state at the expected capture time.
 */
data class PredictedFocusState(
    /** Predicted focus position at capture time [0..1] */
    val predictedPosition: Float,
    /** Temporally-smoothed confidence [0..1] */
    val predictedConfidence: Float,
    /** Current filter-estimated position [0..1] */
    val currentPosition: Float,
    /** Current estimated velocity (position units per ms) */
    val velocity: Float,
    /** Whether the subject is detected as moving */
    val isSubjectMoving: Boolean,
    /** Absolute motion magnitude (position units per second) */
    val motionMagnitude: Float,
    /** Active focus mode */
    val focusMode: FocusMode,
    /** Current Kalman gain (diagnostic) */
    val kalmanGain: Float,
)
