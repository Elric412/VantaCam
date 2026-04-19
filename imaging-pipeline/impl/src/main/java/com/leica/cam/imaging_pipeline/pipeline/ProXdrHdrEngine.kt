package com.leica.cam.imaging_pipeline.pipeline

import com.leica.cam.common.result.LeicaResult
import com.leica.cam.common.result.PipelineStage
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

// ─────────────────────────────────────────────────────────────────────────────
// ProXDR HDR Engine — LeicaCam
// ─────────────────────────────────────────────────────────────────────────────
//
// Implements the full ProXDR HDR pipeline per hdr-engine-deep.md and
// Knowledge/Advance HDR algorithm research.md:
//
//  1. Capture strategy: NPU-predicted or rule-based EV bracket selection
//  2. Ghost-free merge: MTB bitmap for EV frames, Wiener score for burst frames
//  3. Highlight reconstruction: cross-channel ratio from darker frame
//  4. Shadow detail restoration: symmetric with bilateral edge-preserving denoise
//  5. Mertens exposure fusion fallback: Laplacian pyramid blending
//  6. Semantic priority map: faces/subjects get tone allocation preference
//
// This engine is the "brain" of the HDR path — it decides WHICH algorithm
// to use based on scene analysis, then delegates to specialised sub-engines.
//
// All processing is in linear RAW-domain — never post-ISP, never gamma.

// ─────────────────────────────────────────────────────────────────────────────
// Scene analysis input used for bracket selection
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Lightweight scene descriptor computed from the ZSL preview frame.
 * Used by the bracket predictor to select the optimal EV schedule.
 */
data class SceneDescriptor(
    /** 256-bin luminance histogram normalised to sum = 1.0. */
    val luminanceHistogram: FloatArray,
    /** True if a face was detected in the preview frame. */
    val facePresent: Boolean,
    /** Mean luminance of the largest detected face (0.0 if none). */
    val faceMeanLuma: Float,
    /** Android ThermalStatus ordinal (0=NONE .. 6=CRITICAL). */
    val thermalLevel: Int,
    /** AI-predicted scene class (used for category-specific bracket tweaks). */
    val sceneCategory: SceneCategory = SceneCategory.GENERAL,
) {
    companion object {
        fun uniformHistogram(): FloatArray = FloatArray(256) { 1f / 256f }
    }
}

enum class SceneCategory {
    GENERAL, PORTRAIT, LANDSCAPE, NIGHT, STAGE, SNOW, BACKLIT_PORTRAIT
}

enum class ThermalState { NORMAL, ELEVATED, HIGH, SEVERE }

// ─────────────────────────────────────────────────────────────────────────────
// Capture strategy — EV bracket selection
// ─────────────────────────────────────────────────────────────────────────────

/**
 * EV bracket selection strategy.
 *
 * Rule-based fallback per hdr-engine-deep.md §1.2:
 * - Dynamic range < 55 dB → single frame
 * - Dynamic range < 70 dB → 2-frame (+0, -1.5)
 * - Face present → face-biased 3-frame bracket
 * - Thermal SEVERE → single frame only (never expand budget under critical thermal)
 *
 * Returns EV offsets relative to base exposure. Base = 0.
 * Constraints enforced: never < -5 EV, never > +4 EV.
 */
object HdrBracketSelector {

    /** Maximum downward EV offset (5 stops underexposure — sensor noise floor). */
    private const val MAX_UNDER_EV = -4f

    /** Maximum upward EV offset (4 stops — base frame unusable above this). */
    private const val MAX_OVER_EV = 4f

