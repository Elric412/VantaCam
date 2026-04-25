package com.leica.cam.capture.metering

import com.leica.cam.capture.orchestrator.CaptureRequest
import com.leica.cam.ai_engine.api.CaptureMode
import kotlin.math.ln
import kotlin.math.max

/**
 * Capture-time metering engine that provides exposure evaluation at shutter-press.
 *
 * Bridges the AdvancedMeteringEngine (which operates on YUV frames) with the
 * capture orchestrator by computing a recommended EV bias based on:
 * - Scene classification (backlit, night, etc.)
 * - HDR mode (ProXDR needs wider EV spread)
 * - Face-priority metering (bias toward face exposure)
 * - Highlight/shadow protection
 *
 * Five metering modes from Implementation.md:
 *   1. Zone Matrix: 8×8 grid with center and face weighting
 *   2. Zone Center: Circular center-weighted (50% radius)
 *   3. Zone Spot: Small region around AF point
 *   4. Highlight Weighted: Protect highlights (98th percentile)
 *   5. Shadow Biased: Lift shadows (5th percentile)
 *
 * Reference: Implementation.md — Advanced Metering System
 */
class CaptureTimeMeteringEngine {

    /**
     * Evaluate scene metering for a capture request and return recommended EV bias.
     *
     * @param request The capture request containing scene and mode information
     * @return Recommended EV compensation value
     */
    fun evaluate(request: CaptureRequest): Float {
        val sceneEvBias = sceneBasedEvBias(request.captureMode)
        val hdrEvBias = hdrModeEvBias(request.hdrMode)
        val facePriorityBias = if (request.neuralSubjectConfidence > FACE_CONFIDENCE_THRESHOLD) {
            FACE_PRIORITY_EV_BIAS
        } else {
            0f
        }

        // Combine biases with safety clamping
        val totalBias = (sceneEvBias + hdrEvBias + facePriorityBias)
            .coerceIn(MIN_EV_BIAS, MAX_EV_BIAS)

        return totalBias
    }

    /**
     * Evaluate with full frame data for production-grade metering.
     *
     * @param yPlane Luminance plane data
     * @param width Frame width
     * @param height Frame height
     * @param mode Metering mode to use
     * @param faceRegions Detected face bounding boxes in pixel coordinates
     * @param afSpot AF point region for spot metering
     * @return Detailed metering result with recommended compensation
     */
    fun evaluateWithFrame(
        yPlane: ByteArray,
        width: Int,
        height: Int,
        mode: MeteringMode = MeteringMode.ZONE_MATRIX,
        faceRegions: List<FaceRegion> = emptyList(),
        afSpot: SpotRegion? = null,
    ): MeteringEvaluation {
        val histogram = computeHistogram(yPlane)
        val sceneLuminance = when (mode) {
            MeteringMode.ZONE_MATRIX -> evaluateZoneMatrix(yPlane, width, height, faceRegions)
            MeteringMode.ZONE_CENTER -> evaluateZoneCenter(yPlane, width, height)
            MeteringMode.ZONE_SPOT -> evaluateZoneSpot(yPlane, width, height, afSpot)
            MeteringMode.HIGHLIGHT_WEIGHTED -> evaluateHighlightWeighted(histogram)
            MeteringMode.SHADOW_BIASED -> evaluateShadowBiased(histogram)
        }

        val targetLuminance = when (mode) {
            MeteringMode.ZONE_MATRIX -> TARGET_MID_GRAY
            MeteringMode.ZONE_CENTER -> TARGET_MID_GRAY
            MeteringMode.ZONE_SPOT -> TARGET_MID_GRAY
            MeteringMode.HIGHLIGHT_WEIGHTED -> TARGET_HIGHLIGHT
            MeteringMode.SHADOW_BIASED -> TARGET_SHADOW
        }

        val deltaEv = estimateEvDelta(sceneLuminance, targetLuminance)
        val highlightHeadroom = computeHighlightHeadroom(histogram)
        val shadowDepth = computeShadowDepth(histogram)

        return MeteringEvaluation(
            mode = mode,
            sceneLuminance = sceneLuminance,
            recommendedEvBias = deltaEv,
            highlightHeadroom = highlightHeadroom,
            shadowDepth = shadowDepth,
            dynamicRange = highlightHeadroom + shadowDepth,
            histogram = histogram,
        )
    }

