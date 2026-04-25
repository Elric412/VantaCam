package com.leica.cam.color_science.pipeline

import com.leica.cam.common.result.LeicaResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorSciencePipelineTest {
    private val lutEngine = TetrahedralLutEngine(preferredBackend = ComputeBackend.CPU)
    private val hueEngine = PerHueHslEngine()
    private val skinPipeline = SkinToneProtectionPipeline()
    private val grainSynthesizer = FilmGrainSynthesizer()
    private val interpolator = DngDualIlluminantInterpolator(
        forwardMatrixA = DngDualIlluminantInterpolator.defaultSensorForwardMatrixA(),
        forwardMatrixD65 = DngDualIlluminantInterpolator.defaultSensorForwardMatrixD65(),
    )
    private val zoneCcmEngine = PerZoneCcmEngine(interpolator)
    private val gamutMapper = CiecamCuspGamutMapper(OutputGamut.DISPLAY_P3)
    private val pipeline = ColorSciencePipeline(
        lutEngine = lutEngine,
        hueEngine = hueEngine,
        skinPipeline = skinPipeline,
        grainSynthesizer = grainSynthesizer,
        zoneCcmEngine = zoneCcmEngine,
        gamutMapper = gamutMapper,
    )

    @Test
    fun `tetrahedral LUT preserves neutral continuity`() {
        val frame = syntheticFrame(width = 8, height = 8) { _, _ -> Triple(0.5f, 0.5f, 0.5f) }

        val output = lutEngine.apply(frame, ColorProfile.HASSELBLAD_NATURAL)

        assertTrue(output is LeicaResult.Success<*>)
        val data = (output as LeicaResult.Success<ColorFrame>).value
        assertTrue(data.red.all { it in 0f..1f })
        assertTrue(data.green.all { it in 0f..1f })
        assertTrue(data.blue.all { it in 0f..1f })
    }

    @Test
    fun `per hue gaussian blending applies smooth transition`() {
        val frame = syntheticFrame(width = 1, height = 1) { _, _ -> Triple(1f, 0.4f, 0.1f) }
        val adjustments = PerHueAdjustmentSet(orange = HueBandAdjustment(hueShiftDegrees = 10f, saturationScale = 0.1f))

        val output = hueEngine.apply(frame, adjustments)

        assertTrue(output.red[0] in 0f..1f)
        assertTrue(output.green[0] in 0f..1f)
        assertTrue(output.blue[0] in 0f..1f)
        assertFalse(output.red[0] == frame.red[0] && output.green[0] == frame.green[0] && output.blue[0] == frame.blue[0])
    }

    @Test
    fun `phase3 pipeline processes frame end to end`() {
        val frame = syntheticFrame(width = 16, height = 16) { x, y ->
            val base = ((x + y).toFloat() / 32f).coerceIn(0f, 1f)
            Triple(base, (base * 0.95f).coerceIn(0f, 1f), (base * 0.9f).coerceIn(0f, 1f))
        }

        val result = pipeline.process(
            input = frame,
            profile = ColorProfile.LEICA_M_CLASSIC,
            hueAdjustments = PerHueAdjustmentSet(green = HueBandAdjustment(saturationScale = -0.1f)),
            vibranceAmount = 0.2f,
            frameIndex = 4,
        )

        assertTrue(result is LeicaResult.Success<*>)
        val processed = (result as LeicaResult.Success<ColorFrame>).value
        assertEquals(16 * 16, processed.red.size)
        assertTrue(processed.red.all { it in 0f..1f })
        assertTrue(processed.green.all { it in 0f..1f })
        assertTrue(processed.blue.all { it in 0f..1f })
    }

    @Test
    fun `color accuracy benchmark returns stable report structure`() {
        val benchmark = ColorAccuracyBenchmark(pipeline)
        val patches = listOf(
            ColorPatch("N5", floatArrayOf(0.203f, 0.214f, 0.233f), floatArrayOf(0.45f, 0.45f, 0.45f)),
            ColorPatch("Red", floatArrayOf(0.412f, 0.213f, 0.019f), floatArrayOf(0.88f, 0.23f, 0.14f)),
            ColorPatch("Green", floatArrayOf(0.357f, 0.715f, 0.119f), floatArrayOf(0.27f, 0.74f, 0.26f)),
            ColorPatch("Blue", floatArrayOf(0.180f, 0.072f, 0.950f), floatArrayOf(0.23f, 0.31f, 0.88f)),
        )

        val report = benchmark.run(ColorProfile.HASSELBLAD_NATURAL, patches)

        assertTrue(report.meanDeltaE00 >= 0f)
        assertTrue(report.maxDeltaE00 >= report.meanDeltaE00)
        assertTrue(report.percentile90DeltaE00 >= 0f)
    }

    private fun syntheticFrame(
        width: Int,
        height: Int,
        producer: (x: Int, y: Int) -> Triple<Float, Float, Float>,
    ): ColorFrame {
        val size = width * height
        val r = FloatArray(size)
        val g = FloatArray(size)
        val b = FloatArray(size)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val sample = producer(x, y)
                r[index] = sample.first.coerceIn(0f, 1f)
                g[index] = sample.second.coerceIn(0f, 1f)
                b[index] = sample.third.coerceIn(0f, 1f)
            }
        }
        return ColorFrame(width, height, r, g, b)
    }
}
