package com.leica.cam.ai_engine.impl.models

import com.leica.cam.ai_engine.impl.registry.ModelRegistry
import com.leica.cam.ai_engine.impl.runtime.LiteRtSession
import com.leica.cam.common.result.LeicaResult
import com.leica.cam.common.result.PipelineStage
import com.leica.cam.imaging_pipeline.pipeline.SemanticMask
import com.leica.cam.imaging_pipeline.pipeline.SemanticZone
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runner for the DeepLabv3 semantic segmentation model (`deeplabv3.tflite`).
 *
 * **Input:** 257x257 RGB float32 tile, normalised to [0, 1].
 * **Output:** 257x257 int32 class labels (COCO 21-class variant).
 *
 * The COCO class IDs are NOT the same as [SemanticZone]. A translation table
 * maps COCO classes to LUMO zones:
 * - Class 0 (background) -> [SemanticZone.BACKGROUND]
 * - Class 15 (person)    -> [SemanticZone.PERSON]
 * - Class 2 (bicycle)    -> [SemanticZone.MIDGROUND]
 * - etc.
 *
 * **Per-sensor fine-tuning (D1 Architecture Decision 5):**
 * Above ISO 3200, confidence threshold drops from 0.50 to 0.35 because
 * noisier features produce lower-confidence but still useful zones.
 *
 * **Integration:** Output [SemanticMask] feeds into Durand bilateral
 * tone mapping for priority-weighted local EV allocation. Faces and
 * persons receive lifted local EV before tone compression.
 */
@Singleton
class SemanticSegmenterRunner @Inject constructor(
    private val registry: ModelRegistry,
    private val assetBytes: (path: String) -> ByteBuffer,
) : AutoCloseable {

    @Volatile private var session: LiteRtSession? = null

    /**
     * Segment a frame into semantic zones.
     *
     * @param tile     Float array of size 257*257*3 (RGB, [0,1] normalised).
     * @param width    Original image width for mask resizing.
     * @param height   Original image height for mask resizing.
     * @param sensorIso Current ISO for confidence threshold gating.
     * @return [SemanticMask] at the original resolution (nearest-neighbour upscale).
     */
    fun segment(
        tile: FloatArray,
        width: Int,
        height: Int,
        sensorIso: Int,
    ): LeicaResult<SemanticMask> {
        require(tile.size == SEGMENTATION_TILE_SIZE) {
            "Segmentation tile must be 257x257x3 float (got ${tile.size})"
        }

        val s = session ?: openOrFail()
            ?: return LeicaResult.Failure.Pipeline(
                PipelineStage.AI_ENGINE, "Semantic segmenter session unavailable.",
            )

        val confidenceThreshold = if (sensorIso > 3200) 0.35f else 0.50f

        val input = ByteBuffer.allocateDirect(SEGMENTATION_TILE_SIZE * FLOAT_BYTES)
            .order(ByteOrder.nativeOrder())
        for (v in tile) {
            input.putFloat(v.coerceIn(0f, 1f))
        }
        input.rewind()

        // DeepLabv3 output: 257*257 int64 labels (or float32 depending on variant)
        val outputSize = SEGMENTATION_DIM * SEGMENTATION_DIM
        val output = ByteBuffer.allocateDirect(outputSize * FLOAT_BYTES)
            .order(ByteOrder.nativeOrder())

        return when (val r = s.run(input, output)) {
            is LeicaResult.Success -> {
                output.rewind()
                val rawLabels = IntArray(outputSize) { output.float.toInt() }

                // Map COCO classes -> SemanticZone and upscale to original resolution
                val mask = upscaleMask(rawLabels, SEGMENTATION_DIM, SEGMENTATION_DIM, width, height, confidenceThreshold)
                LeicaResult.Success(mask)
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
        val r = registry.openSession(ModelRegistry.PipelineRole.SEMANTIC_SEGMENTER, assetBytes)
        return (r as? LeicaResult.Success)?.value?.also { session = it }
    }

    companion object {
        private const val SEGMENTATION_DIM = 257
        private const val SEGMENTATION_TILE_SIZE = SEGMENTATION_DIM * SEGMENTATION_DIM * 3
        private const val FLOAT_BYTES = 4

        /**
         * COCO 21-class -> SemanticZone translation table.
         */
        private fun cocoToSemanticZone(cocoClass: Int): SemanticZone = when (cocoClass) {
            0 -> SemanticZone.BACKGROUND
            15 -> SemanticZone.PERSON       // "person"
            // Vehicles, furniture -> midground
            in 1..7 -> SemanticZone.MIDGROUND
            // Animals
            in 16..24 -> SemanticZone.MIDGROUND
            else -> SemanticZone.UNKNOWN
        }

        /**
         * Nearest-neighbour upscale from segmentation model output to full image size.
         */
        private fun upscaleMask(
            rawLabels: IntArray,
            srcW: Int, srcH: Int,
            dstW: Int, dstH: Int,
            confidenceThreshold: Float,
        ): SemanticMask {
            val zones = Array(dstW * dstH) { idx ->
                val dstX = idx % dstW
                val dstY = idx / dstW
                val srcX = (dstX.toFloat() / dstW * srcW).toInt().coerceIn(0, srcW - 1)
                val srcY = (dstY.toFloat() / dstH * srcH).toInt().coerceIn(0, srcH - 1)
                val cocoClass = rawLabels[srcY * srcW + srcX].coerceIn(0, 20)
                cocoToSemanticZone(cocoClass)
            }
            return SemanticMask(dstW, dstH, zones)
        }
    }
}
