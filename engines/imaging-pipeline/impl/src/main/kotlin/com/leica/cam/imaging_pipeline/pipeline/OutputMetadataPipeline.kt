package com.leica.cam.imaging_pipeline.pipeline

import com.leica.cam.common.result.LeicaResult
import com.leica.cam.common.result.PipelineStage
import java.time.Instant
import java.util.UUID

/** Supported output formats for phase 10 image exports. */
enum class OutputPhotoFormat {
    JPEG,
    HEIC_PHOTO,
    HEIC_HDR,
    RAW_DNG,
    RAW_PLUS_JPEG,
    PRORAW_STYLE,
}

/** Full export request assembled from capture + pipeline state. */
data class ExportRequest(
    val outputFormat: OutputPhotoFormat,
    val captureTimestampUtc: Instant,
    val captureMetadata: CaptureMetadata,
    val lensMetadata: LensMetadata,
    val dngCalibrationMetadata: DngCalibrationMetadata,
    val processingMetadata: ProcessingMetadata,
    val asShotNeutral: Triple<Float, Float, Float>,
    val asShotWhiteXy: Pair<Float, Float>,
)

/** Camera capture metadata that maps to EXIF and DNG fields. */
data class CaptureMetadata(
    val iso: Int,
    val exposureTimeUs: Long,
    val focalLengthMm: Float,
    val sensorTemperatureCelsius: Float?,
    val noiseProfile: List<Pair<Double, Double>>,
    val colorMatrix1: List<Double>,
    val colorMatrix2: List<Double>,
    val cameraCalibration1: List<Double>,
    val cameraCalibration2: List<Double>,
    val forwardMatrix1: List<Double>,
    val forwardMatrix2: List<Double>,
    val calibrationIlluminant1: Int,
    val calibrationIlluminant2: Int,
    val exifTags: Map<String, String>,
)

/** Lens metadata used for DNG and EXIF enrichment. */
data class LensMetadata(
    val minFocalLengthMm: Float,
    val maxFocalLengthMm: Float,
    val minAperture: Float,
    val maxAperture: Float,
    val lensModel: String,
)

/** DNG calibration payload used for opcode construction. */
data class DngCalibrationMetadata(
    val warpRectilinearCoefficients: List<Double>,
    val gainMapCoefficients: List<Double>,
    val vignetteRadialCoefficients: List<Double>,
    val subTileBlockSize: Int,
)

/** Phase 10 custom processing metadata for XMP namespace `pc:`. */
data class ProcessingMetadata(
    val lutProfile: String,
    val hdrFrameCount: Int,
    val hdrStrategy: String,
    val wbKelvin: Int,
    val wbTint: Float,
    val wbIlluminant: String,
    val nrModel: String,
    val nrStrength: Float,
    val srApplied: Boolean,
    val neuralIspUsed: Boolean,
    val sceneCategory: String,
    val faceCount: Int,
    val shotQualityScore: Float,
    val processingTimeMs: Long,
    val appVersion: String,
    val faceRectangles: List<FaceRectangle>,
    val gpsLocation: GpsLocation?,
)

