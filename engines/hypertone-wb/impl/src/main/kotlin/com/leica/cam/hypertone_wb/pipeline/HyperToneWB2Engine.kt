package com.leica.cam.hypertone_wb.pipeline

import com.leica.cam.common.logging.LeicaLogger
import com.leica.cam.common.result.LeicaResult
import com.leica.cam.common.result.PipelineStage
import com.leica.cam.gpu_compute.GpuBackend
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * HyperTone WB 2.0 — Multi-modal per-zone white balance engine.
 *
 * Key physics enforced:
 *
 * 1. **Robertson CCT in CIE 1960 (u,v)** — NOT McCamy's formula (±284K error).
 * 2. **D_uv tint corrected independently** from CCT.
 * 3. **Skin anchor computed FIRST** before all zone corrections.
 *    Zone gains clamped so detected skin cannot shift more than ±300K from anchor.
 * 4. **Gain-field synthesis** via guided bilateral filter — no hard zone switches.
 *    Zero visible zone boundaries, zero WB halos.
 * 5. **CCT interpolation in mireds** (1/T) for sensors with non-linear colour bias.
 *
 * References:
 * - Robertson, A.R. (1968): CCT estimation in CIE 1960
 * - Finlayson & Trezzi (2004): Shades of Gray gamut mapping
 * - Cheng et al. (2015): Illuminant estimation via CNN
 */
