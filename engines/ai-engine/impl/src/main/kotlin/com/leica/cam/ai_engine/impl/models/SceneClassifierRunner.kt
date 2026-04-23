package com.leica.cam.ai_engine.impl.models

import com.leica.cam.ai_engine.api.SceneClassificationOutput
import com.leica.cam.ai_engine.api.SceneClassifier
import com.leica.cam.ai_engine.api.SceneLabel
import com.leica.cam.ai_engine.impl.registry.ModelRegistry
import com.leica.cam.ai_engine.impl.runtime.LiteRtSession
import com.leica.cam.common.result.LeicaResult
import com.leica.cam.common.result.PipelineStage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** Runner for the scene/image classification model. */
@Singleton
class SceneClassifierRunner @Inject constructor(
    private val registry: ModelRegistry,
    @Named("assetBytes") private val assetBytes: (path: String) -> ByteBuffer,
) : AutoCloseable, SceneClassifier {

    @Volatile private var session: LiteRtSession? = null

    override fun classify(tileRgb: FloatArray): LeicaResult<SceneClassificationOutput> {
        require(tileRgb.size == TILE_SIZE) {
            "Scene classifier tile must be 224x224x3 float (got ${tileRgb.size})"
        }

        val activeSession = session ?: openOrFail()
            ?: return LeicaResult.Failure.Pipeline(
                PipelineStage.AI_ENGINE,
                "Scene classifier session unavailable.",
            )

        val input = ByteBuffer.allocateDirect(TILE_SIZE * FLOAT_BYTES)
            .order(ByteOrder.nativeOrder())
        for (value in tileRgb) {
            input.putFloat((value.coerceIn(0f, 1f) - 0.5f) * 2f)
        }
        input.rewind()

        val output = ByteBuffer.allocateDirect(NUM_CLASSES * FLOAT_BYTES)
            .order(ByteOrder.nativeOrder())

        return when (val runResult = activeSession.run(input, output)) {
            is LeicaResult.Success -> {
                output.rewind()
                val probabilities = FloatArray(NUM_CLASSES) { output.float }
                val top5 = probabilities.indices
                    .sortedByDescending { probabilities[it] }
                    .take(5)
                    .map { classIndex ->
                        mapClassIdToLabel(classIndex) to probabilities[classIndex]
                    }
                LeicaResult.Success(
                    SceneClassificationOutput(
                        primaryLabel = top5.firstOrNull()?.first ?: SceneLabel.GENERAL,
                        primaryConfidence = top5.firstOrNull()?.second ?: 0f,
                        top5 = top5,
                    ),
                )
            }
            is LeicaResult.Failure -> runResult
        }
    }

    fun shouldClassify(sensorId: String): Boolean {
        val lower = sensorId.lowercase()
        return !lower.contains("sc202cs") && !lower.contains("sc202pcs")
    }

    override fun close() {
        session?.close()
        session = null
    }

    @Synchronized
    private fun openOrFail(): LiteRtSession? {
        session?.let { return it }
        val primary = registry.openSession(ModelRegistry.PipelineRole.SCENE_CLASSIFIER, assetBytes)
        if (primary is LeicaResult.Success) {
            session = primary.value
            return primary.value
        }
        val fallback = registry.openSession(ModelRegistry.PipelineRole.IMAGE_CLASSIFIER, assetBytes)
        return (fallback as? LeicaResult.Success)?.value?.also { session = it }
    }

    private fun mapClassIdToLabel(classIndex: Int): SceneLabel = when (classIndex) {
        in 924..950 -> SceneLabel.FOOD
        in 629..640 -> SceneLabel.LANDSCAPE
        in 560..570 -> SceneLabel.ARCHITECTURE
        981, 982, 983 -> SceneLabel.NIGHT
        else -> SceneLabel.GENERAL
    }

    private companion object {
        private const val TILE_SIZE = 224 * 224 * 3
        private const val NUM_CLASSES = 1000
        private const val FLOAT_BYTES = 4
    }
}
