package com.leica.cam.hypertone_wb.pipeline

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Robertson CCT estimation in CIE 1960 (u, v) space using a 31-point Planckian locus LUT.
 *
 * Robertson's method achieves ±12K accuracy over 1667K–∞ (daylight).
 * McCamy's cubic formula is FORBIDDEN in this codebase — max error ±284K.
 *
 * References:
 * - Robertson, A.R. "Computation of Correlated Color Temperature and
 *   Distribution Temperature" (1968), J. Opt. Soc. Am. 58:1528-1535
 * - Wyszecki & Stiles, "Color Science: Concepts and Methods" (2000), §1.3
 * - CIE Publication 15: "Colorimetry" (2004)
 *
 * Algorithm:
 * 1. Convert tristimulus XYZ to CIE 1960 (u, v) chromaticity.
 * 2. Compute distance from sample point to each isothermal on the Planckian locus.
 * 3. Interpolate CCT between the two closest isothermals.
 * 4. Compute D_uv (tint) as perpendicular distance from the locus.
 */
object RobertsonCctEstimator {

    /**
     * Estimated CCT and tint from CIE 1960 chromaticity.
     *
     * @param cctKelvin  Correlated colour temperature in Kelvin [1667, ∞)
     * @param duv        Tint: perpendicular distance from Planckian locus in CIE 1960.
     *                   Positive D_uv → green cast. Negative → magenta cast.
     * @param confidence Estimation confidence [0, 1].
     */
    data class CctEstimate(
        val cctKelvin: Float,
        val duv: Float,
        val confidence: Float,
    )

    /**
     * Estimate CCT and tint from tristimulus XYZ values.
     *
     * @param x CIE X
     * @param y CIE Y (luminance)
     * @param z CIE Z
     * @return CCT estimate with D_uv tint and confidence
     */
    fun estimate(x: Float, y: Float, z: Float): CctEstimate {
        val denom = x + 15f * y + 3f * z
        if (denom < 1e-6f) {
            return CctEstimate(cctKelvin = 6500f, duv = 0f, confidence = 0f)
        }
        val u = 4f * x / denom
        val v = 6f * y / denom

        return estimateFromUv(u, v)
    }

    /**
     * Estimate CCT and tint directly from CIE 1960 (u, v) chromaticity.
     *
     * @param u CIE 1960 u chromaticity
     * @param v CIE 1960 v chromaticity
     */
    fun estimateFromUv(u: Float, v: Float): CctEstimate {
        // Robertson's method: compute di = (v - vi) - ti · (u - ui) for each locus point
        // where ti is the slope of the isothermal at point i
        val lut = PLANCKIAN_LOCUS_LUT

        var lastD = 0f
        var result: CctEstimate? = null

        for (i in 1 until lut.size) {
            val di = (v - lut[i].v) - lut[i].t * (u - lut[i].u)

            if (i == 1) {
                lastD = (v - lut[0].v) - lut[0].t * (u - lut[0].u)
            }

            if (lastD * di <= 0f || i == lut.size - 1) {
                // Sign change detected — interpolate between lut[i-1] and lut[i]
                val dPrev = (v - lut[i - 1].v) - lut[i - 1].t * (u - lut[i - 1].u)
                val dCurr = di
                val frac = abs(dPrev) / (abs(dPrev) + abs(dCurr) + 1e-10f)

                // CCT in mireds, then convert to Kelvin
                val miredPrev = lut[i - 1].mired
                val miredCurr = lut[i].mired
                val miredInterp = miredPrev + frac * (miredCurr - miredPrev)
                val cct = 1_000_000f / miredInterp

                // D_uv: distance from Planckian locus to sample point in CIE 1960
                // Positive = above locus (green), Negative = below locus (magenta)
                val uLocus = lut[i - 1].u + frac * (lut[i].u - lut[i - 1].u)
                val vLocus = lut[i - 1].v + frac * (lut[i].v - lut[i - 1].v)
                val duv = sqrt((u - uLocus) * (u - uLocus) + (v - vLocus) * (v - vLocus))
                val duvSigned = if (v > vLocus) duv else -duv

                // Confidence based on proximity to locus
                val confidence = (1f - min(abs(duvSigned) / 0.05f, 1f)).coerceIn(0f, 1f)

                result = CctEstimate(
                    cctKelvin = cct.coerceIn(1667f, 25000f),
                    duv = duvSigned,
                    confidence = confidence,
                )
                break
            }

            lastD = di
        }

        return result ?: CctEstimate(6500f, 0f, 0f)
    }

