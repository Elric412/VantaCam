package com.leica.cam.capture.quality

import com.leica.cam.ai_engine.api.SceneAnalysis
import com.leica.cam.ai_engine.api.SceneLabel
import com.leica.cam.face_engine.api.FaceAnalysis
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Intelligent Shot Quality Scoring System — Implementation.md Section 7.7
 *
 * Computes a real-time ShotQualityScore (0.0–1.0) at 5fps to assist the user
 * in finding the optimal capture moment. Also provides AI-powered burst frame
 * selection and ranking.
 *
 * Component scores:
 *   S_sharpness   = tanh(3 × laplacian_variance / 200)       · weight: 0.28
 *   S_exposure    = 1 − |histogram_mean − 0.46| / 0.46       · weight: 0.18
 *   S_composition = compute_composition_score()               · weight: 0.15
 *   S_faces       = mean(FaceQuality_scores) if faces > 0     · weight: 0.22
 *   S_motion_blur = 1 − motion_score                          · weight: 0.10
 *   S_noise       = 1 − noise_level / 0.1                    · weight: 0.07
 *
 * Face Quality per face:
 *   FQ = 0.40 × eye_openness + 0.25 × face_sharpness
 *      + 0.20 × gaze_score + 0.15 × (1 − head_tilt)
 *
 * Composition score evaluates:
 *   - Rule of thirds overlap with primary subject
 *   - Horizon line tilt penalty (> 2°)
 *   - Portrait centering in upper third
 *
 * Reference: Implementation.md — Intelligent Shot Scoring (Section 7.7)
 */
class ShotQualityScoringEngine {

    /**
     * Compute the overall shot quality score for a single frame.
     *
     * @param input Shot quality input metrics
     * @return Shot quality result with component scores and overall score
     */
    fun score(input: ShotQualityInput): ShotQualityResult {
        val sSharpness = computeSharpnessScore(input.laplacianVariance)
        val sExposure = computeExposureScore(input.histogramMean)
        val sComposition = computeCompositionScore(input.composition)
        val sFaces = computeFaceScore(input.faceQualities)
        val sMotionBlur = computeMotionBlurScore(input.motionScore)
        val sNoise = computeNoiseScore(input.noiseLevel)

        val overallScore = sSharpness * WEIGHT_SHARPNESS +
            sExposure * WEIGHT_EXPOSURE +
            sComposition * WEIGHT_COMPOSITION +
            sFaces * WEIGHT_FACES +
            sMotionBlur * WEIGHT_MOTION_BLUR +
            sNoise * WEIGHT_NOISE

        val isPeakMoment = overallScore > PEAK_MOMENT_THRESHOLD

        // Determine quality issues for user feedback
        val issues = mutableListOf<QualityIssue>()
        if (sSharpness < 0.5f) issues.add(QualityIssue.LOW_SHARPNESS)
        if (sExposure < 0.5f) {
            if (input.histogramMean > 0.46f) issues.add(QualityIssue.OVER_EXPOSED)
            else issues.add(QualityIssue.UNDER_EXPOSED)
        }
        if (sMotionBlur < 0.5f) issues.add(QualityIssue.MOTION_BLUR)
        if (sNoise < 0.5f) issues.add(QualityIssue.HIGH_NOISE)
        if (sFaces < 0.5f && input.faceQualities.isNotEmpty()) {
            if (input.faceQualities.any { it.eyeOpenness < 0.2f }) {
                issues.add(QualityIssue.EYES_CLOSED)
            }
            if (input.faceQualities.any { it.gazeScore < 0.3f }) {
                issues.add(QualityIssue.NOT_LOOKING)
            }
        }

        return ShotQualityResult(
            overallScore = overallScore.coerceIn(0f, 1f),
            sharpnessScore = sSharpness,
            exposureScore = sExposure,
            compositionScore = sComposition,
            faceScore = sFaces,
            motionBlurScore = sMotionBlur,
            noiseScore = sNoise,
            isPeakMoment = isPeakMoment,
            qualityIssues = issues,
        )
    }

    // ──────────────────────────────────────────────────────────────────
    // AI-Powered Burst Selection
    // ──────────────────────────────────────────────────────────────────

