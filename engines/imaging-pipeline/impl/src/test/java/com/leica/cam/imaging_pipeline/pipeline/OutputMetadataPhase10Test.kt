package com.leica.cam.imaging_pipeline.pipeline

import com.leica.cam.common.result.LeicaResult
import com.leica.cam.common.result.PipelineStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class OutputMetadataPhase10Test {
    private val dngComposer = DngMetadataComposer()
    private val heicSelector = HeicProfileSelector()
    private val xmpComposer = XmpMetadataComposer()

    @Test
    fun `dng composer emits required tags and opcodes`() {
        val result = dngComposer.compose(baseRequest())

        assertTrue(result is LeicaResult.Success)
        val dng = (result as LeicaResult.Success).value
        val required = setOf(
            "DNGVersion",
            "DNGBackwardVersion",
            "AsShotNeutral",
            "AsShotWhiteXY",
            "LensModel",
            "NoiseProfile",
            "PreviewColorSpace",
        )
        assertTrue(required.all(dng.tags::containsKey))
        assertTrue(dng.opcodeList1.startsWith("WarpRectilinear"))
        assertTrue(dng.opcodeList2.startsWith("GainMap"))
        assertTrue(dng.opcodeList3.startsWith("FixVignetteRadial"))
        assertTrue(dng.fullResolutionJpegPreviewEmbedded)
    }

    @Test
    fun `heic selector maps photo and hdr correctly`() {
        val sdr = heicSelector.select(OutputPhotoFormat.HEIC_PHOTO)
        val hdr = heicSelector.select(OutputPhotoFormat.HEIC_HDR)

        assertTrue(sdr is LeicaResult.Success)
        assertTrue(hdr is LeicaResult.Success)
        val sdrProfile = (sdr as LeicaResult.Success).value
        val hdrProfile = (hdr as LeicaResult.Success).value

        assertEquals("Display P3", sdrProfile.colorSpace)
        assertEquals(10, sdrProfile.bitDepth)
        assertFalse(sdrProfile.hdr10)

        assertEquals("Rec.2020", hdrProfile.colorSpace)
        assertEquals(10, hdrProfile.bitDepth)
        assertTrue(hdrProfile.hdr10)
    }

    @Test
    fun `xmp metadata composer outputs all pc tags and namespace packet`() {
        val tagsResult = xmpComposer.compose(baseProcessingMetadata())

        assertTrue(tagsResult is LeicaResult.Success)
        val tags = (tagsResult as LeicaResult.Success).value
        assertEquals(15, tags.size)
        assertTrue(tags.containsKey("pc:ShotQualityScore"))

        val packet = xmpComposer.toXmpPacket(tags)
        assertTrue(packet.contains("xmlns:pc=\"http://ns.provisioncam.app/1.0/\""))
        assertTrue(packet.contains("<pc:SceneCategory>portrait</pc:SceneCategory>"))
    }

    @Test
    fun `privacy policy strips forbidden metadata and limits audit entries`() {
        val privacyLog = InMemoryPrivacyAuditLog(maxEntries = 2)
        val policy = PrivacyMetadataPolicy(privacyLog)
        val tags = (xmpComposer.compose(baseProcessingMetadata()) as LeicaResult.Success).value

        val first = policy.enforce(baseRequest(), tags, PrivacySettings(locationTaggingEnabled = false))
        val second = policy.enforce(baseRequest(), tags, PrivacySettings(locationTaggingEnabled = true))
        val third = policy.enforce(baseRequest(), tags, PrivacySettings(locationTaggingEnabled = true))

        assertTrue(first is LeicaResult.Success)
        assertTrue(second is LeicaResult.Success)
        assertTrue(third is LeicaResult.Success)

        val firstSanitized = (first as LeicaResult.Success).value.first
        val secondSanitized = (second as LeicaResult.Success).value.first

        assertFalse(firstSanitized.exifTags.containsKey("GPSLatitude"))
        assertFalse(firstSanitized.exifTags.containsKey("GPSLongitude"))
        assertTrue(secondSanitized.exifTags.containsKey("GPSLatitude"))
        assertTrue(secondSanitized.exifTags.containsKey("GPSLongitude"))
        assertFalse(secondSanitized.exifTags.keys.any { it.contains("imei", ignoreCase = true) })

        val entries = privacyLog.all()
        assertEquals(2, entries.size)
        assertTrue(entries.all { it.includedMetadataKeys.isNotEmpty() })
    }

    private fun baseRequest(): ExportRequest {
        return ExportRequest(
            outputFormat = OutputPhotoFormat.RAW_DNG,
            captureTimestampUtc = Instant.parse("2026-04-09T10:15:30Z"),
            captureMetadata = CaptureMetadata(
                iso = 200,
                exposureTimeUs = 8_000,
                focalLengthMm = 6.8f,
                sensorTemperatureCelsius = 36.2f,
                noiseProfile = listOf(0.00042 to 0.00003, 0.00040 to 0.00002),
                colorMatrix1 = List(9) { index -> 0.1 + index * 0.01 },
                colorMatrix2 = List(9) { index -> 0.2 + index * 0.01 },
                cameraCalibration1 = List(9) { index -> 0.3 + index * 0.01 },
                cameraCalibration2 = List(9) { index -> 0.4 + index * 0.01 },
                forwardMatrix1 = List(9) { index -> 0.5 + index * 0.01 },
                forwardMatrix2 = List(9) { index -> 0.6 + index * 0.01 },
                calibrationIlluminant1 = 21,
                calibrationIlluminant2 = 17,
                exifTags = mapOf(
                    "Make" to "LeicaCam",
                    "Model" to "LC-1",
                    "IMEI" to "forbidden",
                ),
            ),
            lensMetadata = LensMetadata(
                minFocalLengthMm = 5.2f,
                maxFocalLengthMm = 23.5f,
                minAperture = 1.6f,
                maxAperture = 2.8f,
                lensModel = "Leica Summicron mobile 6.8mm",
            ),
            dngCalibrationMetadata = DngCalibrationMetadata(
                warpRectilinearCoefficients = listOf(0.01, -0.002, 0.0001),
                gainMapCoefficients = listOf(1.0, 0.98, 0.97, 1.01),
                vignetteRadialCoefficients = listOf(0.002, -0.0007, 0.00009),
                subTileBlockSize = 256,
            ),
            processingMetadata = baseProcessingMetadata().copy(
                faceRectangles = listOf(FaceRectangle(1, 1, 8, 8)),
                gpsLocation = GpsLocation(12.123456, 77.654321),
            ),
            asShotNeutral = Triple(2.0f, 1.0f, 1.8f),
            asShotWhiteXy = 0.3127f to 0.3290f,
        )
    }

    private fun baseProcessingMetadata(): ProcessingMetadata {
        return ProcessingMetadata(
            lutProfile = "Leica Natural",
            hdrFrameCount = 9,
            hdrStrategy = "MOTION_AWARE",
            wbKelvin = 5200,
            wbTint = 0.02f,
            wbIlluminant = "DAYLIGHT",
            nrModel = "FFDNet",
            nrStrength = 0.35f,
            srApplied = true,
            neuralIspUsed = true,
            sceneCategory = "portrait",
            faceCount = 1,
            shotQualityScore = 0.92f,
            processingTimeMs = 1620,
            appVersion = "1.0.0 (100)",
            faceRectangles = emptyList(),
            gpsLocation = null,
        )
    }
}
