package com.leica.cam.ai_engine.api

import com.leica.cam.common.result.LeicaResult
import com.leica.cam.photon_matrix.FusedPhotonBuffer

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

interface IAiEngine {
    suspend fun classifyAndScore(
        fused: FusedPhotonBuffer,
        captureMode: CaptureMode,
        sceneTileRgb224x224: FloatArray? = null,
        segTileRgb257x257: FloatArray? = null,
        faceArgb8888: IntArray? = null,
        faceWidth: Int = 0,
        faceHeight: Int = 0,
        isFrontCamera: Boolean = false,
    ): LeicaResult<SceneAnalysis>
}

interface ObjectDetector {
    fun detect(frame: AiFrame): List<ObjectDetection>
}

interface ByteTrackTracker {
    fun update(detections: List<ObjectDetection>): List<TrackedObject>
}

interface ShotQualityEngine {
    fun score(frame: AiFrame): ShotQualityScore
}
