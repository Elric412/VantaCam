package com.leica.cam.sensor_hal.capability

import android.graphics.Rect
import android.hardware.camera2.params.ColorSpaceTransform
import android.util.Size
import android.util.SizeF
import com.leica.cam.sensor_hal.sensor.profiles.SensorProfile
import com.leica.cam.sensor_hal.sensor.profiles.SensorProfileRegistry
import com.leica.cam.sensor_hal.soc.SoCProfile

/**
 * Full hardware capability profile for a physical camera participating in a logical camera setup.
 *
 * Extended with LUMO-specific fields:
 * - [sensorProfile]: Per-sensor tuning from [SensorProfileRegistry]
 * - [socProfile]: SoC-specific compute routing from [SoCProfile]
 * - [halSensorString]: Raw HAL sensor identifier for lens variant detection
 * - [lensVariant]: Detected lens manufacturer variant (e.g., "aac", "ofilm")
 * - [isChinaMarketVariant]: Whether this is a `_cn` hardware variant
 */
data class CameraCapabilityProfile(
    val logicalCameraId: String,
    val physicalCameraId: String,
    val focalLengths: FloatArray,
    val apertures: FloatArray,
    val minimumFocusDistance: Float,
    val physicalSensorSize: SizeF,
    val pixelArraySize: Size,
    val activeArraySize: Rect,
    val lensIntrinsicCalibration: FloatArray?,
    val lensPoseTranslation: FloatArray?,
    val lensPoseRotation: FloatArray?,
    val lensDistortion: FloatArray?,
    val lensRadialDistortion: FloatArray?,
    val availableAberrationModes: IntArray,
    val sensorNoiseProfile: Array<Pair<Double, Double>>?,
    val sensorCalibrationTransform1: ColorSpaceTransform?,
    val sensorCalibrationTransform2: ColorSpaceTransform?,
    val sensorColorTransform1: ColorSpaceTransform?,
    val sensorColorTransform2: ColorSpaceTransform?,
    val sensorForwardMatrix1: ColorSpaceTransform?,
    val sensorForwardMatrix2: ColorSpaceTransform?,
    val sensorReferenceIlluminant1: Int?,
    val sensorReferenceIlluminant2: Int?,
    // ── LUMO extensions ──────────────────────────────────────────────
    /** Resolved sensor tuning profile. Null if sensor is unrecognised. */
    val sensorProfile: SensorProfile? = null,
    /** SoC compute routing profile. Null until SoC detection runs. */
    val socProfile: SoCProfile? = null,
    /** Raw HAL sensor identifier string from Camera2 INFO_VERSION. */
    val halSensorString: String? = null,
    /** Detected lens manufacturer variant (e.g., "aac", "ofilm", "sunny"). */
    val lensVariant: String? = null,
    /** Whether this is a China-market `_cn` hardware variant. */
    val isChinaMarketVariant: Boolean = false,
)

/**
 * Portable metadata contract for camera capability extraction.
 */
data class CameraMetadata(
    val physicalIds: Set<String> = emptySet(),
    val focalLengths: FloatArray,
    val apertures: FloatArray,
    val minimumFocusDistance: Float,
    val physicalSensorSize: SizeF,
    val pixelArraySize: Size,
    val activeArraySize: Rect,
    val lensIntrinsicCalibration: FloatArray? = null,
    val lensPoseTranslation: FloatArray? = null,
    val lensPoseRotation: FloatArray? = null,
    val lensDistortion: FloatArray? = null,
    val lensRadialDistortion: FloatArray? = null,
    val availableAberrationModes: IntArray,
    val sensorNoiseProfile: Array<Pair<Double, Double>>? = null,
    val sensorCalibrationTransform1: ColorSpaceTransform? = null,
    val sensorCalibrationTransform2: ColorSpaceTransform? = null,
    val sensorColorTransform1: ColorSpaceTransform? = null,
    val sensorColorTransform2: ColorSpaceTransform? = null,
    val sensorForwardMatrix1: ColorSpaceTransform? = null,
    val sensorForwardMatrix2: ColorSpaceTransform? = null,
    val sensorReferenceIlluminant1: Int? = null,
    val sensorReferenceIlluminant2: Int? = null,
    /** HAL sensor identifier string — populated from Camera2 vendor tags. */
    val halSensorString: String? = null,
)

/**
 * Builds [CameraCapabilityProfile] entries from metadata provider.
 *
 * Auto-resolves [SensorProfile] and lens variant from HAL sensor strings
 * or pixel array dimensions as a fallback.
 */
class CameraCapabilityProfileBuilder(
    private val metadataSource: CameraMetadataSource,
    private val sensorRegistry: SensorProfileRegistry = SensorProfileRegistry(),
    private val socProfile: SoCProfile? = null,
) {
    fun buildProfiles(logicalCameraId: String): List<CameraCapabilityProfile> {
        val logicalMetadata = metadataSource.metadata(logicalCameraId)
        val physicalIds = logicalMetadata.physicalIds.ifEmpty { setOf(logicalCameraId) }

        return physicalIds.map { physicalId ->
            val metadata = metadataSource.metadata(physicalId)

            // Resolve sensor profile: HAL string first, dimensions fallback
            val halString = metadata.halSensorString
            val sensorProfile = when {
                halString != null -> sensorRegistry.resolve(halString)
                else -> sensorRegistry.resolveByDimensions(
                    metadata.pixelArraySize.width,
                    metadata.pixelArraySize.height,
                )
            }

            // Extract lens variant and CN market flag
            val lensVariant = halString?.let { sensorRegistry.extractLensVariant(it) }
            val isCn = halString?.let { sensorRegistry.isChinaMarketVariant(it) } ?: false

            CameraCapabilityProfile(
                logicalCameraId = logicalCameraId,
                physicalCameraId = physicalId,
                focalLengths = metadata.focalLengths,
                apertures = metadata.apertures,
                minimumFocusDistance = metadata.minimumFocusDistance,
                physicalSensorSize = metadata.physicalSensorSize,
                pixelArraySize = metadata.pixelArraySize,
                activeArraySize = metadata.activeArraySize,
                lensIntrinsicCalibration = metadata.lensIntrinsicCalibration,
                lensPoseTranslation = metadata.lensPoseTranslation,
                lensPoseRotation = metadata.lensPoseRotation,
                lensDistortion = metadata.lensDistortion,
                lensRadialDistortion = metadata.lensRadialDistortion,
                availableAberrationModes = metadata.availableAberrationModes,
                sensorNoiseProfile = metadata.sensorNoiseProfile,
                sensorCalibrationTransform1 = metadata.sensorCalibrationTransform1,
                sensorCalibrationTransform2 = metadata.sensorCalibrationTransform2,
                sensorColorTransform1 = metadata.sensorColorTransform1,
                sensorColorTransform2 = metadata.sensorColorTransform2,
                sensorForwardMatrix1 = metadata.sensorForwardMatrix1,
                sensorForwardMatrix2 = metadata.sensorForwardMatrix2,
                sensorReferenceIlluminant1 = metadata.sensorReferenceIlluminant1,
                sensorReferenceIlluminant2 = metadata.sensorReferenceIlluminant2,
                // LUMO extensions
                sensorProfile = sensorProfile,
                socProfile = socProfile,
                halSensorString = halString,
                lensVariant = lensVariant,
                isChinaMarketVariant = isCn,
            )
        }
    }
}

/** Abstraction for retrieving camera metadata from CameraManager. */
fun interface CameraMetadataSource {
    fun metadata(cameraId: String): CameraMetadata
}
