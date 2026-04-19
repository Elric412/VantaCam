package com.leica.cam.ai_engine.api

enum class SceneType { PORTRAIT, LANDSCAPE, NIGHT, DOCUMENT, FOOD, PET, BACKLIT, MACRO, INDOOR, UNKNOWN }
data class SceneClassification(val type: SceneType, val confidence: Float) {
    // For compatibility with old code that might use .scene
    val scene: SceneType get() = type
}

interface SceneClassifier { fun classify(frame: AiFrame): SceneClassification }

enum class ObjectClass { PERSON, FACE, PET, VEHICLE, FOOD, PLANT, DOCUMENT, SKY, OTHER }
data class ObjectDetection(val label: ObjectClass, val box: NormalizedBox, val confidence: Float)
interface ObjectDetector { fun detect(frame: AiFrame): List<ObjectDetection> }

data class TrackedObject(val trackId: Long, val label: ObjectClass, val box: NormalizedBox, val confidence: Float)
interface ByteTrackTracker { fun update(detections: List<ObjectDetection>): List<TrackedObject> }

data class ShotQualityScore(val overall: Float)
interface ShotQualityEngine { fun score(frame: AiFrame): ShotQualityScore }
