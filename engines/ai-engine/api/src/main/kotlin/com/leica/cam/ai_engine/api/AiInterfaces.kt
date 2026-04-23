package com.leica.cam.ai_engine.api

typealias SceneType = SceneLabel

data class SceneClassification(
    val type: SceneType,
    val confidence: Float,
) {
    val scene: SceneType get() = type
}

data class ObjectDetection(
    val label: ObjectClass,
    val box: NormalizedBox,
    val confidence: Float,
)

data class ShotQualityScore(val overall: Float)

interface SceneClassifier { fun classify(frame: AiFrame): SceneClassification }

interface ObjectDetector { fun detect(frame: AiFrame): List<ObjectDetection> }

interface ByteTrackTracker { fun update(detections: List<ObjectDetection>): List<TrackedObject> }

interface ShotQualityEngine { fun score(frame: AiFrame): ShotQualityScore }
