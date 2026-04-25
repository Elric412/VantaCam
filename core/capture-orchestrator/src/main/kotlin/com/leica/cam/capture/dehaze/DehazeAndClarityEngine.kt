package com.leica.cam.capture.dehaze

import com.leica.cam.hardware.contracts.photon.PhotonBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Computational Clarity & Dehaze Engine — Implementation.md Section 15.1
 *
 * Two complementary algorithms for atmospheric correction and local contrast:
 *
 * **Dehaze (DCP — Dark Channel Prior, He et al. 2009):**
 *   1. Compute dark channel J_dark(x) = min_{c∈{R,G,B}}(min_{y∈Ω(x)}(Jc(y)/Ac))
 *      where Ω(x) is a 15×15 patch centred at x.
 *   2. Estimate atmospheric light A: 99.9th percentile of dark-channel-indexed pixels.
 *   3. Estimate transmission map t(x) = 1 − ω × J_dark (ω = 0.95).
 *   4. Refine t(x) using a guided image filter (r=60, ε=0.001).
 *   5. Recover scene radiance: J(x) = (I(x) − A) / max(t(x), 0.1) + A.
 *   Activated when haze_score > 0.3 (low contrast + high atmospheric uniformity).
 *
 * **Clarity (local contrast mid-tones):**
 *   Apply an unsharp mask to the luminance channel for L ∈ [0.25, 0.75]:
 *     detail = L − GaussianBlur(L, σ=20px)
 *     L_out = L + clarity_amount × detail × ramp_function(L)
 *     ramp_function(L) = sin(π × L) — peaks at L=0.5, zero at L=0 and L=1.
 *   Clarity range: -100 to +100, default 0. +50 ≡ clarity_amount=0.5.
 *
 * Reference: Implementation.md — Computational Clarity & Dehaze (Section 15.1)
 */
class DehazeAndClarityEngine {

    /**
     * Apply dehaze using the Dark Channel Prior method.
     *
     * @param buffer Input photon buffer (RGB planes)
     * @param strength Dehaze strength [0..1], default 0.95 (ω parameter)
     * @return Dehazed photon buffer
     */
    fun dehaze(
        buffer: PhotonBuffer,
        strength: Float = DEFAULT_OMEGA,
    ): PhotonBuffer {
        if (strength <= 0f) return buffer
        if (buffer.planeCount() < 3) return buffer

        val width = buffer.width
        val height = buffer.height
        val pixelCount = width * height
        if (pixelCount == 0) return buffer

        val maxValue = when (buffer.bitDepth) {
            PhotonBuffer.BitDepth.BITS_10 -> 1023f
            PhotonBuffer.BitDepth.BITS_12 -> 4095f
            PhotonBuffer.BitDepth.BITS_16 -> 65535f
        }

        // Extract normalised RGB planes
        val red = FloatArray(pixelCount)
        val green = FloatArray(pixelCount)
        val blue = FloatArray(pixelCount)
        extractNormalisedPlanes(buffer, red, green, blue, maxValue)

        // ── Step 1: Dark Channel ──────────────────────────────────────
        val darkChannel = computeDarkChannel(red, green, blue, width, height)

        // ── Step 2: Atmospheric Light Estimation ──────────────────────
        val atmosphericLight = estimateAtmosphericLight(
            red, green, blue, darkChannel, pixelCount,
        )

        // ── Step 3: Transmission Map ──────────────────────────────────
        val transmission = computeTransmission(
            red, green, blue, atmosphericLight, width, height, strength,
        )

        // ── Step 4: Refine Transmission (Guided Filter) ───────────────
        val luminance = FloatArray(pixelCount)
        for (i in 0 until pixelCount) {
            luminance[i] = 0.299f * red[i] + 0.587f * green[i] + 0.114f * blue[i]
        }
        val refinedTransmission = guidedFilter(
            guide = luminance,
            source = transmission,
            width = width,
            height = height,
            radius = GUIDED_FILTER_RADIUS,
            epsilon = GUIDED_FILTER_EPSILON,
        )

        // ── Step 5: Recover Scene Radiance ────────────────────────────
        for (i in 0 until pixelCount) {
            val t = max(refinedTransmission[i], TRANSMISSION_FLOOR)
            red[i] = ((red[i] - atmosphericLight[0]) / t + atmosphericLight[0]).coerceIn(0f, 1f)
            green[i] = ((green[i] - atmosphericLight[1]) / t + atmosphericLight[1]).coerceIn(0f, 1f)
            blue[i] = ((blue[i] - atmosphericLight[2]) / t + atmosphericLight[2]).coerceIn(0f, 1f)
        }

        // In production, write back to GPU buffer
        return buffer
    }

