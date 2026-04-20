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
 * Runner for the MicroISP neural refinement model (`MicroISP_V4_fp16.tflite`).
 *
 * **Input:** 256x256x4 Bayer tile (R, Gr, Gb, B) in float16 representation
 *            stored as float32 in the input buffer (the model is fp16 internally).
 * **Output:** 256x256x4 refined Bayer tile.
 *
 * **Per-sensor eligibility (D1 Architecture Decision 5):**
 * - ONLY runs on ultra-wide (`OV08D10`) and front cameras (`OV16A1Q`, `GC16B3`)
 *   where the vendor ISP leaves room for a neural refiner.
 * - DISABLED on Samsung S5KHM6 (main camera): the pre-applied Imagiq ISP is
 *   already high-quality; running MicroISP on top causes double-processing
 *   artefacts (over-sharpening, halo around high-contrast edges).
 *
 * **LUMO Law 2:** Float16 precision throughout -- never 8-bit intermediates.
 * The model internally processes in fp16; we feed float32 buffers that the
 * delegate auto-converts.
 *
 * **Integration:** Called in `ImagingPipeline.process` at Stage 3.5, AFTER
 * shadow denoising and BEFORE tone mapping. Gated by `SensorProfile.microIspEligible`.
 */
@Singleton
class MicroIspRunner @Inject constructor(
    private val registry: ModelRegistry,
    private val assetBytes: (path: String) -> ByteBuffer,
) : AutoCloseable {

    @Volatile private var session: LiteRtSession? = null

    /**
     * Refine a Bayer tile via neural ISP.
     *
     * @param bayerTile Float array of size 256*256*4 (R, Gr, Gb, B planes).
     * @return Refined Bayer tile of the same dimensions.
     */
    fun refine(bayerTile: FloatArray): LeicaResult<FloatArray> {
        require(bayerTile.size == BAYER_TILE_SIZE) {
            "MicroISP Bayer tile must be 256x256x4 float (got ${bayerTile.size})"
        }

        val s = session ?: openOrFail()
            ?: return LeicaResult.Failure.Pipeline(
                PipelineStage.AI_ENGINE, "MicroISP session unavailable.",
            )

        val input = ByteBuffer.allocateDirect(BAYER_TILE_SIZE * FLOAT_BYTES)
            .order(ByteOrder.nativeOrder())
        for (v in bayerTile) {
            input.putFloat(v)
        }
        input.rewind()

        val output = ByteBuffer.allocateDirect(BAYER_TILE_SIZE * FLOAT_BYTES)
            .order(ByteOrder.nativeOrder())

        return when (val r = s.run(input, output)) {
            is LeicaResult.Success -> {
                output.rewind()
                val result = FloatArray(BAYER_TILE_SIZE) { output.float }
                LeicaResult.Success(result)
            }
            is LeicaResult.Failure -> r
        }
    }

    /**
     * Whether this sensor is eligible for MicroISP refinement.
     *
     * Eligible: OV08D10 (ultra-wide), OV16A1Q (front primary), GC16B3 (front secondary).
     * NOT eligible: S5KHM6 (main Samsung -- Imagiq ISP already high-quality),
     *               OV64B40, OV50D40, SC202CS, SC202PCS.
     */
    fun isEligible(sensorId: String): Boolean {
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
        val r = registry.openSession(ModelRegistry.PipelineRole.MICRO_ISP, assetBytes)
        return (r as? LeicaResult.Success)?.value?.also { session = it }
    }

    companion object {
        /** 256 * 256 * 4 (R, Gr, Gb, B) float values. */
        private const val BAYER_TILE_SIZE = 256 * 256 * 4
        private const val FLOAT_BYTES = 4
    }
}
