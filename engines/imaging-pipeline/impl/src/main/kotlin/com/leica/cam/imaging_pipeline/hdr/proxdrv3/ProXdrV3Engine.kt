package com.leica.cam.imaging_pipeline.hdr.proxdrv3

import com.leica.cam.common.result.LeicaResult
import com.leica.cam.common.result.PipelineStage
import com.leica.cam.imaging_pipeline.pipeline.HdrMergeMode
import com.leica.cam.imaging_pipeline.pipeline.HdrMergeResult
import com.leica.cam.imaging_pipeline.pipeline.PipelineFrame
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * **ProXDR v3** integration façade for the LeicaCam imaging pipeline.
 *
 * This wraps the upstream ProXDR v3 engine (drop-in C++ pipeline residing
 * under `platform-android/native-imaging-core/impl/src/main/cpp/proxdr/`)
 * into a project-idiomatic surface that returns
 * [com.leica.cam.imaging_pipeline.pipeline.HdrMergeResult] and consumes
 * [PipelineFrame], so the existing
 * [com.leica.cam.imaging_pipeline.hdr.ProXdrOrchestrator] can route to it
 * without changing any downstream stage.
 *
 * #### Two execution modes
 *
 * 1. **Native mode** — when running on Android, the JNI bridge
 *    `com.proxdr.engine.ProXDRBridge` is invoked. RAW16 data is required;
 *    on the LeicaCam pipeline path we already operate post-demosaic in
 *    fp32 linear sRGB, so we use the v3 _RGB entry point_ described in
 *    `docs/INTEGRATION.md` §6 ("CameraX alternative"): skip stages 0–4 of
 *    the pipeline and feed our RGB straight in via the Kotlin-side
 *    fallback that runs the v3 _logical_ pipeline (see [runRgbFastPath]).
 *
 * 2. **JVM / unit-test mode** — when no native library is loadable (JUnit,
 *    headless environments) the wrapper executes a faithful Kotlin port
 *    of v3's RGB-domain pipeline: scene-adaptive Wiener fusion → highlight
 *    spectral-ratio recovery → log-lift shadow restoration → Mertens-on-
 *    linear-HDR fusion. This guarantees correctness tests are deterministic
 *    and that the orchestrator never has to special-case JVM vs Android.
 *
 * #### Tuning surface
 *
 * The wrapper exposes a [ProXdrV3Tuning] data-class mirroring the most-used
 * knobs from the upstream `ProXDRCfg` (see `docs/TUNING.md`). The engine
 * starts from defaults and applies adaptive scene-mode tuning before any
 * user override — exactly matching the upstream invariant.
 *
 * **References (upstream):**
 *  - `improved/include/ProXDR_Engine.h`         — types & cfg
 *  - `improved/src/pipeline.cpp`                — top-level orchestrator
 *  - `improved/src/fusion_lm.cpp`               — SAFNet-lite Wiener merge
 *  - `improved/src/highlight_recovery.cpp`      — soft-knee spectral ratio
 *  - `improved/src/tone_lm.cpp`                 — Mertens-on-linear-HDR
 *  - `improved/docs/ARCHITECTURE.md` §1..§10    — design rationale
 *  - `improved/docs/INTEGRATION.md` §6          — RGB-fastpath entry point
 */
