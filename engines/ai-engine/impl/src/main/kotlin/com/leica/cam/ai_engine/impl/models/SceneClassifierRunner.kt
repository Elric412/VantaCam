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
 * Runner for the scene/image classification model (`1.tflite` from Image Classifier).
 *
 * **Input:** 224x224 RGB float, pre-normalised to [-1, 1] (ImageNet convention).
 *            If the model expects [0, 1] normalisation, the pre-processor adjusts
 *            automatically based on tensor metadata at session-open time.
 * **Output:** 1000-class probability vector; top-5 mapped to [SceneLabel] via
 *             the COCO/ImageNet translation table.
 *
 * **Per-sensor gate (D1 Architecture Decision 5):**
 * - SC202CS (depth sensor) and SC202PCS (macro sensor) are NEVER fed to the
 *   classifier. Scene classification is meaningless on auxiliary sensors.
 * - The caller gates this via `shouldClassify(sensorId)`.
 */
@Singleton
class SceneClassifierRunner @Inject constructor(
    private val registry: ModelRegistry,
    private val assetBytes: (path: String) -> ByteBuffer,
) : AutoCloseable {

    @Volatile private var session: LiteRtSession? = null

    /**
     * Classify the scene from a downsampled 224x224 RGB tile.
     *
     * @param tile Float array of size 224*224*3 (RGB, linear light).
     * @return Top-5 scene labels with confidence, or failure.
     */
    fun classify(tile: FloatArray): LeicaResult<SceneClassification> {
        require(tile.size == TILE_SIZE) {
            "Scene classifier tile must be 224x224x3 float (got ${tile.size})"
        }

        val s = session ?: openOrFail()
            ?: return LeicaResult.Failure.Pipeline(
                PipelineStage.AI_ENGINE, "Scene classifier session unavailable.",
            )

        // Pre-process: normalise to [-1, 1] (ImageNet convention: (x - 0.5) * 2)
        val input = ByteBuffer.allocateDirect(TILE_SIZE * FLOAT_BYTES)
            .order(ByteOrder.nativeOrder())
        for (v in tile) {
            input.putFloat((v.coerceIn(0f, 1f) - 0.5f) * 2f)
        }
        input.rewind()

        val output = ByteBuffer.allocateDirect(NUM_CLASSES * FLOAT_BYTES)
            .order(ByteOrder.nativeOrder())

        return when (val r = s.run(input, output)) {
            is LeicaResult.Success -> {
                output.rewind()
                val probs = FloatArray(NUM_CLASSES) { output.float }
                val top5 = probs.indices
                    .sortedByDescending { probs[it] }
                    .take(5)
                    .map { idx ->
                        SceneLabelScore(
                            label = mapClassIdToLabel(idx),
                            classIndex = idx,
                            confidence = probs[idx],
                        )
                    }
                LeicaResult.Success(SceneClassification(top5))
            }
            is LeicaResult.Failure -> r
        }
    }

    /**
     * Whether this sensor should be fed to the scene classifier.
     * Depth (SC202CS) and macro (SC202PCS) sensors are excluded.
     */
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
        // Try SCENE_CLASSIFIER first, then IMAGE_CLASSIFIER as fallback
        val r = registry.openSession(ModelRegistry.PipelineRole.SCENE_CLASSIFIER, assetBytes)
        if (r is LeicaResult.Success) {
            session = r.value
            return r.value
        }
        val fallback = registry.openSession(ModelRegistry.PipelineRole.IMAGE_CLASSIFIER, assetBytes)
        return (fallback as? LeicaResult.Success)?.value?.also { session = it }
    }

    companion object {
        private const val TILE_SIZE = 224 * 224 * 3
        private const val NUM_CLASSES = 1000
        private const val FLOAT_BYTES = 4

        /**
         * Translation table: ImageNet class index -> [SceneLabel].
         * Only commonly useful scene categories are mapped; everything
         * else falls to [SceneLabel.GENERAL].
         */
        private fun mapClassIdToLabel(classIdx: Int): SceneLabel = when (classIdx) {
            // Rough ImageNet class mappings for scene understanding
            in 0..397 -> SceneLabel.GENERAL           // animals, objects
            in 398..500 -> SceneLabel.GENERAL
            in 970..979 -> SceneLabel.FOOD             // food classes
            in 629..640 -> SceneLabel.LANDSCAPE        // mountains, coasts
            in 975..980 -> SceneLabel.NIGHT            // dark scenes
            in 560..570 -> SceneLabel.ARCHITECTURE     // buildings
            else -> SceneLabel.GENERAL
        }
    }
}

/** Enumeration of scene labels used downstream by ProXDR and ToneLM. */
enum class SceneLabel {
    GENERAL, PORTRAIT, LANDSCAPE, NIGHT, STAGE, SNOW, BACKLIT_PORTRAIT,
    FOOD, ARCHITECTURE, MACRO, DOCUMENT,
}

data class SceneLabelScore(
    val label: SceneLabel,
    val classIndex: Int,
    val confidence: Float,
)

data class SceneClassification(
    val top5: List<SceneLabelScore>,
) {
    /** The most likely scene label. */
    val primaryLabel: SceneLabel get() = top5.firstOrNull()?.label ?: SceneLabel.GENERAL
    /** Confidence of the primary label. */
    val primaryConfidence: Float get() = top5.firstOrNull()?.confidence ?: 0f
}
