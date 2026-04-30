package com.leica.cam.imaging_pipeline.pipeline

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tanh

// ─────────────────────────────────────────────────────────────────────────────
// ToneLM 2.0 — Advanced Tone Mapping Engine
// ─────────────────────────────────────────────────────────────────────────────
//
// Implements the full ToneLM 2.0 render pipeline per hdr-engine-deep.md §9:
//
// Render order (sacred — never reorder):
//  1. Shadow denoising     (BEFORE any tone lift — critical physics law)
//  2. Semantic local EV    (face/person/sky zones get priority allocation)
//  3. Cinematic S-curve    (global tone shape with profile-specific curve)
//  4. Durand bilateral     (local contrast via base/detail decomposition)
//  5. Face tone pass       (per-face shadow floor + highlight shoulder override)
//  6. Luminosity sharpen   (Lab L-channel only — no chromatic fringing)
//  7. Gamma encode         (sRGB OETF or PQ/HLG for HDR output)
//
// Key physics enforced:
// - Denoising BEFORE tone lift: amplifying shadows amplifies noise
// - Semantic priority map: faces target mean luma 0.80, sky targets 0.375
// - Detail preservation: Durand base/detail prevents flattening
// - Face-first: dedicated per-face overrides after global tone pass

// ─────────────────────────────────────────────────────────────────────────────
// Output transfer functions
// ─────────────────────────────────────────────────────────────────────────────

enum class TransferCurve {
    /** Standard sRGB OETF — for display-referred 8-bit output. */
    SRGB,
    /** BT.2100 PQ (ST 2084) — for HDR10 10-bit HEIC output. */
    PQ,
    /** BT.2100 HLG — for HLG broadcast-compatible output. */
    HLG,
    /** Linear passthrough — for downstream compositing / grading. */
    LINEAR,
}

// ─────────────────────────────────────────────────────────────────────────────
// S-Curve profile parameters
// ─────────────────────────────────────────────────────────────────────────────

/**
 * S-curve shape parameters for ToneLM cinematic rendering.
 *
 * Default values implement the ToneLM 2.0 cinematic curve:
 *   Shadows  [0.00, 0.18] → toe lift to floor 0.02
 *   Midtones [0.18, 0.72] → linear at 1.0 scale (no mid contrast change)
 *   Shoulder [0.72, 1.00] → tanh rolloff to 0.97
 */
