package com.leica.cam.hypertone_wb.pipeline

data class SceneContext(
    val sceneCategory: String,
    val hourBucket: Int,
    val locationGeohash: String,
    val timestampMillis: Long,
)

data class StoredWbEstimate(
    val cctKelvin: Float,
    val tint: Float,
    val sceneHash: String,
    val timestampMillis: Long,
)

interface WbMemoryStore {
    fun loadRecent(limit: Int): List<StoredWbEstimate>
    fun save(estimate: StoredWbEstimate)
}

data class RgbFrame(
    val width: Int,
    val height: Int,
    val red: FloatArray,
    val green: FloatArray,
    val blue: FloatArray,
) {
    fun size(): Int = width * height
    fun luminanceAt(index: Int): Float =
        (red[index] * 0.2126f + green[index] * 0.7152f + blue[index] * 0.0722f).coerceIn(0f, 1f)
}

enum class IlluminantMethod {
    GRAY_WORLD_EDGE_EXCLUSION,
    MAX_RGB_WHITE_PATCH,
    GAMUT_MAPPING_PLANCKIAN,
    DEEP_LEARNING_ENSEMBLE,
}

data class MethodEstimate(
    val method: IlluminantMethod,
    val cctKelvin: Float,
    val tint: Float,
    val confidence: Float,
    val rgbIlluminant: FloatArray? = null,
    val diagnostics: Map<String, Float> = emptyMap(),
)

data class NeuralIlluminantPrediction(
    val cctKelvin: Float,
    val tint: Float,
    val confidence: Float,
)

data class WhiteBalanceResult(
    val cctKelvin: Float,
    val tint: Float,
    val confidence: Float,
    val mixedLightDetected: Boolean,
    val recommendedAwbAutoFallback: Boolean,
    val colorCorrectionMatrix3x3: FloatArray,
    val methodEstimates: List<MethodEstimate>,
    val spatialMap: SpatialWbMap? = null,
)

data class SpatialWbMap(
    val width: Int,
    val height: Int,
    val cctKelvinMap: FloatArray,
)

interface IlluminantPredictor {
    fun predict(frame: RgbFrame, sceneContext: SceneContext?): NeuralIlluminantPrediction
}
