package com.leica.cam.ai_engine.api

import com.leica.cam.common.result.LeicaResult
import java.nio.ByteBuffer

/** Neural AWB model output shared between :ai-engine:impl and :hypertone-wb:impl. */
data class AwbNeuralPrior(
    val cctKelvin: Float,
    val tintDuv: Float,
    val confidence: Float,
)

/**
 * Pure contract consumed by HyperTone WB. The LiteRT-backed implementation lives
 * in :ai-engine:impl as AwbModelRunner.
 */
interface AwbPredictor {
    /**
     * @param tileRgb 224*224*3 interleaved RGB float tile, linear scene-referred.
     * @param sensorWbBias Per-sensor RGB gain bias, applied after inference by the caller.
     */
    fun predict(tileRgb: FloatArray, sensorWbBias: FloatArray): LeicaResult<AwbNeuralPrior>
}

/** Neural ISP refinement contract. */
interface NeuralIspRefiner {
    fun isEligible(sensorId: String): Boolean
    fun refine(bayerTile: FloatArray): LeicaResult<FloatArray>
}

/** Semantic segmentation contract. */
interface SemanticSegmenter {
    fun segment(
        tileRgb: FloatArray,
        originalWidth: Int,
        originalHeight: Int,
        sensorIso: Int,
    ): LeicaResult<SemanticSegmentationOutput>
}

data class SemanticSegmentationOutput(
    val width: Int,
    val height: Int,
    val zoneCodes: IntArray,
)

enum class SemanticZoneCode { BACKGROUND, MIDGROUND, PERSON, SKY, UNKNOWN }

/** Scene classification contract. */
interface SceneClassifier {
    fun classify(tileRgb: FloatArray): LeicaResult<SceneClassificationOutput>
}

data class SceneClassificationOutput(
    val primaryLabel: SceneLabel,
    val primaryConfidence: Float,
    val top5: List<Pair<SceneLabel, Float>>,
)

/** Face landmark contract (478-point MediaPipe mesh). */
interface FaceLandmarker {
    fun detect(
        bitmapArgb8888: IntArray,
        width: Int,
        height: Int,
        isFrontCamera: Boolean,
    ): LeicaResult<FaceLandmarkerOutput>
}

data class FaceLandmarkerOutput(
    val faces: List<FaceMesh>,
    val imageWidth: Int,
    val imageHeight: Int,
) {
    val hasFaces: Boolean get() = faces.isNotEmpty()
}

data class FaceMesh(
    val points478Normalized: FloatArray,
    val confidence: Float,
)

typealias AssetBytesLoader = (path: String) -> ByteBuffer
