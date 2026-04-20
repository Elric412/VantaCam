package com.leica.cam.imaging_pipeline.hdr

import kotlin.math.log10

/**
 * EV bracket selection strategy for ProXDR HDR capture.
 *
 * Rule-based fallback per hdr-engine-deep.md:
 * - Dynamic range < 55 dB -> single frame
 * - Dynamic range < 70 dB -> 2-frame (+0, -1.5)
 * - Face present -> face-biased 3-frame bracket
 * - Thermal SEVERE -> single frame only
 *
 * D2.6: Per-sensor overrides:
 * - S5KHM6 (108MP main): cap at 3 frames (thermal constraint from full-res readout)
 * - OV08D10 (ultra-wide): cap at 2 frames (larger AA filter, more motion blur)
 * - SC202PCS (macro): always single frame (macro doesn't need HDR)
 * - SC202CS (depth): never enters HDR pipeline (assert at top of method)
 */
object BracketSelector {

    private const val MAX_UNDER_EV = -4f
    private const val MAX_OVER_EV = 4f

    /**
     * Select EV bracket based on scene dynamic range and thermal state.
     *
     * @return List of EV offsets relative to the base exposure.
     */
    fun pickBracket(scene: SceneDescriptor): List<Float> {
        val thermal = thermalFromLevel(scene.thermalLevel)
        if (thermal == ThermalState.SEVERE) return listOf(0f)

        val hist = scene.luminanceHistogram
        val p01 = histPercentile(hist, 0.01f)
        val p99 = histPercentile(hist, 0.99f)
        val dynRangeDb = if (p01 > 1e-4f) 20f * log10(p99 / p01) else 90f

        return when {
            dynRangeDb < 55f -> listOf(0f)
            dynRangeDb < 70f -> listOf(0f, -1.5f)
            scene.facePresent -> listOf(-1f, 0f, +1.5f)
            dynRangeDb < 90f -> listOf(-2f, 0f, +1.5f)
            thermal == ThermalState.HIGH -> listOf(-2f, 0f, +1.5f)
            else -> listOf(-4f, -2f, 0f, +2f, +4f)
        }.map { ev -> ev.coerceIn(MAX_UNDER_EV, MAX_OVER_EV) }
    }

    /**
     * Per-sensor bracket override. Applied AFTER [pickBracket] logic.
     *
     * @param sensorId Active sensor identifier string.
     * @param scene    Scene descriptor for base bracket selection.
     * @return Sensor-constrained EV bracket.
     */
    fun pickBracketForSensor(scene: SceneDescriptor, sensorId: String): List<Float> {
        val lower = sensorId.lowercase()

        // SC202CS (depth): never enters HDR pipeline
        require(!lower.contains("sc202cs")) {
            "Depth sensor SC202CS must not enter the HDR pipeline"
        }

        // SC202PCS (macro): always single frame -- macro doesn't need HDR
        if (lower.contains("sc202pcs")) return listOf(0f)

        val baseBracket = pickBracket(scene)

        return when {
            // S5KHM6 (Samsung main, 108MP): cap at 3 frames (thermal from full-res readout)
            lower.contains("s5khm6") -> baseBracket.take(3)
            // OV08D10 (ultra-wide): cap at 2 frames (larger AA filter, more motion blur)
            lower.contains("ov08d10") -> baseBracket.take(2)
            else -> baseBracket
        }
    }

    /**
     * Compute Nth percentile from a 256-bin normalised histogram.
     */
    fun histPercentile(hist: FloatArray, frac: Float): Float {
        var cumSum = 0f
        for (bin in hist.indices) {
            cumSum += hist[bin]
            if (cumSum >= frac) return bin / 255f
        }
        return 1f
    }

    private fun thermalFromLevel(level: Int): ThermalState = when {
        level >= 6 -> ThermalState.SEVERE
        level >= 4 -> ThermalState.HIGH
        level >= 2 -> ThermalState.ELEVATED
        else -> ThermalState.NORMAL
    }
}
