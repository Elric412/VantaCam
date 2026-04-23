package com.leica.cam.ai_engine.api

data class AiFrame(
    val width: Int,
    val height: Int,
    val r: FloatArray,
    val g: FloatArray,
    val b: FloatArray,
    val metrics: FrameMetrics = FrameMetrics(),
    val hints: FrameHints = FrameHints(),
) {
    val pixelCount: Int = width * height
    fun luminanceAt(i: Int): Float = (0.2126f * r[i] + 0.7152f * g[i] + 0.0722f * b[i]).coerceIn(0f, 1f)
}

data class FrameMetrics(
    val sharpness: Float = 0.5f,
    val motionBlur: Float = 0.0f,
    val isoNoise: Float = 0.2f,
    val exposureBiasEv: Float = 0.0f,
    val deviceStability: Float = 0.7f,
)

data class FrameHints(
    val candidateFaces: List<CandidateFace> = emptyList(),
    val candidateObjects: List<CandidateObject> = emptyList(),
    val semanticHint: SceneLabel? = null,
)

data class CandidateFace(val box: NormalizedBox)
data class CandidateObject(val label: ObjectClass, val box: NormalizedBox, val confidence: Float)

data class SegmentationMask(val width: Int, val height: Int, val labels: IntArray, val confidence: FloatArray)
data class DepthMap(val width: Int, val height: Int, val depth: FloatArray, val confidence: Float)

enum class AiModelKind { SCENE_CLASSIFIER, OBJECT_DETECTOR, FACE_MESH, SEGMENTATION, DEPTH, SHOT_QUALITY }
interface AiModel { val kind: AiModelKind; val version: String; val estimatedMemoryMb: Int; fun close() }

class AiModelManager(private val budgetMb: Int = 384) {
    private val registry = mutableMapOf<AiModelKind, AiModel>()

    fun <T : AiModel> getOrLoad(kind: AiModelKind, loader: () -> T, now: Long): T {
        @Suppress("UNCHECKED_CAST")
        return (registry[kind] ?: loader().also { registry[kind] = it }) as T
    }
}
