package com.leica.cam.camera_core.impl.isp

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest

/**
 * Applies chipset-aware camera request/session knobs.
 */
interface IspOptimizer {
    fun applySessionParameters(builder: CaptureRequest.Builder)

    fun applyRepeatingRequest(builder: CaptureRequest.Builder, captureMode: CaptureIntent)

    fun selectStreamUseCase(captureMode: CaptureIntent): Long

    companion object {
        fun create(chipsetFamily: ChipsetFamily): IspOptimizer {
            return when (chipsetFamily) {
                ChipsetFamily.Qualcomm -> QualcommIspOptimizer()
                ChipsetFamily.MediaTek -> MediaTekIspOptimizer()
                ChipsetFamily.Exynos -> ExynosIspOptimizer()
                ChipsetFamily.Generic -> GenericIspOptimizer()
            }
        }
    }
}

enum class CaptureIntent {
    Preview,
    StillCapture,
    VideoRecord,
    Night,
    Portrait,
    Landscape,
}

internal abstract class ReflectiveIspOptimizer : IspOptimizer {

    protected fun setVendorValue(
        builder: CaptureRequest.Builder,
        keyName: String,
        value: Any,
    ) {
        try {
            val keyClass = Class.forName("android.hardware.camera2.CaptureRequest\$Key")
            val constructor = keyClass.getConstructor(String::class.java, Class::class.java)
            val key = constructor.newInstance(keyName, value.javaClass)

            @Suppress("UNCHECKED_CAST")
            builder.set(key as CaptureRequest.Key<Any>, value)
        } catch (_: Throwable) {
            // Vendor key does not exist or is unsupported on this firmware.
        }
    }

    protected fun setStandardParameter(
        builder: CaptureRequest.Builder,
        key: CaptureRequest.Key<Int>,
        value: Int,
    ) {
        try {
            builder.set(key, value)
        } catch (_: Throwable) {
            // Some legacy HALs still reject standard controls for specific templates.
        }
    }
}

internal class QualcommIspOptimizer : ReflectiveIspOptimizer() {

    override fun applySessionParameters(builder: CaptureRequest.Builder) {
        // EnableInsensorZoom: improved detail retention for digital zoom path.
        setVendorValue(builder, "org.codeaurora.qcamera3.enableInsensorZoom.enableInsensorZoom", 1.toByte())

        // QCamera3.SensorMode: choose binned mode for preview responsiveness.
        setVendorValue(builder, "org.codeaurora.qcamera3.sensormode.sensor_mode", 1)
    }

    override fun applyRepeatingRequest(builder: CaptureRequest.Builder, captureMode: CaptureIntent) {
        if (captureMode == CaptureIntent.VideoRecord) {
            // QCamera3.HDRVideoMode
            setVendorValue(builder, "org.codeaurora.qcamera3.hdr_video.hdr_video_mode", 1)
        }

        // Prefer higher quality temporal denoise when HAL exposes the tag.
        setVendorValue(builder, "org.codeaurora.qcamera3.temporal_denoise.denoise", 2)
    }

    override fun selectStreamUseCase(captureMode: CaptureIntent): Long = when (captureMode) {
        CaptureIntent.StillCapture,
        CaptureIntent.Night,
        CaptureIntent.Portrait,
        CaptureIntent.Landscape,
        -> CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES_STILL_CAPTURE.toLong()

        CaptureIntent.VideoRecord -> CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES_VIDEO_RECORD.toLong()
        CaptureIntent.Preview -> CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES_PREVIEW.toLong()
    }
}

internal class MediaTekIspOptimizer : ReflectiveIspOptimizer() {

    override fun applySessionParameters(builder: CaptureRequest.Builder) {
        // MediaTek HDR video switch (namespace varies by firmware, keep reflective).
        setVendorValue(builder, "com.mediatek.camera.hdrVideoMode", 1)

        // AI noise reduction hint for Imagiq path.
        setVendorValue(builder, "com.mediatek.camera.aiNrMode", 1)
    }