    // ──────────────────────────────────────────────────────────────────
    // Zone Matrix Metering (8×8 grid)
    // ──────────────────────────────────────────────────────────────────

    private fun evaluateZoneMatrix(
        yPlane: ByteArray,
        width: Int,
        height: Int,
        faceRegions: List<FaceRegion>,
    ): Float {
        val zoneW = max(1, width / MATRIX_ZONES)
        val zoneH = max(1, height / MATRIX_ZONES)
        var weightedSum = 0.0
        var weightTotal = 0.0

        for (row in 0 until MATRIX_ZONES) {
            for (col in 0 until MATRIX_ZONES) {
                val centerX = (col * zoneW + zoneW / 2).coerceIn(0, width - 1)
                val centerY = (row * zoneH + zoneH / 2).coerceIn(0, height - 1)
                val index = centerY * width + centerX
                if (index >= yPlane.size) continue

                val luminance = (yPlane[index].toInt() and 0xFF) / 255f

                // Center weighting: inner 4×4 zones get 3× weight
                val centerWeight = if (row in 2..5 && col in 2..5) 3.0 else 1.0

                // Face priority: zones containing faces get 8× weight
                val faceWeight = if (faceRegions.any { face ->
                        col * zoneW in face.left..face.right &&
                            row * zoneH in face.top..face.bottom
                    }) FACE_ZONE_WEIGHT else 1.0

                val weight = centerWeight * faceWeight
                weightedSum += luminance * weight
                weightTotal += weight
            }
        }

        return if (weightTotal > 0) (weightedSum / weightTotal).toFloat() else TARGET_MID_GRAY
    }

    // ──────────────────────────────────────────────────────────────────
    // Zone Center Metering (circular weighted)
    // ──────────────────────────────────────────────────────────────────

    private fun evaluateZoneCenter(
        yPlane: ByteArray,
        width: Int,
        height: Int,
    ): Float {
        val cx = width / 2f
        val cy = height / 2f
        val radius = (width.coerceAtMost(height) * CENTER_RADIUS_FRACTION) / 2f
        val radiusSq = radius * radius
        var sum = 0.0
        var count = 0

        // Sample every Nth pixel for performance
        val step = max(1, (width * height) / MAX_CENTER_SAMPLES)
        var pixelIndex = 0
        while (pixelIndex < yPlane.size) {
            val x = pixelIndex % width
            val y = pixelIndex / width
            val dx = x - cx
            val dy = y - cy
            if (dx * dx + dy * dy <= radiusSq) {
                sum += (yPlane[pixelIndex].toInt() and 0xFF) / 255.0
                count++
            }
            pixelIndex += step
        }

        return if (count > 0) (sum / count).toFloat() else TARGET_MID_GRAY
    }

    // ──────────────────────────────────────────────────────────────────
    // Zone Spot Metering
    // ──────────────────────────────────────────────────────────────────

    private fun evaluateZoneSpot(
        yPlane: ByteArray,
        width: Int,
        height: Int,
        spot: SpotRegion?,
    ): Float {
        if (spot == null) return evaluateZoneCenter(yPlane, width, height)

        var sum = 0.0
        var count = 0
        for (y in spot.top.coerceAtLeast(0) until spot.bottom.coerceAtMost(height)) {
            for (x in spot.left.coerceAtLeast(0) until spot.right.coerceAtMost(width)) {
                val index = y * width + x
                if (index < yPlane.size) {
                    sum += (yPlane[index].toInt() and 0xFF) / 255.0
                    count++
                }
            }
        }

        return if (count > 0) (sum / count).toFloat() else TARGET_MID_GRAY
    }

    // ──────────────────────────────────────────────────────────────────
    // Highlight Weighted Metering
    // ──────────────────────────────────────────────────────────────────

    private fun evaluateHighlightWeighted(histogram: IntArray): Float {
        return percentile(histogram, HIGHLIGHT_PERCENTILE)
    }

    // ──────────────────────────────────────────────────────────────────
    // Shadow Biased Metering
    // ──────────────────────────────────────────────────────────────────

    private fun evaluateShadowBiased(histogram: IntArray): Float {
        return percentile(histogram, SHADOW_PERCENTILE)
    }

    // ──────────────────────────────────────────────────────────────────
    // Utility Functions
    // ──────────────────────────────────────────────────────────────────

