package com.leica.cam.ai_engine.impl.models

import android.content.Context
import android.graphics.Bitmap
import com.leica.cam.common.result.LeicaResult
import com.leica.cam.common.result.PipelineStage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runner for the MediaPipe Face Landmarker task bundle (`face_landmarker.task`).
 *
 * Wraps MediaPipe Tasks (not LiteRT directly) because the `.task` bundle
 * is a MediaPipe-native format that includes pre/post-processing graphs
 * alongside the TFLite model.
 *
 * **Per-sensor fine-tuning (D1 Architecture Decision 5):**
 * - Front cameras (`OV16A1Q`, `GC16B3`): detection confidence threshold = 0.45
 *   (lower because selfie distances are short, features are larger).
 * - Rear cameras: detection confidence threshold = 0.55.
 *
 * **Mode:** Uses `RunningMode.IMAGE` for still capture (synchronous).
 * For viewfinder preview, a SEPARATE runner with `LIVE_STREAM` mode
 * must be instantiated (not this one -- LIVE_STREAM requires a callback
 * and cannot return synchronous results).
 *
 * **LUMO Law 5:** Face landmarks feed the skin-anchor computation in
 * HyperTone WB. The anchor is computed FIRST, before zone corrections.
 */
@Singleton
class FaceLandmarkerRunner @Inject constructor(
    private val context: Context,
) : AutoCloseable {

    @Volatile private var landmarkerHandle: Any? = null

    /**
     * Detect face landmarks on a still-capture image.
     *
     * @param imagePixels  ARGB_8888 pixel array (width * height).
     * @param width        Image width in pixels.
     * @param height       Image height in pixels.
     * @param isFrontCamera True for OV16A1Q/GC16B3 (adjusts confidence threshold).
     * @return Detected face landmarks as a list of normalised (x,y) pairs per face,
     *         or an empty list if no faces found.
     */
    fun detect(
        imagePixels: IntArray,
        width: Int,
        height: Int,
        isFrontCamera: Boolean,
    ): LeicaResult<FaceLandmarkerOutput> {
        val l = landmarkerHandle ?: openOrFail(isFrontCamera)
            ?: return LeicaResult.Failure.Pipeline(
                PipelineStage.AI_ENGINE,
                "FaceLandmarker unavailable -- MediaPipe .task file may be missing from assets.",
            )

        return try {
            // In production this calls FaceLandmarker.detect(MPImage).
            // Reflective call for compile-time independence from MediaPipe SDK.
            val result = invokeLandmarkerDetect(l, imagePixels, width, height)
            LeicaResult.Success(result)
        } catch (t: Throwable) {
            if (t is InterruptedException || t.javaClass.name.contains("CancellationException")) throw t
            LeicaResult.Failure.Pipeline(
                PipelineStage.AI_ENGINE,
                "FaceLandmarker detect() failed: ${t.message}",
                cause = t,
            )
        }
    }

    override fun close() {
        runCatching {
            landmarkerHandle?.let { handle ->
                handle.javaClass.getMethod("close").invoke(handle)
            }
        }
        landmarkerHandle = null
    }

    @Synchronized
    private fun openOrFail(isFrontCamera: Boolean): Any? {
        landmarkerHandle?.let { return it }

        val minDetConf = if (isFrontCamera) 0.45f else 0.55f

        return try {
            // Reflective construction of MediaPipe FaceLandmarker
            val baseOptsBuilderClass = Class.forName("com.google.mediapipe.tasks.core.BaseOptions\$Builder")
            val baseOptsBuilder = baseOptsBuilderClass.getDeclaredConstructor().newInstance()
            val setModelPath = baseOptsBuilderClass.getMethod("setModelAssetPath", String::class.java)
            setModelPath.invoke(baseOptsBuilder, "models/Face Landmarker/face_landmarker.task")
            val buildBaseOpts = baseOptsBuilderClass.getMethod("build")
            val baseOpts = buildBaseOpts.invoke(baseOptsBuilder)

            val flOptsBuilderClass = Class.forName(
                "com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker\$FaceLandmarkerOptions\$Builder",
            )
            val flOptsBuilder = flOptsBuilderClass.getDeclaredConstructor().newInstance()

            val setBaseOpts = flOptsBuilderClass.getMethod(
                "setBaseOptions",
                Class.forName("com.google.mediapipe.tasks.core.BaseOptions"),
            )
            setBaseOpts.invoke(flOptsBuilder, baseOpts)

            val runningModeClass = Class.forName("com.google.mediapipe.tasks.vision.core.RunningMode")
            val imageMode = runningModeClass.getField("IMAGE").get(null)
            val setRunningMode = flOptsBuilderClass.getMethod("setRunningMode", runningModeClass)
            setRunningMode.invoke(flOptsBuilder, imageMode)

            val setMinDet = flOptsBuilderClass.getMethod("setMinFaceDetectionConfidence", Float::class.java)
            setMinDet.invoke(flOptsBuilder, minDetConf)
            val setMinPres = flOptsBuilderClass.getMethod("setMinFacePresenceConfidence", Float::class.java)
            setMinPres.invoke(flOptsBuilder, 0.50f)
            val setMinTrack = flOptsBuilderClass.getMethod("setMinTrackingConfidence", Float::class.java)
            setMinTrack.invoke(flOptsBuilder, 0.50f)
            val setNumFaces = flOptsBuilderClass.getMethod("setNumFaces", Int::class.java)
            setNumFaces.invoke(flOptsBuilder, 3)

            val buildOpts = flOptsBuilderClass.getMethod("build")
            val flOpts = buildOpts.invoke(flOptsBuilder)

            val flClass = Class.forName("com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker")
            val createFrom = flClass.getMethod(
                "createFromOptions",
                Context::class.java,
                Class.forName("com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker\$FaceLandmarkerOptions"),
            )
            val landmarker = createFrom.invoke(null, context, flOpts)
            landmarkerHandle = landmarker
            landmarker
        } catch (e: Exception) {
            System.err.println("FaceLandmarkerRunner: failed to initialise: ${e.message}")
            null
        }
    }

    /**
     * Invoke detect on the MediaPipe FaceLandmarker handle.
     * Returns a simplified output structure.
     */
    private fun invokeLandmarkerDetect(
        handle: Any,
        pixels: IntArray,
        width: Int,
        height: Int,
    ): FaceLandmarkerOutput {
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        val mpImage = createMpImage(bitmap)
        val detectMethod = handle.javaClass.methods.firstOrNull {
            it.name == "detect" && it.parameterCount == 1
        } ?: throw IllegalStateException("FaceLandmarker.detect(MPImage) method not found")
        val detectResult = detectMethod.invoke(handle, mpImage)
        return parseDetectResult(detectResult, width, height)
    }

    private fun createMpImage(bitmap: Bitmap): Any {
        val imageBuilderClass = Class.forName("com.google.mediapipe.framework.image.BitmapImageBuilder")
        val builder = imageBuilderClass.getDeclaredConstructor(Bitmap::class.java).newInstance(bitmap)
        val buildMethod = imageBuilderClass.getMethod("build")
        return buildMethod.invoke(builder)
    }

    private fun parseDetectResult(
        detectResult: Any?,
        width: Int,
        height: Int,
    ): FaceLandmarkerOutput {
        if (detectResult == null) {
            return FaceLandmarkerOutput(emptyList(), width, height)
        }

        val faceLandmarksMethod = detectResult.javaClass.methods.firstOrNull {
            it.name == "faceLandmarks" && it.parameterCount == 0
        } ?: return FaceLandmarkerOutput(emptyList(), width, height)
        val facesRaw = faceLandmarksMethod.invoke(detectResult) as? List<*> ?: emptyList<Any>()

        val faces = facesRaw.mapNotNull { faceRaw ->
            val pointsRaw = faceRaw as? List<*> ?: return@mapNotNull null
            val points = pointsRaw.mapNotNull { point ->
                point?.let {
                    val x = invokeFloatGetter(it, "x") ?: return@let null
                    val y = invokeFloatGetter(it, "y") ?: return@let null
                    val z = invokeFloatGetter(it, "z") ?: 0f
                    Triple(x, y, z)
                }
            }
            if (points.isEmpty()) return@mapNotNull null
            FaceLandmarks(points = points, confidence = 1f)
        }

        return FaceLandmarkerOutput(
            faces = faces,
            imageWidth = width,
            imageHeight = height,
        )
    }

    private fun invokeFloatGetter(target: Any, methodName: String): Float? {
        val method = target.javaClass.methods.firstOrNull { it.name == methodName && it.parameterCount == 0 }
            ?: return null
        return (method.invoke(target) as? Number)?.toFloat()
    }
}

/** Output from the face landmarker. */
data class FaceLandmarkerOutput(
    /** One entry per detected face; each face has 478 (x,y,z) landmark triples. */
    val faces: List<FaceLandmarks>,
    val imageWidth: Int,
    val imageHeight: Int,
) {
    val hasFaces: Boolean get() = faces.isNotEmpty()
}

/** Normalised landmarks for a single face. */
data class FaceLandmarks(
    /** 478 landmark points, each (x, y, z) normalised to [0,1]. */
    val points: List<Triple<Float, Float, Float>>,
    /** Detection confidence in [0,1]. */
    val confidence: Float,
)
