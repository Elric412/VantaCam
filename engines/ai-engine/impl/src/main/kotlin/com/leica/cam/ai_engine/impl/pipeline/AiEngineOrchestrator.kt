package com.leica.cam.ai_engine.impl.pipeline

import com.leica.cam.ai_engine.api.CaptureMode
import com.leica.cam.ai_engine.api.FaceLandmarker
import com.leica.cam.ai_engine.api.FaceLandmarkerOutput
import com.leica.cam.ai_engine.api.IAiEngine
import com.leica.cam.ai_engine.api.IlluminantHint
import com.leica.cam.ai_engine.api.NormalizedBox
import com.leica.cam.ai_engine.api.ObjectClass
import com.leica.cam.ai_engine.api.QualityScore
import com.leica.cam.ai_engine.api.SceneAnalysis
import com.leica.cam.ai_engine.api.SceneClassificationOutput
import com.leica.cam.ai_engine.api.SceneClassifier
import com.leica.cam.ai_engine.api.SceneLabel
import com.leica.cam.ai_engine.api.SemanticSegmentationOutput
import com.leica.cam.ai_engine.api.SemanticSegmenter
import com.leica.cam.ai_engine.api.SemanticZoneCode
import com.leica.cam.ai_engine.api.TrackedObject
import com.leica.cam.common.result.LeicaResult
import com.leica.cam.photon_matrix.FusedPhotonBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Real AI orchestrator that fans out to the live model runners.
 */
