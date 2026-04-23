package com.leica.cam.ai_engine.impl.models

import com.leica.cam.ai_engine.api.NeuralIspRefiner
import com.leica.cam.ai_engine.impl.registry.ModelRegistry
import com.leica.cam.ai_engine.impl.runtime.LiteRtSession
import com.leica.cam.common.result.LeicaResult
import com.leica.cam.common.result.PipelineStage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** Runner for the MicroISP neural refinement model. */
@Singleton
class MicroIspRunner @Inject constructor(
    private val registry: ModelRegistry,
    @Named("assetBytes") private val assetBytes: (path: String) -> ByteBuffer,
) : AutoCloseable, NeuralIspRefiner {

    @Volatile private var session: LiteRtSession? = null

    override fun refine(bayerTile: FloatArray): LeicaResult<FloatArray> {
        require(bayerTile.size == BAYER_TILE_SIZE) {
            "MicroISP Bayer tile must be 256x256x4 float (got ${bayerTile.size})"
        }

        val activeSession = session ?: openOrFail()
            ?: return LeicaResult.Failure.Pipeline(
                PipelineStage.AI_ENGINE,
                "MicroISP session unavailable.",
            )

        val input = ByteBuffer.allocateDirect(BAYER_TILE_SIZE * FLOAT_BYTES)
            .order(ByteOrder.nativeOrder())
        for (value in bayerTile) {
            input.putFloat(value)
        }
        input.rewind()

        val output = ByteBuffer.allocateDirect(BAYER_TILE_SIZE * FLOAT_BYTES)
            .order(ByteOrder.nativeOrder())

        return when (val runResult = activeSession.run(input, output)) {
            is LeicaResult.Success -> {
                output.rewind()
                LeicaResult.Success(FloatArray(BAYER_TILE_SIZE) { output.float })
            }
            is LeicaResult.Failure -> runResult
        }
    }

    override fun isEligible(sensorId: String): Boolean {
        val lower = sensorId.lowercase()
        return lower.contains("ov08d10") ||
            lower.contains("ov16a1q") ||
            lower.contains("gc16b3")
    }

    override fun close() {
        session?.close()
        session = null
    }

    @Synchronized
    private fun openOrFail(): LiteRtSession? {
        session?.let { return it }
        val result = registry.openSession(ModelRegistry.PipelineRole.MICRO_ISP, assetBytes)
        return (result as? LeicaResult.Success)?.value?.also { session = it }
    }

    private companion object {
        private const val BAYER_TILE_SIZE = 256 * 256 * 4
        private const val FLOAT_BYTES = 4
    }
}