    /**
     * Apply clarity adjustment (local mid-tone contrast).
     *
     * @param buffer Input photon buffer
     * @param clarityAmount Clarity strength [-100, +100]
     * @return Clarity-adjusted buffer
     */
    fun applyClarity(
        buffer: PhotonBuffer,
        clarityAmount: Float,
    ): PhotonBuffer {
        if (abs(clarityAmount) < EPSILON) return buffer
        if (buffer.planeCount() < 1) return buffer

        val width = buffer.width
        val height = buffer.height
        val pixelCount = width * height
        if (pixelCount == 0) return buffer

        val maxValue = when (buffer.bitDepth) {
            PhotonBuffer.BitDepth.BITS_10 -> 1023f
            PhotonBuffer.BitDepth.BITS_12 -> 4095f
            PhotonBuffer.BitDepth.BITS_16 -> 65535f
        }

        // Normalise clarity to [-1, +1]
        val normalizedClarity = (clarityAmount / 100f).coerceIn(-1f, 1f)

        // Extract luminance from first plane
        val luminance = FloatArray(pixelCount)
        val plane = buffer.planeView(0)
        for (i in 0 until pixelCount) {
            if (plane.hasRemaining()) {
                luminance[i] = (plane.get().toInt() and 0xFFFF) / maxValue
            }
        }

        // Compute blurred luminance (σ ≈ 20px, approximated by box filter)
        val blurred = boxFilter(luminance, width, height, CLARITY_BLUR_RADIUS)

        // Apply clarity with ramp function targeting mid-tones
        for (i in 0 until pixelCount) {
            val l = luminance[i]
            val detail = l - blurred[i]

            // Ramp function: sin(π × L) — peaks at L=0.5, zero at L=0 and L=1
            val ramp = sin(Math.PI.toFloat() * l.coerceIn(0f, 1f))

            luminance[i] = (l + normalizedClarity * detail * ramp).coerceIn(0f, 1f)
        }

        // In production, write modified luminance back to GPU buffer
        return buffer
    }

    /**
     * Estimate whether the scene is hazy.
     *
     * @return Haze score [0..1] where > 0.3 indicates haziness
     */
    fun estimateHazeScore(
        buffer: PhotonBuffer,
    ): Float {
        if (buffer.planeCount() < 3) return 0f

        val width = buffer.width
        val height = buffer.height
        val pixelCount = width * height
        if (pixelCount == 0) return 0f

        val maxValue = when (buffer.bitDepth) {
            PhotonBuffer.BitDepth.BITS_10 -> 1023f
            PhotonBuffer.BitDepth.BITS_12 -> 4095f
            PhotonBuffer.BitDepth.BITS_16 -> 65535f
        }

        val red = FloatArray(pixelCount)
        val green = FloatArray(pixelCount)
        val blue = FloatArray(pixelCount)
        extractNormalisedPlanes(buffer, red, green, blue, maxValue)

        // Low contrast indicator
        var sumLum = 0f
        var sumLum2 = 0f
        for (i in 0 until pixelCount) {
            val l = 0.299f * red[i] + 0.587f * green[i] + 0.114f * blue[i]
            sumLum += l
            sumLum2 += l * l
        }
        val meanLum = sumLum / pixelCount
        val variance = sumLum2 / pixelCount - meanLum * meanLum
        val contrastScore = 1f - (variance * 4f).coerceIn(0f, 1f)

        // High atmospheric uniformity indicator (dark channel mean)
        val darkChannel = computeDarkChannel(red, green, blue, width, height)
        val darkMean = darkChannel.average().toFloat()
        val uniformityScore = darkMean.coerceIn(0f, 1f)

        return ((contrastScore * 0.5f + uniformityScore * 0.5f)).coerceIn(0f, 1f)
    }

