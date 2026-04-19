package com.leica.cam.sensor_hal.metering

import kotlin.math.max

/** Camera metering strategy that supports multiple exposure estimation modes. */
class AdvancedMeteringEngine(
    private val aeCompensationRange: IntRange,
    private val aeCompensationStepEv: Float,
) {
    fun evaluate(input: MeteringInput): MeteringResult {
        val luminance = when (input.mode) {
            MeteringMode.ZONE_MATRIX -> zoneMatrixLuminance(input)
            MeteringMode.ZONE_CENTER -> zoneCenterLuminance(input) + EV_BIAS_CENTER
            MeteringMode.ZONE_SPOT -> spotLuminance(input)
            MeteringMode.ZONE_HIGHLIGHT_WEIGHTED -> percentile(input.histogram, 0.98f)
            MeteringMode.ZONE_SHADOW_BIASED -> percentile(input.histogram, 0.05f)
        }

        val target = when (input.mode) {
            MeteringMode.ZONE_MATRIX -> 0.46f
            MeteringMode.ZONE_CENTER -> 0.46f
            MeteringMode.ZONE_SPOT -> 0.46f
            MeteringMode.ZONE_HIGHLIGHT_WEIGHTED -> 0.95f
            MeteringMode.ZONE_SHADOW_BIASED -> 0.08f
        }

        val deltaEv = estimateEvDelta(currentLuminance = luminance, targetLuminance = target)
        val compensationSteps = (deltaEv / aeCompensationStepEv).toInt().coerceIn(aeCompensationRange)
        return MeteringResult(
            mode = input.mode,
            sceneLuminance = luminance,
            sceneEv = input.currentEv + deltaEv,
            recommendedAeCompensation = compensationSteps,
        )
    }

    private fun zoneMatrixLuminance(input: MeteringInput): Float {
        val pixels = input.yPlane
        val width = input.width
        val height = input.height
        val zoneW = max(1, width / 8)
        val zoneH = max(1, height / 8)

        var weightedSum = 0.0
        var weightTotal = 0.0

        for (row in 0 until 8) {
            for (col in 0 until 8) {
                val centerX = col * zoneW + zoneW / 2
                val centerY = row * zoneH + zoneH / 2
                val index = centerY.coerceIn(0, height - 1) * width + centerX.coerceIn(0, width - 1)
                val luminance = (pixels[index].toInt() and 0xFF) / 255f
                val centerWeighted = if (row in 2..5 && col in 2..5) 3.0 else 1.0
                val faceWeighted = if (input.faceZones.any { it.contains(col, row) }) 8.0 else 1.0
                val weight = centerWeighted * faceWeighted
                weightedSum += luminance * weight
                weightTotal += weight
            }
        }

        return (weightedSum / weightTotal).toFloat()
    }

    private fun zoneCenterLuminance(input: MeteringInput): Float {
        val cx = input.width / 2f
        val cy = input.height / 2f
        val radius = (input.width.coerceAtMost(input.height) * CENTER_REGION_FACTOR) / 2f
        var sum = 0.0
        var count = 0

        for (y in 0 until input.height) {
            for (x in 0 until input.width) {
                val dx = x - cx
                val dy = y - cy
                if (dx * dx + dy * dy <= radius * radius) {
                    val luma = (input.yPlane[y * input.width + x].toInt() and 0xFF) / 255f
                    sum += luma
                    count += 1
                }
            }
        }

        return if (count == 0) 0f else (sum / count).toFloat()
    }

    private fun spotLuminance(input: MeteringInput): Float {
        val spot = input.afSpot ?: return zoneCenterLuminance(input)
        var sum = 0.0
        var count = 0
        for (y in spot.top until spot.bottom) {
            for (x in spot.left until spot.right) {
                if (x in 0 until input.width && y in 0 until input.height) {
                    sum += (input.yPlane[y * input.width + x].toInt() and 0xFF) / 255f
                    count += 1
                }
            }
        }
        return if (count == 0) 0f else (sum / count).toFloat()
    }

    private fun percentile(histogram: IntArray, percentile: Float): Float {
        val total = histogram.sum().coerceAtLeast(1)
        val threshold = total * percentile
        var cumulative = 0
        for ((bin, count) in histogram.withIndex()) {
            cumulative += count
            if (cumulative >= threshold) {
                return bin / 255f
            }
        }
        return 1f
    }

    private fun estimateEvDelta(currentLuminance: Float, targetLuminance: Float): Float {
        val safeLuminance = currentLuminance.coerceAtLeast(MIN_LUMINANCE)
        return (kotlin.math.ln(targetLuminance / safeLuminance) / kotlin.math.ln(2.0)).toFloat()
    }

    private companion object {
        private const val MIN_LUMINANCE = 1e-4f
        private const val CENTER_REGION_FACTOR = 0.5f
        private const val EV_BIAS_CENTER = 0.3f
    }
}

/** Supported metering modes. */
enum class MeteringMode {
    ZONE_MATRIX,
    ZONE_CENTER,
    ZONE_SPOT,
    ZONE_HIGHLIGHT_WEIGHTED,
    ZONE_SHADOW_BIASED,
}

/** Input frame and metadata needed to produce AE compensation recommendation. */
data class MeteringInput(
    val mode: MeteringMode,
    val width: Int,
    val height: Int,
    val yPlane: ByteArray,
    val histogram: IntArray,
    val currentEv: Float,
    val afSpot: PixelRect? = null,
    val faceZones: List<Zone> = emptyList(),
)

/** Metering decision output that can be attached to capture diagnostics. */
data class MeteringResult(
    val mode: MeteringMode,
    val sceneLuminance: Float,
    val sceneEv: Float,
    val recommendedAeCompensation: Int,
)

/** Pixel space rectangle for spot metering. */
data class PixelRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

/** 8x8 zone-space rectangle for matrix metering face weighting. */
data class Zone(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    fun contains(x: Int, y: Int): Boolean = x in left..right && y in top..bottom
}
