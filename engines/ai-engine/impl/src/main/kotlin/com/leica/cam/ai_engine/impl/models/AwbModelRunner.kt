package com.leica.cam.ai_engine.impl.models

import com.leica.cam.ai_engine.api.AwbNeuralPrior
import com.leica.cam.ai_engine.api.AwbPredictor
import com.leica.cam.ai_engine.impl.registry.ModelRegistry
import com.leica.cam.ai_engine.impl.runtime.LiteRtSession
import com.leica.cam.common.result.LeicaResult
import com.leica.cam.common.result.PipelineStage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Runner for the per-frame AWB neural model (`awb_final_full_integer_quant.tflite`).
 */
@Singleton
class AwbModelRunner @Inject constructor(
    private val registry: ModelRegistry,
    @Named("assetBytes") private val assetBytes: (path: String) -> ByteBuffer,
) : AutoCloseable, AwbPredictor {

    @Volatile private var session: LiteRtSession? = null

    override fun predict(
        tileRgb: FloatArray,
        sensorWbBias: FloatArray,
    ): LeicaResult<AwbNeuralPrior> {
        require(tileRgb.size == AWB_TILE_SIZE) {
            "AWB tile must be 224x224x3 float (got ${tileRgb.size})"
        }
        require(sensorWbBias.size == 3) { "sensorWbBias must have 3 elements (R,G,B)" }

        val activeSession = session ?: openOrFail()
            ?: return LeicaResult.Failure.Pipeline(
                PipelineStage.AI_ENGINE,
                "AWB session unavailable.",
            )

        val input = ByteBuffer.allocateDirect(AWB_TILE_SIZE * FLOAT_BYTES)
            .order(ByteOrder.nativeOrder())
        for (value in tileRgb) {
            input.putFloat(value)
        }
        input.rewind()

        val output = ByteBuffer.allocateDirect(3 * FLOAT_BYTES)
            .order(ByteOrder.nativeOrder())

        return when (val runResult = activeSession.run(input, output)) {
            is LeicaResult.Success -> {
                output.rewind()
                LeicaResult.Success(
                    AwbNeuralPrior(
                        cctKelvin = output.float,
                        tintDuv = output.float,
                        confidence = output.float,
                    ),
                )
            }
            is LeicaResult.Failure -> runResult
        }
    }

    override fun close() {
        session?.close()
        session = null
    }

    @Synchronized
    private fun openOrFail(): LiteRtSession? {
        session?.let { return it }
        val result = registry.openSession(ModelRegistry.PipelineRole.AUTO_WHITE_BALANCE, assetBytes)
        return (result as? LeicaResult.Success)?.value?.also { session = it }
    }

    private companion object {
        private const val AWB_TILE_SIZE = 224 * 224 * 3
        private const val FLOAT_BYTES = 4
    }
}
