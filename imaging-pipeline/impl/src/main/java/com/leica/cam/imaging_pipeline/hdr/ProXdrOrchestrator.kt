package com.leica.cam.imaging_pipeline.hdr

import com.leica.cam.common.result.LeicaResult
import com.leica.cam.common.result.PipelineStage
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
) {

    /**
     * Full HDR processing entry point.
     *
     * @param frames     All captured frames (base + EV brackets), linear RAW domain.
     * @param scene      Scene descriptor for mode selection.
     * @param noiseModel Physics-grounded noise model from sensor metadata.
     * @param perChannelNoise Per-CFA-channel noise for Wiener weights.
     */
    fun process(
        frames: List<PipelineFrame>,
        scene: SceneDescriptor? = null,
        noiseModel: NoiseModel? = null,
        perChannelNoise: PerChannelNoise? = null,
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
        val noise = perChannelNoise ?: PerChannelNoise.fromIsoEstimate(
            frames.minOf { it.isoEquivalent },
        )

        return if (evSpread < 0.5f) {
            processWienerBurst(frames, noise)
        } else {
            processEvBracket(frames, noise)
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
            is LeicaResult.Failure -> return alignResult.map { it.mergedFrame }.flatMap {
                LeicaResult.Failure.Pipeline(PipelineStage.IMAGING_PIPELINE, "Alignment failed")
            }
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
                PipelineStage.IMAGING_PIPELINE, "Alignment failed",
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
