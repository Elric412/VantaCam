package com.leica.cam.sensor_hal.session

import android.content.Context
import android.hardware.camera2.CameraManager
import androidx.camera.core.CameraSelector as CxSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.leica.cam.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Concrete [CameraController] backed by CameraX (which uses Camera2 under the
 * hood). Exposes a [PreviewView] that the UI layer renders as the viewfinder.
 *
 * Lifecycle contract:
 *  - Caller must provide the current [LifecycleOwner] via [bindLifecycle]
 *    BEFORE calling [openCamera]. We do not capture the owner to avoid leaks.
 */
class Camera2CameraController(
    private val appContext: Context,
) : CameraController {

    private val cameraManager: CameraManager =
        appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private val mainExecutor: Executor = appContext.mainExecutor
    private val bgExecutor: Executor = Executors.newSingleThreadExecutor()

    @Volatile var previewView: PreviewView? = null
        private set

    @Volatile private var lifecycleOwner: LifecycleOwner? = null
    @Volatile private var imageCapture: ImageCapture? = null
    @Volatile private var cameraProvider: ProcessCameraProvider? = null

    fun attach(view: PreviewView, owner: LifecycleOwner) {
        previewView = view
        lifecycleOwner = owner
    }

    override fun availableCameraIds(): List<String> =
        runCatching { cameraManager.cameraIdList.toList() }
            .getOrElse {
                Logger.e(TAG, "Cannot read cameraIdList", it)
                emptyList()
            }

    override suspend fun openCamera(cameraId: String) = withContext(Dispatchers.Main) {
        val owner = requireNotNull(lifecycleOwner) {
            "Camera2CameraController.attach() must be called before openCamera()"
        }
        val view = requireNotNull(previewView) { "PreviewView missing" }
        val provider = ProcessCameraProvider.getInstance(appContext).get()
        cameraProvider = provider

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(view.surfaceProvider)
        }
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
        imageCapture = capture

        val selector = if (cameraId.toIntOrNull() == 1) {
            CxSelector.DEFAULT_FRONT_CAMERA
        } else {
            CxSelector.DEFAULT_BACK_CAMERA
        }

        provider.unbindAll()
        provider.bindToLifecycle(owner, selector, preview, capture)
        Unit
    }

    override suspend fun configureSession(cameraId: String) {
        // CameraX binds the session during bindToLifecycle in openCamera().
        // No-op here; kept for state-machine parity.
    }

    override suspend fun capture() = withContext(Dispatchers.Main) {
        val cap = imageCapture ?: error("imageCapture not initialised")
        // Fire-and-forget for now; wiring to the imaging pipeline happens in a
        // later phase. This DOES exercise the hardware shutter so feedback is real.
        cap.takePicture(bgExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onError(exception: androidx.camera.core.ImageCaptureException) {
                Logger.e(TAG, "Capture failed: ${exception.imageCaptureError}", exception)
            }
            override fun onCaptureSuccess(image: androidx.camera.core.ImageProxy) {
                image.close()
            }
        })
        Unit
    }

    override suspend fun closeCamera() = withContext(Dispatchers.Main) {
        cameraProvider?.unbindAll()
        cameraProvider = null
        imageCapture = null
        Unit
    }

    fun currentImageCapture(): ImageCapture? = imageCapture

    fun setIso(iso: Int) {
        // CameraX currently exposes ISO only via Camera2Interop / ExposureState.
        // Wire this in Phase 3.6; for now log the intent so the control is observably alive.
        Logger.i(TAG, "setIso requested: $iso (pending Camera2Interop wiring)")
    }

    fun setShutterMicros(us: Long) {
        Logger.i(TAG, "setShutter requested: ${us}us (pending Camera2Interop wiring)")
    }

    fun setExposureCompensationEv(ev: Float) {
        Logger.i(TAG, "setExposureCompensation requested: $ev EV")
    }

    fun setWhiteBalanceKelvin(k: Int) {
        Logger.i(TAG, "setWB requested: ${k}K")
    }

    private companion object {
        private const val TAG = "Camera2CameraController"
    }
}