package com.leica.cam.ai_engine.impl.pipeline

import com.leica.cam.ai_engine.api.CaptureMode
import com.leica.cam.ai_engine.api.FaceLandmarker
import com.leica.cam.ai_engine.api.FaceLandmarkerOutput
import com.leica.cam.ai_engine.api.FaceMesh
import com.leica.cam.ai_engine.api.SceneClassificationOutput
import com.leica.cam.ai_engine.api.SceneClassifier
import com.leica.cam.ai_engine.api.SceneLabel
import com.leica.cam.ai_engine.api.SemanticSegmentationOutput
import com.leica.cam.ai_engine.api.SemanticSegmenter
import com.leica.cam.common.result.LeicaResult
import com.leica.cam.hardware.contracts.photon.PhotonBuffer
import com.leica.cam.photon_matrix.FusedPhotonBuffer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiEngineOrchestratorTest {
    @Test
    fun `classifyAndScore uses live classifier result`() = runBlocking {
        val orchestrator = AiEngineOrchestrator(
            sceneClassifier = FakeSceneClassifier(SceneLabel.PORTRAIT),
            semanticSegmenter = FakeSemanticSegmenter(),
            faceLandmarker = FakeFaceLandmarker(),
            objectTracker = ObjectTrackingEngine(),
            illuminantEstimator = IlluminantEstimator(),
            qualityEngine = ShotQualityEngine(),
        )

        val result = orchestrator.classifyAndScore(
            fused = fusedBuffer(),
            captureMode = CaptureMode.AUTO,
            sceneTileRgb224x224 = FloatArray(224 * 224 * 3),
            segTileRgb257x257 = FloatArray(257 * 257 * 3),
            faceArgb8888 = IntArray(4) { 0xff000000.toInt() },
            faceWidth = 2,
            faceHeight = 2,
            isFrontCamera = false,
        )

        assertTrue(result is LeicaResult.Success)
        val analysis = (result as LeicaResult.Success).value
        assertEquals(SceneLabel.PORTRAIT, analysis.sceneLabel)
        assertTrue(analysis.trackedObjects.any { it.label.name == "FACE" })
    }

    private fun fusedBuffer(): FusedPhotonBuffer = FusedPhotonBuffer(
        underlying = PhotonBuffer.create16Bit(
            width = 1,
            height = 1,
            planes = listOf(shortArrayOf(1), shortArrayOf(1), shortArrayOf(1)),
        ),
        fusionQuality = 1f,
        frameCount = 1,
        motionMagnitude = 0f,
    )
}

private class FakeSceneClassifier(
    private val label: SceneLabel,
) : SceneClassifier {
    override fun classify(tileRgb: FloatArray): LeicaResult<SceneClassificationOutput> =
        LeicaResult.Success(
            SceneClassificationOutput(
                primaryLabel = label,
                primaryConfidence = 0.9f,
                top5 = listOf(label to 0.9f),
            ),
        )
}

private class FakeSemanticSegmenter : SemanticSegmenter {
    override fun segment(
        tileRgb: FloatArray,
        originalWidth: Int,
        originalHeight: Int,
        sensorIso: Int,
    ): LeicaResult<SemanticSegmentationOutput> =
        LeicaResult.Success(
            SemanticSegmentationOutput(
                width = originalWidth,
                height = originalHeight,
                zoneCodes = IntArray(originalWidth * originalHeight),
            ),
        )
}

private class FakeFaceLandmarker : FaceLandmarker {
    override fun detect(
        bitmapArgb8888: IntArray,
        width: Int,
        height: Int,
        isFrontCamera: Boolean,
    ): LeicaResult<FaceLandmarkerOutput> =
        LeicaResult.Success(
            FaceLandmarkerOutput(
                faces = listOf(
                    FaceMesh(
                        points478Normalized = floatArrayOf(0.1f, 0.1f, 0f, 0.9f, 0.9f, 0f),
                        confidence = 0.8f,
                    ),
                ),
                imageWidth = width,
                imageHeight = height,
            ),
        )
}
