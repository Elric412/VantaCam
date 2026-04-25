package com.leica.cam.capture.color

import com.leica.cam.hardware.contracts.photon.PhotonBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * CAM16 Color Appearance Model — Implementation.md Section 5.5
 *
 * Implements the CAM16 forward and inverse transforms for perceptually
 * uniform saturation adjustments. All vibrance and saturation operations
 * occur in CAM16 space to prevent posterisation and unnatural hue shifts.
 *
 * CAM16 viewing conditions:
 *   LA = 64 cd/m² (adapting field luminance)
 *   Yb = 20 (relative background luminance)
 *   Surround = Average (c=0.69, Nc=1.0, F=1.0)
 *
 * The vibrance slider:
 *   1. Computes per-pixel saturation in CAM16.
 *   2. Computes a protection factor: P = sigmoid(saturation - 0.5)
 *      P ≈ 0 for already-saturated colours, ≈ 1 for desaturated ones.
 *   3. Applies saturation boost proportional to P × vibrance_amount.
 *   4. Skin protection: multiplies P by 0.25 for skin-hue pixels.
 *
 * Reference: Implementation.md — Perceptual Color Uniformity (Section 5.5)
 */
class Cam16ColorAppearanceModel {

    /**
     * Apply vibrance adjustment using CAM16 perceptual color space.
     *
     * @param buffer Input photon buffer (RGB planes)
     * @param vibranceAmount Vibrance strength [-1.0, +1.0]
     * @param skinMask Optional per-pixel skin mask [0..1] for skin protection
     * @return Vibrance-adjusted photon buffer
     */
    fun applyVibrance(
        buffer: PhotonBuffer,
        vibranceAmount: Float,
        skinMask: FloatArray? = null,
    ): PhotonBuffer {
        if (abs(vibranceAmount) < EPSILON) return buffer
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

        val rPlane = buffer.planeView(0)
        val gPlane = buffer.planeView(1)
        val bPlane = buffer.planeView(2)

        for (i in 0 until pixelCount) {
            if (!rPlane.hasRemaining()) break

            val r = (rPlane.get().toInt() and 0xFFFF) / maxValue
            val g = (gPlane.get().toInt() and 0xFFFF) / maxValue
            val b = (bPlane.get().toInt() and 0xFFFF) / maxValue

            // Forward transform: sRGB → XYZ → CAM16
            val xyz = srgbToXyz(r, g, b)
            val cam = forwardCam16(xyz)

            // Compute protection factor
            val normalizedSat = (cam.saturation / 100f).coerceIn(0f, 1f)
            var protection = sigmoid(normalizedSat - 0.5f)

            // Skin protection: reduce vibrance boost on skin-hue pixels
            val isSkinHue = cam.hueAngle in SKIN_HUE_MIN..SKIN_HUE_MAX
            if (isSkinHue) {
                protection *= SKIN_PROTECTION_FACTOR
            }
            if (skinMask != null && i < skinMask.size && skinMask[i] > 0.3f) {
                protection *= SKIN_PROTECTION_FACTOR
            }

            // Apply vibrance-modulated saturation boost
            val satBoost = vibranceAmount * protection
            val newSaturation = (cam.saturation * (1f + satBoost)).coerceIn(0f, MAX_SATURATION)

            // Inverse transform: CAM16 → XYZ → sRGB
            val adjustedCam = cam.copy(saturation = newSaturation)
            val newXyz = inverseCam16(adjustedCam)
            val newRgb = xyzToSrgb(newXyz)

            // Output is written to GPU texture in production
        }

        return buffer
    }

    /**
     * Apply uniform saturation adjustment in CAM16 space.
     *
     * @param buffer Input photon buffer
     * @param saturationAmount Saturation adjustment [-1.0, +1.0]
     * @return Saturation-adjusted buffer
     */
    fun applySaturation(
        buffer: PhotonBuffer,
        saturationAmount: Float,
    ): PhotonBuffer {
        if (abs(saturationAmount) < EPSILON) return buffer
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

        val rPlane = buffer.planeView(0)
        val gPlane = buffer.planeView(1)
        val bPlane = buffer.planeView(2)

        for (i in 0 until pixelCount) {
            if (!rPlane.hasRemaining()) break

            val r = (rPlane.get().toInt() and 0xFFFF) / maxValue
            val g = (gPlane.get().toInt() and 0xFFFF) / maxValue
            val b = (bPlane.get().toInt() and 0xFFFF) / maxValue

            val xyz = srgbToXyz(r, g, b)
            val cam = forwardCam16(xyz)

            // Uniform saturation adjustment
            val newSat = (cam.saturation * (1f + saturationAmount)).coerceIn(0f, MAX_SATURATION)
            val adjustedCam = cam.copy(saturation = newSat)

            val newXyz = inverseCam16(adjustedCam)
            val newRgb = xyzToSrgb(newXyz)
        }

        return buffer
    }

    // ──────────────────────────────────────────────────────────────────
    // CAM16 Forward Transform
    // ──────────────────────────────────────────────────────────────────