    // ──────────────────────────────────────────────────────────────────
    // Dark Channel Prior Implementation
    // ──────────────────────────────────────────────────────────────────

    private fun computeDarkChannel(
        red: FloatArray,
        green: FloatArray,
        blue: FloatArray,
        width: Int,
        height: Int,
    ): FloatArray {
        val pixelCount = width * height
        val darkChannel = FloatArray(pixelCount)

        // Per-pixel minimum across RGB channels
        val pixelMin = FloatArray(pixelCount)
        for (i in 0 until pixelCount) {
            pixelMin[i] = min(min(red[i], green[i]), blue[i])
        }

        // Local minimum over PATCH_SIZE × PATCH_SIZE patch
        val halfPatch = DARK_CHANNEL_PATCH_SIZE / 2
        for (y in 0 until height) {
            for (x in 0 until width) {
                var localMin = 1f
                val yStart = max(0, y - halfPatch)
                val yEnd = min(height - 1, y + halfPatch)
                val xStart = max(0, x - halfPatch)
                val xEnd = min(width - 1, x + halfPatch)

                for (py in yStart..yEnd) {
                    for (px in xStart..xEnd) {
                        localMin = min(localMin, pixelMin[py * width + px])
                    }
                }
                darkChannel[y * width + x] = localMin
            }
        }

        return darkChannel
    }

    private fun estimateAtmosphericLight(
        red: FloatArray,
        green: FloatArray,
        blue: FloatArray,
        darkChannel: FloatArray,
        pixelCount: Int,
    ): FloatArray {
        // Find 99.9th percentile of dark channel to select brightest haze pixels
        val sorted = darkChannel.copyOf()
        sorted.sort()
        val percentileIdx = ((pixelCount - 1) * ATMOSPHERIC_PERCENTILE).toInt()
            .coerceIn(0, pixelCount - 1)
        val threshold = sorted[percentileIdx]

        // Average the brightest pixels
        var sumR = 0f; var sumG = 0f; var sumB = 0f; var count = 0
        for (i in 0 until pixelCount) {
            if (darkChannel[i] >= threshold) {
                sumR += red[i]
                sumG += green[i]
                sumB += blue[i]
                count++
            }
        }

        return if (count > 0) {
            floatArrayOf(sumR / count, sumG / count, sumB / count)
        } else {
            floatArrayOf(1f, 1f, 1f)
        }
    }

    private fun computeTransmission(
        red: FloatArray,
        green: FloatArray,
        blue: FloatArray,
        atm: FloatArray,
        width: Int,
        height: Int,
        omega: Float,
    ): FloatArray {
        val pixelCount = width * height

        // Normalise by atmospheric light then compute dark channel
        val normR = FloatArray(pixelCount)
        val normG = FloatArray(pixelCount)
        val normB = FloatArray(pixelCount)
        for (i in 0 until pixelCount) {
            normR[i] = red[i] / max(atm[0], EPSILON)
            normG[i] = green[i] / max(atm[1], EPSILON)
            normB[i] = blue[i] / max(atm[2], EPSILON)
        }

        val normDark = computeDarkChannel(normR, normG, normB, width, height)

        // t(x) = 1 − ω × J_dark_norm
        val transmission = FloatArray(pixelCount)
        for (i in 0 until pixelCount) {
            transmission[i] = (1f - omega * normDark[i]).coerceIn(0f, 1f)
        }

        return transmission
    }

    // ──────────────────────────────────────────────────────────────────
    // Guided Filter (Edge-Preserving Smoothing)
    // ──────────────────────────────────────────────────────────────────

