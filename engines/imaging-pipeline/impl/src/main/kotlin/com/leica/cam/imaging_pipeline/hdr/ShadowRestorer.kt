package com.leica.cam.imaging_pipeline.hdr

import com.leica.cam.imaging_pipeline.pipeline.PipelineFrame
import kotlin.math.exp
import kotlin.math.ln

/**
 * Restores shadow detail by blending from the brighter (+EV) frame.
 * Symmetric dual of [HighlightReconstructor].
 *
 * Ported from `ProXdrHdrEngine.ShadowDetailRestorer` per D2.1.
 *
 * D2.5 fix: The old code (line 254) applied `evScale` to the bright frame's
 * RAW pixel values BEFORE the clip check, then used the already-scaled value
 * in the blend. This caused double-scaling. Fixed: compute and check clip on
 * the UN-scaled bright frame; apply evScale only inside the lerp.
 *
 * Applied only where base frame is noise-floor limited (luma <= shadowThresh)
 * and the brighter frame is unclipped.
 */
class ShadowRestorer {

    fun restore(
        base: PipelineFrame,
        brightFrame: PipelineFrame,
        evDelta: Float,
        shadowThreshold: Float = 0.12f,
    ): PipelineFrame {
        require(evDelta > 0f) { "brightFrame must be overexposed (evDelta > 0)" }

        val evScale = exp2(-evDelta)
        val size = base.width * base.height
        val outR = base.red.copyOf()
        val outG = base.green.copyOf()
        val outB = base.blue.copyOf()
        val luma = base.luminance()

        for (i in 0 until size) {
            if (luma[i] > shadowThreshold) continue

            // D2.5 fix: Check clip on the UN-scaled bright frame (not scaled)
            if (brightFrame.red[i] >= HighlightReconstructor.CLIP_THRESHOLD) continue
            if (brightFrame.green[i] >= HighlightReconstructor.CLIP_THRESHOLD) continue

            // Apply evScale only in the lerp, not before the clip check
            val bR = brightFrame.red[i] * evScale
            val bG = brightFrame.green[i] * evScale
            val bB = brightFrame.blue[i] * evScale

            // Soft blend: 1.0 at zero luma, 0.0 at threshold
            val blend = ((shadowThreshold - luma[i]) / shadowThreshold).coerceIn(0f, 1f)

            outR[i] = lerp(base.red[i], bR, blend)
            outG[i] = lerp(base.green[i], bG, blend)
            outB[i] = lerp(base.blue[i], bB, blend)
        }

        return PipelineFrame(base.width, base.height, outR, outG, outB, base.evOffset, base.isoEquivalent, base.exposureTimeNs)
    }

    private fun exp2(x: Float): Float = exp(x * ln(2f))
    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
