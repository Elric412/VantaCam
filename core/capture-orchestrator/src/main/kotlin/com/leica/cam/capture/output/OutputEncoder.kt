package com.leica.cam.capture.output

import com.leica.cam.common.logging.LeicaLogger
import com.leica.cam.common.result.LeicaResult
import com.leica.cam.common.result.PipelineStage
import com.leica.cam.hardware.contracts.photon.PhotonBuffer
import com.leica.cam.photon_matrix.ProXdrOutputMode

/**
 * Output Encoder handles the final stage of the capture pipeline:
 * encoding the processed photon buffer into the requested output format.
 *
 * Supported output formats:
 *   - HEIC (default): High-efficiency image format, 10-bit, HDR metadata
 *   - DNG: Raw output with LUMO processing metadata in XMP sidecar
 *   - HDR10: PQ transfer function, BT.2020 gamut, HDR10+ metadata
 *   - ProXDR: Extended dynamic range format with gain-map for SDR fallback
 *
 * The encoder also embeds:
 *   - EXIF metadata (ISO, shutter, focal length, GPS, device info)
 *   - XMP sidecar for LUMO pipeline diagnostics
 *   - ICC colour profile for Display P3 or sRGB
 *   - Gain map for ProXDR/HDR backward compatibility
 *
 * Reference: Implementation.md — Output Pipeline
 */
class OutputEncoder {

