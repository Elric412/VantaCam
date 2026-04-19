package com.leica.cam.ai_engine.impl.pipeline
import com.leica.cam.ai_engine.api.*
import javax.inject.Inject

class SceneClassifierImpl @Inject constructor() : SceneClassifier {
    override fun classify(frame: AiFrame) = SceneClassification(SceneType.LANDSCAPE, 0.95f)
}
class ObjectDetectorImpl @Inject constructor() : ObjectDetector {
    override fun detect(frame: AiFrame) = emptyList<ObjectDetection>()
}
class ByteTrackTrackerImpl @Inject constructor() : ByteTrackTracker {
    override fun update(detections: List<ObjectDetection>) = emptyList<TrackedObject>()
}
class ShotQualityEngineImpl @Inject constructor() : ShotQualityEngine {
    override fun score(frame: AiFrame) = ShotQualityScore(0.88f)
}
