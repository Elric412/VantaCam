package com.leica.cam.sensor_hal.autofocus

import kotlin.math.abs

/**
 * Hybrid auto-focus selector that fuses PDAF, contrast AF, and neural confidence seed.
 */
class HybridAutoFocusEngine {
    fun evaluate(input: AutoFocusInput): AutoFocusDecision {
        val pdafScore = pdafReliability(input.pdafPhaseError)
        val contrastScore = contrastReliability(input.contrastMetric)
        val neuralScore = input.neuralSubjectConfidence.coerceIn(0f, 1f)

        val fused =
            pdafScore * PDAF_WEIGHT + contrastScore * CONTRAST_WEIGHT + neuralScore * NEURAL_WEIGHT

        val mode = when {
            pdafScore >= PDAF_HARD_LOCK_THRESHOLD -> FocusMode.PDAF_PRIMARY
            contrastScore >= CONTRAST_HARD_LOCK_THRESHOLD -> FocusMode.CONTRAST_PRIMARY
            else -> FocusMode.HYBRID_SWEEP
        }

        return AutoFocusDecision(
            focusMode = mode,
            focusConfidence = fused.coerceIn(0f, 1f),
            shouldTriggerFullSweep = fused < FULL_SWEEP_THRESHOLD,
        )
    }

    private fun pdafReliability(phaseError: Float): Float =
        (1f - abs(phaseError) / MAX_PDAF_PHASE_ERROR).coerceIn(0f, 1f)

    private fun contrastReliability(contrastMetric: Float): Float =
        ((contrastMetric - CONTRAST_LOW) / (CONTRAST_HIGH - CONTRAST_LOW)).coerceIn(0f, 1f)

    private companion object {
        private const val MAX_PDAF_PHASE_ERROR = 1.0f
        private const val CONTRAST_LOW = 0.15f
        private const val CONTRAST_HIGH = 0.85f
        private const val PDAF_WEIGHT = 0.45f
        private const val CONTRAST_WEIGHT = 0.35f
        private const val NEURAL_WEIGHT = 0.20f
        private const val FULL_SWEEP_THRESHOLD = 0.45f
        private const val PDAF_HARD_LOCK_THRESHOLD = 0.8f
        private const val CONTRAST_HARD_LOCK_THRESHOLD = 0.75f
    }
}

/** Inputs collected from AF sensors and AI priors. */
data class AutoFocusInput(
    val pdafPhaseError: Float,
    val contrastMetric: Float,
    val neuralSubjectConfidence: Float,
)

/** Focus mode and confidence used by camera session. */
data class AutoFocusDecision(
    val focusMode: FocusMode,
    val focusConfidence: Float,
    val shouldTriggerFullSweep: Boolean,
)

enum class FocusMode {
    PDAF_PRIMARY,
    CONTRAST_PRIMARY,
    HYBRID_SWEEP,
}