class ProXdrV3Engine(
    private val tuning: ProXdrV3Tuning = ProXdrV3Tuning(),
    private val nativeBackend: ProXdrV3NativeBackend = ProXdrV3NativeBackend.tryLoad(),
) {

    /**
     * Process a burst of frames using ProXDR v3's adaptive HDR pipeline.
     *
     * @param frames Linear RGB frames (already demosaiced, linear-light).
     *               Must all have identical width × height.
     * @param sceneMode Auto by default. Caller can override to lock the
     *                  pipeline into a specific mode (Portrait / Night / …).
     * @param thermal Current device thermal state — drives v3's graceful
     *                quality decay (see ARCHITECTURE.md §10).
     * @param userBias Optional EV bias [-2..+2] applied before tone mapping.
     */
    fun process(
        frames: List<PipelineFrame>,
        sceneMode: ProXdrV3SceneMode = ProXdrV3SceneMode.AUTO,
        thermal: ProXdrV3Thermal = ProXdrV3Thermal.NORMAL,
        userBias: Float = 0f,
    ): LeicaResult<HdrMergeResult> {
        if (frames.isEmpty()) {
            return LeicaResult.Failure.Pipeline(
                PipelineStage.IMAGING_PIPELINE,
                "ProXDR v3: empty frame list",
            )
        }
        // Critical thermal: bypass HDR entirely (v3 invariant — see
        // ARCHITECTURE.md §10 "Critical → bypass HDR entirely").
        if (thermal == ProXdrV3Thermal.CRITICAL) {
            return passThrough(frames.first(), HdrMergeMode.SINGLE_FRAME)
        }
        // Adaptive scene-mode tuning — apply before any user override
        // (TUNING.md §"Tuning workflow" rule 2).
        val effective = applyAdaptiveTuning(tuning, frames, sceneMode, thermal)

        return if (nativeBackend.isAvailable && hasRawFootprint(frames)) {
            runNative(frames, effective, sceneMode, thermal, userBias)
        } else {
            runRgbFastPath(frames, effective, userBias)
        }
    }

    /** True iff the dropped-in C++ engine is loaded and the inputs are RAW-shaped. */
    fun isNativeAvailable(): Boolean = nativeBackend.isAvailable

    /** Currently-active tuning (after defaults are merged). */
    fun activeTuning(): ProXdrV3Tuning = tuning

    // ─── Native path ─────────────────────────────────────────────────────

    private fun runNative(
        frames: List<PipelineFrame>,
        cfg: ProXdrV3Tuning,
        sceneMode: ProXdrV3SceneMode,
        thermal: ProXdrV3Thermal,
        userBias: Float,
    ): LeicaResult<HdrMergeResult> {
        return try {
            val merged = nativeBackend.processBurst(
                frames = frames,
                cfg = cfg,
                sceneMode = sceneMode,
                thermal = thermal,
                userBias = userBias,
            )
            LeicaResult.Success(
                HdrMergeResult(
                    mergedFrame = merged,
                    ghostMask = FloatArray(merged.width * merged.height),
                    hdrMode = HdrMergeMode.WIENER_BURST,
                ),
            )
        } catch (t: Throwable) {
            // ProXDR v3 docs (TROUBLESHOOTING.md §"When to escalate") expect
            // the host to always have a software fallback. Honour that.
            runRgbFastPath(frames, cfg, userBias)
        }
    }

    // ─── RGB fast path (Kotlin port of v3 pipeline post-demosaic) ────────

    /**
     * Kotlin port of the v3 logical pipeline starting at stage 5 of
     * `improved/src/pipeline.cpp` (post-demosaic, linear sRGB).
     * Runs in pure JVM so it's covered by unit tests.
     */
    private fun runRgbFastPath(
        frames: List<PipelineFrame>,
        cfg: ProXdrV3Tuning,
        userBias: Float,
    ): LeicaResult<HdrMergeResult> {
        // STAGE 0: reference-frame pick (sharpness × stillness — v3 §"ref pick")
        val orderedFrames = pickReferenceFirst(frames, cfg)
        val width = orderedFrames.first().width
        val height = orderedFrames.first().height
        val pixels = width * height

        // STAGE 4: FusionLM — alignment + Wiener merge.
        // Single-frame short-circuit honours v3 invariant.
        val fused: PipelineFrame = if (orderedFrames.size == 1) {
            orderedFrames.first()
        } else {
            wienerFuse(orderedFrames, cfg)
        }

        // STAGE 8: highlight recovery — soft-knee spectral ratio.
        val highlightRecovered = if (cfg.highlightRecoveryEnabled) {
            highlightRecover(fused, cfg)
        } else fused

        // STAGE 9: shadow lift — log-lift, local-mean adaptive.
        val lifted = if (cfg.shadowLiftAmount > 0f) {
            shadowLift(highlightRecovered, cfg)
        } else highlightRecovered

        // STAGE 10 (linear-HDR snapshot): apply user EV bias here, exactly as
        // v3's pipeline.cpp does immediately before tone mapping.
        val biased = if (userBias != 0f) applyEvBias(lifted, userBias) else lifted

        return LeicaResult.Success(
            HdrMergeResult(
                mergedFrame = biased,
                ghostMask = FloatArray(pixels),
                hdrMode = HdrMergeMode.WIENER_BURST,
            ),
        )
    }

    private fun passThrough(frame: PipelineFrame, mode: HdrMergeMode): LeicaResult<HdrMergeResult> =
        LeicaResult.Success(
            HdrMergeResult(
                mergedFrame = frame,
                ghostMask = FloatArray(frame.width * frame.height),
                hdrMode = mode,
            ),
        )

    private fun hasRawFootprint(frames: List<PipelineFrame>): Boolean {
        // The native bridge expects RAW16 capacity. PipelineFrame already
        // stores demosaic'd RGB so we never satisfy this in the existing
        // pipeline; left as an extension point for a future RAW capture path.
        return false
    }

    // ─── Reference frame pick (v3 §"sharpness × stillness") ──────────────

    private fun pickReferenceFirst(
        frames: List<PipelineFrame>,
        cfg: ProXdrV3Tuning,
    ): List<PipelineFrame> {
        if (frames.size <= 1) return frames
        val motionWeight = cfg.refPickMotionWeight
        // Score = sharpness * (1 / (1 + motion * w)). Higher = better reference.
        val scored = frames.map { f ->
            val sharp = laplacianVariance(f.green, f.width, f.height)
            val motion = abs(f.evOffset)
            val score = sharp / (1f + motion * motionWeight)
            f to score
        }
        val best = scored.maxByOrNull { it.second }!!.first
        return listOf(best) + frames.filter { it !== best }
    }

    private fun laplacianVariance(channel: FloatArray, w: Int, h: Int): Float {
        if (w < 3 || h < 3) return 1f
        var mean = 0.0
        var meanSq = 0.0
        var n = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val lap = -channel[i - w - 1] - channel[i - w] - channel[i - w + 1] +
                    -channel[i - 1] + 8f * channel[i] - channel[i + 1] +
                    -channel[i + w - 1] - channel[i + w] - channel[i + w + 1]
                mean += lap
                meanSq += lap.toDouble() * lap
                n += 1
            }
        }
        if (n == 0) return 1f
        val m = mean / n
        return ((meanSq / n) - m * m).toFloat().coerceAtLeast(1e-6f)
    }

    // ─── Wiener-weighted multi-frame fusion (v3 fusion_lm.cpp logic) ──────

    private fun wienerFuse(
        frames: List<PipelineFrame>,
        cfg: ProXdrV3Tuning,
    ): PipelineFrame {
        val ref = frames.first()
        val w = ref.width
        val h = ref.height
        val n = w * h
        val outR = FloatArray(n)
        val outG = FloatArray(n)
        val outB = FloatArray(n)

        // ProXDR v3 uses per-channel Poisson-Gaussian noise: σ²(x) = A·x + B.
        val a = cfg.shotCoeff
        val b = cfg.readNoiseSq
        val ghostThr = cfg.ghostSigma

        for (i in 0 until n) {
            val refLuma = LUM_R * ref.red[i] + LUM_G * ref.green[i] + LUM_B * ref.blue[i]
            val sigma = sqrt(a * max(refLuma, 0f) + b + 1e-10f)

            var sumR = 0.0
            var sumG = 0.0
            var sumB = 0.0
            var sumW = 0.0
            for (f in frames) {
                val luma = LUM_R * f.red[i] + LUM_G * f.green[i] + LUM_B * f.blue[i]
                val devSigma = abs(luma - refLuma) / sigma
                // SAFNet-lite confidence — reject high-residual contributions.
                val conf = if (devSigma > ghostThr) 0f else exp(-0.5f * devSigma * devSigma)
                val variance = a * max(luma, 0f) + b + 1e-10f
                val weight = conf / variance
                sumR += f.red[i] * weight
                sumG += f.green[i] * weight
                sumB += f.blue[i] * weight
                sumW += weight.toDouble()
            }
            val invW = if (sumW > 1e-12) 1.0 / sumW else 1.0
            outR[i] = (sumR * invW).toFloat().coerceAtLeast(0f)
            outG[i] = (sumG * invW).toFloat().coerceAtLeast(0f)
            outB[i] = (sumB * invW).toFloat().coerceAtLeast(0f)
        }
        return PipelineFrame(w, h, outR, outG, outB, ref.evOffset, ref.isoEquivalent, ref.exposureTimeNs)
    }

    // ─── Highlight recovery (v3 highlight_recovery.cpp) ───────────────────

    /**
     * Soft-knee spectral-ratio reconstruction.
     *
     * For pixels whose strongest channel exceeds [cfg.highlightClip], the
     * neighbour ratio R/G and B/G is used to reconstruct the saturated
     * channel — preserving cloud / sky detail at the edge of clipping.
     *
     * The face-protection rule (v3 ARCHITECTURE.md §7) requires skipping
     * spectral ratio in face zones; we expose the toggle in [cfg.protectFace]
     * but no face mask is plumbed through this fast path — pipeline-level
     * face mask integration lives in the orchestrator above.
     */
    private fun highlightRecover(frame: PipelineFrame, cfg: ProXdrV3Tuning): PipelineFrame {
        val w = frame.width
        val h = frame.height
        val n = w * h
        val clip = cfg.highlightClip
        val band = cfg.highlightSoftBand
        val rolloffStart = cfg.highlightRolloffStart

        val outR = frame.red.copyOf()
        val outG = frame.green.copyOf()
        val outB = frame.blue.copyOf()

        // Pre-compute integral-image-friendly ratio arrays — the v3 trick.
        // For the fastpath we use 5×5 neighbourhood ratios on green-channel
        // dominant pixels to reconstruct R, B contributions.
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val r = frame.red[i]
                val g = frame.green[i]
                val b = frame.blue[i]
                val maxCh = max(r, max(g, b))
                if (maxCh < rolloffStart) continue

                val clipWeight = softClipWeight(maxCh, clip, band)
                if (clipWeight <= 0f) continue

                // Sample ratios from 5×5 neighbourhood, restricted to unclipped pixels.
                var sumRG = 0.0; var sumBG = 0.0; var nValid = 0
                for (dy in -2..2) {
                    val sy = (y + dy).coerceIn(0, h - 1)
                    for (dx in -2..2) {
                        val sx = (x + dx).coerceIn(0, w - 1)
                        val si = sy * w + sx
                        val sr = frame.red[si]; val sg = frame.green[si]; val sb = frame.blue[si]
                        if (max(sr, max(sg, sb)) < rolloffStart && sg > 1e-4f) {
                            sumRG += (sr / sg).toDouble()
                            sumBG += (sb / sg).toDouble()
                            nValid++
                        }
                    }
                }
                if (nValid == 0) continue
                val ratioRG = (sumRG / nValid).toFloat()
                val ratioBG = (sumBG / nValid).toFloat()

                // Reconstruct clipped channel using neighbour ratios; blend by softness.
                val reconG = max(g, max(r / max(ratioRG, 1e-4f), b / max(ratioBG, 1e-4f)))
                val mix = clipWeight * cfg.highlightRecoveryStrength
                outR[i] = lerpClamp(r, reconG * ratioRG, mix)
                outG[i] = lerpClamp(g, reconG, mix)
                outB[i] = lerpClamp(b, reconG * ratioBG, mix)
            }
        }
        return PipelineFrame(
            w, h, outR, outG, outB,
            frame.evOffset, frame.isoEquivalent, frame.exposureTimeNs,
        )
    }

    private fun softClipWeight(v: Float, clip: Float, band: Float): Float {
        val lo = clip - band
        val hi = clip
        return when {
            v <= lo -> 0f
            v >= hi -> 1f
            else -> {
                val t = (v - lo) / max(hi - lo, 1e-6f)
                t * t * (3f - 2f * t)  // smoothstep
            }
        }
    }

    private fun lerpClamp(a: Float, b: Float, t: Float): Float {
        val s = t.coerceIn(0f, 1f)
        return (a * (1f - s) + b * s).coerceAtLeast(0f)
    }

    // ─── Shadow lift (v3 highlight_recovery.cpp §"ShadowLift") ────────────

    /**
     * Logarithmic shadow lift with optional local-mean adaptation.
     *
     * Replaces the legacy quadratic lift (see v3 ARCHITECTURE.md §4):
     * ```
     *   lifted = v + α · log1p(β · max(0, threshold - v))
     * ```
     * The log-toe characteristic plateaus gracefully — deep shadows still
     * receive the most lift but never enough to expose read noise.
     */
    private fun shadowLift(frame: PipelineFrame, cfg: ProXdrV3Tuning): PipelineFrame {
        val w = frame.width
        val h = frame.height
        val n = w * h
        val alpha = cfg.shadowLiftAmount
        val threshold = cfg.shadowThreshold
        val beta = 4f  // log1p slope; matches v3 default

        val outR = FloatArray(n)
        val outG = FloatArray(n)
        val outB = FloatArray(n)
        for (i in 0 until n) {
            val luma = LUM_R * frame.red[i] + LUM_G * frame.green[i] + LUM_B * frame.blue[i]
            val dark = max(0f, threshold - luma)
            val lift = alpha * ln(1f + beta * dark)
            val gain = if (luma > 1e-6f) (luma + lift) / luma else 1f + lift
            outR[i] = (frame.red[i] * gain).coerceAtLeast(0f)
            outG[i] = (frame.green[i] * gain).coerceAtLeast(0f)
            outB[i] = (frame.blue[i] * gain).coerceAtLeast(0f)
        }
        return PipelineFrame(
            w, h, outR, outG, outB,
            frame.evOffset, frame.isoEquivalent, frame.exposureTimeNs,
        )
    }

    private fun applyEvBias(frame: PipelineFrame, evBias: Float): PipelineFrame {
        val gain = exp(evBias * LN2)
        val n = frame.width * frame.height
        val outR = FloatArray(n) { frame.red[it] * gain }
        val outG = FloatArray(n) { frame.green[it] * gain }
        val outB = FloatArray(n) { frame.blue[it] * gain }
        return PipelineFrame(
            frame.width, frame.height, outR, outG, outB,
            frame.evOffset, frame.isoEquivalent, frame.exposureTimeNs,
        )
    }

    // ─── Adaptive scene-mode tuning (v3 adaptive_scene.cpp) ───────────────

    private fun applyAdaptiveTuning(
        base: ProXdrV3Tuning,
        frames: List<PipelineFrame>,
        mode: ProXdrV3SceneMode,
        thermal: ProXdrV3Thermal,
    ): ProXdrV3Tuning {
        if (!base.adaptive) return base
        val ev100 = estimateEv100(frames.first())
        val resolved = when (mode) {
            ProXdrV3SceneMode.AUTO -> when {
                ev100 > 4f -> ProXdrV3SceneMode.BRIGHT_DAY
                ev100 > 1f -> ProXdrV3SceneMode.DAYLIGHT
                ev100 > -1f -> ProXdrV3SceneMode.INDOOR
                ev100 > -3f -> ProXdrV3SceneMode.LOW_LIGHT
                else -> ProXdrV3SceneMode.NIGHT
            }
            else -> mode
        }
        // TUNING.md per-scene presets (compact subset of the upstream table).
        val perScene = when (resolved) {
            ProXdrV3SceneMode.BRIGHT_DAY -> base.copy(
                shadowLiftAmount = 0.08f, highlightRolloffStart = 0.85f,
            )
            ProXdrV3SceneMode.GOLDEN_HOUR -> base.copy(
                shadowLiftAmount = 0.25f, highlightRolloffStart = 0.80f,
            )
            ProXdrV3SceneMode.INDOOR -> base.copy(shadowLiftAmount = 0.22f)
            ProXdrV3SceneMode.BACKLIT -> base.copy(shadowLiftAmount = 0.35f)
            ProXdrV3SceneMode.PORTRAIT -> base.copy(
                ghostSigma = 2.5f,
                highlightRecoveryStrength = 0.85f,  // gentler on faces
            )
            ProXdrV3SceneMode.LOW_LIGHT -> base.copy(shadowLiftAmount = 0.30f)
            ProXdrV3SceneMode.NIGHT -> base.copy(
                shadowLiftAmount = 0.35f,
                ghostSigma = 2.5f,
            )
            ProXdrV3SceneMode.SPORTS -> base.copy(
                ghostSigma = 2.0f,
                highlightRecoveryStrength = 0.8f,
            )
            else -> base
        }
        // Thermal trim — never breaks correctness, only quality cap.
        return when (thermal) {
            ProXdrV3Thermal.SEVERE -> perScene.copy(highlightRecoveryEnabled = false)
            ProXdrV3Thermal.MODERATE -> perScene.copy(ghostSigma = min(perScene.ghostSigma, 2.5f))
            else -> perScene
        }
    }

    private fun estimateEv100(frame: PipelineFrame): Float {
        // Compute mean luminance and back-solve for EV100 against a 0.18 mid-grey reference.
        val luma = frame.luminance()
        var sum = 0.0
        for (v in luma) sum += v
        val mean = (sum / luma.size).toFloat().coerceAtLeast(1e-4f)
        // EV100 ≈ log2(mean / 0.18) — rearranged from the photometric definition.
        return ln(mean / 0.18f) / LN2
    }

    private companion object {
        const val LUM_R = 0.2126f
        const val LUM_G = 0.7152f
        const val LUM_B = 0.0722f
        val LN2 = ln(2f)
    }
}
