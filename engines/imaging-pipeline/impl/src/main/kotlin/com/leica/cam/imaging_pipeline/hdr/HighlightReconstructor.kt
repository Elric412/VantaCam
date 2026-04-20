package com.leica.cam.imaging_pipeline.hdr

import com.leica.cam.imaging_pipeline.pipeline.PipelineFrame
import kotlin.math.exp
import kotlin.math.ln

/**
 * Reconstructs clipped highlights from the darker (-EV) exposure frame.
 *
 * Ported from `ProXdrHdrEngine.HighlightReconstructionEngine` per D2.1.
 *
 * Per hdr-engine-deep.md section 4:
 * - Single-channel clip: infer from unclipped channels via cross-channel ratio
 *   from the dark frame.
 * - All-channel clip: scale dark frame by EV ratio.
 * - Extreme clip (all frames clip): soft shoulder rolloff to max-in-frame value.
 *
 * This eliminates the "pink/magenta highlight hallucination" of naive saturation
 * and the "blown white" of hard clipping.
 */
class HighlightReconstructor {

    companion object {
        /** Pixel is considered clipped at this fraction of white level. */
        const val CLIP_THRESHOLD = 0.98f
    }

    /**
     * Reconstruct any clipped pixels in [base] using the underexposed [darkFrame].
     *
     * @param evDelta EV difference: darkFrame.evOffset - base.evOffset (negative).
     */
    fun reconstruct(
        base: PipelineFrame,
        darkFrame: PipelineFrame,
        evDelta: Float,
    ): PipelineFrame {
        require(evDelta < 0f) { "darkFrame must be underexposed (evDelta < 0)" }

        val size = base.width * base.height
        val outR = base.red.copyOf()
        val outG = base.green.copyOf()
        val outB = base.blue.copyOf()

        val evScale = exp2(-evDelta) // 2^|evDelta|: maps dark -> base scale

        for (i in 0 until size) {
            val rClip = base.red[i] >= CLIP_THRESHOLD
            val gClip = base.green[i] >= CLIP_THRESHOLD
            val bClip = base.blue[i] >= CLIP_THRESHOLD

            if (!rClip && !gClip && !bClip) continue

            val dR = darkFrame.red[i] * evScale
            val dG = darkFrame.green[i] * evScale
            val dB = darkFrame.blue[i] * evScale

            when {
                rClip && gClip && bClip -> {
                    outR[i] = dR; outG[i] = dG; outB[i] = dB
                }
                rClip && !gClip && !bClip -> {
                    val ratio = if (darkFrame.green[i] > 1e-6f) darkFrame.red[i] / darkFrame.green[i] else 1f
                    outR[i] = base.green[i] * ratio
                }
                bClip && !rClip && !gClip -> {
                    val ratio = if (darkFrame.green[i] > 1e-6f) darkFrame.blue[i] / darkFrame.green[i] else 1f
                    outB[i] = base.green[i] * ratio
                }
                gClip && !rClip && !bClip -> {
                    val avgRB = (base.red[i] + base.blue[i]) / 2f
                    outG[i] = if (avgRB > 1e-6f && darkFrame.red[i] > 1e-6f) {
                        avgRB * (darkFrame.green[i] / darkFrame.red[i])
                    } else base.green[i]
                }
                else -> { outR[i] = dR; outG[i] = dG; outB[i] = dB }
            }
        }

        return PipelineFrame(base.width, base.height, outR, outG, outB, base.evOffset, base.isoEquivalent, base.exposureTimeNs)
    }

    private fun exp2(x: Float): Float = exp(x * ln(2f))
}
