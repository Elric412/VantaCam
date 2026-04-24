package com.leica.cam.ai_engine.api

data class SceneAnalysis(
    val sceneLabel: SceneLabel,
    val qualityScore: QualityScore,
    val illuminantHint: IlluminantHint,
    val trackedObjects: List<TrackedObject>,
)

enum class SceneLabel {
    PORTRAIT,
    LANDSCAPE,
    NIGHT,
    DOCUMENT,
    FOOD,
    PET,
    BACKLIT,
    BACKLIT_PORTRAIT,
    MACRO,
    INDOOR,
    OUTDOOR,
    GENERAL,
    STAGE,
    SNOW,
    ARCHITECTURE,
    UNKNOWN,
}

data class QualityScore(
    val overall: Float,
    val sharpness: Float = 0.5f,
    val exposure: Float = 0.5f,
    val stability: Float = 0.7f,
) {
    init {
        require(overall in 0f..1f) { "Quality score must be in [0, 1]" }
    }
}

data class IlluminantHint(
    val estimatedKelvin: Float,
    val confidence: Float,
    val isMixedLight: Boolean,
)

data class TrackedObject(
    val trackId: Long,
    val label: ObjectClass,
    val confidence: Float,
    val boundingBox: NormalizedBox,
)

enum class ObjectClass {
    PERSON,
    FACE,
    PET,
    VEHICLE,
    FOOD,
    PLANT,
    DOCUMENT,
    SKY,
    OTHER,
}

data class NormalizedBox(
    val xMin: Float,
    val yMin: Float,
    val xMax: Float,
    val yMax: Float,
) {
    val width: Float get() = xMax - xMin
    val height: Float get() = yMax - yMin
}

enum class CaptureMode {
    AUTO,
    PORTRAIT,
    LANDSCAPE,
    NIGHT,
    MACRO,
    VIDEO,
}