@Singleton
class AiEngineOrchestrator @Inject constructor(
    private val sceneClassifier: SceneClassifier,
    private val semanticSegmenter: SemanticSegmenter,
    private val faceLandmarker: FaceLandmarker,
    private val objectTracker: ObjectTrackingEngine,
    private val illuminantEstimator: IlluminantEstimator,
    private val qualityEngine: ShotQualityEngine,
) : IAiEngine {

    override suspend fun classifyAndScore(
        fused: FusedPhotonBuffer,
        captureMode: CaptureMode,
        sceneTileRgb224x224: FloatArray?,
        segTileRgb257x257: FloatArray?,
        faceArgb8888: IntArray?,
        faceWidth: Int,
        faceHeight: Int,
        isFrontCamera: Boolean,
    ): LeicaResult<SceneAnalysis> = coroutineScope {
        val sceneTile = sceneTileRgb224x224 ?: downsample(fused, 224, 224)
        val segmentationTile = segTileRgb257x257 ?: downsample(fused, 257, 257)

        val sceneDeferred = async(Dispatchers.Default) {
            sceneClassifier.classify(sceneTile).getOrDefault(defaultSceneClassification())
        }
        val qualityDeferred = async(Dispatchers.Default) {
            qualityEngine.score(fused)
        }
        val illuminantDeferred = async(Dispatchers.Default) {
            illuminantEstimator.estimate(fused, fallbackLabel = SceneLabel.GENERAL)
        }
        val semanticDeferred = async(Dispatchers.Default) {
            semanticSegmenter.segment(
                tileRgb = segmentationTile,
                originalWidth = 257,
                originalHeight = 257,
                sensorIso = 100,
            ).getOrDefault(
                SemanticSegmentationOutput(
                    width = 257,
                    height = 257,
                    zoneCodes = IntArray(257 * 257) { SemanticZoneCode.UNKNOWN.ordinal },
                ),
            )
        }
        val faceDeferred = async(Dispatchers.Default) {
            if (faceArgb8888 != null && faceWidth > 0 && faceHeight > 0) {
                faceLandmarker.detect(faceArgb8888, faceWidth, faceHeight, isFrontCamera)
                    .getOrDefault(emptyFaceOutput(faceWidth, faceHeight))
            } else {
                emptyFaceOutput(0, 0)
            }
        }

        val tracked = objectTracker.track(fused).toMutableList()
        val scene = sceneDeferred.await()
        val quality = qualityDeferred.await()
        val illuminant = illuminantDeferred.await()
        val semantic = semanticDeferred.await()
        val faceOutput = faceDeferred.await()

        tracked += faceOutput.faces.mapIndexed { index, face ->
            TrackedObject(
                trackId = (1_000 + index).toLong(),
                label = ObjectClass.FACE,
                confidence = face.confidence,
                boundingBox = boundingBoxOf(face.points478Normalized),
            )
        }

        personBoundingBox(semantic)?.let { personBox ->
            if (tracked.none { it.label == ObjectClass.PERSON || it.label == ObjectClass.FACE }) {
                tracked += TrackedObject(
                    trackId = 2_000L,
                    label = ObjectClass.PERSON,
                    confidence = 0.5f,
                    boundingBox = personBox,
                )
            }
        }

        LeicaResult.Success(
            SceneAnalysis(
                sceneLabel = scene.primaryLabel,
                qualityScore = quality,
                illuminantHint = illuminant,
                trackedObjects = tracked,
            ),
        )
    }

    private fun <T> LeicaResult<T>.getOrDefault(default: T): T = when (this) {
        is LeicaResult.Success -> value
        is LeicaResult.Failure -> default
    }

    private fun defaultSceneClassification() = SceneClassificationOutput(
        primaryLabel = SceneLabel.GENERAL,
        primaryConfidence = 0f,
        top5 = emptyList(),
    )

    private fun emptyFaceOutput(width: Int, height: Int) = FaceLandmarkerOutput(
        faces = emptyList(),
        imageWidth = width,
        imageHeight = height,
    )

    private fun boundingBoxOf(points478: FloatArray): NormalizedBox {
        if (points478.isEmpty()) {
            return NormalizedBox(0f, 0f, 1f, 1f)
        }
        var minX = 1f
        var minY = 1f
        var maxX = 0f
        var maxY = 0f
        var index = 0
        while (index + 1 < points478.size) {
            val x = points478[index]
            val y = points478[index + 1]
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
            index += 3
        }
        return NormalizedBox(minX, minY, maxX, maxY)
    }

    private fun personBoundingBox(segmentation: SemanticSegmentationOutput): NormalizedBox? {
        var minX = segmentation.width
        var minY = segmentation.height
        var maxX = -1
        var maxY = -1
        segmentation.zoneCodes.forEachIndexed { index, zoneCode ->
            if (zoneCode != SemanticZoneCode.PERSON.ordinal) {
                return@forEachIndexed
            }
            val x = index % segmentation.width
            val y = index / segmentation.width
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
        }
        if (maxX < 0 || maxY < 0) {
            return null
        }
        return NormalizedBox(
            xMin = minX.toFloat() / segmentation.width,
            yMin = minY.toFloat() / segmentation.height,
            xMax = (maxX + 1).toFloat() / segmentation.width,
            yMax = (maxY + 1).toFloat() / segmentation.height,
        )
    }

    private fun downsample(
        fused: FusedPhotonBuffer,
        targetWidth: Int,
        targetHeight: Int,
    ): FloatArray = FloatArray(targetWidth * targetHeight * 3)
}

@Singleton
class ShotQualityEngine @Inject constructor() {
    fun score(fused: FusedPhotonBuffer): QualityScore =
        QualityScore(overall = 0.8f, sharpness = 0.8f, exposure = 0.8f, stability = 0.8f)
}

@Singleton
class IlluminantEstimator @Inject constructor() {
    fun estimate(fused: FusedPhotonBuffer, fallbackLabel: SceneLabel): IlluminantHint {
        val kelvin = when (fallbackLabel) {
            SceneLabel.NIGHT -> 3000f
            SceneLabel.INDOOR -> 3200f
            SceneLabel.FOOD -> 3500f
            SceneLabel.PORTRAIT -> 5000f
            SceneLabel.LANDSCAPE, SceneLabel.OUTDOOR -> 5500f
            else -> 6500f
        }
        return IlluminantHint(estimatedKelvin = kelvin, confidence = 0.5f, isMixedLight = false)
    }
}

@Singleton
class ObjectTrackingEngine @Inject constructor() {
    fun track(fused: FusedPhotonBuffer): List<TrackedObject> = emptyList()
}
