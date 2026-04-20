package com.leica.cam.sensor_hal.sensor

import com.leica.cam.sensor_hal.sensor.profiles.SensorProfile

/**
 * Smart-ISO Pro mode detector and configuration for Samsung ISOCELL S5KHM6.
 *
 * Smart-ISO Pro captures two readouts at different ISO sensitivities simultaneously:
 * - Low-ISO readout: clean highlights, low noise floor
 * - High-ISO readout: better shadow detail, higher noise
 *
 * The FusionLM merge engine then blends the two readouts using per-pixel
 * Wiener weighting — shadows from high-ISO, highlights from low-ISO.
 *
 * This is distinct from staggered HDR (different exposure times) —
 * Smart-ISO Pro uses the SAME exposure time but different analog gains.
 *
 * Detection: read CaptureResult vendor tag for dual-readout mode.
 * On Dimensity SoCs, the tag is typically under the "com.mediatek" namespace.
 *
 * References:
 * - Samsung ISOCELL HM6 datasheet: Smart-ISO Pro specification
 * - Dimension 5 spec: "S5KHM6 Smart-ISO Pro detection"
 */
class SmartIsoProDetector {

    /**
     * Smart-ISO Pro readout pair metadata.
     *
     * @param lowIso      Low-gain readout ISO value (e.g., 100)
     * @param highIso     High-gain readout ISO value (e.g., 800)
     * @param isoRatio    Ratio of highIso/lowIso (typically 4–8×)
     * @param isActive    Whether Smart-ISO Pro is active for this capture
     */
    data class SmartIsoReadout(
        val lowIso: Int,
        val highIso: Int,
        val isoRatio: Float,
        val isActive: Boolean,
    ) {
        companion object {
            val INACTIVE = SmartIsoReadout(lowIso = 0, highIso = 0, isoRatio = 1f, isActive = false)
        }
    }

    /**
     * Detect whether Smart-ISO Pro is active and extract readout parameters.
     *
     * @param sensorProfile Sensor profile (must be S5KHM6 with hasSmartIsoPro = true)
     * @param vendorIsoLow  Low-gain ISO from vendor tag (0 if unavailable)
     * @param vendorIsoHigh High-gain ISO from vendor tag (0 if unavailable)
     * @param currentIso    Current capture ISO from CaptureResult.SENSOR_SENSITIVITY
     * @return Smart-ISO readout metadata
     */
    fun detect(
        sensorProfile: SensorProfile,
        vendorIsoLow: Int,
        vendorIsoHigh: Int,
        currentIso: Int,
    ): SmartIsoReadout {
        // Only S5KHM6 supports Smart-ISO Pro
        if (!sensorProfile.specialModes.hasSmartIsoPro) return SmartIsoReadout.INACTIVE

        // Vendor tags present: use them directly
        if (vendorIsoLow > 0 && vendorIsoHigh > 0 && vendorIsoHigh > vendorIsoLow) {
            return SmartIsoReadout(
                lowIso = vendorIsoLow,
                highIso = vendorIsoHigh,
                isoRatio = vendorIsoHigh.toFloat() / vendorIsoLow,
                isActive = true,
            )
        }

        // Heuristic detection: Smart-ISO Pro activates in specific ISO ranges
        // On HM6, it typically engages between ISO 200–3200
        return when {
            currentIso in 200..3200 -> {
                // Estimate dual readout from capture ISO
                val lowIso = (currentIso / 4).coerceAtLeast(50)
                val highIso = currentIso
                SmartIsoReadout(
                    lowIso = lowIso,
                    highIso = highIso,
                    isoRatio = highIso.toFloat() / lowIso,
                    isActive = true,
                )
            }
            else -> SmartIsoReadout.INACTIVE
        }
    }

    /**
     * Compute per-pixel blend weight for Smart-ISO Pro fusion.
     *
     * For each pixel, the weight determines how much of the low-ISO readout
     * vs high-ISO readout to use:
     *
     * - Highlights (luminance > 0.7): weight → 1.0 (use low-ISO for clean highlights)
     * - Shadows (luminance < 0.15): weight → 0.0 (use high-ISO for shadow detail)
     * - Midtones: smooth crossfade based on luminance
     *
     * @param luminance Per-pixel luminance array [0, 1]
     * @param readout   Smart-ISO readout metadata
     * @return Per-pixel blend weight [0, 1] where 1.0 = 100% low-ISO readout
     */
    fun computeBlendWeights(
        luminance: FloatArray,
        readout: SmartIsoReadout,
    ): FloatArray {
        if (!readout.isActive) return FloatArray(luminance.size) { 0.5f }

        // Crossfade zone: [shadowThreshold, highlightThreshold]
        val shadowThreshold = 0.15f
        val highlightThreshold = 0.70f
        val range = highlightThreshold - shadowThreshold

        return FloatArray(luminance.size) { i ->
            when {
                luminance[i] >= highlightThreshold -> 1.0f  // Low-ISO (clean highlights)
                luminance[i] <= shadowThreshold -> 0.0f     // High-ISO (shadow detail)
                else -> {
                    // Smooth hermite interpolation in the crossfade zone
                    val t = (luminance[i] - shadowThreshold) / range
                    t * t * (3f - 2f * t) // smoothstep
                }
            }
        }
    }
}