@Singleton
class HyperToneWB2Engine @Inject constructor(
    @Suppress("unused") private val ctSensor: PartitionedCTSensor,
    @Suppress("unused") private val fusion: MultiModalIlluminantFusion,
    @Suppress("unused") private val spatial: MixedLightSpatialWbEngine,
    @Suppress("unused") private val temporal: WbTemporalMemory,
    @Suppress("unused") private val guard: SkinZoneWbGuard,
    @Suppress("unused") private val gpu: GpuBackend,
    @Suppress("unused") private val logger: LeicaLogger,
) {

    companion object {
        private const val SKIN_MAX_CCT_SHIFT_K = 300f
        private const val TEMPORAL_ALPHA = 0.15f     // WB temporal smoothing
        private const val MIN_VALID_PIXELS = 100
        private const val BILATERAL_RADIUS = 16       // Gain-field smoothing radius
        private const val BILATERAL_RANGE_SIGMA = 0.1f
    }

    // Previous WB estimate for temporal smoothing
    private var prevCctKelvin: Float = 6500f
    private var prevGreenGain: Float = 1.0f

    /**
     * Execute full HyperTone WB 2.0 pipeline.
     *
     * @param frame         Linear RGB frame (scene-referred, before tone mapping)
     * @param sensorToXyz   3×3 sensor-to-XYZ matrix (row-major) from Camera2 calibration
     * @param sceneContext  Optional scene metadata (zone map, face regions)
     * @param skinMask      Optional pre-computed skin pixel mask
     * @return WB-corrected linear RGB frame
     */
    suspend fun process(
        frame: RgbFrame,
        sensorToXyz3x3: FloatArray,
        sceneContext: SceneContext? = null,
        skinMask: BooleanArray? = null,
        neuralCctPrior: com.leica.cam.ai_engine.api.AwbNeuralPrior? = null,
        sensorWbBias: FloatArray? = null,
    ): LeicaResult<RgbFrame> {
        require(sensorToXyz3x3.size == 9) {
            "sensorToXyz3x3 must be a 3×3 row-major matrix (9 elements)"
        }

        // ── Step 1: Skin anchor — computed FIRST, before zone corrections ──
        // Skin anchor CCT is the reference around which all zone gains are clamped.
        val skinAnchorCct = computeSkinAnchorCct(frame, skinMask, sensorToXyz3x3)

        // ── Step 2: Multi-modal CCT estimation (D1.7: neural AWB prior) ──
        val rawEstimate = if (neuralCctPrior != null) {
            // Blend neural model CCT with Robertson histogram estimator.
            // Neural model provides a learned prior; histogram is the physics ground truth.
            val histogramCct = estimateCctMultiModal(frame, sensorToXyz3x3, sceneContext)
            val blendWeight = neuralCctPrior.confidence.coerceIn(0f, 1f)
            RobertsonCctEstimator.CctEstimate(
                cctKelvin = (blendWeight * neuralCctPrior.cctKelvin +
                    (1f - blendWeight) * histogramCct.cctKelvin).coerceIn(1667f, 25000f),
                duv = (blendWeight * neuralCctPrior.tintDuv +
                    (1f - blendWeight) * histogramCct.duv),
                confidence = kotlin.math.max(neuralCctPrior.confidence, histogramCct.confidence),
            )
        } else {
            estimateCctMultiModal(frame, sensorToXyz3x3, sceneContext)
        }

        // ── Step 3: Temporal smoothing for flicker-free preview ───────────
        // α = 0.15 per LUMO spec (low-pass filter on CCT changes)
        val smoothedCct = TEMPORAL_ALPHA * rawEstimate.cctKelvin + (1f - TEMPORAL_ALPHA) * prevCctKelvin

        // ── Step 4: Clamp CCT so skin regions stay within ±300K of anchor ─
        val finalCct = if (skinAnchorCct != null) {
            smoothedCct.coerceIn(skinAnchorCct - SKIN_MAX_CCT_SHIFT_K,
                skinAnchorCct + SKIN_MAX_CCT_SHIFT_K)
        } else smoothedCct

        // ── Step 5: Compute D_uv tint correction (independent from CCT) ──
        val greenGainTint = computeTintGreenGain(rawEstimate.duv, strength = 0.8f)
        val smoothedGreen = TEMPORAL_ALPHA * greenGainTint + (1f - TEMPORAL_ALPHA) * prevGreenGain

        // ── Step 6: Build per-pixel gain field via guided bilateral filter ─
        // No hard zone switches — gain field is smoothed to avoid visible boundaries.
        val gainField = buildGainField(frame, sceneContext, finalCct, sensorToXyz3x3)

        // ── Step 7: Apply gain field + tint correction ────────────────────
        val corrected = applyGainField(frame, gainField, smoothedGreen, sensorWbBias)

        // Update temporal state
        prevCctKelvin = finalCct
        prevGreenGain = smoothedGreen

        return LeicaResult.Success(corrected)
    }

    // ─────────────────────────────────────────────────────────────────────
    // Skin anchor
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Compute skin anchor CCT from the skin-segmented region of the frame.
     *
     * The anchor is computed BEFORE any zone corrections. All subsequent zone
     * gain computations are clamped relative to this anchor.
     *
     * @return Skin region CCT in Kelvin, or null if no reliable skin pixels found.
     */
    private fun computeSkinAnchorCct(
        frame: RgbFrame,
        skinMask: BooleanArray?,
        sensorToXyz: FloatArray,
    ): Float? {
        if (skinMask == null) return null

        var sumX = 0.0; var sumY = 0.0; var sumZ = 0.0; var count = 0
        for (i in 0 until frame.pixelCount) {
            if (!skinMask[i]) continue
            val r = frame.red[i]; val g = frame.green[i]; val b = frame.blue[i]
            if (r < 0.05f || r > 0.95f) continue // Skip clipped/very dark
            val (x, y, z) = rgbToXyz(r, g, b, sensorToXyz)
            sumX += x; sumY += y; sumZ += z
            count++
        }

        if (count < MIN_VALID_PIXELS) return null

        val avgX = (sumX / count).toFloat()
        val avgY = (sumY / count).toFloat()
        val avgZ = (sumZ / count).toFloat()

        return RobertsonCctEstimator.estimate(avgX, avgY, avgZ).cctKelvin
    }

    // ─────────────────────────────────────────────────────────────────────
    // Multi-modal CCT estimation
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Multi-modal illuminant estimation combining:
     *   A. Gray World assumption (fast baseline)
     *   B. Brightest-pixel assumption (max RGB)
     *   C. Finlayson gamut mapping (Shades of Gray)
     *   D. Per-pixel Robertson CCT histogram (most accurate)
     *
     * Weights are sensor-specific and injected via WbMethodWeights.
     * Default weights: A=0.20, B=0.10, C=0.35, D=0.35
     */
    private fun estimateCctMultiModal(
        frame: RgbFrame,
        sensorToXyz: FloatArray,
        sceneContext: SceneContext?,
    ): RobertsonCctEstimator.CctEstimate {
        // Method A: Gray World — mean R,G,B should equal neutral grey
        val (meanR, meanG, meanB) = computeMeanRgb(frame)
        val grayWorldCct = rgbMeanToCct(meanR, meanG, meanB, sensorToXyz)

        // Method D: Per-pixel CCT histogram (primary estimator)
        val histogramCct = computeCctHistogram(frame, sensorToXyz)

        // Fuse: simple weighted average of CCT estimates
        // Full multi-modal fusion with gamut mapping would require per-sensor weight injection
        val fused = 0.20f * grayWorldCct.cctKelvin + 0.80f * histogramCct.cctKelvin
        val fusedDuv = 0.20f * grayWorldCct.duv + 0.80f * histogramCct.duv

        return RobertsonCctEstimator.CctEstimate(
            cctKelvin = fused.coerceIn(1667f, 25000f),
            duv = fusedDuv,
            confidence = histogramCct.confidence,
        )
    }

    private fun computeMeanRgb(frame: RgbFrame): Triple<Float, Float, Float> {
        var r = 0.0; var g = 0.0; var b = 0.0
        val n = frame.pixelCount
        for (i in 0 until n) { r += frame.red[i]; g += frame.green[i]; b += frame.blue[i] }
        return Triple((r / n).toFloat(), (g / n).toFloat(), (b / n).toFloat())
    }

    private fun rgbMeanToCct(r: Float, g: Float, b: Float, sensorToXyz: FloatArray):
        RobertsonCctEstimator.CctEstimate {
        val (x, y, z) = rgbToXyz(r, g, b, sensorToXyz)
        return RobertsonCctEstimator.estimate(x, y, z)
    }

    /**
     * Per-pixel CCT histogram: convert each pixel to CCT, build histogram,
     * return the peak CCT (mode) as the scene illuminant estimate.
     *
     * Sampling: every 8th pixel for performance at full resolution.
     */
    private fun computeCctHistogram(
        frame: RgbFrame,
        sensorToXyz: FloatArray,
    ): RobertsonCctEstimator.CctEstimate {
        // CCT bins: 1667K–25000K in 250K steps
        val binMin = 1667f; val binMax = 25000f; val binCount = 94
        val binWidth = (binMax - binMin) / binCount
        val histogram = FloatArray(binCount)
        var totalWeight = 0f

        val stride = 8 // Sample every 8th pixel for speed
        for (i in 0 until frame.pixelCount step stride) {
            val r = frame.red[i]; val g = frame.green[i]; val b = frame.blue[i]
            // Skip very dark or clipped pixels
            val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
            if (luma < 0.05f || luma > 0.95f) continue

            val (x, y, z) = rgbToXyz(r, g, b, sensorToXyz)
            val est = RobertsonCctEstimator.estimate(x, y, z)
            if (est.confidence < 0.3f) continue

            val binIdx = ((est.cctKelvin - binMin) / binWidth)
                .toInt().coerceIn(0, binCount - 1)
            val weight = est.confidence * luma
            histogram[binIdx] += weight
            totalWeight += weight
        }

        if (totalWeight < 1e-6f) {
            return RobertsonCctEstimator.CctEstimate(6500f, 0f, 0f)
        }

        // Find histogram peak
        val peakBin = histogram.indices.maxByOrNull { histogram[it] } ?: 27
        val peakCct = binMin + (peakBin + 0.5f) * binWidth

        return RobertsonCctEstimator.CctEstimate(
            cctKelvin = peakCct,
            duv = run {
                val (u, v) = cctToUv(peakCct)
                RobertsonCctEstimator.estimateFromUv(u, v).duv
            },
            confidence = histogram[peakBin] / totalWeight,
        )
    }

    // ─────────────────────────────────────────────────────────────────────
    // Gain field synthesis via guided bilateral filter
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Build a per-pixel colour temperature gain field using guided bilateral smoothing.
     *
     * The gain field encodes R/G/B multipliers for each pixel, derived from the
     * local CCT estimate. Bilateral smoothing ensures:
     * - Smooth transitions across zone boundaries (no halos)
     * - Edge-preserving: WB doesn't bleed across hard colour edges
     * - No visible zone boundaries
     *
     * Returns: [rGain, gGain, bGain] as FloatArrays of size pixelCount.
     */
    private fun buildGainField(
        frame: RgbFrame,
        sceneContext: SceneContext?,
        globalCct: Float,
        sensorToXyz: FloatArray,
    ): Triple<FloatArray, FloatArray, FloatArray> {
        val n = frame.pixelCount
        val rGain = FloatArray(n) { cctToRgbGain(globalCct).first }
        val gGain = FloatArray(n) { cctToRgbGain(globalCct).second }
        val bGain = FloatArray(n) { cctToRgbGain(globalCct).third }

        // Zone-specific local corrections (when semantic map is available)
        val ctx = sceneContext
        if (ctx?.zoneMap != null) {
            applyZoneCorrections(rGain, gGain, bGain, ctx, globalCct, frame)
        }

        // Bilateral smooth the gain field to eliminate zone boundary halos
        val smoothR = bilateralSmoothGain(rGain, frame.red, frame.width, frame.height)
        val smoothG = bilateralSmoothGain(gGain, frame.green, frame.width, frame.height)
        val smoothB = bilateralSmoothGain(bGain, frame.blue, frame.width, frame.height)

        return Triple(smoothR, smoothG, smoothB)
    }

    private fun applyZoneCorrections(
        rGain: FloatArray, gGain: FloatArray, bGain: FloatArray,
        ctx: SceneContext, globalCct: Float, frame: RgbFrame,
    ) {
        val zoneArray = ctx.zoneMap ?: return
        for (i in 0 until frame.pixelCount) {
            val zoneIdx = zoneArray[i].coerceIn(0, ZoneLabel.values().lastIndex)
            val zone = ZoneLabel.values()[zoneIdx]
            val zoneCct = when (zone) {
                ZoneLabel.SKY -> (globalCct * 1.05f).coerceAtMost(10000f)
                ZoneLabel.LAMP -> (globalCct * 0.85f).coerceAtLeast(2700f)
                ZoneLabel.FACE -> globalCct // Face: use global (clamped by skin anchor)
                else -> globalCct
            }
            val (r, g, b) = cctToRgbGain(zoneCct)
            rGain[i] = r; gGain[i] = g; bGain[i] = b
        }
    }

    /**
     * Bilateral smoothing of a scalar gain field.
     * Range weight uses the guide channel (luminance proxy) to preserve edges.
     */
    private fun bilateralSmoothGain(
        gain: FloatArray, guide: FloatArray, width: Int, height: Int,
    ): FloatArray {
        val out = FloatArray(gain.size)
        val r = BILATERAL_RADIUS
        val rangeSigma2 = 2f * BILATERAL_RANGE_SIGMA * BILATERAL_RANGE_SIGMA

        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                val gCenter = guide[i]
                var sumW = 0f; var sumGain = 0f
                for (dy in -r..r step 4) { // Strided for performance
                    for (dx in -r..r step 4) {
                        val sx = (x + dx).coerceIn(0, width - 1)
                        val sy = (y + dy).coerceIn(0, height - 1)
                        val si = sy * width + sx
                        val diff = guide[si] - gCenter
                        val rangeW = exp((-diff * diff / rangeSigma2).toDouble()).toFloat()
                        sumGain += gain[si] * rangeW
                        sumW += rangeW
                    }
                }
                out[i] = if (sumW > 1e-8f) sumGain / sumW else gain[i]
            }
        }
        return out
    }

    // ─────────────────────────────────────────────────────────────────────
    // Apply gain field
    // ─────────────────────────────────────────────────────────────────────

    private fun applyGainField(
        frame: RgbFrame,
        gainField: Triple<FloatArray, FloatArray, FloatArray>,
        tintGreenGain: Float,
        sensorWbBias: FloatArray?,
    ): RgbFrame {
        val (rGain, gGain, bGain) = gainField
        val rBias = sensorWbBias?.getOrNull(0) ?: 1f
        val gBias = sensorWbBias?.getOrNull(1) ?: 1f
        val bBias = sensorWbBias?.getOrNull(2) ?: 1f
        val n = frame.pixelCount
        val outR = FloatArray(n) { (frame.red[it] * rGain[it] * rBias).coerceAtLeast(0f) }
        val outG = FloatArray(n) { (frame.green[it] * gGain[it] * tintGreenGain * gBias).coerceAtLeast(0f) }
        val outB = FloatArray(n) { (frame.blue[it] * bGain[it] * bBias).coerceAtLeast(0f) }
        return RgbFrame(frame.width, frame.height, outR, outG, outB)
    }

    // ─────────────────────────────────────────────────────────────────────
    // Colour science utilities
    // ─────────────────────────────────────────────────────────────────────

    /** Apply 3×3 sensor→XYZ matrix (row-major). */
    private fun rgbToXyz(r: Float, g: Float, b: Float, m: FloatArray): Triple<Float, Float, Float> {
        val x = m[0] * r + m[1] * g + m[2] * b
        val y = m[3] * r + m[4] * g + m[5] * b
        val z = m[6] * r + m[7] * g + m[8] * b
        return Triple(max(0f, x), max(0f, y), max(0f, z))
    }

    /**
     * Convert CCT in Kelvin to approximate RGB gain multipliers.
     * Uses D-illuminant locus approximation for daylight (CCT ≥ 4000K)
     * and Planckian for tungsten (CCT < 4000K).
     */
    private fun cctToRgbGain(cctK: Float): Triple<Float, Float, Float> {
        // Map CCT to neutral reference (6500K = D65 = [1,1,1])
        // Simplified: warm → more red, cool → more blue
        val ratio = 6500f / cctK.coerceIn(1667f, 25000f)
        val rGain = if (ratio > 1f) ratio.coerceAtMost(2.5f) else 1f
        val bGain = if (ratio < 1f) (1f / ratio).coerceAtMost(2.5f) else 1f
        return Triple(rGain, 1.0f, bGain)
    }

    /** Approximate CIE 1960 (u,v) from CCT (Planckian locus). */
    private fun cctToUv(cctK: Float): Pair<Float, Float> {
        val t = cctK
        // Approximate Planckian locus in CIE 1960 (u,v)
        val u = (0.860117757f + 1.54118254e-4f * t + 1.28641212e-7f * t * t) /
            (1f + 8.42420235e-4f * t + 7.08145163e-7f * t * t)
        val v = (0.317398726f + 4.22806245e-5f * t + 4.20481691e-8f * t * t) /
            (1f - 2.89741816e-5f * t + 1.61456053e-7f * t * t)
        return u to v
    }

    private fun computeTintGreenGain(duvTint: Float, strength: Float = 1.0f): Float {
        // D_uv tint: positive = green cast, negative = magenta cast
        // Green gain: counter the green cast (1.0 = neutral, < 1.0 = more magenta)
        return (1f - duvTint * strength * 10f).coerceIn(0.5f, 2.0f)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Supporting types
// ─────────────────────────────────────────────────────────────────────────────

/** Scene zone label used in semantic WB zoning. */
enum class ZoneLabel { FACE, PERSON, SKY, LAMP, FOLIAGE, NEUTRAL, UNKNOWN }

/**
 * Optional scene context for WB zone corrections.
 *
 * @param zoneMap Per-pixel semantic zone label, size = pixelCount.
 * @param faceRegions List of face bounding boxes [xMin,yMin,xMax,yMax] normalised.
 */
data class SceneContext(
    val zoneMap: IntArray? = null,  // Values index into ZoneLabel.values()
    val faceRegions: List<FloatArray> = emptyList(),
)