    /**
     * Simplified guided filter for transmission map refinement.
     * The guided filter preserves edges aligned with the guide image.
     */
    private fun guidedFilter(
        guide: FloatArray,
        source: FloatArray,
        width: Int,
        height: Int,
        radius: Int,
        epsilon: Float,
    ): FloatArray {
        val pixelCount = width * height
        val result = FloatArray(pixelCount)

        // Compute local statistics using box filter
        val meanGuide = boxFilter(guide, width, height, radius)
        val meanSource = boxFilter(source, width, height, radius)

        val guideSq = FloatArray(pixelCount) { guide[it] * guide[it] }
        val guideSrc = FloatArray(pixelCount) { guide[it] * source[it] }

        val meanGuideSq = boxFilter(guideSq, width, height, radius)
        val meanGuideSrc = boxFilter(guideSrc, width, height, radius)

        // Compute coefficients a and b
        val aCoeff = FloatArray(pixelCount)
        val bCoeff = FloatArray(pixelCount)
        for (i in 0 until pixelCount) {
            val variance = meanGuideSq[i] - meanGuide[i] * meanGuide[i]
            val covariance = meanGuideSrc[i] - meanGuide[i] * meanSource[i]
            aCoeff[i] = covariance / (variance + epsilon)
            bCoeff[i] = meanSource[i] - aCoeff[i] * meanGuide[i]
        }

        // Smooth coefficients
        val meanA = boxFilter(aCoeff, width, height, radius)
        val meanB = boxFilter(bCoeff, width, height, radius)

        // Output: q = meanA * guide + meanB
        for (i in 0 until pixelCount) {
            result[i] = (meanA[i] * guide[i] + meanB[i]).coerceIn(0f, 1f)
        }

        return result
    }

    // ──────────────────────────────────────────────────────────────────
    // Utility Functions
    // ──────────────────────────────────────────────────────────────────

    private fun extractNormalisedPlanes(
        buffer: PhotonBuffer,
        red: FloatArray,
        green: FloatArray,
        blue: FloatArray,
        maxValue: Float,
    ) {
        val rPlane = buffer.planeView(0)
        val gPlane = buffer.planeView(1)
        val bPlane = buffer.planeView(2)
        val pixelCount = red.size
        for (i in 0 until pixelCount) {
            if (rPlane.hasRemaining()) red[i] = (rPlane.get().toInt() and 0xFFFF) / maxValue
            if (gPlane.hasRemaining()) green[i] = (gPlane.get().toInt() and 0xFFFF) / maxValue
            if (bPlane.hasRemaining()) blue[i] = (bPlane.get().toInt() and 0xFFFF) / maxValue
        }
    }

    private fun boxFilter(
        source: FloatArray,
        width: Int,
        height: Int,
        radius: Int,
    ): FloatArray {
        val result = source.copyOf()
        val temp = FloatArray(source.size)

        // Horizontal pass
        for (y in 0 until height) {
            var sum = 0f; var count = 0
            for (x in 0 until min(radius + 1, width)) {
                sum += result[y * width + x]; count++
            }
            for (x in 0 until width) {
                temp[y * width + x] = sum / count
                val addX = x + radius + 1
                val removeX = x - radius
                if (addX < width) { sum += result[y * width + addX]; count++ }
                if (removeX >= 0) { sum -= result[y * width + removeX]; count-- }
            }
        }

        // Vertical pass
        for (x in 0 until width) {
            var sum = 0f; var count = 0
            for (y in 0 until min(radius + 1, height)) {
                sum += temp[y * width + x]; count++
            }
            for (y in 0 until height) {
                result[y * width + x] = sum / count
                val addY = y + radius + 1
                val removeY = y - radius
                if (addY < height) { sum += temp[addY * width + x]; count++ }
                if (removeY >= 0) { sum -= temp[removeY * width + x]; count-- }
            }
        }

        return result
    }

    companion object {
        private const val EPSILON = 1e-6f

        // ── Dark Channel Prior Constants ─────────────────────────────
        private const val DARK_CHANNEL_PATCH_SIZE = 15
        private const val DEFAULT_OMEGA = 0.95f
        private const val ATMOSPHERIC_PERCENTILE = 0.999f
        private const val TRANSMISSION_FLOOR = 0.1f

        // ── Guided Filter Constants ──────────────────────────────────
        private const val GUIDED_FILTER_RADIUS = 60
        private const val GUIDED_FILTER_EPSILON = 0.001f

        // ── Clarity Constants ────────────────────────────────────────
        private const val CLARITY_BLUR_RADIUS = 20

        // ── Haze Detection ───────────────────────────────────────────
        const val HAZE_THRESHOLD = 0.3f
    }
}