    override fun applyRepeatingRequest(builder: CaptureRequest.Builder, captureMode: CaptureIntent) {
        if (captureMode == CaptureIntent.Night || captureMode == CaptureIntent.StillCapture) {
            setVendorValue(builder, "com.mediatek.camera.multiFrameNr", 1)
        }
    }

    override fun selectStreamUseCase(captureMode: CaptureIntent): Long = when (captureMode) {
        CaptureIntent.VideoRecord -> CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES_VIDEO_RECORD.toLong()
        CaptureIntent.StillCapture,
        CaptureIntent.Night,
        CaptureIntent.Portrait,
        CaptureIntent.Landscape,
        -> CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES_STILL_CAPTURE.toLong()

        CaptureIntent.Preview -> CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES_PREVIEW.toLong()
    }
}

internal class ExynosIspOptimizer : ReflectiveIspOptimizer() {

    override fun applySessionParameters(builder: CaptureRequest.Builder) {
        // Samsung M-ISP / NPU scene analysis toggle.
        setVendorValue(builder, "com.samsung.android.camera.aiSceneDetection", 1)
        setVendorValue(builder, "com.samsung.android.camera.postProcessingMode", 1)
    }

    override fun applyRepeatingRequest(builder: CaptureRequest.Builder, captureMode: CaptureIntent) {
        val sceneMode = when (captureMode) {
            CaptureIntent.Portrait -> CaptureRequest.CONTROL_SCENE_MODE_PORTRAIT
            CaptureIntent.Landscape -> CaptureRequest.CONTROL_SCENE_MODE_LANDSCAPE
            CaptureIntent.Night -> CaptureRequest.CONTROL_SCENE_MODE_NIGHT
            else -> CaptureRequest.CONTROL_SCENE_MODE_DISABLED
        }

        setStandardParameter(builder, CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE)
        setStandardParameter(builder, CaptureRequest.CONTROL_SCENE_MODE, sceneMode)
    }

    override fun selectStreamUseCase(captureMode: CaptureIntent): Long = when (captureMode) {
        CaptureIntent.VideoRecord -> CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES_VIDEO_RECORD.toLong()
        CaptureIntent.StillCapture,
        CaptureIntent.Night,
        CaptureIntent.Portrait,
        CaptureIntent.Landscape,
        -> CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES_STILL_CAPTURE.toLong()

        CaptureIntent.Preview -> CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES_PREVIEW.toLong()
    }
}

internal class GenericIspOptimizer : ReflectiveIspOptimizer() {

    override fun applySessionParameters(builder: CaptureRequest.Builder) {
        setStandardParameter(
            builder,
            CaptureRequest.NOISE_REDUCTION_MODE,
            CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY,
        )
    }

    override fun applyRepeatingRequest(builder: CaptureRequest.Builder, captureMode: CaptureIntent) {
        val captureIntent = when (captureMode) {
            CaptureIntent.VideoRecord -> CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD
            CaptureIntent.StillCapture,
            CaptureIntent.Night,
            CaptureIntent.Portrait,
            CaptureIntent.Landscape,
            -> CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE

            CaptureIntent.Preview -> CaptureRequest.CONTROL_CAPTURE_INTENT_PREVIEW
        }
        setStandardParameter(builder, CaptureRequest.CONTROL_CAPTURE_INTENT, captureIntent)
    }

    override fun selectStreamUseCase(captureMode: CaptureIntent): Long = when (captureMode) {
        CaptureIntent.VideoRecord -> CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES_VIDEO_RECORD.toLong()
        CaptureIntent.StillCapture,
        CaptureIntent.Night,
        CaptureIntent.Portrait,
        CaptureIntent.Landscape,
        -> CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES_STILL_CAPTURE.toLong()

        CaptureIntent.Preview -> CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES_PREVIEW.toLong()
    }
}
