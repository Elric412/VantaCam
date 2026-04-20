package com.leica.cam.imaging_pipeline.hdr

import com.leica.cam.imaging_pipeline.pipeline.PipelineFrame
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Pre-alignment ghost detection. Returns a per-pixel **soft** mask in [0,1]
 * where 1.0 = confident ghost (dynamic subject), 0.0 = static.
 *
 * **Method:** MTB (Ward 2003) + bitmap XOR accumulation + Gaussian-weighted
 * soft dilation. Runs on GREEN channel only (highest SNR on Bayer).
 *
 * **CRITICAL:** Must run BEFORE alignment. The old code (ProXdrHdrEngine.kt:619)
 * ran MTB AFTER alignment, which defeats the purpose: alignment can be biased
 * by moving subjects if the mask is not available to weight the alignment search.
 *
 * **D2 fix:** Ghost mask now feeds into [DeformableFeatureAligner] to downweight
 * flow estimation in ghost regions (avoids solving flow on moving subjects).
 *
 * **Gotcha:** Always produces a **soft** mask via Gaussian dilation. Hard masks
 * (0 or 1) cause visible seams at mask boundaries in the merge stage.
 * [RadianceMerger] rejects hard masks with a `require` check.
 */
class GhostMaskEngine {

    /**
     * Compute soft ghost mask from MTB difference between reference and alternates.
     *
     * @param reference  The reference frame (usually EV=0 or closest to 0).
     * @param alternates All other frames in the bracket set.
     * @param dilateRadius Gaussian dilation radius for soft mask edges.
     * @return Soft ghost mask aligned to the reference's coordinate system.
     *         Size = reference.width * reference.height. Values in [0,1].
     */
    fun computeSoftMask(
        reference: PipelineFrame,
        alternates: List<PipelineFrame>,
        dilateRadius: Int = 8,
    ): FloatArray {
        require(alternates.isNotEmpty()) { "At least one alternate frame required for ghost detection" }

        val size = reference.width * reference.height
        val refMtb = binaryMtb(reference)
        val accum = FloatArray(size)

        for (alt in alternates) {
            val altMtb = binaryMtb(alt)
            for (i in 0 until size) {
                if (refMtb[i] != altMtb[i]) accum[i] += 1f
            }
        }

        // Normalise to [0, 1]
        val denom = alternates.size.coerceAtLeast(1).toFloat()
        for (i in 0 until size) accum[i] /= denom

        // Gaussian-weighted soft dilation to feather edges
        return softDilate(accum, reference.width, reference.height, dilateRadius)
    }

    /**
     * Binary MTB (Median Threshold Bitmap) -- binarise green-channel luminance
     * at its median. Used for alignment-invariant motion detection.
     */
    private fun binaryMtb(frame: PipelineFrame): BooleanArray {
        val luma = frame.luminance()
        val sorted = luma.copyOf()
        sorted.sort()
        val median = sorted[sorted.size / 2]
        return BooleanArray(luma.size) { luma[it] > median }
    }

    /**
     * Gaussian-weighted soft dilation.
     *
     * Separable box-filter approximation of Gaussian, then clamp to [0,1].
     * This produces a soft mask suitable for weighting in [RadianceMerger].
     * Never produces hard (0 or 1) mask values at boundaries.
     */
    private fun softDilate(mask: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        if (radius <= 0) return mask

        // Build Gaussian kernel
        val sigma = radius / 2.5f
        val sigma2x2 = 2f * sigma * sigma

        // Separable: horizontal pass
        val temp = FloatArray(mask.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0f
                var wSum = 0f
                for (dx in -radius..radius) {
                    val sx = (x + dx).coerceIn(0, w - 1)
                    val gw = exp(-(dx * dx).toFloat() / sigma2x2)
                    sum += mask[y * w + sx] * gw
                    wSum += gw
                }
                temp[y * w + x] = sum / max(wSum, 1e-8f)
            }
        }

        // Separable: vertical pass
        val out = FloatArray(mask.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0f
                var wSum = 0f
                for (dy in -radius..radius) {
                    val sy = (y + dy).coerceIn(0, h - 1)
                    val gw = exp(-(dy * dy).toFloat() / sigma2x2)
                    sum += temp[sy * w + x] * gw
                    wSum += gw
                }
                out[y * w + x] = (sum / max(wSum, 1e-8f)).coerceIn(0f, 1f)
            }
        }
        return out
    }
}