data class SCurveProfile(
    val shadowFloor: Float = 0.02f,
    val shadowEnd: Float = 0.18f,
    val midEnd: Float = 0.72f,
    val highlightCap: Float = 0.97f,
    val midContrastScale: Float = 1.0f,
) {
    companion object {
        /** Default cinematic profile — Leica/Hasselblad inspired rendering. */
        val CINEMATIC_DEFAULT = SCurveProfile()

        /**
         * Face-specific curve override — per hdr-engine-deep.md §9.2:
         * - Shadow floor +3% vs global (prevents under-eye crush)
         * - Highlight shoulder starts earlier at 0.65 (protects skin from blowing)
         * - Midtone contrast reduced 8% (gentler skin rendering)
         */
        val FACE_OVERRIDE = SCurveProfile(
            shadowFloor = 0.05f,
            shadowEnd = 0.18f,
            midEnd = 0.65f,
            highlightCap = 0.97f,
            midContrastScale = 0.92f,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Detected face descriptor
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Minimal face descriptor for the per-face tone pass.
 * In production this comes from FaceEngine via the AI pipeline.
 */
data class FaceDescriptor(
    /** Bounding box as fraction of frame dimensions [0,1]. */
    val xMin: Float,
    val yMin: Float,
    val xMax: Float,
    val yMax: Float,
    /** Confidence score [0,1]. */
    val confidence: Float = 1.0f,
)

// ─────────────────────────────────────────────────────────────────────────────
// ToneLM 2.0 Engine
// ─────────────────────────────────────────────────────────────────────────────

/**
 * ToneLM 2.0 — the authoritative tone mapping engine for the LeicaCam pipeline.
 *
 * This is the Kotlin CPU reference implementation. The production path should
 * delegate to the Vulkan compute shader `shaders/tone_durand.comp` for GPU
 * execution. This implementation is used for:
 *   1. Unit test validation against the shader output
 *   2. CPU fallback on devices without Vulkan 1.1 support
 *   3. Thumbnail and gallery preview rendering (lower resolution)
 */
class ToneLM2Engine(
    private val shadowDenoiser: ShadowDenoiseEngine = ShadowDenoiseEngine(),
    private val bilateralTM: DurandBilateralToneMappingEngine = DurandBilateralToneMappingEngine(),
    private val sCurve: CinematicSCurveEngine = CinematicSCurveEngine(),
    private val sharpener: LuminositySharpener = LuminositySharpener(),
) {

    /**
     * Full ToneLM 2.0 render pass.
     *
     * @param input          WB-corrected linear RGB frame.
     * @param noiseModel     Physics-grounded noise model for shadow denoising.
     * @param semanticMask   Per-pixel semantic zone map (optional).
     * @param faces          Detected faces for per-face curve override.
     * @param transfer       Output transfer function (sRGB, PQ, HLG, linear).
     */
    fun render(
        input: PipelineFrame,
        noiseModel: NoiseModel,
        semanticMask: SemanticMask? = null,
        faces: List<FaceDescriptor> = emptyList(),
        transfer: TransferCurve = TransferCurve.SRGB,
    ): PipelineFrame {

        // ── Step 1: Shadow denoising BEFORE tone mapping ──────────────────────
        // Physics law: denoise before tone lift — lifting shadows amplifies noise.
        // Shadow threshold = 0.18 (below knee in standard display zone curves).
        val denoised = shadowDenoiser.denoise(input, noiseModel, shadowThreshold = 0.18f)

        // ── Step 2: Semantic local EV modulation ──────────────────────────────
        // Apply priority-weighted local EV field before bilateral decomposition.
        // Face zones: target luma 0.80, Sky: 0.375, Per ToneLM §8.2.
        val durandToned = bilateralTM.apply(denoised, semanticMask)

        // ── Step 3: Cinematic S-curve ─────────────────────────────────────────
        val sCurved = sCurve.apply(durandToned, inFaceMask = null)

        // ── Step 4: Per-face tone pass ────────────────────────────────────────
        val faceProcessed = faces.fold(sCurved) { frame, face ->
            applyFaceTonePass(frame, face)
        }

        // ── Step 5: Luminosity-only sharpening (Lab L-channel) ───────────────
        val sharpened = sharpener.sharpen(faceProcessed, amount = 0.5f, radius = 1.0f)

        // ── Step 6: Output encoding ───────────────────────────────────────────
        return encodeTransfer(sharpened, transfer)
    }

    /**
     * Per-face tone curve override.
     *
     * Applies the FACE_OVERRIDE S-curve inside the feathered face bounding box.
     * Uses Gaussian feathering at the face bbox boundary to prevent visible edges.
     */
    private fun applyFaceTonePass(
        frame: PipelineFrame,
        face: FaceDescriptor,
    ): PipelineFrame {
        val w = frame.width; val h = frame.height
        val outR = frame.red.copyOf()
        val outG = frame.green.copyOf()
        val outB = frame.blue.copyOf()

        // Face bounding box in pixel coordinates
        val fxMin = (face.xMin * w).toInt().coerceIn(0, w - 1)
        val fxMax = (face.xMax * w).toInt().coerceIn(0, w - 1)
        val fyMin = (face.yMin * h).toInt().coerceIn(0, h - 1)
        val fyMax = (face.yMax * h).toInt().coerceIn(0, h - 1)
        val faceW = (fxMax - fxMin).coerceAtLeast(1)
        val faceH = (fyMax - fyMin).coerceAtLeast(1)

        // Feather distance: 10% of face dimension
        val featherX = (faceW * 0.10f).coerceAtLeast(4f)
        val featherY = (faceH * 0.10f).coerceAtLeast(4f)
        val faceCurve = SCurveProfile.FACE_OVERRIDE

        for (y in fyMin..fyMax) {
            for (x in fxMin..fxMax) {
                val i = y * w + x
                // Soft blend at edges: t=1 in centre, t=0 at boundary
                val tx = softEdgeMask(x.toFloat(), fxMin.toFloat(), fxMax.toFloat(), featherX)
                val ty = softEdgeMask(y.toFloat(), fyMin.toFloat(), fyMax.toFloat(), featherY)
                val blend = tx * ty * face.confidence

                if (blend < 0.01f) continue

                val fR = applyFaceCurve(frame.red[i], faceCurve)
                val fG = applyFaceCurve(frame.green[i], faceCurve)
                val fB = applyFaceCurve(frame.blue[i], faceCurve)

                outR[i] = lerp(frame.red[i], fR, blend)
                outG[i] = lerp(frame.green[i], fG, blend)
                outB[i] = lerp(frame.blue[i], fB, blend)
            }
        }
        return PipelineFrame(w, h, outR, outG, outB, frame.evOffset, frame.isoEquivalent, frame.exposureTimeNs)
    }

    private fun applyFaceCurve(x: Float, curve: SCurveProfile): Float {
        return when {
            x <= curve.shadowEnd -> {
                val t = x / curve.shadowEnd
                curve.shadowFloor + t * (curve.shadowEnd - curve.shadowFloor)
            }
            x <= curve.midEnd -> {
                curve.shadowEnd + (x - curve.shadowEnd) * curve.midContrastScale
            }
            else -> {
                val t = (x - curve.midEnd) / (1f - curve.midEnd) * (Math.PI.toFloat() / 2f)
                curve.midEnd + tanh(t.toDouble()).toFloat() * (curve.highlightCap - curve.midEnd)
            }
        }.coerceIn(0f, curve.highlightCap)
    }

    /**
     * Smooth edge mask for feathering face bounding box.
     * Returns 1.0 inside, tanh rolloff at edges within [feather] pixels.
     */
    private fun softEdgeMask(pos: Float, minV: Float, maxV: Float, feather: Float): Float {
        val distMin = pos - minV
        val distMax = maxV - pos
        val dist = min(distMin, distMax)
        if (dist >= feather) return 1f
        if (dist <= 0f) return 0f
        return (dist / feather).let { t -> t * t * (3f - 2f * t) }  // smoothstep
    }

    /**
     * Apply OETF (Optical-Electro Transfer Function) for output encoding.
     *
     * Input: linear scene-referred or display-referred float [0, ∞).
     * Output: encoded float [0, 1].
     */
    private fun encodeTransfer(frame: PipelineFrame, transfer: TransferCurve): PipelineFrame {
        return when (transfer) {
            TransferCurve.LINEAR -> frame
            TransferCurve.SRGB -> encodeWithFunction(frame, ::srgbOetf)
            TransferCurve.PQ -> encodeWithFunction(frame, ::pqOetf)
            TransferCurve.HLG -> encodeWithFunction(frame, ::hlgOetf)
        }
    }

    private fun encodeWithFunction(frame: PipelineFrame, fn: (Float) -> Float): PipelineFrame {
        val size = frame.width * frame.height
        return PipelineFrame(
            frame.width, frame.height,
            FloatArray(size) { fn(frame.red[it]) },
            FloatArray(size) { fn(frame.green[it]) },
            FloatArray(size) { fn(frame.blue[it]) },
            frame.evOffset, frame.isoEquivalent, frame.exposureTimeNs,
        )
    }

    // ── Transfer functions ──────────────────────────────────────────────────

    /** sRGB OETF per IEC 61966-2-1. */
    private fun srgbOetf(x: Float): Float {
        val lin = x.coerceAtLeast(0f)
        return if (lin <= 0.0031308f) 12.92f * lin
        else 1.055f * lin.pow(1f / 2.4f) - 0.055f
    }

    /** BT.2100 PQ EOTF inverse (ST 2084). */
    private fun pqOetf(y: Float): Float {
        if (y <= 0f) return 0f
        val m1 = 0.1593017578125f
        val m2 = 78.84375f
        val c1 = 0.8359375f
        val c2 = 18.8515625f
        val c3 = 18.6875f
        val ym1 = y.pow(m1)
        return ((c1 + c2 * ym1) / (1f + c3 * ym1)).pow(m2)
    }

    /** BT.2100 HLG OETF. */
    private fun hlgOetf(e: Float): Float {
        val a = 0.17883277f
        val b = 1f - 4f * a
        val c = 0.5f - a * ln(4f * a)
        return if (e <= 1f / 12f) sqrt(3f * e)
        else a * ln(12f * e - b) + c
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Utilities
// ─────────────────────────────────────────────────────────────────────────────

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)
private fun tanh(x: Double): Double = kotlin.math.tanh(x)