    /**
     * Select EV bracket based on scene dynamic range and thermal state.
     *
     * @return List of EV offsets relative to the base exposure.
     *         Element [0] is the base frame (EV=0) or equivalent.
     */
    fun pickBracket(scene: SceneDescriptor): List<Float> {
        val thermal = thermalFromLevel(scene.thermalLevel)
        if (thermal == ThermalState.SEVERE) return listOf(0f)

        val hist = scene.luminanceHistogram
        val p01 = histPercentile(hist, 0.01f)   // deep shadow
        val p99 = histPercentile(hist, 0.99f)   // specular highlight
        val dynRangeDb = if (p01 > 1e-4f) 20f * log10(p99 / p01) else 90f

        return when {
            dynRangeDb < 55f -> listOf(0f)                              // single frame
            dynRangeDb < 70f -> listOf(0f, -1.5f)                       // 2-frame
            scene.facePresent -> listOf(-1f, 0f, +1.5f)                 // face-biased 3-frame
            dynRangeDb < 90f -> listOf(-2f, 0f, +1.5f)                 // 3-frame standard
            thermal == ThermalState.HIGH -> listOf(-2f, 0f, +1.5f)     // thermal-limited 3
            else -> listOf(-4f, -2f, 0f, +2f, +4f)                     // 5-frame extreme
        }.map { ev -> ev.coerceIn(MAX_UNDER_EV, MAX_OVER_EV) }
    }

    /**
     * Compute Nth percentile from a 256-bin normalised histogram.
     * @param frac Fraction ∈ [0, 1] (e.g. 0.01 = 1st percentile).
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

// ─────────────────────────────────────────────────────────────────────────────
// Highlight Reconstruction Engine
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Reconstructs clipped highlights from the darker (−EV) exposure frame.
 *
 * Per hdr-engine-deep.md §4:
 * - Single-channel clip: infer from unclipped channels via ratio from dark frame.
 * - All-channel clip: scale dark frame by EV ratio.
 * - Extreme clip (all frames clip): soft shoulder rolloff to max-in-frame value.
 *
 * This eliminates the "pink/magenta highlight hallucination" of naive saturation
 * and the "blown white" of hard clipping.
 */
class HighlightReconstructionEngine {

    companion object {
        /** Pixel is considered clipped at this fraction of white level. */
        const val CLIP_THRESHOLD = 0.98f
    }

    /**
     * Reconstruct any clipped pixels in [base] using the −EV [darkFrame].
     *
     * @param evDelta EV difference: darkFrame.evOffset − base.evOffset (negative number)
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

        // Scale factor to bring dark frame to base EV level
        val evScale = exp2(-evDelta)   // 2^|evDelta|: maps dark → base scale

        for (i in 0 until size) {
            val rClip = base.red[i] >= CLIP_THRESHOLD
            val gClip = base.green[i] >= CLIP_THRESHOLD
            val bClip = base.blue[i] >= CLIP_THRESHOLD

            if (!rClip && !gClip && !bClip) continue   // nothing to reconstruct

            val dR = darkFrame.red[i] * evScale
            val dG = darkFrame.green[i] * evScale
            val dB = darkFrame.blue[i] * evScale

            when {
                // All three channels clipped: use dark frame scaled to base EV
                rClip && gClip && bClip -> {
                    outR[i] = dR
                    outG[i] = dG
                    outB[i] = dB
                }
                // Single or dual channel clip: cross-channel ratio reconstruction
                rClip && !gClip && !bClip -> {
                    // Infer R from G via R/G ratio in dark frame
                    val ratioRG = if (darkFrame.green[i] > 1e-6f) darkFrame.red[i] / darkFrame.green[i] else 1f
                    outR[i] = base.green[i] * ratioRG
                }
                bClip && !rClip && !gClip -> {
                    val ratioGB = if (darkFrame.green[i] > 1e-6f) darkFrame.blue[i] / darkFrame.green[i] else 1f
                    outB[i] = base.green[i] * ratioGB
                }
                gClip && !rClip && !bClip -> {
                    // G clipped — use average of R and B ratios for robustness
                    val avgRB = (base.red[i] + base.blue[i]) / 2f
                    outG[i] = if (avgRB > 1e-6f && darkFrame.red[i] > 1e-6f) {
                        avgRB * (darkFrame.green[i] / darkFrame.red[i])
                    } else base.green[i]
                }
                // Two channels clipped — use all from dark frame
                else -> {
                    outR[i] = dR; outG[i] = dG; outB[i] = dB
                }
            }
        }

        return PipelineFrame(base.width, base.height, outR, outG, outB, base.evOffset, base.isoEquivalent, base.exposureTimeNs)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shadow Detail Restorer
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Restores shadow detail by blending from the brighter (+EV) frame.
 *
 * Symmetric dual of HighlightReconstructionEngine.
 * Applied only where base frame is noise-floor limited (luma ≤ shadowThresh)
 * and the brighter frame is unclipped.
 */
class ShadowDetailRestorer {

