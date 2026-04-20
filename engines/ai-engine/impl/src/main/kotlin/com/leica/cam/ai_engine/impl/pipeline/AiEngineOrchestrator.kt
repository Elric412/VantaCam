package com.leica.cam.ai_engine.impl.pipeline

import com.leica.cam.ai_engine.api.CaptureMode
import com.leica.cam.ai_engine.api.IAiEngine
import com.leica.cam.ai_engine.api.IlluminantHint
import com.leica.cam.ai_engine.api.QualityScore
import com.leica.cam.ai_engine.api.SceneAnalysis
import com.leica.cam.ai_engine.api.SceneLabel
import com.leica.cam.ai_engine.api.TrackedObject
import com.leica.cam.common.result.LeicaResult
import com.leica.cam.photon_matrix.FusedPhotonBuffer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiEngineOrchestrator @Inject constructor(
    private val sceneClassifier: SceneClassifier,
    private val qualityEngine: ShotQualityEngine,
    private val objectTracker: ObjectTrackingEngine,
) : IAiEngine {

    override suspend fun classifyAndScore(
        fused: FusedPhotonBuffer,
        captureMode: CaptureMode,
    ): LeicaResult<SceneAnalysis> {
        val sceneLabel = sceneClassifier.classify(fused, captureMode)
        val quality = qualityEngine.score(fused)
        val illuminantHint = sceneClassifier.estimateIlluminant(fused, sceneLabel)
        val tracked = objectTracker.track(fused)

        return LeicaResult.Success(
            SceneAnalysis(
                sceneLabel = sceneLabel,
                qualityScore = quality,
                illuminantHint = illuminantHint,
                trackedObjects = tracked,
            ),
        )
    }
}

@Singleton
class SceneClassifier @Inject constructor() {
    fun classify(fused: FusedPhotonBuffer, mode: CaptureMode): SceneLabel {
        return SceneLabel.LANDSCAPE
    }

    fun estimateIlluminant(fused: FusedPhotonBuffer, scene: SceneLabel): IlluminantHint {
        val kelvin = when (scene) {
            SceneLabel.NIGHT -> 3000f
            SceneLabel.INDOOR -> 3200f
            SceneLabel.FOOD -> 3500f
            SceneLabel.PORTRAIT -> 5000f
            SceneLabel.LANDSCAPE -> 5500f
            else -> 6500f
        }
        return IlluminantHint(estimatedKelvin = kelvin, confidence = 0.7f, isMixedLight = false)
    }
}

@Singleton
class ShotQualityEngine @Inject constructor() {
    fun score(fused: FusedPhotonBuffer): QualityScore {
        return QualityScore(overall = 0.88f, sharpness = 0.85f, exposure = 0.9f, stability = 0.92f)
    }
}

@Singleton
class ObjectTrackingEngine @Inject constructor() {
    fun track(fused: FusedPhotonBuffer): List<TrackedObject> = emptyList()
}
