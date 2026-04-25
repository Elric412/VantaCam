package com.leica.cam.imaging_pipeline.pipeline

import com.leica.cam.color_science.api.IColorLM2Engine
import com.leica.cam.color_science.api.IlluminantHint
import com.leica.cam.color_science.api.SceneContext
import com.leica.cam.common.Logger
import com.leica.cam.common.result.LeicaResult
import com.leica.cam.common.result.PipelineStage
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Imaging-pipeline-side adapter for the cross-module color-science API.
 *
 * **Pipeline position:** Sits between [HyperToneWhiteBalanceEngine] (calibration)
 * and [DurandBilateralToneMappingEngine] + [CinematicSCurveEngine] (rendering).
 *
 * **Why this adapter exists here (not in `:color-science:impl`):**
 * The adapter joins three engine domains:
 *   - `:imaging-pipeline:impl` — owns the [PipelineFrame] type and scene context.
 *   - `:hypertone-wb:impl` — provides the estimated CCT via `dominantKelvin`.
 *   - `:color-science:api` — the cross-module interface ([IColorLM2Engine]).
 *
 * Only `:imaging-pipeline:impl` has the right knowledge to bridge these three.
 * Color-science itself does not know about [PipelineFrame].
 *
 * **Render order (sacred — do NOT reorder):**
 * ```
 * [HyperToneWB output]
 *   → ColorSciencePipelineStage          ← THIS STAGE
 *       per-hue HSL → vibrance/CAM → skin protection →
 *       per-zone CCM (DNG dual-illuminant) →
 *       3D LUT (65³ tetrahedral, ACEScg linear) →
 *       CIECAM02 CUSP gamut map (Display-P3 / sRGB) →
 *       film grain
 *   → DurandBilateralToneMappingEngine
 *   → CinematicSCurveEngine
 *   → LuminositySharpener
 * ```
 *
 * **Cross-module dependency rule:** This class depends on [IColorLM2Engine]
 * (from `:color-science:api`), **never** on any `:color-science:impl` class.
 * The impl module is DI-injected via [ColorScienceModule].
 *
 * @param colorEngine The [IColorLM2Engine] implementation, provided by
 *   [com.leica.cam.color_science.di.ColorScienceModule] via Hilt.
 */
