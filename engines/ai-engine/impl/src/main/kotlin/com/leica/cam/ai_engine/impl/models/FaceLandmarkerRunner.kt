package com.leica.cam.ai_engine.impl.models

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.leica.cam.ai_engine.api.FaceLandmarker as FaceLandmarkerContract
import com.leica.cam.ai_engine.api.FaceLandmarkerOutput
import com.leica.cam.ai_engine.api.FaceMesh
import com.leica.cam.common.result.LeicaResult
import com.leica.cam.common.result.PipelineStage
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/** Runner for the MediaPipe Face Landmarker task bundle. */
@Singleton
class FaceLandmarkerRunner @Inject constructor(
    @ApplicationContext private val context: Context,
) : AutoCloseable, FaceLandmarkerContract {

    @Volatile private var landmarkerHandle: FaceLandmarker? = null
    @Volatile private var reusableBitmap: Bitmap? = null

    override fun detect(
        bitmapArgb8888: IntArray,
        width: Int,
        height: Int,
        isFrontCamera: Boolean,
    ): LeicaResult<FaceLandmarkerOutput> {
        val landmarker = landmarkerHandle ?: openOrFail(isFrontCamera)
            ?: return LeicaResult.Failure.Pipeline(
                PipelineStage.AI_ENGINE,
                "FaceLandmarker unavailable -- MediaPipe task file may be missing from assets.",
            )

        return try {
            LeicaResult.Success(invokeLandmarkerDetect(landmarker, bitmapArgb8888, width, height))
        } catch (t: Throwable) {
            if (t is CancellationException || t is InterruptedException) throw t
            LeicaResult.Failure.Pipeline(
                PipelineStage.AI_ENGINE,
                "FaceLandmarker detect() failed: ${t.message}",
                cause = t,
            )
        }
    }

    override fun close() {
        runCatching { landmarkerHandle?.close() }
            .onFailure { error ->
                System.err.println("FaceLandmarkerRunner: close failed: ${error.message}")
            }
        runCatching { reusableBitmap?.recycle() }
        landmarkerHandle = null
        reusableBitmap = null
    }

    @Synchronized
    private fun openOrFail(isFrontCamera: Boolean): FaceLandmarker? {
        landmarkerHandle?.let { return it }
        val minDetConf = if (isFrontCamera) 0.45f else 0.55f
        return runCatching {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_ASSET_PATH)
                .build()
            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setMinFaceDetectionConfidence(minDetConf)
                .setMinFacePresenceConfidence(0.50f)
                .setMinTrackingConfidence(0.50f)
                .setNumFaces(3)
                .build()
            FaceLandmarker.createFromOptions(context, options)
        }.getOrElse { error ->
            if (error is CancellationException || error is InterruptedException) throw error
            System.err.println("FaceLandmarkerRunner: failed to initialise: ${error.message}")
            null
        }?.also { landmarkerHandle = it }
    }

    private fun invokeLandmarkerDetect(
        handle: FaceLandmarker,
        pixels: IntArray,
        width: Int,
        height: Int,
    ): FaceLandmarkerOutput {
        val bitmap = obtainBitmap(width, height)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        val detectResult = handle.detect(BitmapImageBuilder(bitmap).build())
        val faces = detectResult.faceLandmarks().map { landmarks ->
            val points = FloatArray(landmarks.size * COMPONENTS_PER_POINT)
            landmarks.forEachIndexed { index, point ->
                val base = index * COMPONENTS_PER_POINT
                points[base] = point.x()
                points[base + 1] = point.y()
                points[base + 2] = point.z()
            }
            FaceMesh(
                points478Normalized = points,
                confidence = 1f,
            )
        }
        return FaceLandmarkerOutput(
            faces = faces,
            imageWidth = width,
            imageHeight = height,
        )
    }

    @Synchronized
    private fun obtainBitmap(width: Int, height: Int): Bitmap {
        val existing = reusableBitmap
        if (existing != null && existing.width == width && existing.height == height && !existing.isRecycled) {
            return existing
        }
        reusableBitmap?.takeIf { !it.isRecycled }?.recycle()
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { reusableBitmap = it }
    }

    private companion object {
        private const val MODEL_ASSET_PATH = "models/Face Landmarker/face_landmarker.task"
        private const val COMPONENTS_PER_POINT = 3
    }
}
