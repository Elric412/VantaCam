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
    @Named("assetBytes") private val assetBytes: @JvmSuppressWildcards (path: String) -> ByteBuffer,
) : AutoCloseable, SceneClassifier {

    @Volatile private var session: LiteRtSession? = null
    /**
     * Normalisation style detected at session-open time from the model's tensor metadata.
     * MINUS_ONE_TO_ONE → (x - 0.5) * 2  (MobileNetV1-style [-1,1])
     * ZERO_TO_ONE      → x as-is         (MobileNetV2-style [0,1])
     */
    @Volatile private var normStyle: NormStyle = NormStyle.MINUS_ONE_TO_ONE

    private enum class NormStyle { MINUS_ONE_TO_ONE, ZERO_TO_ONE }

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
        val style = normStyle
        for (value in tileRgb) {
            val normalized = when (style) {
                NormStyle.MINUS_ONE_TO_ONE -> (value.coerceIn(0f, 1f) - 0.5f) * 2f
                NormStyle.ZERO_TO_ONE -> value.coerceIn(0f, 1f)
            }
            input.putFloat(normalized)
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
            normStyle = detectNormStyle(primary.value)
            return primary.value
        }
        val fallback = registry.openSession(ModelRegistry.PipelineRole.IMAGE_CLASSIFIER, assetBytes)
        return (fallback as? LeicaResult.Success)?.value?.also {
            session = it
            normStyle = detectNormStyle(it)
        }
    }

    /**
     * Inspect the model's input quantisation parameters to decide normalisation.
     *
     * If the tensor has a non-trivial scale (~0.003922 = 1/255) it expects [0,1]
     * (MobileNetV2-style). Otherwise assume [-1,1] (MobileNetV1-style).
     * Falls back to [-1,1] if metadata is unavailable.
     */
    private fun detectNormStyle(s: LiteRtSession): NormStyle {
        val interpreter = runCatching {
            val field = s.javaClass.getDeclaredField("interpreterHandle")
            field.isAccessible = true
            field.get(s) as? org.tensorflow.lite.Interpreter
        }.getOrNull() ?: return NormStyle.MINUS_ONE_TO_ONE
        return runCatching {
            val qp = interpreter.getInputTensor(0).quantizationParams()
            // scale close to 1/255 → model expects [0,1] input
            if (qp.scale > 0.001f && qp.scale < 0.005f) NormStyle.ZERO_TO_ONE
            else NormStyle.MINUS_ONE_TO_ONE
        }.getOrDefault(NormStyle.MINUS_ONE_TO_ONE)
    }

    /**
     * Map ImageNet-1000 class indices to scene labels.
     *
     * Ranges are non-overlapping and sorted by specificity.
     * References: ImageNet class-index list (https://deeplearning.cms.waikato.ac.nz/user-guide/class-maps/IMAGENET/):
     *   - 562 = church (architecture), 563-569 = street/building classes
     *   - 629-640 = landscape/outdoor classes (coast, cliff, mountain, etc.)
     *   - 924-950 = food items
     *   - 975-980 = night-sky / astronomy classes (NOT food — previous overlap fixed)
     *   - 981-983 = night scenes (campfire, spotlight, etc.)
     */
    private fun mapClassIdToLabel(classIndex: Int): SceneLabel = when (classIndex) {
        // Food classes (ImageNet 924-950) — checked BEFORE night to avoid the
        // old overlap where 975-979 accidentally hit FOOD first.
        in 924..950 -> SceneLabel.FOOD
        // Architecture / urban (562-570)
        in 560..570 -> SceneLabel.ARCHITECTURE
        // Landscape / outdoor (629-640)
        in 629..640 -> SceneLabel.LANDSCAPE
        // Night / low-light scenes (975-983) — non-overlapping with food
        in 975..983 -> SceneLabel.NIGHT
        // Portrait-related classes (humans, face, person): 0-19
        in 0..19 -> SceneLabel.PORTRAIT
        // Macro / close-up (insects, flowers): 300-399 partial
        in 300..319 -> SceneLabel.MACRO
        // Snow / winter (mountains, ski): 970-974
        in 970..974 -> SceneLabel.SNOW
        else -> SceneLabel.GENERAL
    }

    private companion object {
        private const val TILE_SIZE = 224 * 224 * 3
        private const val NUM_CLASSES = 1000
        private const val FLOAT_BYTES = 4
    }
}