@Singleton
class ColorSciencePipelineStage @Inject constructor(
    private val colorEngine: IColorLM2Engine,
) {

    /**
     * Apply the full ColorLM 2.0 color science pipeline to a [PipelineFrame].
     *
     * The frame is converted to a [com.leica.cam.photon_matrix.FusedPhotonBuffer]
     * for the cross-module API call, then the result is converted back to
     * [PipelineFrame] for continued processing in [ImagingPipeline].
     *
     * @param wbCorrected   Linear sensor RGB frame after HyperTone WB correction.
     * @param sceneLabel    Semantic scene label from SceneClassifier (e.g. "portrait").
     * @param estimatedKelvin Scene CCT estimated by HyperTone WB (used for dual-illuminant
     *                       matrix interpolation).
     * @param kelvinConfidence Confidence ∈ [0,1] of the CCT estimate.
     * @param isMixedLight  True if the scene contains multiple illuminants.
     * @param captureMode   Current capture mode string (e.g. "auto", "pro", "portrait").
     * @return Color-mapped [PipelineFrame] or a [LeicaResult.Failure] if the stage fails.
     */
    fun apply(
        wbCorrected: PipelineFrame,
        sceneLabel: String,
        estimatedKelvin: Float,
        kelvinConfidence: Float,
        isMixedLight: Boolean,
        captureMode: String,
    ): LeicaResult<PipelineFrame> {
        return try {
            val fused = wbCorrected.toFusedPhotonBuffer()
            val context = SceneContext(
                sceneLabel = sceneLabel,
                illuminantHint = IlluminantHint(
                    estimatedKelvin = estimatedKelvin,
                    confidence = kelvinConfidence,
                    isMixedLight = isMixedLight,
                ),
                captureMode = captureMode,
            )

            // IColorLM2Engine.mapColours is a suspend function; we bridge to
            // the synchronous imaging-pipeline flow using runBlocking.
            // This is safe here because ImagingPipeline.process() is already
            // called on a background thread by ImagingPipelineOrchestrator.
            val result = runBlocking { colorEngine.mapColours(fused, context) }

            when (result) {
                is LeicaResult.Success -> {
                    val mapped = result.value.underlying
                    LeicaResult.Success(mapped.toPipelineFrame(wbCorrected))
                }
                is LeicaResult.Failure -> {
                    Logger.e(
                        TAG,
                        "ColorLM2 stage returned failure: ${result.failureMessage()}",
                    )
                    // Graceful degradation: return the un-color-mapped WB output
                    // so downstream tone-mapping can still produce a usable image.
                    // The failure is logged but does not abort the capture.
                    LeicaResult.Success(wbCorrected)
                }
            }
        } catch (t: Throwable) {
            Logger.e(TAG, "ColorSciencePipelineStage threw an unhandled exception", throwable = t)
            // Graceful degradation: pass through the WB-corrected frame unchanged.
            LeicaResult.Success(wbCorrected)
        }
    }

    /**
     * Convert [PipelineFrame] to [com.leica.cam.photon_matrix.FusedPhotonBuffer].
     *
     * Encodes float channels [0,1] as 16-bit shorts. The FusedPhotonBuffer metadata
     * (fusionQuality = 1.0, frameCount = 1, motionMagnitude = 0) is nominal for the
     * color-science stage — the actual values are not used by the color engine.
     */
    private fun PipelineFrame.toFusedPhotonBuffer(): com.leica.cam.photon_matrix.FusedPhotonBuffer {
        val size = width * height
        val scale = 65535f
        val rShorts = ShortArray(size) { i -> (red[i].coerceIn(0f, 1f) * scale).toInt().toShort() }
        val gShorts = ShortArray(size) { i -> (green[i].coerceIn(0f, 1f) * scale).toInt().toShort() }
        val bShorts = ShortArray(size) { i -> (blue[i].coerceIn(0f, 1f) * scale).toInt().toShort() }

        val photonBuffer = com.leica.cam.hardware.contracts.photon.PhotonBuffer.create16Bit(
            width = width,
            height = height,
            planes = listOf(rShorts, gShorts, bShorts),
        )
        return com.leica.cam.photon_matrix.FusedPhotonBuffer(
            underlying = photonBuffer,
            fusionQuality = 1.0f,
            frameCount = 1,
            motionMagnitude = 0f,
        )
    }

    /**
     * Convert a [com.leica.cam.photon_matrix.FusedPhotonBuffer] back to [PipelineFrame].
     *
     * Preserves the [PipelineFrame] metadata (EV offset, ISO, exposure time) from
     * [template] so downstream stages have accurate sensor metadata.
     */
    private fun com.leica.cam.photon_matrix.FusedPhotonBuffer.toPipelineFrame(
        template: PipelineFrame,
    ): PipelineFrame {
        val buf = this.underlying
        val size = buf.width * buf.height
        val scale = 65535f

        val rPlane = buf.planeView(0)
        val gPlane = buf.planeView(1)
        val bPlane = buf.planeView(2)

        val r = FloatArray(size) { i -> (rPlane.get(i).toInt() and 0xFFFF) / scale }
        val g = FloatArray(size) { i -> (gPlane.get(i).toInt() and 0xFFFF) / scale }
        val b = FloatArray(size) { i -> (bPlane.get(i).toInt() and 0xFFFF) / scale }

        return PipelineFrame(
            width = buf.width,
            height = buf.height,
            red = r,
            green = g,
            blue = b,
            evOffset = template.evOffset,
            isoEquivalent = template.isoEquivalent,
            exposureTimeNs = template.exposureTimeNs,
        )
    }

    /** Extract a user-readable failure message from any [LeicaResult.Failure]. */
    private fun LeicaResult.Failure.failureMessage(): String =
        when (this) {
            is LeicaResult.Failure.Pipeline -> "Pipeline[$stage]: $message"
            else -> this.toString()
        }

    private companion object {
        private const val TAG = "ColorSciencePipelineStage"
    }
}
