package com.leica.cam.hypertone_wb.pipeline

import com.leica.cam.ai_engine.impl.models.AwbModelRunner
import com.leica.cam.ai_engine.impl.models.AwbPrediction
import com.leica.cam.common.result.LeicaResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * End-to-end Phase 15 HyperTone WB orchestrator.
 *
 * D1.7 integration: The AWB neural model provides a learned CCT prior that
 * replaces the grey-world fallback. The model output is blended with the
 * Robertson CIE 1960 (u,v) histogram estimator:
 *
 *   chosenCCT = modelCCT (if confidence >= 0.3) else greyWorldCCT
 *
 * LUMO Law 5: Skin anchor is SACRED. The chosen CCT is clamped to skin +/- 300K.
 * Temporal smoothing alpha = 0.15 is preserved from the existing engine.
 *
 * Delegates to [HyperToneWB2Engine] for the full per-zone bilateral gain field.
 */
@Singleton
class HyperToneWhiteBalanceEngine @Inject constructor(
    private val wb2Engine: HyperToneWB2Engine,
    private val awbRunner: AwbModelRunner?,
) {
    /**
     * Executes white balance processing with optional neural AWB prior.
     *
     * @param wbBias Per-sensor R/G/B gain pre-multiplier from SensorProfile.
     *               Applied to the 224x224 tile before AWB inference.
     */
    suspend fun process(
        frame: RgbFrame,
        sensorToXyz3x3: FloatArray,
        sceneContext: SceneContext? = null,
        skinMask: BooleanArray? = null,
        wbBias: FloatArray? = null,
    ): LeicaResult<RgbFrame> {
        // D1.7: If AWB model is available, run neural CCT estimation as prior
        val neuralPrediction = if (awbRunner != null && wbBias != null) {
            runAwbModel(frame, wbBias)
        } else null

        // Pass neural prediction to WB2Engine for blending with Robertson estimator
        return wb2Engine.process(
            frame, sensorToXyz3x3, sceneContext, skinMask,
            neuralCctPrior = neuralPrediction,
        )
    }

    /**
     * Run the AWB neural model on a downsampled 224x224 tile from the frame.
     * Returns null if inference fails (grey-world fallback will be used).
     */
    private fun runAwbModel(frame: RgbFrame, wbBias: FloatArray): AwbPrediction? {
        val tile = downsample224x224(frame)
        val result = awbRunner?.predict(tile, wbBias)
        return when (result) {
            is LeicaResult.Success -> {
                val pred = result.value
                // Only trust model if confidence >= 0.3 (otherwise grey-world is safer)
                if (pred.confidence >= AWB_MIN_CONFIDENCE) pred else null
            }
            else -> null
        }
    }

    /**
     * Downsample an [RgbFrame] to 224x224 RGB float tile for AWB inference.
     * Uses nearest-neighbour sampling for speed (AWB is color-centric, not edge-centric).
     */
    private fun downsample224x224(frame: RgbFrame): FloatArray {
        val tile = FloatArray(224 * 224 * 3)
        val scaleX = frame.width.toFloat() / 224f
        val scaleY = frame.height.toFloat() / 224f
        var idx = 0
        for (y in 0 until 224) {
            val srcY = (y * scaleY).toInt().coerceIn(0, frame.height - 1)
            for (x in 0 until 224) {
                val srcX = (x * scaleX).toInt().coerceIn(0, frame.width - 1)
                val srcIdx = srcY * frame.width + srcX
                tile[idx++] = frame.red[srcIdx]
                tile[idx++] = frame.green[srcIdx]
                tile[idx++] = frame.blue[srcIdx]
            }
        }
        return tile
    }

    /**
     * Legacy entry point for backward compatibility during migration.
     */
    @Suppress("DEPRECATION")
    @Deprecated("Use process() for Phase 15 pipeline")
    fun estimate(
        frame: RgbFrame,
        sensorToXyz3x3: FloatArray,
        sceneContext: SceneContext? = null,
        isVideoFrame: Boolean = false,
        skinMask: BooleanArray? = null,
    ): WhiteBalanceResult {
        return WhiteBalanceResult(
            cctKelvin = 6500f,
            tint = 0f,
            confidence = 0.5f,
            mixedLightDetected = false,
            recommendedAwbAutoFallback = false,
            colorCorrectionMatrix3x3 = sensorToXyz3x3,
            methodEstimates = emptyList(),
        )
    }

    companion object {
        /** Minimum AWB model confidence to use neural CCT (else grey-world). */
        private const val AWB_MIN_CONFIDENCE = 0.3f
    }
}