    /**
     * Rank burst frames and select the best ones for the user.
     *
     * @param frames List of burst frames with their quality inputs
     * @param scene Scene analysis for context-aware ranking
     * @param topN Number of top frames to return (default 5)
     * @return Ranked list of burst frames with scores and issues
     */
    fun rankBurstFrames(
        frames: List<BurstFrameInput>,
        scene: SceneAnalysis,
        topN: Int = BURST_TOP_N,
    ): List<BurstFrameRanking> {
        val scored = frames.map { frame ->
            val result = score(frame.qualityInput)
            BurstFrameRanking(
                frameIndex = frame.frameIndex,
                timestampNs = frame.timestampNs,
                qualityResult = result,
                contextScore = computeContextScore(result, scene),
            )
        }

        // Sort by context-aware composite score
        val sorted = when (scene.sceneLabel) {
            SceneLabel.PORTRAIT, SceneLabel.BACKLIT_PORTRAIT -> {
                // Portrait: prioritise face quality
                scored.sortedByDescending { it.qualityResult.faceScore * 0.6f + it.contextScore * 0.4f }
            }
            SceneLabel.LANDSCAPE, SceneLabel.ARCHITECTURE -> {
                // Landscape: prioritise sharpness + exposure
                scored.sortedByDescending {
                    it.qualityResult.sharpnessScore * 0.4f +
                        it.qualityResult.exposureScore * 0.3f +
                        it.contextScore * 0.3f
                }
            }
            else -> {
                // Default: use overall score
                scored.sortedByDescending { it.contextScore }
            }
        }

        return sorted.take(topN)
    }

    // ──────────────────────────────────────────────────────────────────
    // Component Score Functions
    // ──────────────────────────────────────────────────────────────────

    private fun computeSharpnessScore(laplacianVariance: Float): Float {
        // S = tanh(3 × variance / 200)
        return tanh(3f * laplacianVariance / 200f).coerceIn(0f, 1f)
    }

    private fun computeExposureScore(histogramMean: Float): Float {
        // S = 1 − |mean − 0.46| / 0.46
        return (1f - abs(histogramMean - TARGET_EXPOSURE) / TARGET_EXPOSURE).coerceIn(0f, 1f)
    }

    private fun computeCompositionScore(composition: CompositionMetrics?): Float {
        if (composition == null) return 0.5f // Neutral score when no data

        var score = 0f
        var totalWeight = 0f

        // Rule of thirds: how well subject aligns with power points
        if (composition.ruleOfThirdsOverlap >= 0f) {
            score += composition.ruleOfThirdsOverlap * COMP_WEIGHT_THIRDS
            totalWeight += COMP_WEIGHT_THIRDS
        }

        // Horizon level: penalty for tilt > 2°
        if (composition.horizonTiltDegrees >= 0f) {
            val tiltPenalty = if (composition.horizonTiltDegrees > HORIZON_TILT_THRESHOLD) {
                1f - (composition.horizonTiltDegrees - HORIZON_TILT_THRESHOLD) / 10f
            } else {
                1f
            }
            score += tiltPenalty.coerceIn(0f, 1f) * COMP_WEIGHT_HORIZON
            totalWeight += COMP_WEIGHT_HORIZON
        }

        // Subject centering (for portraits)
        if (composition.subjectCenteringScore >= 0f) {
            score += composition.subjectCenteringScore * COMP_WEIGHT_CENTERING
            totalWeight += COMP_WEIGHT_CENTERING
        }

        return if (totalWeight > 0f) (score / totalWeight).coerceIn(0f, 1f) else 0.5f
    }

    private fun computeFaceScore(faceQualities: List<FaceQualityMetrics>): Float {
        if (faceQualities.isEmpty()) return 0.5f // Neutral when no faces

        val faceScores = faceQualities.map { face ->
            // FQ = 0.40 × eye_openness + 0.25 × face_sharpness
            //    + 0.20 × gaze_score + 0.15 × (1 − head_tilt)
            val eyeScore = face.eyeOpenness.coerceIn(0f, 1f)
            val sharpScore = face.faceSharpness.coerceIn(0f, 1f)
            val gazeScore = face.gazeScore.coerceIn(0f, 1f)
            val tiltScore = (1f - face.headTiltNormalized.coerceIn(0f, 1f))

            eyeScore * FQ_WEIGHT_EYES +
                sharpScore * FQ_WEIGHT_SHARPNESS +
                gazeScore * FQ_WEIGHT_GAZE +
                tiltScore * FQ_WEIGHT_TILT
        }

        return faceScores.average().toFloat().coerceIn(0f, 1f)
    }

    private fun computeMotionBlurScore(motionScore: Float): Float {
        return (1f - motionScore).coerceIn(0f, 1f)
    }

    private fun computeNoiseScore(noiseLevel: Float): Float {
        return (1f - noiseLevel / NOISE_REFERENCE_LEVEL).coerceIn(0f, 1f)
    }

