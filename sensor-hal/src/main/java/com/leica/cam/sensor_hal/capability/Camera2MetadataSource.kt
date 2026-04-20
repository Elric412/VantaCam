package com.leica.cam.sensor_hal.capability

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Pair

/** Camera2-backed implementation of [CameraMetadataSource]. */
class Camera2MetadataSource(
    private val cameraManager: CameraManager,
) : CameraMetadataSource {
    override fun metadata(cameraId: String): CameraMetadata {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        return CameraMetadata(
            physicalIds = emptySet(),
            focalLengths = characteristics.require(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS),
            apertures = characteristics.require(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES),
            minimumFocusDistance = characteristics.require(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE),
            physicalSensorSize = characteristics.require(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE),
            pixelArraySize = characteristics.require(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE),
            activeArraySize = characteristics.require(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE),
            lensIntrinsicCalibration = characteristics.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION),
            lensPoseTranslation = characteristics.get(CameraCharacteristics.LENS_POSE_TRANSLATION),
            lensPoseRotation = characteristics.get(CameraCharacteristics.LENS_POSE_ROTATION),
            lensDistortion = null,
            lensRadialDistortion = null,
            availableAberrationModes = characteristics.require(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES),
            sensorNoiseProfile = null,
            sensorCalibrationTransform1 = characteristics.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1),
            sensorCalibrationTransform2 = characteristics.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2),
            sensorColorTransform1 = characteristics.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1),
            sensorColorTransform2 = characteristics.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2),
            sensorForwardMatrix1 = characteristics.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1),
            sensorForwardMatrix2 = characteristics.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2),
            sensorReferenceIlluminant1 = characteristics.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1)?.toInt(),
            sensorReferenceIlluminant2 = characteristics.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt(),
        )
    }

    private fun <T> CameraCharacteristics.require(key: CameraCharacteristics.Key<T>): T =
        requireNotNull(get(key)) { "Missing required key ${key.name}" }
}
