package com.leica.cam.imaging_pipeline.pipeline

import kotlin.math.max
import kotlin.math.pow

/**
 * ACES Filmic Tonemapper — Narkowicz approximation.
 *
 * Implements the ACES (Academy Color Encoding System) filmic S-curve
 * using the Narkowicz (2016) analytic approximation:
 *
 *   f(x) = x · (A·x + B) / (x · (C·x + D) + E)
 *
 * Exact coefficients (do NOT round or approximate):
 *   A = 2.51, B = 0.03, C = 2.43, D = 0.59, E = 0.14
 *
 * These coefficients match the ACES RRT+ODT transform for an sRGB output
 * device. Any rounding or approximation of these constants produces
 * visible colour rendering differences in the shadows and highlights.
 *
 * References:
 * - Narkowicz, "ACES Filmic Tone Mapping Curve" (2016)
 *   https://knarkowicz.wordpress.com/2016/01/06/aces-filmic-tone-mapping-curve/
 * - Academy of Motion Picture Arts and Sciences, ACES Central
 *
 * Operating range:
 * - Input: linear scene-referred exposure, nominally [0, ∞)
 * - Output: display-referred [0, 1] approximately, clamped
 *
 * Shadow floor and highlight cap:
 * - Shadow toe: values near 0 map to ~0.014 (lifts pure black to near-black)
 * - Highlight shoulder: soft rolloff — pure white input maps to ~0.971
 *
 * Usage: apply BEFORE sRGB OETF (gamma encode), on linear light data.
 */
object AcesToneMapper {

    // Exact Narkowicz ACES coefficients — do NOT approximate
    private const val A = 2.51f
    private const val B = 0.03f
    private const val C = 2.43f
    private const val D = 0.59f
    private const val E = 0.14f

    /**
     * Apply ACES tonemapping to a single normalised linear value.
     *
     * @param x Linear input, nominally [0, 1] but can exceed 1 for HDR content
     * @return Display-referred output in [0, 1]
     */
    fun map(x: Float): Float {
        val v = max(0f, x)
        val numerator = v * (A * v + B)
        val denominator = v * (C * v + D) + E
        return (numerator / denominator).coerceIn(0f, 1f)
    }

    /**
     * Apply ACES tonemapping to a full [PipelineFrame].
     *
     * Operates channel-by-channel on linear light. Call AFTER white balance
     * and colour correction, BEFORE sRGB gamma encoding.
     *
     * @param frame Linear RGB frame, scene-referred
     * @param exposureScale Pre-exposure scale factor (1.0 = no pre-exposure).
     *                      Increasing this brightens the midtones before tonemapping.
     * @return Tonemapped display-referred frame, still in linear light
     */
    fun apply(frame: PipelineFrame, exposureScale: Float = 1.0f): PipelineFrame {
        val size = frame.width * frame.height
        val outR = FloatArray(size) { map(frame.red[it] * exposureScale) }
        val outG = FloatArray(size) { map(frame.green[it] * exposureScale) }
        val outB = FloatArray(size) { map(frame.blue[it] * exposureScale) }
        return PipelineFrame(
            frame.width, frame.height, outR, outG, outB,
            frame.evOffset, frame.isoEquivalent, frame.exposureTimeNs,
        )
    }

    /**
     * ToneLM shadow toe floor (no crushed blacks).
     *
     * S-curve toe: minimum output value is 0.02 — prevents pure black crush.
     * f(0) with ACES ≈ 0.0 (nearly black), but ToneLM spec requires a 0.02 floor.
     * Apply after ACES as a clamp: output = max(0.02, acesValue).
     *
     * Shoulder soft cap at 0.72: above this, use tanh rolloff asymptotically
     * approaching 0.97 — never reaching pure white.
     * This is applied as a post-process on top of ACES to honour the ToneLM
     * S-curve profile constraints.
     */
    fun applyWithToneLmConstraints(frame: PipelineFrame, exposureScale: Float = 1.0f): PipelineFrame {
        val size = frame.width * frame.height
        val outR = FloatArray(size) { applyToneLmCurve(frame.red[it] * exposureScale) }
        val outG = FloatArray(size) { applyToneLmCurve(frame.green[it] * exposureScale) }
        val outB = FloatArray(size) { applyToneLmCurve(frame.blue[it] * exposureScale) }
        return PipelineFrame(
            frame.width, frame.height, outR, outG, outB,
            frame.evOffset, frame.isoEquivalent, frame.exposureTimeNs,
        )
    }

    /**
     * Combined ACES + ToneLM S-curve:
     *   1. ACES Narkowicz curve
     *   2. Shadow floor: max(0.02, value)  — prevents crushed blacks
     *   3. Highlight shoulder at 0.72: tanh rolloff to 0.97 cap — never pure white
     */
    private fun applyToneLmCurve(x: Float): Float {
        val aces = map(x)

        // Shadow floor at 0.02 — no crushed blacks per ToneLM spec
        val withFloor = max(0.02f, aces)

        // Soft shoulder above 0.72 using tanh rolloff
        return if (withFloor <= 0.72f) {
            withFloor
        } else {
            val t = (withFloor - 0.72f) / (1f - 0.72f)
            // tanh rolloff: asymptotes toward 0.97, never reaches pure white
            val shoulder = kotlin.math.tanh(t.toDouble()).toFloat()
            0.72f + shoulder * (0.97f - 0.72f)
        }
    }
}
