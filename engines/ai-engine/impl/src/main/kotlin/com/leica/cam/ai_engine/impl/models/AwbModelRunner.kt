package com.leica.cam.ai_engine.impl.models

import com.leica.cam.ai_engine.impl.registry.ModelRegistry
import com.leica.cam.ai_engine.impl.runtime.LiteRtSession
import com.leica.cam.common.result.LeicaResult
import com.leica.cam.common.result.PipelineStage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runner for the per-frame AWB neural model (`awb_final_full_integer_quant.tflite`).
 *
 * **Input:** 224x224 RGB float32 tile (already gamma-decoded to linear scene-referred).
 * **Output:** 3 floats = [CCT_kelvin, tint_duv, confidence].
 *
 * Per-sensor fine-tuning is applied at the pre-processing boundary,
 * NOT in the model: the raw tile is multiplied by [SensorProfile.wbBias]
 * before being handed to the network. This avoids re-training and keeps
 * the model sensor-agnostic.
 *
 * **Physics:** AWB expects **linear** scene-referred data. Downsample from
 * the already-linearised RAW, never from the gamma-encoded preview surface.
 *
 * **LUMO Law 5:** Skin anchor is computed first; the AWB model output
 * is blended with the skin-anchor CCT by [HyperToneWhiteBalanceEngine].
 * Zone gains are clamped to +/-300K of the skin anchor.
 */
@Singleton
class AwbModelRunner @Inject constructor(
    private val registry: ModelRegistry,
    private val assetBytes: (path: String) -> ByteBuffer,
) : AutoCloseable {

    @Volatile private var session: LiteRtSession? = null

    /**
     * Predict AWB parameters from a downsampled linear tile.
     *
     * @param tile   Float array of size 224*224*3 (RGB interleaved, linear light).
     * @param wbBias Per-sensor R/G/B gain bias from `SensorProfile.wbBias`.
     *               Applied as a pre-multiplier before inference.
     * @return [AwbPrediction] with CCT, tint, and confidence.
     */
    fun predict(
        tile: FloatArray,
        wbBias: FloatArray,
    ): LeicaResult<AwbPrediction> {
        require(tile.size == AWB_TILE_SIZE) {
            "AWB tile must be 224x224x3 float (got ${tile.size})"
        }
        require(wbBias.size == 3) { "wbBias must have 3 elements (R,G,B)" }

        val s = session ?: openOrFail()
            ?: return LeicaResult.Failure.Pipeline(
                PipelineStage.AI_ENGINE, "AWB session unavailable.",
            )

        // Pre-process: apply per-sensor bias in-place before feeding the network
        val input = ByteBuffer.allocateDirect(AWB_TILE_SIZE * FLOAT_BYTES)
            .order(ByteOrder.nativeOrder())
        for (i in tile.indices) {
            val channel = i % 3
            input.putFloat(tile[i] * wbBias[channel])
        }
        input.rewind()

        val output = ByteBuffer.allocateDirect(3 * FLOAT_BYTES)
            .order(ByteOrder.nativeOrder())

        return when (val r = s.run(input, output)) {
            is LeicaResult.Success -> {
                output.rewind()
                LeicaResult.Success(
                    AwbPrediction(
                        cctKelvin = output.float,
                        tintDuv = output.float,
                        confidence = output.float,
                    ),
                )
            }
            is LeicaResult.Failure -> r
        }
    }

    override fun close() {
        session?.close()
        session = null
    }

    @Synchronized
    private fun openOrFail(): LiteRtSession? {
        session?.let { return it }
        val r = registry.openSession(ModelRegistry.PipelineRole.AUTO_WHITE_BALANCE, assetBytes)
        return (r as? LeicaResult.Success)?.value?.also { session = it }
    }

    companion object {
        /** 224 * 224 * 3 RGB float values. */
        private const val AWB_TILE_SIZE = 224 * 224 * 3
        private const val FLOAT_BYTES = 4
    }
}

/**
 * AWB neural prediction output.
 * Blended with the skin-anchor CCT by [HyperToneWhiteBalanceEngine].
 */
data class AwbPrediction(
    /** Correlated colour temperature in Kelvin. */
    val cctKelvin: Float,
    /** Duv tint offset (green-magenta axis). */
    val tintDuv: Float,
    /** Model confidence in [0, 1]. Low confidence => fall back to grey-world. */
    val confidence: Float,
)