    /**
     * Encode the processed buffer into the requested output format.
     *
     * @param buffer Processed photon buffer ready for encoding
     * @param outputMode Target output format
     * @param metadata Capture metadata for EXIF embedding
     * @param pipelineDiagnostics Optional pipeline diagnostic data for XMP
     * @return Encoded output result
     */
    fun encode(
        buffer: PhotonBuffer,
        outputMode: ProXdrOutputMode,
        metadata: OutputMetadata,
        pipelineDiagnostics: PipelineDiagnostics? = null,
    ): LeicaResult<EncodedOutput> {
        return when (outputMode) {
            is ProXdrOutputMode.Heic -> encodeHeic(buffer, metadata, pipelineDiagnostics)
            is ProXdrOutputMode.Dng -> encodeDng(buffer, metadata, pipelineDiagnostics)
            is ProXdrOutputMode.Hdr10 -> encodeHdr10(buffer, metadata, pipelineDiagnostics)
            is ProXdrOutputMode.ProXdr -> encodeProXdr(buffer, metadata, pipelineDiagnostics)
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // HEIC Encoding
    // ──────────────────────────────────────────────────────────────────

    private fun encodeHeic(
        buffer: PhotonBuffer,
        metadata: OutputMetadata,
        diagnostics: PipelineDiagnostics?,
    ): LeicaResult<EncodedOutput> {
        // In production: MediaCodec HEVC encoder with 10-bit profile
        // Applies sRGB or Display P3 ICC profile
        // Embeds EXIF with all capture metadata

        val exif = buildExifData(metadata)
        val iccProfile = if (metadata.useDisplayP3) ICC_DISPLAY_P3 else ICC_SRGB

        return LeicaResult.Success(
            EncodedOutput(
                format = OutputFormat.HEIC,
                width = buffer.width,
                height = buffer.height,
                bitDepth = 10,
                colorSpace = if (metadata.useDisplayP3) "Display P3" else "sRGB",
                transferFunction = "sRGB",
                estimatedFileSizeBytes = estimateFileSize(buffer, OutputFormat.HEIC),
                exifData = exif,
                iccProfile = iccProfile,
                xmpSidecar = diagnostics?.let { buildXmpSidecar(it) },
                hasGainMap = false,
            ),
        )
    }

    // ──────────────────────────────────────────────────────────────────
    // DNG Encoding
    // ──────────────────────────────────────────────────────────────────

    private fun encodeDng(
        buffer: PhotonBuffer,
        metadata: OutputMetadata,
        diagnostics: PipelineDiagnostics?,
    ): LeicaResult<EncodedOutput> {
        // In production: DNG writer with LUMO processing metadata
        // Preserves full 16-bit RAW data
        // Embeds colour matrices, noise profiles, and calibration data

        val exif = buildExifData(metadata)

        return LeicaResult.Success(
            EncodedOutput(
                format = OutputFormat.DNG,
                width = buffer.width,
                height = buffer.height,
                bitDepth = 16,
                colorSpace = "ProPhoto RGB",
                transferFunction = "Linear",
                estimatedFileSizeBytes = estimateFileSize(buffer, OutputFormat.DNG),
                exifData = exif,
                iccProfile = ICC_PROPHOTO,
                xmpSidecar = diagnostics?.let { buildXmpSidecar(it) },
                hasGainMap = false,
            ),
        )
    }

    // ──────────────────────────────────────────────────────────────────
    // HDR10 Encoding
    // ──────────────────────────────────────────────────────────────────

    private fun encodeHdr10(
        buffer: PhotonBuffer,
        metadata: OutputMetadata,
        diagnostics: PipelineDiagnostics?,
    ): LeicaResult<EncodedOutput> {
        // In production: HEIF with PQ transfer function
        // BT.2020 gamut, HDR10+ dynamic metadata
        // MaxCLL and MaxFALL computed from buffer content

        val exif = buildExifData(metadata)

        return LeicaResult.Success(
            EncodedOutput(
                format = OutputFormat.HDR10,
                width = buffer.width,
                height = buffer.height,
                bitDepth = 10,
                colorSpace = "BT.2020",
                transferFunction = "PQ (ST 2084)",
                estimatedFileSizeBytes = estimateFileSize(buffer, OutputFormat.HDR10),
                exifData = exif,
                iccProfile = ICC_BT2020,
                xmpSidecar = diagnostics?.let { buildXmpSidecar(it) },
                hasGainMap = false,
                hdrMetadata = HdrMetadata(
                    maxCll = 1000, // Max Content Light Level (nits)
                    maxFall = 400, // Max Frame Average Light Level (nits)
                    masteringDisplayPrimaries = "BT.2020",
                    masteringDisplayLuminance = "0.0001-1000",
                ),
            ),
        )
    }

    // ──────────────────────────────────────────────────────────────────
    // ProXDR Encoding
    // ──────────────────────────────────────────────────────────────────

    private fun encodeProXdr(
        buffer: PhotonBuffer,
        metadata: OutputMetadata,
        diagnostics: PipelineDiagnostics?,
    ): LeicaResult<EncodedOutput> {
        // In production: HEIF with gain map for SDR/HDR dual rendering
        // Gain map encodes per-pixel HDR recovery data
        // SDR fallback renders at base exposure
        // HDR rendering applies gain map for extended dynamic range

        val exif = buildExifData(metadata)

        return LeicaResult.Success(
            EncodedOutput(
                format = OutputFormat.PRO_XDR,
                width = buffer.width,
                height = buffer.height,
                bitDepth = 10,
                colorSpace = "Display P3",
                transferFunction = "HLG",
                estimatedFileSizeBytes = estimateFileSize(buffer, OutputFormat.PRO_XDR),
                exifData = exif,
                iccProfile = ICC_DISPLAY_P3,
                xmpSidecar = diagnostics?.let { buildXmpSidecar(it) },
                hasGainMap = true,
                hdrMetadata = HdrMetadata(
                    maxCll = 1600,
                    maxFall = 600,
                    masteringDisplayPrimaries = "Display P3",
                    masteringDisplayLuminance = "0.001-1600",
                ),
            ),
        )
    }

    // ──────────────────────────────────────────────────────────────────
    // Helper Functions
    // ──────────────────────────────────────────────────────────────────

    private fun buildExifData(metadata: OutputMetadata): Map<String, String> = buildMap {
        put("Make", "Leica")
        put("Model", "LeicaCam LUMO")
        put("Software", "LeicaCam v3.0")
        put("ISO", metadata.iso.toString())
        put("ExposureTime", "${metadata.exposureTimeNs}ns")
        put("FocalLength", "${metadata.focalLengthMm}mm")
        put("WhiteBalance", "${metadata.whiteBalanceKelvin}K")
        put("DateTime", metadata.captureTimestamp)
        put("SceneType", metadata.sceneLabel)
        put("ProcessingPipeline", "LUMO 3.0")
        metadata.gpsLatitude?.let { put("GPSLatitude", it.toString()) }
        metadata.gpsLongitude?.let { put("GPSLongitude", it.toString()) }
    }

    private fun buildXmpSidecar(diagnostics: PipelineDiagnostics): String = buildString {
        appendLine("<?xpacket begin='﻿' id='W5M0MpCehiHzreSzNTczkc9d'?>")
        appendLine("<x:xmpmeta xmlns:x='adobe:ns:meta/'>")
        appendLine("  <rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'>")
        appendLine("    <rdf:Description")
        appendLine("      leica:PipelineVersion='LUMO 3.0'")
        appendLine("      leica:CaptureLatencyMs='${diagnostics.captureLatencyMs}'")
        appendLine("      leica:IspMode='${diagnostics.ispMode}'")
        appendLine("      leica:SceneLabel='${diagnostics.sceneLabel}'")
        appendLine("      leica:FocusConfidence='${diagnostics.focusConfidence}'")
        appendLine("      leica:HdrMode='${diagnostics.hdrMode}'")
        appendLine("      leica:ColorProfile='${diagnostics.colorProfile}'")
        appendLine("      leica:ToneProfile='${diagnostics.toneProfile}'")
        appendLine("      leica:GrainAmount='${diagnostics.grainAmount}'")
        appendLine("      leica:FrameCount='${diagnostics.burstFrameCount}'")
        appendLine("    />")
        appendLine("  </rdf:RDF>")
        appendLine("</x:xmpmeta>")
        appendLine("<?xpacket end='w'?>")
    }

    private fun estimateFileSize(buffer: PhotonBuffer, format: OutputFormat): Long {
        val rawBytes = buffer.width.toLong() * buffer.height * buffer.planeCount() * 2 // 16-bit planes
        return when (format) {
            OutputFormat.HEIC -> rawBytes / 12  // ~8:1 compression
            OutputFormat.DNG -> rawBytes * 2    // Lossless + metadata overhead
            OutputFormat.HDR10 -> rawBytes / 8  // ~6:1 with HDR metadata
            OutputFormat.PRO_XDR -> rawBytes / 6 // Base + gain map
        }
    }

    companion object {
        private const val ICC_SRGB = "sRGB IEC61966-2.1"
        private const val ICC_DISPLAY_P3 = "Display P3"
        private const val ICC_PROPHOTO = "ProPhoto RGB"
        private const val ICC_BT2020 = "ITU-R BT.2020"
    }
}

// ──────────────────────────────────────────────────────────────────────
// Data Models
// ──────────────────────────────────────────────────────────────────────

enum class OutputFormat {
    HEIC, DNG, HDR10, PRO_XDR,
}

data class OutputMetadata(
    val iso: Int = 200,
    val exposureTimeNs: Long = 4_000_000,
    val focalLengthMm: Float = 24f,
    val whiteBalanceKelvin: Float = 5500f,
    val captureTimestamp: String = "",
    val sceneLabel: String = "auto",
    val useDisplayP3: Boolean = true,
    val gpsLatitude: Double? = null,
    val gpsLongitude: Double? = null,
)

data class EncodedOutput(
    val format: OutputFormat,
    val width: Int,
    val height: Int,
    val bitDepth: Int,
    val colorSpace: String,
    val transferFunction: String,
    val estimatedFileSizeBytes: Long,
    val exifData: Map<String, String>,
    val iccProfile: String,
    val xmpSidecar: String?,
    val hasGainMap: Boolean,
    val hdrMetadata: HdrMetadata? = null,
)

data class HdrMetadata(
    val maxCll: Int,
    val maxFall: Int,
    val masteringDisplayPrimaries: String,
    val masteringDisplayLuminance: String,
)

data class PipelineDiagnostics(
    val captureLatencyMs: Long,
    val ispMode: String,
    val sceneLabel: String,
    val focusConfidence: Float,
    val hdrMode: String,
    val colorProfile: String,
    val toneProfile: String,
    val grainAmount: Float,
    val burstFrameCount: Int,
)
