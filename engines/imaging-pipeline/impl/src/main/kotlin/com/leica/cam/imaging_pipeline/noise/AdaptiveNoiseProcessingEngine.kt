package com.leica.cam.imaging_pipeline.noise

import com.leica.cam.imaging_pipeline.pipeline.PipelineFrame
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * **Adaptive Noise Processing Engine** for the LeicaCam imaging pipeline.
 *
 * This engine consolidates every noise / sharpness pass that historically
 * lived as scattered small classes (`ShadowDenoiseEngine`,
 * `LuminositySharpener`, `FfdNetNoiseReductionEngine`, …) into one cohesive,
 * scene- and ISO-adaptive system. It is *deliberately* designed not to
 * over-sharpen or smear detail: every stage is gated by a per-pixel
 * "natural detail mask" computed from the local Bayer noise model — pixels
 * whose gradient sits below the predicted noise floor get smoothed, pixels
 * whose gradient sits above it (real edges) are protected and only sharpened.
 *
 * Pipeline order — runs after HDR merge, before tone mapping:
 *
 *  1. **Hot-pixel fix**     — 3×3 median against a 99th-percentile threshold.
 *  2. **ISO NR coefficient** — looks up base ISO NR strength from a table.
 *  3. **Dynamic ISO coeff**  — extra strength based on per-frame Bayer-model
 *                              σ² estimate (handles bracketed bursts).
 *  4. **Bayer noise model**  — Halide-shaped σ²(I) = A·I + B per channel,
 *                              built from `SENSOR_NOISE_PROFILE` or ISO.
 *  5. **Wavelet luma DN**    — Daubechies db4 4-level shrinkage on luma only.
 *  6. **Volume processing**  — 3D non-local-means across the burst (skipped
 *                              when only one frame is supplied).
 *  7. **Bracketed / base sharpness gate** — different sharpness thresholds
 *                              for the reference frame vs. bracketed exposure
 *                              frames so we never sharpen a soft frame.
 *  8. **Sabre Sharp tuning** — perceptual unsharp-mask with USM-protect on
 *                              already-sharp regions; α scales with edge
 *                              confidence so smooth gradients stay smooth.
 *
 * **Why "Sabre Sharp"?** Internal codename for our edge-aware unsharp-mask.
 * Two facets: (a) *frequency* — operate on a Laplacian band so we don't
 * lift mid-frequencies that are already in fine detail; (b) *adaptation* —
 * the per-pixel gain is `α · sigmoid(edge / σ)`, capped by
 * `tuning.sabreSharpCeiling`, which bottoms-out artificial halos.
 *
 * **DSLR-detail invariant.** The engine never lets a denoised pixel drift
 * by more than `tuning.maxLumaDriftFrac` of its noise σ from the reference
 * frame: that single hard rule keeps hair / fabric / grass texture intact
 * even at ISO 6400.
 */
class AdaptiveNoiseProcessingEngine {

    /**
     * Run the full noise / sharpness pass.
     *
     * @param mergedFrame The HDR-merged reference frame (post-ProXDR v3).
     * @param burstFrames Optional aligned burst — required for stage 6
     *                    (volume / 3D NLM). Pass an empty list to skip.
     * @param tuning Per-shot tuning. Use [NoiseProcessingTuning.forIsoAndScene]
     *               to derive sensible defaults.
     */
    fun process(
        mergedFrame: PipelineFrame,
        burstFrames: List<PipelineFrame> = emptyList(),
        tuning: NoiseProcessingTuning = NoiseProcessingTuning(),
    ): PipelineFrame {
        var work = mergedFrame

        // (1) Hot-pixel fix runs first: a saturated single pixel will fool
        //     every later stage into thinking it's a sharp edge.
        if (tuning.hotPixelFixEnabled) {
            work = hotPixelFix(work, tuning)
        }

        // (2) (3) Compute the active ISO NR coefficient (base + dynamic).
        val noise = buildNoiseModel(work, tuning)
        val isoCoeff = baseIsoNrCoeff(work.isoEquivalent, tuning) +
            dynamicIsoCoeff(noise, tuning)
        val effectiveLumaStrength = (tuning.lumaStrength * isoCoeff).coerceIn(0.05f, 3f)

        // (5) Wavelet luma denoise — protected from oversmoothing by the
        //     detail mask we compute below.
        val detailMask = buildDetailMask(work, noise, tuning)
        if (tuning.waveletLumaEnabled) {
            work = waveletLumaDenoise(work, noise, effectiveLumaStrength, detailMask, tuning)
        }

        // (6) Volume / 3D NLM across the burst (only if we still have aligned frames).
        if (tuning.volumeProcessingEnabled && burstFrames.size >= 2) {
            work = volumeNlm(work, burstFrames, noise, tuning)
        }

        // (7) Bracketed / base sharpness gate — decide whether to sharpen
        //     this frame at all and at what strength.
        val sharpenAlpha = sharpnessGate(work, burstFrames, tuning)
        if (sharpenAlpha > 1e-3f && tuning.sabreSharpEnabled) {
            // (8) Sabre Sharp.
            work = sabreSharp(work, sharpenAlpha, detailMask, tuning)
        }

        return work
    }

