package com.leica.cam.lens_model.correction

import kotlin.math.hypot

/**
 * Linear RGB frame in scene-referred light.
 *
 * All channel arrays must be `width * height` and normalized to `[0f, 1f]`.
 */
data class LinearRgbFrame(
    val width: Int,
    val height: Int,
    val red: FloatArray,
    val green: FloatArray,
    val blue: FloatArray,
)

/** Lens correction calibration package used by [LensCorrectionSuite]. */
data class LensCorrectionProfile(
    val fixedPatternNoiseMap: FloatArray,
    val lensShadingGainMap: FloatArray,
    val radialDistortion: DistortionCoefficients,
    val chromaticAberration: ChromaticAberrationModel,
)

/**
 * Brown-Conrady radial + tangential distortion coefficients.
 *
 * Standard cameras use k1..k3 + p1,p2 (5 coefficients).
 * Ultra-wide cameras (OV08D10) require k4 as the 6th coefficient
 * for acceptable corner correction at FOV > 120°.
 *
 * Model:
 *   x' = x·(1 + k1·r² + k2·r⁴ + k3·r⁶ + k4·r⁸) + 2·p1·x·y + p2·(r² + 2x²)
 *   y' = y·(1 + k1·r² + k2·r⁴ + k3·r⁶ + k4·r⁸) + p1·(r² + 2y²) + 2·p2·x·y
 */
data class DistortionCoefficients(
    val k1: Float,
    val k2: Float,
    val k3: Float,
    val p1: Float,
    val p2: Float,
    /** 4th radial coefficient for ultra-wide lenses. 0f = unused (standard cameras). */
    val k4: Float = 0f,
)

/** Radial chromatic aberration model in normalized image space. */
data class ChromaticAberrationModel(
    val redRadialShift: Float,
    val blueRadialShift: Float,
)

/**
 * Production-grade lens correction suite for raw/linear pipeline input.
 *
 * Stages:
 * 1. Fixed-pattern noise suppression.
 * 2. Lens shading compensation.
 * 3. Chromatic aberration alignment.
 * 4. Geometric distortion correction.
 */
class LensCorrectionSuite {
    fun apply(frame: LinearRgbFrame, profile: LensCorrectionProfile): LinearRgbFrame {
        validateFrame(frame)
        val fpnCorrected = correctFixedPatternNoise(frame, profile.fixedPatternNoiseMap)
        val shadingCorrected = correctLensShading(fpnCorrected, profile.lensShadingGainMap)
        val caCorrected = correctChromaticAberration(shadingCorrected, profile.chromaticAberration)
        return correctDistortion(caCorrected, profile.radialDistortion)
    }

    fun correctFixedPatternNoise(frame: LinearRgbFrame, noiseMap: FloatArray): LinearRgbFrame {
        require(noiseMap.size == frame.width * frame.height) {
            "Noise map must match frame dimensions"
        }
        return LinearRgbFrame(
            width = frame.width,
            height = frame.height,
            red = subtractMap(frame.red, noiseMap),
            green = subtractMap(frame.green, noiseMap),
            blue = subtractMap(frame.blue, noiseMap),
        )
    }

    fun correctLensShading(frame: LinearRgbFrame, gainMap: FloatArray): LinearRgbFrame {
        require(gainMap.size == frame.width * frame.height) {
            "Gain map must match frame dimensions"
        }
        return LinearRgbFrame(
            width = frame.width,
            height = frame.height,
            red = multiplyMap(frame.red, gainMap),
            green = multiplyMap(frame.green, gainMap),
            blue = multiplyMap(frame.blue, gainMap),
        )
    }

    fun correctChromaticAberration(
        frame: LinearRgbFrame,
        model: ChromaticAberrationModel,
    ): LinearRgbFrame {
        val correctedRed = radialRealign(
            source = frame.red,
            width = frame.width,
            height = frame.height,
            radialShiftScale = model.redRadialShift,
        )
        val correctedBlue = radialRealign(
            source = frame.blue,
            width = frame.width,
            height = frame.height,
            radialShiftScale = model.blueRadialShift,
        )

        return LinearRgbFrame(
            width = frame.width,
            height = frame.height,
            red = correctedRed,
            green = frame.green.copyOf(),
            blue = correctedBlue,
        )
    }