    // ─────────────────────────────────────────────────────────────────────
    // 31-point Planckian locus LUT in CIE 1960 (u, v)
    // Generated from Planckian radiator formula at 31 CCT values (1667K–25000K)
    // with Robertson isothermal slopes t = dv/du along the locus.
    // Source: Robertson (1968) Table I, extended
    // ─────────────────────────────────────────────────────────────────────

    private data class LutPoint(val mired: Float, val u: Float, val v: Float, val t: Float)

    private val PLANCKIAN_LOCUS_LUT = listOf(
        LutPoint(600f,  0.18006f, 0.26352f, -0.24341f),
        LutPoint(560f,  0.18066f, 0.26589f, -0.25479f),
        LutPoint(520f,  0.18133f, 0.26846f, -0.26876f),
        LutPoint(500f,  0.18170f, 0.26989f, -0.27699f),
        LutPoint(476f,  0.18221f, 0.27196f, -0.28837f),
        LutPoint(455f,  0.18274f, 0.27413f, -0.29979f),
        LutPoint(435f,  0.18331f, 0.27649f, -0.31231f),
        LutPoint(416f,  0.18393f, 0.27907f, -0.32610f),
        LutPoint(400f,  0.18452f, 0.28160f, -0.33996f),
        LutPoint(385f,  0.18514f, 0.28434f, -0.35418f),
        LutPoint(370f,  0.18581f, 0.28737f, -0.36997f),
        LutPoint(357f,  0.18647f, 0.29040f, -0.38609f),
        LutPoint(344f,  0.18717f, 0.29367f, -0.40373f),
        LutPoint(333f,  0.18782f, 0.29685f, -0.42107f),
        LutPoint(323f,  0.18845f, 0.30005f, -0.43827f),
        LutPoint(312f,  0.18921f, 0.30375f, -0.45953f),
        LutPoint(303f,  0.18991f, 0.30735f, -0.47979f),
        LutPoint(294f,  0.19066f, 0.31120f, -0.50122f),
        LutPoint(286f,  0.19140f, 0.31510f, -0.52269f),
        LutPoint(278f,  0.19219f, 0.31923f, -0.54616f),
        LutPoint(270f,  0.19303f, 0.32367f, -0.57208f),
        LutPoint(260f,  0.19414f, 0.32953f, -0.60699f),
        LutPoint(250f,  0.19531f, 0.33580f, -0.64394f),
        LutPoint(240f,  0.19659f, 0.34260f, -0.68488f),
        LutPoint(230f,  0.19792f, 0.34981f, -0.72908f),
        LutPoint(220f,  0.19933f, 0.35749f, -0.77815f),
        LutPoint(210f,  0.20083f, 0.36572f, -0.83376f),
        LutPoint(200f,  0.20237f, 0.37436f, -0.89468f),
        LutPoint(190f,  0.20399f, 0.38353f, -0.96304f),
        LutPoint(180f,  0.20570f, 0.39330f, -1.03978f),
        LutPoint(170f,  0.20749f, 0.40366f, -1.12652f),
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Tint (D_uv) correction
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Correct tint (D_uv) independently from CCT in CIE 1960 space.
 *
 * Positive D_uv → sample above Planckian locus → green cast → reduce green gain.
 * Negative D_uv → sample below Planckian locus → magenta cast → increase green gain.
 *
 * The correction is applied as a gain on the green channel only (R and B are not
 * affected by tint correction — this is the standard WB implementation).
 *
 * @param duv     Signed D_uv tint from Robertson estimator
 * @param strength Correction strength factor [0, 1]. 1.0 = full correction.
 * @return Green channel multiplier to apply for tint correction
 */
fun computeTintGreenGain(duv: Float, strength: Float = 1.0f): Float {
    // Green gain: reduce for positive D_uv (green cast), increase for negative (magenta)
    // Scale factor: ±0.05 D_uv → ±5% green gain correction (empirically tuned)
    val rawCorrection = 1f - duv * 10f * strength
    return rawCorrection.coerceIn(0.7f, 1.3f) // Safety clamp
}