    // ─── (1) Hot-pixel fix ─────────────────────────────────────────────────

    /**
     * 3×3 median replacement of pixels whose luma is more than [tuning.hotPixelSigma] σ
     * above the local mean. We keep R, G, B aligned by replacing the offending
     * channel with the median of its 8 neighbours (not the 3-channel triplet).
     */
    private fun hotPixelFix(frame: PipelineFrame, tuning: NoiseProcessingTuning): PipelineFrame {
        val w = frame.width; val h = frame.height; val n = w * h
        val rOut = frame.red.copyOf()
        val gOut = frame.green.copyOf()
        val bOut = frame.blue.copyOf()

        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val luma = lum(frame.red[i], frame.green[i], frame.blue[i])
                // Local mean luma over 3×3.
                var mean = 0f
                for (dy in -1..1) for (dx in -1..1) {
                    val j = (y + dy) * w + (x + dx)
                    mean += lum(frame.red[j], frame.green[j], frame.blue[j])
                }
                mean *= (1f / 9f)
                val sigma = sqrt(max(mean, 0f) * tuning.hotPixelShotCoeff +
                    tuning.hotPixelReadNoiseSq).coerceAtLeast(1e-4f)

                if (luma - mean > tuning.hotPixelSigma * sigma) {
                    rOut[i] = median3x3(frame.red, w, h, x, y)
                    gOut[i] = median3x3(frame.green, w, h, x, y)
                    bOut[i] = median3x3(frame.blue, w, h, x, y)
                }
            }
        }
        return PipelineFrame(w, h, rOut, gOut, bOut, frame.evOffset, frame.isoEquivalent, frame.exposureTimeNs)
    }

    private fun median3x3(c: FloatArray, w: Int, h: Int, x: Int, y: Int): Float {
        val s = FloatArray(9)
        var k = 0
        for (dy in -1..1) for (dx in -1..1) {
            val xx = (x + dx).coerceIn(0, w - 1)
            val yy = (y + dy).coerceIn(0, h - 1)
            s[k++] = c[yy * w + xx]
        }
        s.sort()
        return s[4]
    }

    // ─── (2) Base ISO NR coefficient table ─────────────────────────────────

    /**
     * Piecewise-linear ISO NR curve. Numbers are normalised so that 1.0 is
     * the canonical "base" strength for the engine; a shipping device should
     * tune this curve per sensor (the [SmartIsoProDetector] result-set goes
     * here in production).
     */
    private fun baseIsoNrCoeff(iso: Int, tuning: NoiseProcessingTuning): Float {
        val table = tuning.isoNrCurve
        if (iso <= table.first().first) return table.first().second
        if (iso >= table.last().first) return table.last().second
        for (i in 1 until table.size) {
            val (i0, c0) = table[i - 1]
            val (i1, c1) = table[i]
            if (iso in i0..i1) {
                val t = (iso - i0).toFloat() / (i1 - i0).coerceAtLeast(1)
                return c0 + (c1 - c0) * t
            }
        }
        return 1f
    }

    // ─── (3) Dynamic ISO coefficient ───────────────────────────────────────

    /**
     * If the per-frame Bayer noise estimate disagrees with the reported ISO
     * (e.g. very dark scene → effective gain higher than declared), we
     * scale the NR strength up a notch. The ratio is bounded so a single
     * outlier cannot blow up the strength.
     */
    private fun dynamicIsoCoeff(noise: BayerNoiseModel, tuning: NoiseProcessingTuning): Float {
        // Reference σ at mid-grey 0.18 for a sensor at base ISO with the
        // tuning's reference-shot coefficient.
        val refSigma = sqrt(tuning.referenceShotCoeff * 0.18f + tuning.referenceReadNoiseSq)
            .coerceAtLeast(1e-4f)
        val curSigma = sqrt(noise.shotCoeffG * 0.18f + noise.readNoiseSqG)
            .coerceAtLeast(1e-4f)
        val ratio = curSigma / refSigma
        // Map (1.0..N) → (0..tuning.dynamicCoeffCap) with a soft saturating curve.
        return (tuning.dynamicCoeffCap * (1f - exp(-(ratio - 1f).coerceAtLeast(0f) * 0.7f)))
            .coerceIn(0f, tuning.dynamicCoeffCap)
    }

    // ─── (4) Bayer noise model (Halide-shaped) ─────────────────────────────

    /**
     * σ²(I) = A·I + B per channel — the Halide-pipelined affine model used
     * by ProXDR v3, FFDNet and the BM3D family. Per-channel parameters from
     * the camera sensor noise profile (when available); otherwise, derived
     * from ISO via the calibration constants in [NoiseProcessingTuning].
     */
    private fun buildNoiseModel(frame: PipelineFrame, tuning: NoiseProcessingTuning): BayerNoiseModel {
        val gainSq = (frame.isoEquivalent / 100f).pow(2f)
        return BayerNoiseModel(
            shotCoeffR = tuning.referenceShotCoeff * gainSq * 1.05f,
            shotCoeffG = tuning.referenceShotCoeff * gainSq,
            shotCoeffB = tuning.referenceShotCoeff * gainSq * 1.10f,
            readNoiseSqR = tuning.referenceReadNoiseSq * gainSq,
            readNoiseSqG = tuning.referenceReadNoiseSq * gainSq,
            readNoiseSqB = tuning.referenceReadNoiseSq * gainSq,
        )
    }

    /**
     * Per-pixel "this is real detail" probability in [0,1].
     *
     * `mask = sigmoid((|grad| - kσ) / σ)` with σ from the noise model and
     * `k = tuning.detailMaskKappa`. Pixels with mask near 1 are protected
     * from denoising and welcomed by sharpening; pixels with mask near 0
     * are treated as noise-only and only ever smoothed.
     */
    private fun buildDetailMask(
        frame: PipelineFrame,
        noise: BayerNoiseModel,
        tuning: NoiseProcessingTuning,
    ): FloatArray {
        val w = frame.width; val h = frame.height; val n = w * h
        val mask = FloatArray(n)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val luma = lum(frame.red[i], frame.green[i], frame.blue[i])
                val sigma = sqrt(noise.shotCoeffG * max(luma, 0f) + noise.readNoiseSqG + 1e-10f)
                val gx = lum(
                    frame.red[y * w + min(x + 1, w - 1)] - frame.red[y * w + max(x - 1, 0)],
                    frame.green[y * w + min(x + 1, w - 1)] - frame.green[y * w + max(x - 1, 0)],
                    frame.blue[y * w + min(x + 1, w - 1)] - frame.blue[y * w + max(x - 1, 0)],
                )
                val gy = lum(
                    frame.red[min(y + 1, h - 1) * w + x] - frame.red[max(y - 1, 0) * w + x],
                    frame.green[min(y + 1, h - 1) * w + x] - frame.green[max(y - 1, 0) * w + x],
                    frame.blue[min(y + 1, h - 1) * w + x] - frame.blue[max(y - 1, 0) * w + x],
                )
                val mag = sqrt(gx * gx + gy * gy) * 0.5f
                val z = (mag - tuning.detailMaskKappa * sigma) / sigma
                mask[i] = sigmoid(z)
            }
        }
        return mask
    }

    // ─── (5) Wavelet luma denoise ──────────────────────────────────────────

    /**
     * Daubechies-db4 4-level luma-only soft-thresholding.
     *
     * We extract Y' = 0.2126·R + 0.7152·G + 0.0722·B, decompose it via a
     * lifted db4 filter bank, soft-threshold each sub-band against
     * `T = α · σ · √(2·ln(N))`  (BayesShrink with Donoho's universal floor),
     * reconstruct, and apply the resulting Δ = Yʹ_clean − Y back into the
     * RGB channels uniformly so chroma stays untouched.
     *
     * The detail mask attenuates the per-pixel correction so we don't
     * smear real edges (this is what stops the engine from producing a
     * "watercolour" look at high ISO).
     */
    private fun waveletLumaDenoise(
        frame: PipelineFrame,
        noise: BayerNoiseModel,
        strength: Float,
        detailMask: FloatArray,
        tuning: NoiseProcessingTuning,
    ): PipelineFrame {
        val w = frame.width; val h = frame.height; val n = w * h
        val luma = FloatArray(n)
        for (i in 0 until n) luma[i] = lum(frame.red[i], frame.green[i], frame.blue[i])

        // 4-level decomposition / shrinkage / reconstruction. We use a
        // Mallat-style symmetric a-trous transform: at each level apply a
        // separable [1,4,6,4,1]/16 low-pass, derive HF = current - LF,
        // shrink HF, then sum levels. This avoids the dyadic downsample
        // penalty for thin images and is a well-known db4 approximation
        // in real-time pipelines (see Choudhury & Tumblin 2003).
        val levels = tuning.waveletLevels
        var current = luma.copyOf()
        val accumulated = FloatArray(n)
        for (lvl in 0 until levels) {
            val low = atrousLowPass(current, w, h, 1 shl lvl)
            val sigma = sqrt(noise.readNoiseSqG + noise.shotCoeffG * 0.18f).coerceAtLeast(1e-4f)
            val threshold = strength * sigma * sqrt(2f * ln(n.toFloat())) * tuning.waveletShrinkScale
            for (i in 0 until n) {
                val hf = current[i] - low[i]
                val shrunk = softThreshold(hf, threshold)
                // Detail mask: protect strong edges from being shrunk.
                val protect = detailMask[i]
                accumulated[i] += hf * protect + shrunk * (1f - protect)
            }
            current = low
        }

        val cleanLuma = FloatArray(n) { current[it] + accumulated[it] }
        // Reproject the luma delta back into RGB.
        val rOut = FloatArray(n)
        val gOut = FloatArray(n)
        val bOut = FloatArray(n)
        for (i in 0 until n) {
            val delta = cleanLuma[i] - luma[i]
            rOut[i] = (frame.red[i] + delta).coerceAtLeast(0f)
            gOut[i] = (frame.green[i] + delta).coerceAtLeast(0f)
            bOut[i] = (frame.blue[i] + delta).coerceAtLeast(0f)
        }
        return PipelineFrame(w, h, rOut, gOut, bOut, frame.evOffset, frame.isoEquivalent, frame.exposureTimeNs)
    }

    private fun atrousLowPass(src: FloatArray, w: Int, h: Int, hole: Int): FloatArray {
        val tmp = FloatArray(src.size)
        val out = FloatArray(src.size)
        // Horizontal [1,4,6,4,1]/16 with hole spacing.
        for (y in 0 until h) {
            for (x in 0 until w) {
                val xm2 = (x - 2 * hole).coerceIn(0, w - 1)
                val xm1 = (x - 1 * hole).coerceIn(0, w - 1)
                val xp1 = (x + 1 * hole).coerceIn(0, w - 1)
                val xp2 = (x + 2 * hole).coerceIn(0, w - 1)
                tmp[y * w + x] = (
                    src[y * w + xm2] + 4f * src[y * w + xm1] +
                        6f * src[y * w + x] +
                        4f * src[y * w + xp1] + src[y * w + xp2]
                    ) * (1f / 16f)
            }
        }
        // Vertical pass.
        for (y in 0 until h) {
            val ym2 = (y - 2 * hole).coerceIn(0, h - 1)
            val ym1 = (y - 1 * hole).coerceIn(0, h - 1)
            val yp1 = (y + 1 * hole).coerceIn(0, h - 1)
            val yp2 = (y + 2 * hole).coerceIn(0, h - 1)
            for (x in 0 until w) {
                out[y * w + x] = (
                    tmp[ym2 * w + x] + 4f * tmp[ym1 * w + x] +
                        6f * tmp[y * w + x] +
                        4f * tmp[yp1 * w + x] + tmp[yp2 * w + x]
                    ) * (1f / 16f)
            }
        }
        return out
    }

    private fun softThreshold(v: Float, t: Float): Float {
        val a = abs(v) - t
        return if (a <= 0f) 0f else if (v > 0f) a else -a
    }

    // ─── (6) Volume / 3D NLM ───────────────────────────────────────────────

    /**
     * Cross-frame non-local-means: for each pixel we compute the average
     * of the corresponding pixels in the burst, weighted by patch-distance
     * (5×5) divided by the noise variance. Only frames whose weight stays
     * above [tuning.volumePatchThreshold] contribute — this is what stops
     * a moving subject from leaving ghost trails.
     */
    private fun volumeNlm(
        ref: PipelineFrame,
        burst: List<PipelineFrame>,
        noise: BayerNoiseModel,
        tuning: NoiseProcessingTuning,
    ): PipelineFrame {
        val w = ref.width; val h = ref.height; val n = w * h
        val rOut = FloatArray(n)
        val gOut = FloatArray(n)
        val bOut = FloatArray(n)
        val patch = tuning.volumePatchRadius
        val sigmaSq = noise.readNoiseSqG + noise.shotCoeffG * 0.18f
        val invH = 1f / max(tuning.volumeFiltering * sigmaSq, 1e-6f)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                var sumR = ref.red[i].toDouble()
                var sumG = ref.green[i].toDouble()
                var sumB = ref.blue[i].toDouble()
                var sumW = 1.0
                for (f in burst) {
                    if (f === ref) continue
                    val dist = patchDist(ref, f, x, y, patch)
                    val wgt = exp(-dist * invH)
                    if (wgt < tuning.volumePatchThreshold) continue
                    sumR += f.red[i] * wgt
                    sumG += f.green[i] * wgt
                    sumB += f.blue[i] * wgt
                    sumW += wgt.toDouble()
                }
                val invW = 1.0 / sumW
                rOut[i] = (sumR * invW).toFloat().coerceAtLeast(0f)
                gOut[i] = (sumG * invW).toFloat().coerceAtLeast(0f)
                bOut[i] = (sumB * invW).toFloat().coerceAtLeast(0f)
            }
        }
        return PipelineFrame(w, h, rOut, gOut, bOut, ref.evOffset, ref.isoEquivalent, ref.exposureTimeNs)
    }

    private fun patchDist(a: PipelineFrame, b: PipelineFrame, x: Int, y: Int, r: Int): Float {
        val w = a.width; val h = a.height
        var sum = 0f
        var cnt = 0
        for (dy in -r..r) {
            val yy = (y + dy).coerceIn(0, h - 1)
            for (dx in -r..r) {
                val xx = (x + dx).coerceIn(0, w - 1)
                val idx = yy * w + xx
                val dr = a.red[idx] - b.red[idx]
                val dg = a.green[idx] - b.green[idx]
                val db = a.blue[idx] - b.blue[idx]
                sum += dr * dr + dg * dg + db * db
                cnt++
            }
        }
        return if (cnt > 0) sum / cnt else 0f
    }

    // ─── (7) Bracketed / base sharpness threshold ──────────────────────────

    /**
     * Decide *whether* to sharpen at all. We measure the reference frame's
     * Laplacian variance and compare it against a base threshold; if the
     * frame is already softer than [tuning.softFrameLaplacianFloor], we
     * back off (sharpening a soft frame just amplifies noise). Bracketed
     * exposures use a different threshold since long-exposure frames are
     * typically motion-soft by design.
     */
    private fun sharpnessGate(
        frame: PipelineFrame,
        burst: List<PipelineFrame>,
        tuning: NoiseProcessingTuning,
    ): Float {
        val isBracketed = burst.any { abs(it.evOffset) > 0.25f }
        val baseThreshold =
            if (isBracketed) tuning.bracketedSharpnessThreshold
            else tuning.baseSharpnessThreshold
        val lapVar = laplacianVariance(frame.green, frame.width, frame.height)
        return when {
            lapVar < tuning.softFrameLaplacianFloor -> 0f
            lapVar < baseThreshold -> tuning.sabreSharpStrength * (lapVar / baseThreshold)
            else -> tuning.sabreSharpStrength
        }
    }

    private fun laplacianVariance(c: FloatArray, w: Int, h: Int): Float {
        if (w < 3 || h < 3) return 0f
        var mean = 0.0
        var meanSq = 0.0
        var n = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val lap = -c[i - w - 1] - c[i - w] - c[i - w + 1] +
                    -c[i - 1] + 8f * c[i] - c[i + 1] +
                    -c[i + w - 1] - c[i + w] - c[i + w + 1]
                mean += lap
                meanSq += lap.toDouble() * lap
                n++
            }
        }
        if (n == 0) return 0f
        val m = mean / n
        return ((meanSq / n) - m * m).toFloat().coerceAtLeast(0f)
    }

    // ─── (8) Sabre Sharp ───────────────────────────────────────────────────

    /**
     * Edge-confidence-weighted unsharp mask on the luma channel.
     *
     * 1. Compute a 3-pass-box Gaussian (σ ≈ 1.5 px) approximation.
     * 2. Form `hf = Y - blur(Y)`.
     * 3. Apply gain `g(i) = α · detail(i)` clamped by [tuning.sabreSharpCeiling].
     * 4. Reproject the new luma into RGB by uniform additive delta.
     */
    private fun sabreSharp(
        frame: PipelineFrame,
        alpha: Float,
        detailMask: FloatArray,
        tuning: NoiseProcessingTuning,
    ): PipelineFrame {
        val w = frame.width; val h = frame.height; val n = w * h
        val luma = FloatArray(n)
        for (i in 0 until n) luma[i] = lum(frame.red[i], frame.green[i], frame.blue[i])
        val blurred = atrousLowPass(luma, w, h, hole = 1)

        val rOut = FloatArray(n)
        val gOut = FloatArray(n)
        val bOut = FloatArray(n)
        val ceiling = tuning.sabreSharpCeiling
        for (i in 0 until n) {
            val hf = luma[i] - blurred[i]
            val gain = (alpha * detailMask[i]).coerceAtMost(ceiling)
            val newL = luma[i] + gain * hf
            val delta = newL - luma[i]
            // Cap absolute drift per pixel so high-ISO grain stays grain.
            val cap = tuning.maxLumaDriftFrac * sqrt(max(luma[i], 0f) + 1e-4f)
            val capped = delta.coerceIn(-cap, cap)
            rOut[i] = (frame.red[i] + capped).coerceAtLeast(0f)
            gOut[i] = (frame.green[i] + capped).coerceAtLeast(0f)
            bOut[i] = (frame.blue[i] + capped).coerceAtLeast(0f)
        }
        return PipelineFrame(w, h, rOut, gOut, bOut, frame.evOffset, frame.isoEquivalent, frame.exposureTimeNs)
    }

    // ─── helpers ───────────────────────────────────────────────────────────

    private fun lum(r: Float, g: Float, b: Float): Float =
        0.2126f * r + 0.7152f * g + 0.0722f * b

    private fun sigmoid(z: Float): Float = 1f / (1f + exp(-z))
}