    /**
     * Correct geometric distortion using extended Brown-Conrady model.
     *
     * Supports up to 6 coefficients (k1,k2,k3,k4,p1,p2).
     * The k4·r⁸ term is critical for ultra-wide lenses (OV08D10, FOV > 120°).
     * A 5-coefficient model leaves visible barrel distortion at frame corners
     * on ultra-wide optics — this was verified on OV08D10 calibration targets.
     *
     * Model:
     *   radial = 1 + k1·r² + k2·r⁴ + k3·r⁶ + k4·r⁸
     *   x' = x·radial + 2·p1·x·y + p2·(r² + 2x²)
     *   y' = y·radial + p1·(r² + 2y²) + 2·p2·x·y
     */
    fun correctDistortion(
        frame: LinearRgbFrame,
        coefficients: DistortionCoefficients,
    ): LinearRgbFrame {
        val size = frame.width * frame.height
        val outR = FloatArray(size)
        val outG = FloatArray(size)
        val outB = FloatArray(size)

        val centerX = (frame.width - 1) / 2f
        val centerY = (frame.height - 1) / 2f
        val invFx = 2f / frame.width
        val invFy = 2f / frame.height

        for (y in 0 until frame.height) {
            for (x in 0 until frame.width) {
                val nx = (x - centerX) * invFx
                val ny = (y - centerY) * invFy

                val r2 = nx * nx + ny * ny
                val r4 = r2 * r2
                val r6 = r4 * r2
                val r8 = r4 * r4

                // Extended radial distortion with k4·r⁸ term
                val radial = 1f +
                    coefficients.k1 * r2 +
                    coefficients.k2 * r4 +
                    coefficients.k3 * r6 +
                    coefficients.k4 * r8

                // Tangential distortion (decentering)
                val xDistorted = nx * radial +
                    2f * coefficients.p1 * nx * ny +
                    coefficients.p2 * (r2 + 2f * nx * nx)
                val yDistorted = ny * radial +
                    coefficients.p1 * (r2 + 2f * ny * ny) +
                    2f * coefficients.p2 * nx * ny

                val sampleX = xDistorted / invFx + centerX
                val sampleY = yDistorted / invFy + centerY
                val index = y * frame.width + x

                outR[index] = sampleBilinear(frame.red, frame.width, frame.height, sampleX, sampleY)
                outG[index] = sampleBilinear(frame.green, frame.width, frame.height, sampleX, sampleY)
                outB[index] = sampleBilinear(frame.blue, frame.width, frame.height, sampleX, sampleY)
            }
        }

        return LinearRgbFrame(frame.width, frame.height, outR, outG, outB)
    }

    private fun validateFrame(frame: LinearRgbFrame) {
        val expected = frame.width * frame.height
        require(expected > 0) { "Frame dimensions must be positive" }
        require(frame.red.size == expected) { "Red channel size mismatch" }
        require(frame.green.size == expected) { "Green channel size mismatch" }
        require(frame.blue.size == expected) { "Blue channel size mismatch" }
    }

    private fun subtractMap(channel: FloatArray, map: FloatArray): FloatArray {
        val corrected = FloatArray(channel.size)
        channel.indices.forEach { index ->
            corrected[index] = (channel[index] - map[index]).coerceIn(0f, 1f)
        }
        return corrected
    }

    private fun multiplyMap(channel: FloatArray, map: FloatArray): FloatArray {
        val corrected = FloatArray(channel.size)
        channel.indices.forEach { index ->
            corrected[index] = (channel[index] * map[index]).coerceIn(0f, 1f)
        }
        return corrected
    }

    private fun radialRealign(
        source: FloatArray,
        width: Int,
        height: Int,
        radialShiftScale: Float,
    ): FloatArray {
        val corrected = FloatArray(source.size)
        val centerX = (width - 1) / 2f
        val centerY = (height - 1) / 2f
        val maxRadius = hypot(centerX, centerY).coerceAtLeast(1f)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val dx = x - centerX
                val dy = y - centerY
                val radius = hypot(dx, dy)
                val normalizedRadius = radius / maxRadius
                val shiftPixels = normalizedRadius * radialShiftScale * maxRadius
                val directionX = if (radius == 0f) 0f else dx / radius
                val directionY = if (radius == 0f) 0f else dy / radius
                val sampleX = x - directionX * shiftPixels
                val sampleY = y - directionY * shiftPixels
                corrected[y * width + x] = sampleBilinear(source, width, height, sampleX, sampleY)
            }
        }
        return corrected
    }

    private fun sampleBilinear(
        source: FloatArray,
        width: Int,
        height: Int,
        x: Float,
        y: Float,
    ): Float {
        val clampedX = x.coerceIn(0f, (width - 1).toFloat())
        val clampedY = y.coerceIn(0f, (height - 1).toFloat())

        val x0 = clampedX.toInt()
        val y0 = clampedY.toInt()
        val x1 = (x0 + 1).coerceAtMost(width - 1)
        val y1 = (y0 + 1).coerceAtMost(height - 1)

        val wx = clampedX - x0
        val wy = clampedY - y0

        val top = lerp(source[y0 * width + x0], source[y0 * width + x1], wx)
        val bottom = lerp(source[y1 * width + x0], source[y1 * width + x1], wx)
        return lerp(top, bottom, wy)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
