package com.leica.cam.imaging_pipeline.hdr

import com.leica.cam.common.ThermalState
import com.leica.cam.common.result.LeicaResult
import com.leica.cam.common.result.PipelineStage
import com.leica.cam.imaging_pipeline.api.UserHdrMode
import com.leica.cam.imaging_pipeline.hdr.proxdrv3.ProXdrV3Engine
import com.leica.cam.imaging_pipeline.hdr.proxdrv3.ProXdrV3SceneMode
import com.leica.cam.imaging_pipeline.hdr.proxdrv3.ProXdrV3Thermal
import com.leica.cam.imaging_pipeline.pipeline.HdrMergeMode
import com.leica.cam.imaging_pipeline.pipeline.HdrMergeResult
import com.leica.cam.imaging_pipeline.pipeline.NoiseModel
import com.leica.cam.imaging_pipeline.pipeline.PipelineFrame
import kotlin.math.abs

/**
 * ProXDR -- the rebuilt HDR capture and processing orchestrator.
 *
 * Replaces the monolithic `ProXdrHdrEngine.ProXdrHdrOrchestrator` with a
 * clean delegation to the new HDR sub-components:
 *
 * 1. **Pre-alignment ghost detection** via [GhostMaskEngine] (D2.3 fix:
 *    MTB now runs BEFORE alignment, not after).
 * 2. **Dense optical flow alignment** via [DeformableFeatureAligner] (D2.4:
 *    replaces translation-only SAD search).
 * 3. **Per-channel Wiener merge** via [RadianceMerger] (D2.5: fixes the
 *    luminance-only sigma^2 bug).
 * 4. **Highlight reconstruction** via [HighlightReconstructor].
 * 5. **Shadow restoration** via [ShadowRestorer] (D2.5 fix: evScale applied
 *    correctly inside lerp, not before clip check).
 * 6. **Mertens fallback** via [MertensFallback] (D2.2: real Laplacian pyramid).
 *
 * **Thermal policy:** SEVERE -> single frame only.
 */