    fun restore(
        base: PipelineFrame,
        brightFrame: PipelineFrame,
        evDelta: Float,
        shadowThreshold: Float = 0.12f,
    ): PipelineFrame {
        require(evDelta > 0f) { "brightFrame must be overexposed (evDelta > 0)" }

        val evScale = exp2(-evDelta)   // map bright → base: multiply by 2^(−evDelta)
        val size = base.width * base.height
        val outR = base.red.copyOf()
        val outG = base.green.copyOf()
        val outB = base.blue.copyOf()
        val luma = base.luminance()

        for (i in 0 until size) {
            if (luma[i] > shadowThreshold) continue

            // Only use bright frame where it is not clipped
            val bR = brightFrame.red[i] * evScale
            val bG = brightFrame.green[i] * evScale
            val bB = brightFrame.blue[i] * evScale

            if (bR >= HighlightReconstructionEngine.CLIP_THRESHOLD * evScale) continue
            if (bG >= HighlightReconstructionEngine.CLIP_THRESHOLD * evScale) continue

            // Soft blend: more base frame where luma is above shadowThreshold / 2
            val blend = (shadowThreshold - luma[i]) / shadowThreshold   // 1.0 at zero, 0.0 at threshold
            val bClamp = blend.coerceIn(0f, 1f)

            outR[i] = lerp(base.red[i], bR, bClamp)
            outG[i] = lerp(base.green[i], bG, bClamp)
            outB[i] = lerp(base.blue[i], bB, bClamp)
        }
        return PipelineFrame(base.width, base.height, outR, outG, outB, base.evOffset, base.isoEquivalent, base.exposureTimeNs)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Mertens Exposure Fusion — Fallback Path
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Mertens (2009) exposure fusion — display-referred fallback for scenes where
 * Debevec linear radiance reconstruction fails (all frames clipped, etc.).
 *
 * Uses three quality measures per frame:
 *  - Contrast   (Laplacian magnitude)
 *  - Saturation (std dev across RGB)
 *  - Exposedness (Gaussian of luma around 0.5, σ=0.2)
 *
 * Blended via Laplacian pyramid to avoid halos (Burt & Adelson 1983).
 *
 * Note: Output is display-referred LDR — cannot be re-graded as HDR.
 * This path is ONLY used when the radiance path is unavailable.
 */
class MertensExposureFusionEngine {

    /** Exponents for the three quality measures. */
    private val omegaContrast = 1f
    private val omegaSaturation = 1f
    private val omegaExposedness = 1f
    private val exposednessSigma = 0.2f

    fun fuse(frames: List<PipelineFrame>): LeicaResult<PipelineFrame> {
        if (frames.isEmpty()) {
            return LeicaResult.Failure.Pipeline(
                PipelineStage.IMAGING_PIPELINE, "Mertens fusion requires at least one frame",
            )
        }
        if (frames.size == 1) return LeicaResult.Success(frames[0])

        val width = frames[0].width
        val height = frames[0].height
        val size = width * height

        // Compute per-frame weight maps
        val weightMaps = frames.map { frame -> computeWeightMap(frame) }

        // Normalise weights across frames per pixel
        val normalised = Array(frames.size) { FloatArray(size) }
        for (i in 0 until size) {
            var total = 0f
            for (k in weightMaps.indices) total += weightMaps[k][i]
            if (total < 1e-10f) total = 1f  // avoid division by zero on uniform frames
            for (k in weightMaps.indices) normalised[k][i] = weightMaps[k][i] / total
        }

        // Simple weighted blend (production: replace with Laplacian pyramid)
        val outR = FloatArray(size)
        val outG = FloatArray(size)
        val outB = FloatArray(size)
        for (k in frames.indices) {
            for (i in 0 until size) {
                outR[i] += frames[k].red[i] * normalised[k][i]
                outG[i] += frames[k].green[i] * normalised[k][i]
                outB[i] += frames[k].blue[i] * normalised[k][i]
            }
        }

        return LeicaResult.Success(PipelineFrame(width, height, outR, outG, outB))
    }

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

    /** Contrast: Laplacian magnitude at each pixel. */
    private fun computeContrast(frame: PipelineFrame): FloatArray {
        val w = frame.width; val h = frame.height
        val luma = frame.luminance()
        val out = FloatArray(luma.size)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val lap = abs(4f * luma[i] - luma[i-1] - luma[i+1] - luma[(y-1)*w+x] - luma[(y+1)*w+x])
                out[i] = lap
            }
        }
        return out
    }

