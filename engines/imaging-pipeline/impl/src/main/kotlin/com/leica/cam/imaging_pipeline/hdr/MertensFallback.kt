package com.leica.cam.imaging_pipeline.hdr

import com.leica.cam.common.result.LeicaResult
import com.leica.cam.common.result.PipelineStage
import com.leica.cam.imaging_pipeline.pipeline.PipelineFrame
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Mertens (2009) exposure fusion with **real Burt & Adelson (1983) Laplacian
 * pyramid blending** -- replaces the naive weighted mean from the old code.
 *
 * The old `ProXdrHdrEngine.MertensExposureFusionEngine` had an inline comment:
 * `// Simple weighted blend (production: replace with Laplacian pyramid)`
 * This was a known P0 issue (D4: halos at high-contrast edges).
 *
 * D2.2 fix: 4-level Laplacian pyramid blend per Burt & Adelson.
 * - Build Gaussian pyramid of each normalised weight map.
 * - Build Laplacian pyramid of each source frame.
 * - Blend at each level: sum(laplacian_frame[k] * gaussian_weight[k]).
 * - Collapse the blended pyramid back to full resolution.
 *
 * **Output is display-referred LDR.** Cannot be fed into ToneLM bilateral
 * decomposition (Mertens already tone-mapped; bilateral would over-compress).
 * In ImagingPipeline.process, the MERTENS_FUSION branch SKIPS bilateral TM.
 *
 * **P2-14 efficiency fix:** The previous [pyramidBlend] implementation called
 * [gaussianLevel] independently for every channel (R, G, B) and every level,
 * rebuilding the full Gaussian pyramid 3 × levels times per frame.  Since all
 * three channels share the *same* weight map and the *same* Laplacian pyramid
 * structure, we now pre-build every frame's full Gaussian and Laplacian pyramid
 * once and reuse them across channels.  This cuts redundant blur passes from
 * O(3 × levels × frames) to O(levels × frames).
 */
class MertensFallback {

    private val omegaContrast = 1f
    private val omegaSaturation = 1f
    private val omegaExposedness = 1f
    private val exposednessSigma = 0.2f

    fun fuse(frames: List<PipelineFrame>): LeicaResult<PipelineFrame> {
        if (frames.isEmpty()) return LeicaResult.Failure.Pipeline(
            PipelineStage.IMAGING_PIPELINE, "Mertens fusion requires at least one frame.",
        )
        if (frames.size == 1) return LeicaResult.Success(frames[0])

        val width = frames[0].width
        val height = frames[0].height
        val size = width * height
        val levels = pyramidLevels(width, height)

        // Step 1: per-frame quality weight maps
        val weightMaps = frames.map { computeWeightMap(it) }

        // Step 2: normalise weights per pixel
        val normalised = normaliseWeightsPerPixel(weightMaps, frames.size, size)

        // Step 3: Pre-build per-frame Gaussian pyramids for each channel ONCE
        // (P2-14 fix: old code rebuilt them three times — once per R/G/B channel).
        // Also pre-build Gaussian pyramids for normalised weight maps.
        val redPyrs   = frames.map { buildGaussianPyramid(it.red,   width, height, levels) }
        val greenPyrs = frames.map { buildGaussianPyramid(it.green, width, height, levels) }
        val bluePyrs  = frames.map { buildGaussianPyramid(it.blue,  width, height, levels) }
        val weightGaussPyrs = (0 until frames.size).map { f ->
            buildGaussianPyramid(normalised[f], width, height, levels)
        }

        // Step 4: Laplacian pyramid blend per channel using pre-built pyramids
        val outR = pyramidBlendFromPyramids(redPyrs,   weightGaussPyrs, width, height, levels)
        val outG = pyramidBlendFromPyramids(greenPyrs, weightGaussPyrs, width, height, levels)
        val outB = pyramidBlendFromPyramids(bluePyrs,  weightGaussPyrs, width, height, levels)
        val base = frames.minByOrNull { abs(it.evOffset) } ?: frames[0]

        return LeicaResult.Success(
            PipelineFrame(
                width = width,
                height = height,
                red = outR,
                green = outG,
                blue = outB,
                evOffset = 0f,
                isoEquivalent = base.isoEquivalent,
                exposureTimeNs = base.exposureTimeNs,
            ),
        )
    }