    private fun computeContextScore(result: ShotQualityResult, scene: SceneAnalysis): Float {
        // Adjust overall score based on scene context
        val contextBonus = when (scene.sceneLabel) {
            SceneLabel.PORTRAIT -> if (result.faceScore > 0.8f) 0.05f else 0f
            SceneLabel.NIGHT -> if (result.noiseScore > 0.7f) 0.03f else 0f
            SceneLabel.LANDSCAPE -> if (result.compositionScore > 0.8f) 0.04f else 0f
            else -> 0f
        }
        return (result.overallScore + contextBonus).coerceIn(0f, 1f)
    }

    companion object {
        // ── Component Weights (sum = 1.0) ────────────────────────────
        private const val WEIGHT_SHARPNESS = 0.28f
        private const val WEIGHT_EXPOSURE = 0.18f
        private const val WEIGHT_COMPOSITION = 0.15f
        private const val WEIGHT_FACES = 0.22f
        private const val WEIGHT_MOTION_BLUR = 0.10f
        private const val WEIGHT_NOISE = 0.07f

        // ── Face Quality Weights (sum = 1.0) ─────────────────────────
        private const val FQ_WEIGHT_EYES = 0.40f
        private const val FQ_WEIGHT_SHARPNESS = 0.25f
        private const val FQ_WEIGHT_GAZE = 0.20f
        private const val FQ_WEIGHT_TILT = 0.15f

        // ── Composition Weights ──────────────────────────────────────
        private const val COMP_WEIGHT_THIRDS = 0.40f
        private const val COMP_WEIGHT_HORIZON = 0.30f
        private const val COMP_WEIGHT_CENTERING = 0.30f

        // ── Thresholds ───────────────────────────────────────────────
        private const val TARGET_EXPOSURE = 0.46f
        private const val NOISE_REFERENCE_LEVEL = 0.1f
        private const val PEAK_MOMENT_THRESHOLD = 0.82f
        private const val HORIZON_TILT_THRESHOLD = 2f // degrees
        private const val BURST_TOP_N = 5
    }
}

// ──────────────────────────────────────────────────────────────────────
// Data Models
// ──────────────────────────────────────────────────────────────────────

data class ShotQualityInput(
    /** Laplacian variance of the Y plane (higher = sharper) */
    val laplacianVariance: Float = 0f,
    /** Mean luminance of the histogram [0..1] */
    val histogramMean: Float = 0.46f,
    /** Composition analysis metrics */
    val composition: CompositionMetrics? = null,
    /** Per-face quality metrics */
    val faceQualities: List<FaceQualityMetrics> = emptyList(),
    /** Optical flow motion score [0..1] */
    val motionScore: Float = 0f,
    /** Estimated noise level [0..1] */
    val noiseLevel: Float = 0f,
)

data class CompositionMetrics(
    /** How well the primary subject overlaps rule-of-thirds power points [0..1] */
    val ruleOfThirdsOverlap: Float = -1f,
    /** Horizon line tilt in degrees (0 = level) */
    val horizonTiltDegrees: Float = -1f,
    /** Subject centering score for portraits [0..1] */
    val subjectCenteringScore: Float = -1f,
)

data class FaceQualityMetrics(
    /** Eye openness ratio [0..1] (open > 0.2) */
    val eyeOpenness: Float = 1f,
    /** Face region sharpness (normalised Laplacian variance) [0..1] */
    val faceSharpness: Float = 1f,
    /** Gaze direction score [0..1] (1 = looking at camera) */
    val gazeScore: Float = 1f,
    /** Normalised head tilt [0..1] (|roll| / 45°) */
    val headTiltNormalized: Float = 0f,
)

data class ShotQualityResult(
    /** Overall shot quality score [0..1] */
    val overallScore: Float,
    val sharpnessScore: Float,
    val exposureScore: Float,
    val compositionScore: Float,
    val faceScore: Float,
    val motionBlurScore: Float,
    val noiseScore: Float,
    /** True when score > 0.82 — candidate for "perfect moment" */
    val isPeakMoment: Boolean,
    /** Detected quality issues for user feedback */
    val qualityIssues: List<QualityIssue>,
)

enum class QualityIssue(val userMessage: String) {
    LOW_SHARPNESS("Slight blur detected"),
    OVER_EXPOSED("Slightly over-exposed"),
    UNDER_EXPOSED("Slightly under-exposed"),
    MOTION_BLUR("Motion blur detected"),
    HIGH_NOISE("High noise level"),
    EYES_CLOSED("Eyes partially closed"),
    NOT_LOOKING("Subject not looking at camera"),
}

data class BurstFrameInput(
    val frameIndex: Int,
    val timestampNs: Long,
    val qualityInput: ShotQualityInput,
)

data class BurstFrameRanking(
    val frameIndex: Int,
    val timestampNs: Long,
    val qualityResult: ShotQualityResult,
    val contextScore: Float,
)