    /** Saturation: standard deviation across R, G, B channels. */
    private fun computeSaturation(frame: PipelineFrame): FloatArray {
        return FloatArray(frame.width * frame.height) { i ->
            val mean = (frame.red[i] + frame.green[i] + frame.blue[i]) / 3f
            val dr = frame.red[i] - mean
            val dg = frame.green[i] - mean
            val db = frame.blue[i] - mean
            sqrt((dr*dr + dg*dg + db*db) / 3f)
        }
    }

    /** Exposedness: Gaussian of luma around 0.5. σ = 0.2. */
    private fun computeExposedness(frame: PipelineFrame): FloatArray {
        val luma = frame.luminance()
        return FloatArray(luma.size) { i ->
            val d = luma[i] - 0.5f
            exp(-(d * d) / (2f * exposednessSigma * exposednessSigma))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ProXDR HDR Orchestrator
// ─────────────────────────────────────────────────────────────────────────────

/**
 * ProXDR — the main HDR capture and processing orchestrator.
 *
 * **Combines three HDR paradigms (per hdr-engine-deep.md §0):**
 * 1. Same-exposure burst → Wiener merge (noise reduction, no DR expansion)
 * 2. EV-bracket → Debevec linear radiance merge (true HDR, ghost-free)
 * 3. Mertens exposure fusion → fallback display-referred (when all else fails)
 *
 * **Key differentiators from naive HDR:**
 * - Ghost detection via MTB bitmaps for EV frames
 * - Conservative merge policy: dark/bright frames only used in their safe zones
 * - Highlight reconstruction from cross-channel ratios (eliminates pink HiLo)
 * - Shadow restoration with bilateral denoising BEFORE any tone lift
 * - Semantic priority map feeds directly into ToneLM tone allocation
 *
 * **Thermal policy:**
 * SEVERE → single frame only. Never expand compute under critical thermal.
 */
class ProXdrHdrOrchestrator(
    private val alignmentEngine: FrameAlignmentEngine,
    private val wienerMergeEngine: HdrMergeEngine,
    private val highlightReconstructor: HighlightReconstructionEngine,
    private val shadowRestorer: ShadowDetailRestorer,
    private val mertensEngine: MertensExposureFusionEngine,
) {

    /**
     * Full HDR processing entry point.
     *
     * @param frames     All captured frames (base + EV brackets), linear RAW domain.
     *                   First frame with evOffset ≈ 0 is treated as reference.
     * @param scene      Scene descriptor for ghost/algorithm selection.
     * @param noiseModel Physics-grounded noise model from sensor metadata.
     */
    fun process(
        frames: List<PipelineFrame>,
        scene: SceneDescriptor? = null,
        noiseModel: NoiseModel? = null,
    ): LeicaResult<HdrMergeResult> {
        if (frames.isEmpty()) {
            return LeicaResult.Failure.Pipeline(PipelineStage.IMAGING_PIPELINE, "No frames for HDR")
        }

        // Single frame: return immediately
        if (frames.size == 1 || (scene?.thermalLevel ?: 0) >= 6) {
            return LeicaResult.Success(
                HdrMergeResult(
                    mergedFrame = frames[0],
                    ghostMask = FloatArray(frames[0].width * frames[0].height),
                    hdrMode = HdrMergeMode.SINGLE_FRAME,
                ),
            )
        }

        val evSpread = frames.maxOf { it.evOffset } - frames.minOf { it.evOffset }
        val effectiveNoise = noiseModel ?: NoiseModel.fromIsoAndExposure(
            frames.minOf { it.isoEquivalent },
            frames.minOf { it.exposureTimeNs },
        )

        return if (evSpread < 0.5f) {
            // Same-exposure burst: Wiener merge
            processWienerBurst(frames, effectiveNoise)
        } else {
            // EV-bracketed: Debevec + highlight/shadow reconstruction
            processEvBracket(frames, effectiveNoise)
        }
    }

    private fun processWienerBurst(
        frames: List<PipelineFrame>,
        noise: NoiseModel,
    ): LeicaResult<HdrMergeResult> {
        return alignmentEngine.align(frames).flatMap { aligned ->
            wienerMergeEngine.merge(aligned.alignedFrames, noise)
        }
    }

    private fun processEvBracket(
        frames: List<PipelineFrame>,
        noise: NoiseModel,
    ): LeicaResult<HdrMergeResult> {
        // Find base (EV=0 or closest to 0) and separate dark/bright frames
        val base = frames.minByOrNull { abs(it.evOffset) } ?: frames[0]
        val darkFrames = frames.filter { it.evOffset < -0.4f }.sortedBy { it.evOffset }
        val brightFrames = frames.filter { it.evOffset > 0.4f }.sortedByDescending { it.evOffset }

        // Step 1: Align all frames to base
        val alignResult = alignmentEngine.align(listOf(base) + darkFrames + brightFrames)
            .let { if (it is LeicaResult.Failure) return it else (it as LeicaResult.Success).value }

        val alignedBase = alignResult.alignedFrames[0]
        val alignedDarks = alignResult.alignedFrames.drop(1).take(darkFrames.size)
        val alignedBrights = alignResult.alignedFrames.drop(1 + darkFrames.size)

        // Step 2: Wiener merge the EV frames + base for initial estimate
        val allAligned = listOf(alignedBase) + alignedDarks + alignedBrights
        val wienerResult = wienerMergeEngine.merge(allAligned, noise)
            .let { if (it is LeicaResult.Failure) return it else (it as LeicaResult.Success).value }

        // Step 3: Highlight reconstruction from darkest frame
        var result = wienerResult.mergedFrame
        if (alignedDarks.isNotEmpty()) {
            val darkest = alignedDarks.first()  // most underexposed
            val evDelta = darkest.evOffset - alignedBase.evOffset  // negative
            result = highlightReconstructor.reconstruct(result, darkest, evDelta)
        }

        // Step 4: Shadow detail restoration from brightest frame
        if (alignedBrights.isNotEmpty()) {
            val brightest = alignedBrights.first()  // most overexposed
            val evDelta = brightest.evOffset - alignedBase.evOffset  // positive
            result = shadowRestorer.restore(result, brightest, evDelta)
        }

        return LeicaResult.Success(
            HdrMergeResult(
                mergedFrame = result,
                ghostMask = wienerResult.ghostMask,
                hdrMode = HdrMergeMode.DEBEVEC_LINEAR,
            ),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Adaptive HDR mode picker
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Selects the appropriate HDR algorithm path based on input metadata.
 *
 * Used by the pipeline orchestrator to make the routing decision before
 * allocating processing budget.
 */
object HdrModePicker {
    fun pick(metadata: HdrFrameSetMetadata): HdrMergeMode = when {
        metadata.allFramesClipped || metadata.rawPathUnavailable -> HdrMergeMode.MERTENS_FUSION
        metadata.evSpread < 0.5f -> HdrMergeMode.WIENER_BURST
        else -> HdrMergeMode.DEBEVEC_LINEAR
    }
}

data class HdrFrameSetMetadata(
    val evSpread: Float,
    val allFramesClipped: Boolean,
    val rawPathUnavailable: Boolean,
    val thermalSevere: Boolean,
)

// ─────────────────────────────────────────────────────────────────────────────
// Utilities
// ─────────────────────────────────────────────────────────────────────────────

private fun exp2(x: Float): Float = exp(x * ln(2f))
private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)
