package com.leica.cam.imaging_pipeline.pipeline

import com.leica.cam.common.result.LeicaResult
import com.leica.cam.common.result.PipelineStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPipelinePhase8Test {
    private val stabilizer = OisEisFusionStabilizer()
    private val colorEngine = VideoColorProfileEngine()
    private val audioPipeline = ProfessionalAudioPipeline()
    private val lutEngine = RealTimeLutPreviewEngine()
    private val timeLapseEngine = TimeLapseEngine()
    private val cinemaEngine = CinemaVideoModeEngine()

    @Test
    fun `stabilizer returns crop + transform from sensor traces`() {
        val gyro = listOf(
            GyroSample(0L, 0.01f, 0.02f, 0.03f),
            GyroSample(8_333_333L, 0.015f, 0.018f, 0.035f),
            GyroSample(16_666_666L, 0.016f, 0.017f, 0.028f),
        )
        val accel = listOf(
            AccelerometerSample(0L, 0.01f, 0.1f, 9.7f),
            AccelerometerSample(8_333_333L, 0.02f, 0.1f, 9.68f),
        )
        val flow = listOf(
            OpticalFlowVector(20f, 12f, 0.8f, -0.5f),
            OpticalFlowVector(28f, 24f, 0.7f, -0.4f),
            OpticalFlowVector(30f, 18f, 4.5f, 4.8f),
        )

        val result = stabilizer.stabilize(
            gyroSamples = gyro,
            accelerometerSamples = accel,
            opticalFlow = flow,
            intrinsics = CameraIntrinsics(fx = 1420f, fy = 1435f, cx = 960f, cy = 540f),
            frameDurationNs = 16_666_666L,
            rowCount = 1080,
            oisRotationCompensationRadZ = 0.002f,
        )

        assertTrue(result is LeicaResult.Success)
        val value = (result as LeicaResult.Success).value
        assertEquals(0.9f, value.transform.cropFactor)
        assertEquals(1080, value.rowShear.size)
    }

    @Test
    fun `video color profile LOG and HLG transform frame`() {
        val frame = syntheticFrame(4, 4)

        val logFrame = colorEngine.apply(frame, VideoColorProfile.LOG)
        val hlgFrame = colorEngine.apply(frame, VideoColorProfile.HLG)

        assertFalse(logFrame.red.contentEquals(frame.red))
        assertFalse(hlgFrame.green.contentEquals(frame.green))
        assertTrue(logFrame.red.all { it in 0f..1f })
        assertTrue(hlgFrame.blue.all { it in 0f..1f })
    }

    @Test
    fun `professional audio pipeline limits true peak and returns success`() {
        val pcm = FloatArray(4096) { i ->
            val base = if (i % 2 == 0) 0.95f else -0.92f
            base + ((i % 64) / 64f) * 0.08f
        }

        val result = audioPipeline.processStereo(pcm)

        assertTrue(result is LeicaResult.Success)
        val processed = (result as LeicaResult.Success).value
        assertTrue(processed.all { it <= 0.891f && it >= -0.891f })
    }

    @Test
    fun `real-time LUT preview applies trilinear mapping`() {
        val frame = syntheticFrame(2, 2)
        val lut = identityWithBlueLiftLut(size = 4, blueLift = 0.1f)

        val output = lutEngine.apply(frame, lut)

        assertTrue(output.blue.zip(frame.blue).all { (out, original) -> out >= original })
    }

    @Test
    fun `time-lapse planner emits sparse frame indexes for hyper-lapse`() {
        val result = timeLapseEngine.plan(
            totalFrames = 120,
            captureIntervalMs = 500L,
            outputFps = 30,
            hyperLapse = true,
        )

        assertTrue(result is LeicaResult.Success)
        val plan = (result as LeicaResult.Success).value
        assertEquals(30, plan.selectedFrameIndexes.size)
        assertTrue(plan.stabilizationBoost > 1f)
    }

    @Test
    fun `cinema mode enables RAW DNG sequence when supported`() {
        val result = cinemaEngine.configure(
            request = CinemaVideoRequest(
                width = 3840,
                height = 2160,
                fps = 24,
                preferRawVideo = true,
                profile = VideoColorProfile.LOG,
            ),
            deviceSupportsRawVideo = true,
        )

        assertTrue(result is LeicaResult.Success)
        val config = (result as LeicaResult.Success).value
        assertTrue(config.rawDngSequenceEnabled)
        assertEquals("RAW_DNG_SEQUENCE", config.codec)
        assertEquals(12, config.bitDepth)
    }

    private fun syntheticFrame(width: Int, height: Int): PipelineFrame {
        val size = width * height
        val red = FloatArray(size)
        val green = FloatArray(size)
        val blue = FloatArray(size)
        for (i in 0 until size) {
            val v = (i.toFloat() / size).coerceIn(0f, 1f)
            red[i] = v
            green[i] = (v * 0.9f).coerceIn(0f, 1f)
            blue[i] = (v * 0.8f).coerceIn(0f, 1f)
        }
        return PipelineFrame(width, height, red, green, blue)
    }

    private fun identityWithBlueLiftLut(size: Int, blueLift: Float): Lut3D {
        val length = size * size * size
        val red = FloatArray(length)
        val green = FloatArray(length)
        val blue = FloatArray(length)
        var index = 0
        for (bz in 0 until size) {
            for (gy in 0 until size) {
                for (rx in 0 until size) {
                    val r = rx.toFloat() / (size - 1)
                    val g = gy.toFloat() / (size - 1)
                    val b = bz.toFloat() / (size - 1)
                    red[index] = r
                    green[index] = g
                    blue[index] = (b + blueLift).coerceIn(0f, 1f)
                    index++
                }
            }
        }
        return Lut3D(size, red, green, blue)
    }
}