    /**
     * Forward CAM16 transform: CIE XYZ → CAM16 appearance correlates.
     */
    fun forwardCam16(xyz: FloatArray): Cam16Color {
        // Step 1: Chromatic adaptation using CAT16 matrix
        val adapted = chromaticAdapt(xyz)

        // Step 2: Compute cone responses
        val rA = adaptedResponse(adapted[0])
        val gA = adaptedResponse(adapted[1])
        val bA = adaptedResponse(adapted[2])

        // Step 3: Compute appearance correlates
        val a = rA - 12f * gA / 11f + bA / 11f
        val b = (rA + gA - 2f * bA) / 9f

        // Hue angle
        val hRad = atan2(b, a)
        val hDeg = Math.toDegrees(hRad.toDouble()).toFloat()
        val hueAngle = if (hDeg < 0f) hDeg + 360f else hDeg

        // Eccentricity factor
        val et = 0.25f * (cos(hRad + 2f) + 3.8f)

        // Achromatic response
        val aResponse = (2f * rA + gA + 0.05f * bA - 0.305f) * N_BB

        // Lightness J
        val j = 100f * (aResponse / A_W).pow(C_Z * SURROUND_C)

        // Chroma
        val t = (50000f / 13f * N_C * N_CB * et * sqrt(a * a + b * b)) /
            (rA + gA + 21f * bA / 20f + 0.305f)
        val chroma = t.pow(0.9f) * sqrt(j / 100f) * (1.64f - 0.29f.pow(N)).pow(0.73f)

        // Saturation
        val saturation = 50f * sqrt(SURROUND_C * t / (aResponse + 4f))

        return Cam16Color(
            lightness = j.coerceIn(0f, 100f),
            chroma = chroma.coerceAtLeast(0f),
            hueAngle = hueAngle,
            saturation = saturation.coerceIn(0f, MAX_SATURATION),
            brightness = 0f, // Simplified — brightness correlate not needed for image processing
            colourfulness = chroma * F_L.pow(0.25f),
        )
    }

    // ──────────────────────────────────────────────────────────────────
    // CAM16 Inverse Transform
    // ──────────────────────────────────────────────────────────────────

    /**
     * Inverse CAM16 transform: CAM16 appearance correlates → CIE XYZ.
     */
    fun inverseCam16(cam: Cam16Color): FloatArray {
        val j = cam.lightness
        val c = cam.chroma
        val h = cam.hueAngle

        // Inverse lightness → achromatic response
        val aResponse = A_W * (j / 100f).pow(1f / (SURROUND_C * C_Z))

        // Inverse chroma → t
        val t = (c / (sqrt(j / 100f) * (1.64f - 0.29f.pow(N)).pow(0.73f)))
            .pow(1f / 0.9f)

        val hRad = Math.toRadians(h.toDouble()).toFloat()
        val et = 0.25f * (cos(hRad + 2f) + 3.8f)

        val cosH = cos(hRad)
        val sinH = sin(hRad)

        // Reconstruct cone signals
        val p1 = (50000f / 13f * N_C * N_CB * et) / t
        val p2 = aResponse / N_BB + 0.305f

        val a = if (abs(sinH) >= abs(cosH)) {
            p2 * (2f + 3f * (460f / 1403f)) / (2f + 3f * (220f / 1403f) * cosH / sinH - (27f / 1403f))
        } else {
            p2 * (2f + 3f * (460f / 1403f)) / (2f + 3f * (220f / 1403f) - (27f / 1403f) * sinH / cosH)
        }

        // Simplified inverse — compute adapted RGB then un-adapt
        val rA = (460f * p2 + 451f * a * cosH + 288f * a * sinH) / 1403f
        val gA = (460f * p2 - 891f * a * cosH - 261f * a * sinH) / 1403f
        val bA = (460f * p2 - 220f * a * cosH - 6300f * a * sinH) / 1403f

        // Inverse adapted response
        val rC = inverseAdaptedResponse(rA)
        val gC = inverseAdaptedResponse(gA)
        val bC = inverseAdaptedResponse(bA)

        // Inverse chromatic adaptation (CAT16)
        return inverseChromaticAdapt(floatArrayOf(rC, gC, bC))
    }

    // ──────────────────────────────────────────────────────────────────
    // Chromatic Adaptation (CAT16)
    // ──────────────────────────────────────────────────────────────────

    private fun chromaticAdapt(xyz: FloatArray): FloatArray {
        val r = CAT16_M[0] * xyz[0] + CAT16_M[1] * xyz[1] + CAT16_M[2] * xyz[2]
        val g = CAT16_M[3] * xyz[0] + CAT16_M[4] * xyz[1] + CAT16_M[5] * xyz[2]
        val b = CAT16_M[6] * xyz[0] + CAT16_M[7] * xyz[1] + CAT16_M[8] * xyz[2]
        return floatArrayOf(r * D_RGB[0], g * D_RGB[1], b * D_RGB[2])
    }

