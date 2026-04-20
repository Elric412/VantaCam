package com.leica.cam.camera_core.impl.isp

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.ExtensionSessionConfiguration
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.view.Surface
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Demonstrates Camera2 session setup with chipset-aware ISP tuning and a safe fallback.
 */
class Camera2SessionConfigurator(
    private val context: Context,
    private val chipsetDetector: ChipsetDetector = ChipsetDetector(),
) {
    private val cameraExecutor: Executor = Executors.newSingleThreadExecutor()

    fun configureSession(
        cameraDevice: CameraDevice,
        cameraId: String,
        surfaces: List<Surface>,
        captureIntent: CaptureIntent,
        stateCallback: CameraCaptureSession.StateCallback,
    ) {
        val optimizer = IspOptimizer.create(chipsetDetector.detect())
        val sessionBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)

        optimizer.applySessionParameters(sessionBuilder)
        applyStreamUseCase(sessionBuilder, optimizer.selectStreamUseCase(captureIntent))

        val outputConfigs = surfaces.map { OutputConfiguration(it) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val extensionType = chooseExtension(cameraId, captureIntent)
            if (extensionType != null) {
                try {
                    val extensionConfig = ExtensionSessionConfiguration(
                        extensionType,
                        outputConfigs,
                        cameraExecutor,
                        stateCallback,
                    )
                    extensionConfig.sessionParameters = sessionBuilder.build()
                    cameraDevice.createExtensionSession(extensionConfig)
                    return
                } catch (_: Throwable) {
                    // Extension path is optional; continue with standard Camera2 session setup.
                }
            }
        }

        val sessionConfiguration = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            outputConfigs,
            cameraExecutor,
            stateCallback,
        )
        sessionConfiguration.sessionParameters = sessionBuilder.build()
        cameraDevice.createCaptureSession(sessionConfiguration)
    }

    fun buildRepeatingRequest(
        cameraDevice: CameraDevice,
        captureIntent: CaptureIntent,
        targetSurface: Surface,
    ): CaptureRequest {
        val optimizer = IspOptimizer.create(chipsetDetector.detect())
        val requestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        requestBuilder.addTarget(targetSurface)
        optimizer.applyRepeatingRequest(requestBuilder, captureIntent)
        applyStreamUseCase(requestBuilder, optimizer.selectStreamUseCase(captureIntent))
        return requestBuilder.build()
    }

    private fun applyStreamUseCase(builder: CaptureRequest.Builder, streamUseCase: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }

        try {
            builder.set(CaptureRequest.STREAM_USE_CASE, streamUseCase)
        } catch (_: Throwable) {
            // HAL does not support stream use case on this stream configuration.
        }
    }

    private fun chooseExtension(cameraId: String, captureIntent: CaptureIntent): Int? {
        val extensionManager = CameraExtensionManagerCompat.from(context) ?: return null
        val extensionChars = extensionManager.createCameraExtensionCharacteristics(cameraId) ?: return null

        val preferredExtension = when (captureIntent) {
            CaptureIntent.Night -> CameraExtensionManagerCompat.EXTENSION_NIGHT
            CaptureIntent.StillCapture,
            CaptureIntent.Portrait,
            CaptureIntent.Landscape,
            -> CameraExtensionManagerCompat.EXTENSION_HDR

            else -> null
        } ?: return null

        return preferredExtension.takeIf { extensionChars.supportedExtensions().contains(it) }
    }
}

/**
 * Lightweight wrapper around extension APIs so extension checks stay isolated.
 */
private class CameraExtensionManagerCompat private constructor(
    private val manager: Any,
) {

    fun createCameraExtensionCharacteristics(cameraId: String): CameraExtensionCharacteristicsCompat? {
        return try {
            val method = manager.javaClass.getMethod(
                "getCameraExtensionCharacteristics",
                String::class.java,
            )
            val extensionChars = method.invoke(manager, cameraId)
            CameraExtensionCharacteristicsCompat(extensionChars)
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        const val EXTENSION_HDR = 3
        const val EXTENSION_NIGHT = 4

        fun from(context: Context): CameraExtensionManagerCompat? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                return null
            }

            return try {
                val managerClass = Class.forName("android.hardware.camera2.CameraExtensionCharacteristics")
                val serviceNameField = managerClass.getField("CAMERA_EXTENSION_SERVICE")
                val serviceName = serviceNameField.get(null) as String
                val systemService = context.getSystemService(serviceName) ?: return null
                CameraExtensionManagerCompat(systemService)
            } catch (_: Throwable) {
                null
            }
        }
    }
}

private class CameraExtensionCharacteristicsCompat(
    private val extensionCharacteristics: Any,
) {
    fun supportedExtensions(): Set<Int> {
        return try {
            val method = extensionCharacteristics.javaClass.getMethod("getSupportedExtensions")
            @Suppress("UNCHECKED_CAST")
            method.invoke(extensionCharacteristics) as? Set<Int> ?: emptySet()
        } catch (_: Throwable) {
            emptySet()
        }
    }
}
