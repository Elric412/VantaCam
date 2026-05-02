package com.leica.cam.sensor_hal.session

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.RggbChannelVector
import android.os.Build
import android.provider.MediaStore
import android.util.Range
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector as CameraXSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.leica.cam.common.Logger
import com.leica.cam.common.result.LeicaResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Concrete [CameraController] backed by CameraX + Camera2 interop.
 *
 * Preview/session ownership stays with CameraX, while manual ISO/shutter/WB are
 * applied as Camera2 request overrides so the on-screen controls affect the real
 * repeating request and still render through [PreviewView].
 */
class Camera2CameraController(
    private val appContext: Context,
) : CameraController {

    private val cameraManager: CameraManager =
        appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private val mainExecutor = appContext.mainExecutor
    @Volatile
    private var captureExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile
    var previewView: PreviewView? = null
        private set

    @Volatile
    private var lifecycleOwner: LifecycleOwner? = null

    @Volatile
    private var imageCapture: ImageCapture? = null

    @Volatile
    private var cameraProvider: ProcessCameraProvider? = null

    @Volatile
    private var boundCamera: Camera? = null

    @Volatile
    private var runtimeCapabilities: CameraRuntimeCapabilities = CameraRuntimeCapabilities()

    @Volatile
    private var requestState: CameraRequestControlState = CameraRequestControlState()
    @Volatile
    private var currentFlashMode: Int = ImageCapture.FLASH_MODE_OFF
    @Volatile
    private var currentCameraId: String? = null

    @Volatile
    private var preferredLensFacing: Int = CameraCharacteristics.LENS_FACING_BACK

    /** Attaches the active [PreviewView] and [LifecycleOwner] before opening the session. */
    fun attach(view: PreviewView, owner: LifecycleOwner) {
        previewView = view
        lifecycleOwner = owner
    }

    override fun availableCameraIds(): List<String> =
        runCatching {
            cameraManager.cameraIdList.toList().sortedWith(
                compareByDescending<String> { id -> camera2PriorityScore(id) }
                    .thenBy { id -> id.toIntOrNull() ?: Int.MAX_VALUE },
            )
        }.getOrElse {
            Logger.e(TAG, "Cannot read cameraIdList", it)
            emptyList()
        }

    override suspend fun openCamera(cameraId: String) {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("CAMERA permission is not granted")
        }
        val capabilities = withContext(Dispatchers.Default) { loadCapabilities(cameraId) }
        val selector = withContext(Dispatchers.Default) { selectorForCameraId(cameraId) }
        val provider = awaitCameraProvider()
        if (captureExecutor.isShutdown) {
            captureExecutor = Executors.newSingleThreadExecutor()
        }

        withContext(Dispatchers.Main.immediate) {
            val owner = requireNotNull(lifecycleOwner) {
                "Camera2CameraController.attach() must be called before openCamera()"
            }
            val view = requireNotNull(previewView) { "PreviewView missing" }
            val preview = Preview.Builder()
                .build()
                .also { it.setSurfaceProvider(view.surfaceProvider) }

            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setFlashMode(currentFlashMode)
                .build()

            provider.unbindAll()
            val camera = provider.bindToLifecycle(owner, selector, preview, capture)

            cameraProvider = provider
            imageCapture = capture
            boundCamera = camera
            runtimeCapabilities = capabilities
            currentCameraId = cameraId
            applyCurrentRequestState()
        }
    }

    override suspend fun configureSession(cameraId: String) {
        Logger.d(TAG, "Session configured via CameraX bindToLifecycle for cameraId=$cameraId")
    }

    override suspend fun capture(): LeicaResult<Unit> = withContext(Dispatchers.Main.immediate) {
        val capture = imageCapture ?: return@withContext LeicaResult.Failure.Hardware(
            errorCode = ImageCapture.ERROR_CAMERA_CLOSED,
            message = "imageCapture not initialised",
        ) as LeicaResult<Unit>
        suspendCancellableCoroutine<LeicaResult<Unit>> { continuation ->
            capture.takePicture(
                buildOutputOptions(),
                captureExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        Logger.i(TAG, "Capture saved: ${outputFileResults.savedUri ?: "pending_media_store_uri"}")
                        if (continuation.isActive) continuation.resume(LeicaResult.Success(Unit))
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Logger.e(TAG, "Capture failed: ${exception.imageCaptureError}", exception)
                        if (continuation.isActive) {
                            continuation.resume(
                                LeicaResult.Failure.Hardware(
                                    errorCode = exception.imageCaptureError,
                                    message = exception.message ?: "Capture failed",
                                ),
                            )
                        }
                    }
                },
            )
        }
    }

    override suspend fun closeCamera() = withContext(Dispatchers.Main.immediate) {
        cameraProvider?.unbindAll()
        cameraProvider = null
        imageCapture = null
        boundCamera = null
        runtimeCapabilities = CameraRuntimeCapabilities()
        currentCameraId = null
        captureExecutor.shutdown()
        runCatching { captureExecutor.awaitTermination(2, TimeUnit.SECONDS) }
        Unit
    }

    fun currentImageCapture(): ImageCapture? = imageCapture

    /** Applies a paired manual ISO + shutter request so AE can be disabled safely. */
    fun setManualExposure(iso: Int, shutterUs: Long) {
        requestState = requestState
            .withIso(runtimeCapabilities.clampIso(iso))
            .withShutterUs(runtimeCapabilities.clampShutterUs(shutterUs))
        applyCurrentRequestState()
    }

    /** Applies auto-exposure bias when AE is active. */
    fun setExposureCompensationEv(ev: Float) {
        requestState = requestState.withExposureCompensation(ev.coerceIn(-5f, 5f))
        applyCurrentRequestState()
    }

    /** Applies manual white balance when Camera2 reports AWB-off support. */
    fun setWhiteBalanceKelvin(kelvin: Int) {
        if (!runtimeCapabilities.supportsManualWhiteBalance) {
            Logger.i(TAG, "Manual white balance unsupported on active camera; ignoring ${kelvin}K request")
            return
        }
        requestState = requestState.withWhiteBalance(kelvin.coerceIn(2_000, 12_000))
        applyCurrentRequestState()
    }

    /** Restores auto exposure + auto white balance. */
    fun resetToAuto() {
        requestState = CameraRequestControlState()
        applyCurrentRequestState()
    }

    fun setFlashMode(flashMode: CaptureFlashMode) {
        currentFlashMode = flashMode.toCameraX()
        imageCapture?.flashMode = currentFlashMode
    }

    fun setZoomRatio(zoomRatio: Float) {
        val camera = boundCamera ?: return
        val zoomState = camera.cameraInfo.zoomState.value
        val safeZoom = if (zoomState != null) {
            zoomRatio.coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
        } else {
            zoomRatio.coerceAtLeast(1f)
        }
        camera.cameraControl.setZoomRatio(safeZoom)
    }

    fun setPreferredCameraFacing(useFrontCamera: Boolean) {
        preferredLensFacing = if (useFrontCamera) {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }
    }

    suspend fun switchCameraFacing(useFrontCamera: Boolean): LeicaResult<Unit> {
        setPreferredCameraFacing(useFrontCamera)
        val targetCameraId = withContext(Dispatchers.Default) { findCameraIdByFacing(preferredLensFacing) }
            ?: return LeicaResult.Failure.Hardware(errorCode = -1, message = "No camera found for facing=${if (useFrontCamera) "front" else "back"}")

        closeCamera()
        openCamera(targetCameraId)
        return LeicaResult.Success(Unit)
    }

    private fun applyCurrentRequestState() {
        val camera = boundCamera ?: return
        val camera2Control = Camera2CameraControl.from(camera.cameraControl)
        val requestBuilder = CaptureRequestOptions.Builder()

        if (requestState.usesManualExposure) {
            requestBuilder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            requestState.manualIso?.let {
                requestBuilder.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, runtimeCapabilities.clampIso(it))
            }
            requestState.manualShutterUs?.let {
                requestBuilder.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, runtimeCapabilities.clampShutterUs(it) * NANOS_PER_MICROSECOND)
            }
        } else {
            requestBuilder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        }

        if (requestState.usesManualWhiteBalance && runtimeCapabilities.supportsManualWhiteBalance) {
            val gains = requireNotNull(requestState.whiteBalanceGains())
            requestBuilder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            requestBuilder.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            requestBuilder.setCaptureRequestOption(
                CaptureRequest.COLOR_CORRECTION_GAINS,
                RggbChannelVector(gains.red, gains.greenEven, gains.greenOdd, gains.blue),
            )
        } else {
            requestBuilder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        }

        camera2Control.setCaptureRequestOptions(requestBuilder.build())

        if (!requestState.usesManualExposure && runtimeCapabilities.exposureCompensationStepEv > 0f) {
            val index = requestState.exposureCompensationIndex(
                stepEv = runtimeCapabilities.exposureCompensationStepEv,
                supportedRange = runtimeCapabilities.exposureCompensationRange,
            )
            camera.cameraControl.setExposureCompensationIndex(index)
        }
    }

    private fun buildOutputOptions(): ImageCapture.OutputFileOptions {
        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.US)
            .format(java.util.Date())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "LeicaCam_$timestamp.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/LeicaCam")
            }
        }
        return ImageCapture.OutputFileOptions.Builder(
            appContext.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values,
        ).build()
    }

    private suspend fun awaitCameraProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { continuation ->
            val future = ProcessCameraProvider.getInstance(appContext)
            future.addListener(
                {
                    runCatching { future.get() }
                        .onSuccess { continuation.resume(it) }
                        .onFailure { continuation.resumeWithException(it) }
                },
                mainExecutor,
            )
            continuation.invokeOnCancellation { future.cancel(true) }
        }

    private fun loadCapabilities(cameraId: String): CameraRuntimeCapabilities {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES).orEmpty()
        Logger.i(
            TAG,
            "Camera2 capabilities for $cameraId: raw=${capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)}, " +
                "manualSensor=${capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)}, " +
                "priority=${camera2PriorityScore(cameraId)}",
        )
        return CameraRuntimeCapabilities(
            isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.toIntRange(),
            shutterRangeUs = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.toMicrosecondRange(),
            exposureCompensationRange = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)?.toIntRange() ?: 0..0,
            exposureCompensationStepEv = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f,
            supportsManualWhiteBalance = characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
                ?.contains(CaptureRequest.CONTROL_AWB_MODE_OFF) == true,
        )
    }

    private fun camera2PriorityScore(cameraId: String): Int {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
        val hardwareLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
        val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES).orEmpty()

        var score = 0
        if (facing == preferredLensFacing) score += 1_000
        if (facing == CameraCharacteristics.LENS_FACING_BACK) score += 100
        if (hardwareLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3) score += 40
        if (hardwareLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL) score += 30
        if (capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)) score += 20
        if (capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)) score += 20
        if (capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)) score += 5
        return score
    }

    private fun selectorForCameraId(cameraId: String): CameraXSelector {
        val facing = cameraManager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.LENS_FACING)
        val fallbackSelector = if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
            CameraXSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraXSelector.DEFAULT_BACK_CAMERA
        }
        return CameraXSelector.Builder.fromSelector(fallbackSelector)
            .addCameraFilter { infos ->
                infos.filter { Camera2CameraInfo.from(it).cameraId == cameraId }
                    .ifEmpty { infos }
            }
            .build()
    }

    private fun findCameraIdByFacing(facing: Int): String? {
        return cameraManager.cameraIdList.firstOrNull { id ->
            runCatching {
                cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == facing
            }.getOrDefault(false)
        }
    }

    private companion object {
        private const val TAG = "Camera2CameraController"
        private const val NANOS_PER_MICROSECOND = 1_000L
    }
}

enum class CaptureFlashMode {
    OFF,
    ON,
    AUTO,
    ;

    fun toCameraX(): Int = when (this) {
        OFF -> ImageCapture.FLASH_MODE_OFF
        ON -> ImageCapture.FLASH_MODE_ON
        AUTO -> ImageCapture.FLASH_MODE_AUTO
    }
}

private data class CameraRuntimeCapabilities(
    val isoRange: IntRange? = null,
    val shutterRangeUs: LongRange? = null,
    val exposureCompensationRange: IntRange = 0..0,
    val exposureCompensationStepEv: Float = 0f,
    val supportsManualWhiteBalance: Boolean = false,
) {
    fun clampIso(iso: Int): Int = isoRange?.let { iso.coerceIn(it) } ?: iso

    fun clampShutterUs(shutterUs: Long): Long = shutterRangeUs?.let { shutterUs.coerceIn(it) } ?: shutterUs
}

private fun Range<Int>.toIntRange(): IntRange = lower..upper

private fun Range<Long>.toMicrosecondRange(): LongRange =
    (lower / 1_000L)..(upper / 1_000L)