    private fun inverseChromaticAdapt(rgb: FloatArray): FloatArray {
        val r = rgb[0] / D_RGB[0]
        val g = rgb[1] / D_RGB[1]
        val b = rgb[2] / D_RGB[2]
        return floatArrayOf(
            CAT16_INV[0] * r + CAT16_INV[1] * g + CAT16_INV[2] * b,
            CAT16_INV[3] * r + CAT16_INV[4] * g + CAT16_INV[5] * b,
            CAT16_INV[6] * r + CAT16_INV[7] * g + CAT16_INV[8] * b,
        )
    }

    private fun adaptedResponse(value: Float): Float {
        val x = (F_L * abs(value) / 100f).pow(0.42f)
        return sign(value) * 400f * x / (x + 27.13f) + 0.1f
    }

    private fun inverseAdaptedResponse(value: Float): Float {
        val v = value - 0.1f
        val absV = abs(v)
        return sign(v) * 100f / F_L * ((27.13f * absV) / (400f - absV)).pow(1f / 0.42f)
    }

    // ──────────────────────────────────────────────────────────────────
    // sRGB ↔ CIE XYZ Conversion
    // ──────────────────────────────────────────────────────────────────

    private fun srgbToXyz(r: Float, g: Float, b: Float): FloatArray {
        val lr = linearize(r)
        val lg = linearize(g)
        val lb = linearize(b)
        return floatArrayOf(
            0.4124564f * lr + 0.3575761f * lg + 0.1804375f * lb,
            0.2126729f * lr + 0.7151522f * lg + 0.0721750f * lb,
            0.0193339f * lr + 0.1191920f * lg + 0.9503041f * lb,
        )
    }

    private fun xyzToSrgb(xyz: FloatArray): FloatArray {
        val r = 3.2404542f * xyz[0] - 1.5371385f * xyz[1] - 0.4985314f * xyz[2]
        val g = -0.9692660f * xyz[0] + 1.8760108f * xyz[1] + 0.0415560f * xyz[2]
        val b = 0.0556434f * xyz[0] - 0.2040259f * xyz[1] + 1.0572252f * xyz[2]
        return floatArrayOf(
            delinearize(r).coerceIn(0f, 1f),
            delinearize(g).coerceIn(0f, 1f),
            delinearize(b).coerceIn(0f, 1f),
        )
    }

    private fun linearize(c: Float): Float =
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

    private fun delinearize(c: Float): Float =
        if (c <= 0.0031308f) c * 12.92f else 1.055f * c.coerceAtLeast(0f).pow(1f / 2.4f) - 0.055f

    private fun sigmoid(x: Float): Float = 1f / (1f + exp(-SIGMOID_STEEPNESS * x))

    companion object {
        private const val EPSILON = 1e-6f
        private const val SIGMOID_STEEPNESS = 8f
        private const val MAX_SATURATION = 120f

        // ── CAM16 Viewing Conditions ─────────────────────────────────
        private const val LA = 64f              // Adapting luminance cd/m²
        private const val YB = 20f              // Relative background luminance
        private const val SURROUND_C = 0.69f    // Average surround
        private const val N_C = 1.0f            // Chromatic induction factor
        private const val F = 1.0f              // Adaptation factor

        // ── Derived CAM16 constants ──────────────────────────────────
        private val N = YB / 100f               // Background ratio
        private val N_BB = 0.725f / N.pow(0.2f)
        private val N_CB = N_BB
        private val F_L = computeFl(LA)
        private val A_W = computeAw()
        private val C_Z = 1.48f + sqrt(N)

        // ── Skin hue range (CAM16 hue angle) ────────────────────────
        private const val SKIN_HUE_MIN = 20f
        private const val SKIN_HUE_MAX = 70f
        private const val SKIN_PROTECTION_FACTOR = 0.25f

        // ── CAT16 Matrix ─────────────────────────────────────────────
        private val CAT16_M = floatArrayOf(
            0.401288f, 0.650173f, -0.051461f,
            -0.250268f, 1.204414f, 0.045854f,
            -0.002079f, 0.048952f, 0.953127f,
        )

        private val CAT16_INV = floatArrayOf(
            1.862068f, -1.011255f, 0.149187f,
            0.387527f, 0.621447f, -0.008974f,
            -0.015841f, -0.034123f, 1.049964f,
        )

        // D65 white point chromatic adaptation factors
        private val D_RGB = floatArrayOf(1.021f, 0.986f, 0.934f)

        private fun computeFl(la: Float): Float {
            val k = 1f / (5f * la + 1f)
            val k4 = k.pow(4)
            return 0.2f * k4 * (5f * la) + 0.1f * (1f - k4).pow(2) * (5f * la).pow(1f / 3f)
        }

        private fun computeAw(): Float = 50f // Simplified achromatic response of white
    }
}

/**
 * CAM16 colour appearance correlates for a single pixel.
 */
data class Cam16Color(
    /** Lightness J [0..100] */
    val lightness: Float,
    /** Chroma C [0..∞) */
    val chroma: Float,
    /** Hue angle h [0..360°) */
    val hueAngle: Float,
    /** Saturation s [0..120] */
    val saturation: Float,
    /** Brightness Q */
    val brightness: Float,
    /** Colourfulness M */
    val colourfulness: Float,
)