/** Face rectangle captured during analysis. Never persisted to file metadata. */
data class FaceRectangle(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

/** GPS payload controlled by privacy settings. */
data class GpsLocation(
    val latitude: Double,
    val longitude: Double,
)

/** HEIC output profile selected at export time. */
data class HeicOutputProfile(
    val colorSpace: String,
    val bitDepth: Int,
    val hdr10: Boolean,
)

/** Immutable representation of a DNG payload ready for a writer backend. */
data class DngContainer(
    val tags: Map<String, String>,
    val opcodeList1: String,
    val opcodeList2: String,
    val opcodeList3: String,
    val previewColorSpace: String,
    val fullResolutionJpegPreviewEmbedded: Boolean,
)

/** Sanitized metadata after privacy policy enforcement and export hardening. */
data class SanitizedMetadata(
    val xmpTags: Map<String, String>,
    val exifTags: Map<String, String>,
)

/** File write protocol for MediaStore PENDING writes on API 29+. */
data class PendingWritePlan(
    val insertValues: Map<String, String>,
    val finalizeValues: Map<String, String>,
)

/** Persistent privacy audit record for one exported file. */
data class PrivacyAuditEntry(
    val id: String,
    val timestampUtc: Instant,
    val outputFormat: OutputPhotoFormat,
    val includedMetadataKeys: Set<String>,
)

/** User-controllable export privacy settings. */
data class PrivacySettings(
    val locationTaggingEnabled: Boolean = false,
)

/**
 * DNG metadata composer for phase 10.
 *
 * Produces a full tag set aligned with DNG 1.6 requirements described in Implementation.md.
 */
class DngMetadataComposer {
    fun compose(request: ExportRequest): LeicaResult<DngContainer> {
        val capture = request.captureMetadata
        if (capture.iso <= 0 || capture.exposureTimeUs <= 0L || capture.focalLengthMm <= 0f) {
            return LeicaResult.Failure.Pipeline(PipelineStage.IMAGING_PIPELINE, "Invalid capture metadata for DNG")
        }
        if (request.dngCalibrationMetadata.subTileBlockSize <= 0) {
            return LeicaResult.Failure.Pipeline(PipelineStage.IMAGING_PIPELINE, "SubTileBlockSize must be positive")
        }

        val tags = linkedMapOf(
            "DNGVersion" to "1.6.0.0",
            "DNGBackwardVersion" to "1.4.0.0",
            "AsShotNeutral" to formatTriple(request.asShotNeutral),
            "AsShotWhiteXY" to formatPair(request.asShotWhiteXy),
            "ColorMatrix1" to formatList(capture.colorMatrix1),
            "ColorMatrix2" to formatList(capture.colorMatrix2),
            "CameraCalibration1" to formatList(capture.cameraCalibration1),
            "CameraCalibration2" to formatList(capture.cameraCalibration2),
            "ForwardMatrix1" to formatList(capture.forwardMatrix1),
            "ForwardMatrix2" to formatList(capture.forwardMatrix2),
            "CalibrationIlluminant1" to capture.calibrationIlluminant1.toString(),
            "CalibrationIlluminant2" to capture.calibrationIlluminant2.toString(),
            "SensorTemperature" to (capture.sensorTemperatureCelsius?.let { "%.2f".format(it) } ?: "unknown"),
            "LensInfo" to listOf(
                request.lensMetadata.minFocalLengthMm,
                request.lensMetadata.maxFocalLengthMm,
                request.lensMetadata.minAperture,
                request.lensMetadata.maxAperture,
            ).joinToString(",") { "%.2f".format(it) },
            "LensModel" to request.lensMetadata.lensModel,
            "NoiseProfile" to capture.noiseProfile.joinToString(";") { "${it.first},${it.second}" },
            "SubTileBlockSize" to request.dngCalibrationMetadata.subTileBlockSize.toString(),
            "PreviewColorSpace" to "sRGB",
        )

        capture.exifTags.toSortedMap().forEach { (key, value) ->
            tags["EXIF.$key"] = value
        }

        val container = DngContainer(
            tags = tags,
            opcodeList1 = buildOpcode("WarpRectilinear", request.dngCalibrationMetadata.warpRectilinearCoefficients),
            opcodeList2 = buildOpcode("GainMap", request.dngCalibrationMetadata.gainMapCoefficients),
            opcodeList3 = buildOpcode("FixVignetteRadial", request.dngCalibrationMetadata.vignetteRadialCoefficients),
            previewColorSpace = "sRGB",
            fullResolutionJpegPreviewEmbedded = true,
        )

        return if (REQUIRED_DNG_TAGS.all(tags::containsKey)) {
            LeicaResult.Success(container)
        } else {
            LeicaResult.Failure.Pipeline(PipelineStage.IMAGING_PIPELINE, "DNG payload missing required tags")
        }
    }

    private fun buildOpcode(name: String, coefficients: List<Double>): String {
        require(coefficients.isNotEmpty()) { "$name coefficients must not be empty" }
        return "$name(${formatList(coefficients)})"
    }

    private fun formatTriple(value: Triple<Float, Float, Float>): String =
        listOf(value.first, value.second, value.third).joinToString(",") { "%.6f".format(it) }

    private fun formatPair(value: Pair<Float, Float>): String =
        listOf(value.first, value.second).joinToString(",") { "%.6f".format(it) }

    private fun formatList(value: List<Double>): String = value.joinToString(",") { "%.6f".format(it) }

    companion object {
        private val REQUIRED_DNG_TAGS = setOf(
            "DNGVersion",
            "DNGBackwardVersion",
            "AsShotNeutral",
            "AsShotWhiteXY",
            "ColorMatrix1",
            "ColorMatrix2",
            "CameraCalibration1",
            "CameraCalibration2",
            "ForwardMatrix1",
            "ForwardMatrix2",
            "CalibrationIlluminant1",
            "CalibrationIlluminant2",
            "SensorTemperature",
            "LensInfo",
            "LensModel",
            "NoiseProfile",
            "SubTileBlockSize",
            "PreviewColorSpace",
        )
    }
}

/** Decides HEIC export profile for SDR photo and HDR10 output use cases. */
class HeicProfileSelector {
    fun select(outputFormat: OutputPhotoFormat): LeicaResult<HeicOutputProfile> {
        return when (outputFormat) {
            OutputPhotoFormat.HEIC_PHOTO -> LeicaResult.Success(
                HeicOutputProfile(colorSpace = "Display P3", bitDepth = 10, hdr10 = false),
            )

            OutputPhotoFormat.HEIC_HDR -> LeicaResult.Success(
                HeicOutputProfile(colorSpace = "Rec.2020", bitDepth = 10, hdr10 = true),
            )

            else -> LeicaResult.Failure.Pipeline(PipelineStage.IMAGING_PIPELINE, "HEIC profile requested for non-HEIC format")
        }
    }
}

/** Builds phase 10 extended XMP payload with the `pc:` namespace tags. */
class XmpMetadataComposer {
    fun compose(processingMetadata: ProcessingMetadata): LeicaResult<Map<String, String>> {
        if (processingMetadata.hdrFrameCount <= 0 || processingMetadata.wbKelvin <= 0) {
            return LeicaResult.Failure.Pipeline(PipelineStage.IMAGING_PIPELINE, "Invalid XMP metadata payload")
        }
        if (processingMetadata.nrStrength !in 0f..1f || processingMetadata.shotQualityScore !in 0f..1f) {
            return LeicaResult.Failure.Pipeline(PipelineStage.IMAGING_PIPELINE, "XMP values out of bounds")
        }

        val tags = linkedMapOf(
            "pc:LUTProfile" to processingMetadata.lutProfile,
            "pc:HDRFrameCount" to processingMetadata.hdrFrameCount.toString(),
            "pc:HDRStrategy" to processingMetadata.hdrStrategy,
            "pc:WBKelvin" to processingMetadata.wbKelvin.toString(),
            "pc:WBTint" to "%.4f".format(processingMetadata.wbTint),
            "pc:WBIlluminant" to processingMetadata.wbIlluminant,
            "pc:NRModel" to processingMetadata.nrModel,
            "pc:NRStrength" to "%.4f".format(processingMetadata.nrStrength),
            "pc:SRApplied" to processingMetadata.srApplied.toString(),
            "pc:NeuralISPUsed" to processingMetadata.neuralIspUsed.toString(),
            "pc:SceneCategory" to processingMetadata.sceneCategory,
            "pc:FaceCount" to processingMetadata.faceCount.toString(),
            "pc:ShotQualityScore" to "%.4f".format(processingMetadata.shotQualityScore),
            "pc:ProcessingTimeMs" to processingMetadata.processingTimeMs.toString(),
            "pc:AppVersion" to processingMetadata.appVersion,
        )

        return LeicaResult.Success(tags)
    }

    fun toXmpPacket(tags: Map<String, String>): String {
        val body = tags.entries.joinToString("\n") { (key, value) -> "<$key>${escape(value)}</$key>" }
        return """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description xmlns:pc="http://ns.provisioncam.app/1.0/">
                  $body
                </rdf:Description>
              </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()
    }

    private fun escape(value: String): String =
        value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

/** In-memory bounded privacy audit log; intended fallback where Room is unavailable in unit tests. */
class InMemoryPrivacyAuditLog(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private val entries: ArrayDeque<PrivacyAuditEntry> = ArrayDeque(maxEntries)

    fun append(entry: PrivacyAuditEntry) {
        require(maxEntries > 0) { "maxEntries must be positive" }
        while (entries.size >= maxEntries) {
            entries.removeFirst()
        }
        entries.addLast(entry)
    }

    fun all(): List<PrivacyAuditEntry> = entries.toList()

    companion object {
        const val DEFAULT_MAX_ENTRIES = 1_000
    }
}

/**
 * Privacy-first metadata policy enforcement.
 *
 * - Removes face rectangles from persisted metadata.
 * - Conditionally includes GPS only when user opted-in.
 * - Emits a MediaStore PENDING write plan for API 29+ safe writes.
 * - Records an auditable list of embedded metadata keys.
 */
class PrivacyMetadataPolicy(
    private val auditLog: InMemoryPrivacyAuditLog,
) {
    fun enforce(
        request: ExportRequest,
        xmpTags: Map<String, String>,
        privacySettings: PrivacySettings,
    ): LeicaResult<Pair<SanitizedMetadata, PendingWritePlan>> {
        val exif = request.captureMetadata.exifTags
            .filterKeys { key ->
                !key.contains("imei", ignoreCase = true) &&
                    !key.contains("imsi", ignoreCase = true) &&
                    !key.contains("advertising", ignoreCase = true)
            }
            .toMutableMap()

        if (privacySettings.locationTaggingEnabled) {
            request.processingMetadata.gpsLocation?.let {
                exif["GPSLatitude"] = "%.6f".format(it.latitude)
                exif["GPSLongitude"] = "%.6f".format(it.longitude)
            }
        }

        val sanitizedXmp = xmpTags.toMutableMap()
        sanitizedXmp.remove("pc:FaceRectangles")

        val allMetadataKeys = sanitizedXmp.keys + exif.keys
        auditLog.append(
            PrivacyAuditEntry(
                id = UUID.randomUUID().toString(),
                timestampUtc = request.captureTimestampUtc,
                outputFormat = request.outputFormat,
                includedMetadataKeys = allMetadataKeys.toSortedSet(),
            ),
        )

        val writePlan = PendingWritePlan(
            insertValues = mapOf("IS_PENDING" to "1"),
            finalizeValues = mapOf("IS_PENDING" to "0"),
        )
        return LeicaResult.Success(
            SanitizedMetadata(
                xmpTags = sanitizedXmp.toSortedMap(),
                exifTags = exif.toSortedMap(),
            ) to writePlan,
        )
    }
}