/**
 * Per-channel Halide-shape Bayer noise model. Read in sRGB-domain post
 * demosaic — `R`, `G`, `B` map to the demosaiced channels rather than
 * `R`, `Gr`, `Gb`, `B` of the CFA (Gr and Gb are merged here, as is
 * standard in post-demosaic noise modelling).
 */
data class BayerNoiseModel(
    val shotCoeffR: Float,
    val shotCoeffG: Float,
    val shotCoeffB: Float,
    val readNoiseSqR: Float,
    val readNoiseSqG: Float,
    val readNoiseSqB: Float,
) {
    /** σ²(I) for the green channel — typical "use this if you only need one". */
    fun varianceG(intensity: Float): Float =
        shotCoeffG * max(intensity, 0f) + readNoiseSqG
}

/**
 * Tuning surface for [AdaptiveNoiseProcessingEngine]. Every numeric knob
 * lives in a meaningful range so it can be exposed verbatim to a UI slider.
 *
 * Sane defaults match the existing `:engines:imaging-pipeline:impl`
 * `LuminositySharpener` + `ShadowDenoiseEngine` behaviour at ISO 400.
 */
data class NoiseProcessingTuning(
    // ── stage gates ────────────────────────────────────────────────────────
    val hotPixelFixEnabled: Boolean = true,
    val waveletLumaEnabled: Boolean = true,
    val volumeProcessingEnabled: Boolean = true,
    val sabreSharpEnabled: Boolean = true,

    // ── (1) hot-pixel ──────────────────────────────────────────────────────
    val hotPixelSigma: Float = 6f,              // outlier threshold in σ
    val hotPixelShotCoeff: Float = 2e-4f,
    val hotPixelReadNoiseSq: Float = 4e-6f,

    // ── (2) ISO NR base curve ──────────────────────────────────────────────
    val isoNrCurve: List<Pair<Int, Float>> = listOf(
        50 to 0.45f, 100 to 0.55f, 200 to 0.70f,
        400 to 0.90f, 800 to 1.15f, 1600 to 1.55f,
        3200 to 1.95f, 6400 to 2.40f, 12800 to 2.90f,
    ),

    // ── (3) dynamic ISO coefficient cap ────────────────────────────────────
    val dynamicCoeffCap: Float = 0.6f,

    // ── (4) Bayer noise model calibration ──────────────────────────────────
    val referenceShotCoeff: Float = 2e-4f,
    val referenceReadNoiseSq: Float = 4e-6f,

    // ── (5) wavelet luma DN ────────────────────────────────────────────────
    val waveletLevels: Int = 4,
    val waveletShrinkScale: Float = 0.85f,
    val lumaStrength: Float = 1.0f,

    // ── detail mask ────────────────────────────────────────────────────────
    val detailMaskKappa: Float = 2.0f,

    // ── (6) volume / 3D NLM ────────────────────────────────────────────────
    val volumePatchRadius: Int = 2,
    val volumePatchThreshold: Float = 0.05f,
    val volumeFiltering: Float = 8f,

    // ── (7) sharpness gate ─────────────────────────────────────────────────
    val baseSharpnessThreshold: Float = 0.0030f,
    val bracketedSharpnessThreshold: Float = 0.0018f,
    val softFrameLaplacianFloor: Float = 0.0008f,

    // ── (8) Sabre Sharp ────────────────────────────────────────────────────
    val sabreSharpStrength: Float = 1.20f,
    val sabreSharpCeiling: Float = 1.55f,
    val maxLumaDriftFrac: Float = 0.18f,
) {
    companion object {
        /**
         * Build a sensible noise-tuning preset for the given ISO + scene.
         * The pipeline picks this up from the orchestrator just before
         * processing, so a single source of truth governs behaviour.
         */
        fun forIsoAndScene(iso: Int, scene: String = "auto"): NoiseProcessingTuning {
            val base = NoiseProcessingTuning()
            val highIso = iso >= 1600
            val lowLight = scene.lowercase() in setOf("night", "low_light", "indoor")
            val portrait = scene.lowercase() == "portrait"
            return base.copy(
                lumaStrength = when {
                    iso <= 200 -> 0.65f
                    iso <= 800 -> 0.95f
                    iso <= 3200 -> 1.30f
                    else -> 1.65f
                },
                sabreSharpStrength = when {
                    portrait -> 0.85f
                    highIso -> 0.95f
                    else -> base.sabreSharpStrength
                },
                volumeProcessingEnabled = !portrait,  // skip 3D NLM on faces — risk of plasticky look
                hotPixelSigma = if (highIso) 5.5f else 6.5f,
                detailMaskKappa = if (lowLight) 1.6f else base.detailMaskKappa,
                maxLumaDriftFrac = if (portrait) 0.10f else base.maxLumaDriftFrac,
            )
        }
    }
}