    /**
     * Build a full Gaussian pyramid for [src] with [levels] levels.
     *
     * Level 0 = original resolution, level k = 1/(2^k) resolution.
     * Returns a list of FloatArrays, one per level.
     */
    private fun buildGaussianPyramid(
        src: FloatArray, w: Int, h: Int, levels: Int,
    ): List<FloatArray> {
        val pyr = ArrayList<FloatArray>(levels + 1)
        pyr.add(src)
        var cur = src; var cw = w; var ch = h
        repeat(levels) {
            val blurred = gaussianBlur5(cur, cw, ch)
            val dw = (cw + 1) / 2; val dh = (ch + 1) / 2
            cur = FloatArray(dw * dh) { i ->
                val x = (i % dw) * 2; val y = (i / dw) * 2
                blurred[y.coerceIn(0, ch - 1) * cw + x.coerceIn(0, cw - 1)]
            }
            pyr.add(cur)
            cw = dw; ch = dh
        }
        return pyr
    }

    /**
     * P2-14 fix: Blend [nFrames] channels using pre-built Gaussian pyramids.
     *
     * [channelPyrs] — one Gaussian pyramid per frame for one colour channel.
     * [weightPyrs]  — one Gaussian pyramid per frame for the normalised weight.
     *
     * Laplacian level k = gaussPyr[k] − upsample(gaussPyr[k+1]).  This avoids
     * rebuilding the Gaussian pyramid inside [laplacianLevel] for every frame
     * and every channel.
     */
    private fun pyramidBlendFromPyramids(
        channelPyrs: List<List<FloatArray>>,
        weightPyrs: List<List<FloatArray>>,
        w: Int, h: Int, levels: Int,
    ): FloatArray {
        val blendedPyr = Array(levels) { FloatArray(0) }
        val blendedDims = Array(levels) { Pair(0, 0) }

        for (lvl in 0 until levels) {
            val lw = levelDim(w, lvl); val lh = levelDim(h, lvl)
            blendedDims[lvl] = Pair(lw, lh)
            val cur = FloatArray(lw * lh)

            val nw = levelDim(w, lvl + 1); val nh = levelDim(h, lvl + 1)

            for (f in channelPyrs.indices) {
                // Laplacian level = Gaussian(lvl) − upsample(Gaussian(lvl+1))
                val gaussCur = channelPyrs[f][lvl]
                val gaussNxt = channelPyrs[f][lvl + 1]
                val upNxt = upsample2x(gaussNxt, nw, nh, lw, lh)
                val gaussWeight = weightPyrs[f][lvl]
                for (i in 0 until lw * lh) {
                    cur[i] += (gaussCur[i] - upNxt[i]) * gaussWeight[i]
                }
            }
            blendedPyr[lvl] = cur
        }

        return collapsePyramid(blendedPyr, blendedDims)
    }

    // ── Pyramid mathematics ────────────────────────────────────────────

    private fun pyramidLevels(w: Int, h: Int): Int =
        (ln(min(w, h).toDouble()) / ln(2.0)).toInt().coerceIn(3, 6)

    /** Gaussian pyramid level [lvl] of [src] (0 = original, higher = coarser). */
    private fun gaussianLevel(src: FloatArray, w: Int, h: Int, lvl: Int): FloatArray {
        var cur = src; var cw = w; var ch = h
        repeat(lvl) {
            val blurred = gaussianBlur5(cur, cw, ch)
            val dw = (cw + 1) / 2; val dh = (ch + 1) / 2
            cur = FloatArray(dw * dh) { i ->
                val x = (i % dw) * 2; val y = (i / dw) * 2
                blurred[y.coerceIn(0, ch - 1) * cw + x.coerceIn(0, cw - 1)]
            }
            cw = dw; ch = dh
        }
        return cur
    }

    /** Laplacian level = Gaussian(lvl) - upsample(Gaussian(lvl+1)). */
    private fun laplacianLevel(src: FloatArray, w: Int, h: Int, lvl: Int): FloatArray {
        val cur = gaussianLevel(src, w, h, lvl)
        val nxt = gaussianLevel(src, w, h, lvl + 1)
        val cw = levelDim(w, lvl); val ch = levelDim(h, lvl)
        val nw = levelDim(w, lvl + 1); val nh = levelDim(h, lvl + 1)
        val upNxt = upsample2x(nxt, nw, nh, cw, ch)
        return FloatArray(cw * ch) { i -> cur[i] - upNxt[i] }
    }

    private fun levelDim(v: Int, lvl: Int): Int {
        var x = v; repeat(lvl) { x = (x + 1) / 2 }; return x
    }