    private fun computeHistogram(yPlane: ByteArray): IntArray {
        val histogram = IntArray(HISTOGRAM_BINS)
        for (byte in yPlane) {
            val bin = (byte.toInt() and 0xFF).coerceIn(0, HISTOGRAM_BINS - 1)
            histogram[bin]++
        }
        return histogram
    }

    private fun percentile(histogram: IntArray, percentile: Float): Float {
        val total = histogram.sum().coerceAtLeast(1)
        val threshold = (total * percentile).toInt()
        var cumulative = 0
        for ((bin, count) in histogram.withIndex()) {
            cumulative += count
            if (cumulative >= threshold) return bin / 255f
        }
        return 1f
    }

    private fun estimateEvDelta(currentLuminance: Float, targetLuminance: Float): Float {
        val safeLuminance = currentLuminance.coerceAtLeast(MIN_LUMINANCE)
        return (ln(targetLuminance / safeLuminance) / LN2).toFloat()
    }

    private fun computeHighlightHeadroom(histogram: IntArray): Float {
        val p98 = percentile(histogram, 0.98f)
        return -estimateEvDelta(p98, 1f) // Stops below clipping
    }

    private fun computeShadowDepth(histogram: IntArray): Float {
        val p02 = percentile(histogram, 0.02f)
        return estimateEvDelta(p02, MIN_LUMINANCE) // Stops above noise floor
    }

    private fun sceneBasedEvBias(captureMode: CaptureMode): Float = when (captureMode) {
        CaptureMode.NIGHT -> NIGHT_EV_BIAS
        CaptureMode.PORTRAIT -> PORTRAIT_EV_BIAS
        CaptureMode.LANDSCAPE -> LANDSCAPE_EV_BIAS
        CaptureMode.MACRO -> MACRO_EV_BIAS
        else -> 0f
    }

    private fun hdrModeEvBias(hdrMode: com.leica.cam.capture.orchestrator.HdrCaptureMode): Float =
        when (hdrMode) {
            com.leica.cam.capture.orchestrator.HdrCaptureMode.PRO_XDR -> PRO_XDR_EV_BIAS
            com.leica.cam.capture.orchestrator.HdrCaptureMode.SMART -> SMART_HDR_EV_BIAS
            else -> 0f
        }

    companion object {
        private const val MATRIX_ZONES = 8
        private const val HISTOGRAM_BINS = 256
        private const val CENTER_RADIUS_FRACTION = 0.5f
        private const val MAX_CENTER_SAMPLES = 10_000

        private const val TARGET_MID_GRAY = 0.46f
        private const val TARGET_HIGHLIGHT = 0.95f
        private const val TARGET_SHADOW = 0.08f

        private const val HIGHLIGHT_PERCENTILE = 0.98f
        private const val SHADOW_PERCENTILE = 0.05f

        private const val MIN_LUMINANCE = 1e-4f
        private val LN2 = ln(2.0)

        private const val MIN_EV_BIAS = -4f
        private const val MAX_EV_BIAS = 4f

        private const val FACE_CONFIDENCE_THRESHOLD = 0.5f
        private const val FACE_PRIORITY_EV_BIAS = 0.3f
        private const val FACE_ZONE_WEIGHT = 8.0

        private const val NIGHT_EV_BIAS = 0.5f
        private const val PORTRAIT_EV_BIAS = 0.2f
        private const val LANDSCAPE_EV_BIAS = -0.1f
        private const val MACRO_EV_BIAS = 0.1f
        private const val PRO_XDR_EV_BIAS = -0.3f
        private const val SMART_HDR_EV_BIAS = -0.15f
    }
}

/** Metering modes available at capture time. */
enum class MeteringMode {
    ZONE_MATRIX,
    ZONE_CENTER,
    ZONE_SPOT,
    HIGHLIGHT_WEIGHTED,
    SHADOW_BIASED,
}

/** Face bounding box in pixel coordinates. */
data class FaceRegion(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

/** Spot metering region in pixel coordinates. */
data class SpotRegion(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

/** Detailed metering evaluation result. */
data class MeteringEvaluation(
    val mode: MeteringMode,
    val sceneLuminance: Float,
    val recommendedEvBias: Float,
    val highlightHeadroom: Float,
    val shadowDepth: Float,
    val dynamicRange: Float,
    val histogram: IntArray,
)
