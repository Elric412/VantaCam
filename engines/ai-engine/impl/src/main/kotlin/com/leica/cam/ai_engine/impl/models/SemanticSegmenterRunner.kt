package com.leica.cam.ai_engine.impl.models

import com.leica.cam.ai_engine.api.SemanticSegmentationOutput
import com.leica.cam.ai_engine.api.SemanticSegmenter
import com.leica.cam.ai_engine.api.SemanticZoneCode
import com.leica.cam.ai_engine.impl.registry.ModelRegistry
import com.leica.cam.ai_engine.impl.runtime.LiteRtSession
import com.leica.cam.common.result.LeicaResult
import com.leica.cam.common.result.PipelineStage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** Runner for the DeepLabv3 semantic segmentation model. */
@Singleton
class SemanticSegmenterRunner @Inject constructor(
    private val registry: ModelRegistry,
    @Named("assetBytes") private val assetBytes: (path: String) -> ByteBuffer,
) : AutoCloseable, SemanticSegmenter {

    @Volatile private var session: LiteRtSession? = null
    @Volatile private var outputTypeName: String? = null

    override fun segment(
        tileRgb: FloatArray,
        originalWidth: Int,
        originalHeight: Int,
        sensorIso: Int,
    ): LeicaResult<SemanticSegmentationOutput> {
        require(tileRgb.size == SEGMENTATION_TILE_SIZE) {
            "Segmentation tile must be 257x257x3 float (got ${tileRgb.size})"
        }

        val activeSession = session ?: openOrFail()
            ?: return LeicaResult.Failure.Pipeline(
                PipelineStage.AI_ENGINE,
                "Semantic segmenter session unavailable.",
            )

        val confidenceThreshold = if (sensorIso > 3200) 0.35f else 0.50f
        val input = ByteBuffer.allocateDirect(SEGMENTATION_TILE_SIZE * FLOAT_BYTES)
            .order(ByteOrder.nativeOrder())
        for (value in tileRgb) {
            input.putFloat(value.coerceIn(0f, 1f))
        }
        input.rewind()

        val output = allocateOutputBuffer()
        return when (val runResult = activeSession.run(input, output)) {
            is LeicaResult.Success -> {
                output.rewind()
                val mask = when {
                    outputTypeName?.contains("INT64") == true -> upscaleDiscreteMask(
                        rawLabels = IntArray(PIXEL_COUNT) { output.long.toInt() },
                        originalWidth = originalWidth,
                        originalHeight = originalHeight,
                    )
                    else -> upscaleProbabilityMask(
                        output = output,
                        originalWidth = originalWidth,
                        originalHeight = originalHeight,
                        confidenceThreshold = confidenceThreshold,
                    )
                }
                LeicaResult.Success(mask)
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
        val result = registry.openSession(ModelRegistry.PipelineRole.SEMANTIC_SEGMENTER, assetBytes)
        return (result as? LeicaResult.Success)?.value?.also {
            session = it
            outputTypeName = it.outputTensorTypeName()?.uppercase()
        }
    }

    private fun allocateOutputBuffer(): ByteBuffer {
        val bytes = if (outputTypeName?.contains("INT64") == true) {
            PIXEL_COUNT * Long.SIZE_BYTES
        } else {
            PIXEL_COUNT * NUM_CLASSES * FLOAT_BYTES
        }
        return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
    }

    private fun upscaleDiscreteMask(
        rawLabels: IntArray,
        originalWidth: Int,
        originalHeight: Int,
    ): SemanticSegmentationOutput {
        val zoneCodes = IntArray(originalWidth * originalHeight) { index ->
            val dstX = index % originalWidth
            val dstY = index / originalWidth
            val srcX = (dstX.toFloat() / originalWidth * SEGMENTATION_DIM).toInt().coerceIn(0, SEGMENTATION_DIM - 1)
            val srcY = (dstY.toFloat() / originalHeight * SEGMENTATION_DIM).toInt().coerceIn(0, SEGMENTATION_DIM - 1)
            cocoToSemanticZone(rawLabels[srcY * SEGMENTATION_DIM + srcX]).ordinal
        }
        return SemanticSegmentationOutput(
            width = originalWidth,
            height = originalHeight,
            zoneCodes = zoneCodes,
        )
    }

    private fun upscaleProbabilityMask(
        output: ByteBuffer,
        originalWidth: Int,
        originalHeight: Int,
        confidenceThreshold: Float,
    ): SemanticSegmentationOutput {
        val perPixelCodes = IntArray(PIXEL_COUNT)
        for (pixelIndex in 0 until PIXEL_COUNT) {
            var bestClass = 0
            var bestProbability = Float.NEGATIVE_INFINITY
            for (classIndex in 0 until NUM_CLASSES) {
                val probability = output.float
                if (probability > bestProbability) {
                    bestProbability = probability
                    bestClass = classIndex
                }
            }
            perPixelCodes[pixelIndex] = if (bestProbability < confidenceThreshold) {
                SemanticZoneCode.UNKNOWN.ordinal
            } else {
                cocoToSemanticZone(bestClass).ordinal
            }
        }

        val zoneCodes = IntArray(originalWidth * originalHeight) { index ->
            val dstX = index % originalWidth
            val dstY = index / originalWidth
            val srcX = (dstX.toFloat() / originalWidth * SEGMENTATION_DIM).toInt().coerceIn(0, SEGMENTATION_DIM - 1)
            val srcY = (dstY.toFloat() / originalHeight * SEGMENTATION_DIM).toInt().coerceIn(0, SEGMENTATION_DIM - 1)
            perPixelCodes[srcY * SEGMENTATION_DIM + srcX]
        }
        return SemanticSegmentationOutput(
            width = originalWidth,
            height = originalHeight,
            zoneCodes = zoneCodes,
        )
    }

    private fun cocoToSemanticZone(cocoClass: Int): SemanticZoneCode = when (cocoClass.coerceIn(0, 20)) {
        0 -> SemanticZoneCode.BACKGROUND
        15 -> SemanticZoneCode.PERSON
        in 1..20 -> SemanticZoneCode.MIDGROUND
        else -> SemanticZoneCode.UNKNOWN
    }

    private companion object {
        private const val SEGMENTATION_DIM = 257
        private const val PIXEL_COUNT = SEGMENTATION_DIM * SEGMENTATION_DIM
        private const val NUM_CLASSES = 21
        private const val SEGMENTATION_TILE_SIZE = PIXEL_COUNT * 3
        private const val FLOAT_BYTES = 4
    }
}