    /** Bilinear 2x upsample. */
    private fun upsample2x(src: FloatArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int): FloatArray {
        return FloatArray(dstW * dstH) { i ->
            val dx = i % dstW; val dy = i / dstW
            val sx = dx.toFloat() / 2f; val sy = dy.toFloat() / 2f
            sampleBilinear(src, srcW, srcH, sx, sy)
        }
    }

    /** Collapse a blended Laplacian pyramid: upsample coarsest and add finer levels. */
    private fun collapsePyramid(pyr: Array<FloatArray>, dims: Array<Pair<Int, Int>>): FloatArray {
        var cur = pyr[pyr.lastIndex]
        for (lvl in (pyr.lastIndex - 1) downTo 0) {
            val (dw, dh) = dims[lvl]
            val (sw, sh) = dims[lvl + 1]
            val upsampled = upsample2x(cur, sw, sh, dw, dh)
            cur = FloatArray(dw * dh) { i -> upsampled[i] + pyr[lvl][i] }
        }
        return cur
    }

    // ── Weight computation (same as original Mertens) ──────────────────

    private fun computeWeightMap(frame: PipelineFrame): FloatArray {
        val size = frame.width * frame.height
        val contrast = computeContrast(frame)
        val saturation = computeSaturation(frame)
        val exposedness = computeExposedness(frame)
        return FloatArray(size) { i ->
            (contrast[i].pow(omegaContrast) *
                saturation[i].pow(omegaSaturation) *
                exposedness[i].pow(omegaExposedness)).coerceAtLeast(1e-10f)
        }
    }

    private fun normaliseWeightsPerPixel(
        weightMaps: List<FloatArray>, nFrames: Int, size: Int,
    ): Array<FloatArray> {
        val normalised = Array(nFrames) { FloatArray(size) }
        for (i in 0 until size) {
            var total = 0f
            for (k in 0 until nFrames) total += weightMaps[k][i]
            if (total < 1e-10f) total = 1f
            for (k in 0 until nFrames) normalised[k][i] = weightMaps[k][i] / total
        }
        return normalised
    }

    private fun computeContrast(frame: PipelineFrame): FloatArray {
        val w = frame.width; val h = frame.height; val luma = frame.luminance()
        val out = FloatArray(luma.size)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            out[i] = abs(4f * luma[i] - luma[i - 1] - luma[i + 1] - luma[(y - 1) * w + x] - luma[(y + 1) * w + x])
        }
        return out
    }

    private fun computeSaturation(frame: PipelineFrame): FloatArray =
        FloatArray(frame.width * frame.height) { i ->
            val mean = (frame.red[i] + frame.green[i] + frame.blue[i]) / 3f
            val dr = frame.red[i] - mean; val dg = frame.green[i] - mean; val db = frame.blue[i] - mean
            sqrt((dr * dr + dg * dg + db * db) / 3f)
        }

    private fun computeExposedness(frame: PipelineFrame): FloatArray {
        val luma = frame.luminance()
        return FloatArray(luma.size) { i ->
            val d = luma[i] - 0.5f
            exp(-(d * d) / (2f * exposednessSigma * exposednessSigma))
        }
    }

    // ── Utilities ───────────────────────────────────────────────────────

    private fun gaussianBlur5(ch: FloatArray, w: Int, h: Int): FloatArray {
        val k = floatArrayOf(0.0625f, 0.25f, 0.375f, 0.25f, 0.0625f)
        val temp = FloatArray(w * h); val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f; for (ki in -2..2) s += ch[y * w + (x + ki).coerceIn(0, w - 1)] * k[ki + 2]
            temp[y * w + x] = s
        }
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f; for (ki in -2..2) s += temp[(y + ki).coerceIn(0, h - 1) * w + x] * k[ki + 2]
            out[y * w + x] = s
        }
        return out
    }

    private fun sampleBilinear(src: FloatArray, w: Int, h: Int, x: Float, y: Float): Float {
        val cx = x.coerceIn(0f, (w - 1).toFloat()); val cy = y.coerceIn(0f, (h - 1).toFloat())
        val x0 = cx.toInt(); val y0 = cy.toInt()
        val x1 = min(x0 + 1, w - 1); val y1 = min(y0 + 1, h - 1)
        val wx = cx - x0; val wy = cy - y0
        val top = src[y0 * w + x0] * (1f - wx) + src[y0 * w + x1] * wx
        val bot = src[y1 * w + x0] * (1f - wx) + src[y1 * w + x1] * wx
        return top * (1f - wy) + bot * wy
    }
}