class ProXdrOrchestrator(
    private val ghostMaskEngine: GhostMaskEngine = GhostMaskEngine(),
    private val aligner: DeformableFeatureAligner = DeformableFeatureAligner(),
    private val merger: RadianceMerger = RadianceMerger(),
    private val highlightReconstructor: HighlightReconstructor = HighlightReconstructor(),
    private val shadowRestorer: ShadowRestorer = ShadowRestorer(),
    private val mertensFallback: MertensFallback = MertensFallback(),
    /**
     * ProXDR v3 — adaptive AI-powered HDR engine. When the user selects
     * [UserHdrMode.PRO_XDR_V3] (or when the smart picker decides v3 is the
     * right path), the orchestrator routes to this engine instead of the
     * legacy [merger] / [highlightReconstructor] / [shadowRestorer] chain.
     *
     * See `engines/imaging-pipeline/impl/.../hdr/proxdrv3/ProXdrV3Engine.kt`
     * and the upstream docs in
     * `platform-android/native-imaging-core/impl/src/main/cpp/proxdr/docs/`.
     */
    private val proXdrV3Engine: ProXdrV3Engine = ProXdrV3Engine(),
) {

    /**
     * Full HDR processing entry point.
     *
     * @param frames All captured frames (base + EV brackets), linear RAW domain.
     * @param scene Scene descriptor for mode selection.
     * @param noiseModel Physics-grounded noise model from sensor metadata.
     * @param perChannelNoise Per-CFA-channel noise for Wiener weights.
     */
    fun process(
        frames: List<PipelineFrame>,
        scene: SceneDescriptor? = null,
        noiseModel: NoiseModel? = null,
        perChannelNoise: PerChannelNoise? = null,
        userHdrMode: UserHdrMode = UserHdrMode.SMART,
    ): LeicaResult<HdrMergeResult> {
        if (frames.isEmpty()) {
            return LeicaResult.Failure.Pipeline(PipelineStage.IMAGING_PIPELINE, "No frames for HDR")
        }
        if (frames.size == 1) return singleFrameResult(frames.first())
        if (userHdrMode == UserHdrMode.OFF || isThermalSevere(scene)) {
            return singleFrameResult(frames.first())
        }
        // ProXDR v3 path — bypasses the legacy merge stack entirely. The v3
        // engine internally orchestrates SAFNet-lite Wiener fusion, soft-knee
        // spectral highlight recovery, log-lift shadow restoration, and (on
        // native) Mertens-on-linear-HDR.
        if (userHdrMode == UserHdrMode.PRO_XDR_V3) {
            return processProXdrV3(frames, scene)
        }
        val noise = perChannelNoise ?: PerChannelNoise.fromIsoEstimate(frames.minOf { it.isoEquivalent })
        return when (HdrModePicker.pickWithUserOverride(buildMetadata(frames, scene), userHdrMode)) {
            HdrMergeMode.SINGLE_FRAME -> singleFrameResult(frames.first())
            HdrMergeMode.WIENER_BURST -> processWienerBurst(frames, noise)
            HdrMergeMode.DEBEVEC_LINEAR -> processEvBracket(frames, noise)
            HdrMergeMode.MERTENS_FUSION -> processMertensFusion(frames)
        }
    }

    /**
     * Drive ProXDR v3 with scene + thermal context translated from the
     * existing `SceneDescriptor`. The v3 engine returns its own
     * [HdrMergeResult] so we forward it directly.
     */
    private fun processProXdrV3(
        frames: List<PipelineFrame>,
        scene: SceneDescriptor?,
    ): LeicaResult<HdrMergeResult> {
        val sceneMode = scene?.sceneCategory?.toV3() ?: ProXdrV3SceneMode.AUTO
        val thermalLevel = scene?.thermalLevel ?: 0
        val thermal = when {
            thermalLevel >= 4 -> ProXdrV3Thermal.CRITICAL
            thermalLevel >= 3 -> ProXdrV3Thermal.SEVERE
            thermalLevel >= 2 -> ProXdrV3Thermal.MODERATE
            thermalLevel >= 1 -> ProXdrV3Thermal.LIGHT
            else -> ProXdrV3Thermal.NORMAL
        }
        return proXdrV3Engine.process(
            frames = frames,
            sceneMode = sceneMode,
            thermal = thermal,
            userBias = 0f,
        )
    }

    private fun SceneCategory.toV3(): ProXdrV3SceneMode = when (this) {
        SceneCategory.PORTRAIT -> ProXdrV3SceneMode.PORTRAIT
        SceneCategory.LANDSCAPE -> ProXdrV3SceneMode.DAYLIGHT
        SceneCategory.NIGHT -> ProXdrV3SceneMode.NIGHT
        SceneCategory.STAGE -> ProXdrV3SceneMode.LOW_LIGHT
        SceneCategory.SNOW -> ProXdrV3SceneMode.BRIGHT_DAY
        SceneCategory.BACKLIT_PORTRAIT -> ProXdrV3SceneMode.BACKLIT
        SceneCategory.GENERAL -> ProXdrV3SceneMode.AUTO
    }

    private fun buildMetadata(
        frames: List<PipelineFrame>,
        scene: SceneDescriptor?,
    ): HdrFrameSetMetadata = HdrFrameSetMetadata(
        evSpread = frames.evSpread(),
        // The current simplified PipelineFrame model does not expose clip or RAW-path metadata.
        // Preserve legacy behaviour by keeping those signals neutral here.
        allFramesClipped = false,
        rawPathUnavailable = false,
        thermalSevere = isThermalSevere(scene),
    )

    private fun isThermalSevere(scene: SceneDescriptor?): Boolean =
        ThermalState.fromOrdinal(scene?.thermalLevel ?: 0) >= ThermalState.MULTI_FRAME_CUTOFF

    private fun singleFrameResult(frame: PipelineFrame): LeicaResult<HdrMergeResult> =
        LeicaResult.Success(
            HdrMergeResult(
                mergedFrame = frame,
                ghostMask = FloatArray(frame.width * frame.height),
                hdrMode = HdrMergeMode.SINGLE_FRAME,
            ),
        )

    private fun processMertensFusion(frames: List<PipelineFrame>): LeicaResult<HdrMergeResult> {
        return mertensFallback.fuse(frames).map { mergedFrame ->
            HdrMergeResult(
                mergedFrame = mergedFrame,
                ghostMask = FloatArray(frames.first().width * frames.first().height),
                hdrMode = HdrMergeMode.MERTENS_FUSION,
            )
        }
    }

    private fun processWienerBurst(
        frames: List<PipelineFrame>,
        noise: PerChannelNoise,
    ): LeicaResult<HdrMergeResult> {
        val reference = frames[0]
        val alternates = frames.drop(1)

        // D2.3: Ghost mask BEFORE alignment
        val ghostMask = if (alternates.isNotEmpty()) {
            ghostMaskEngine.computeSoftMask(reference, alternates)
        } else null

        // D2.4: Dense optical flow alignment (replaces translation-only SAD)
        val alignResult = aligner.align(reference, alternates, ghostMask)
        val aligned = when (alignResult) {
            is LeicaResult.Success -> alignResult.value.alignedFrames
            is LeicaResult.Failure -> return LeicaResult.Failure.Pipeline(
                PipelineStage.IMAGING_PIPELINE,
                "Alignment failed",
            )
        }

        // D2.5: Per-channel Wiener merge
        return merger.mergeWienerBurst(aligned, noise, ghostMask)
    }

    private fun processEvBracket(
        frames: List<PipelineFrame>,
        noise: PerChannelNoise,
    ): LeicaResult<HdrMergeResult> {
        val base = frames.minByOrNull { abs(it.evOffset) } ?: frames[0]
        val alternates = frames.filter { it !== base }

        // D2.3: Pre-alignment ghost mask
        val ghostMask = if (alternates.isNotEmpty()) {
            ghostMaskEngine.computeSoftMask(base, alternates)
        } else null

        // D2.4: Dense optical flow alignment
        val alignResult = aligner.align(base, alternates, ghostMask)
        val alignedAll = when (alignResult) {
            is LeicaResult.Success -> alignResult.value.alignedFrames
            is LeicaResult.Failure -> return LeicaResult.Failure.Pipeline(
                PipelineStage.IMAGING_PIPELINE,
                "Alignment failed",
            )
        }

        val alignedBase = alignedAll[0]
        val alignedOthers = alignedAll.drop(1)
        val darkFrames = alignedOthers.filter { it.evOffset < -0.4f }.sortedBy { it.evOffset }
        val brightFrames = alignedOthers.filter { it.evOffset > 0.4f }.sortedByDescending { it.evOffset }

        // D2.5: Debevec linear merge with per-channel noise
        val mergeResult = merger.mergeDebevecLinear(alignedAll, ghostMask)
        val merged = when (mergeResult) {
            is LeicaResult.Success -> mergeResult.value
            is LeicaResult.Failure -> return mergeResult
        }

        // Highlight reconstruction from darkest frame
        var result = merged.mergedFrame
        if (darkFrames.isNotEmpty()) {
            val darkest = darkFrames.first()
            val evDelta = darkest.evOffset - alignedBase.evOffset
            result = highlightReconstructor.reconstruct(result, darkest, evDelta)
        }

        // Shadow detail restoration from brightest frame
        if (brightFrames.isNotEmpty()) {
            val brightest = brightFrames.first()
            val evDelta = brightest.evOffset - alignedBase.evOffset
            result = shadowRestorer.restore(result, brightest, evDelta)
        }

        return LeicaResult.Success(
            HdrMergeResult(
                mergedFrame = result,
                ghostMask = merged.ghostMask,
                hdrMode = HdrMergeMode.DEBEVEC_LINEAR,
            ),
        )
    }
}

private fun List<PipelineFrame>.evSpread(): Float =
    maxOf { it.evOffset } - minOf { it.evOffset }
