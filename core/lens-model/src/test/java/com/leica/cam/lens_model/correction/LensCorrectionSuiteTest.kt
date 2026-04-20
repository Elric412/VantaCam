package com.leica.cam.lens_model.correction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LensCorrectionSuiteTest {
    private val suite = LensCorrectionSuite()

    @Test
    fun `fixed pattern noise correction subtracts calibrated noise floor`() {
        val frame = gradientFrame(4, 4, base = 0.4f)
        val noise = FloatArray(16) { 0.1f }

        val corrected = suite.correctFixedPatternNoise(frame, noise)

        assertEquals(0.3f, corrected.green[0], 0.0001f)
        assertTrue(corrected.green.all { it in 0f..1f })
    }

    @Test
    fun `lens shading correction boosts edge energy when gain map is larger at borders`() {
        val frame = gradientFrame(5, 5, base = 0.2f)
        val gainMap = FloatArray(25) { index ->
            val x = index % 5
            val y = index / 5
            if (x == 0 || y == 0 || x == 4 || y == 4) 1.5f else 1f
        }

        val corrected = suite.correctLensShading(frame, gainMap)

        assertTrue(corrected.red[0] > corrected.red[12])
    }

    @Test
    fun `full correction pipeline is deterministic for the same input`() {
        val frame = gradientFrame(8, 8, base = 0.3f)
        val profile = LensCorrectionProfile(
            fixedPatternNoiseMap = FloatArray(64) { 0.02f },
            lensShadingGainMap = FloatArray(64) { index -> if (index % 9 == 0) 1.2f else 1.05f },
            radialDistortion = DistortionCoefficients(0.01f, -0.001f, 0f, 0f, 0f),
            chromaticAberration = ChromaticAberrationModel(redRadialShift = 0.01f, blueRadialShift = -0.01f),
        )

        val first = suite.apply(frame, profile)
        val second = suite.apply(frame, profile)

        assertEquals(first.red[10], second.red[10], 0.000001f)
        assertEquals(first.green[40], second.green[40], 0.000001f)
        assertEquals(first.blue[63], second.blue[63], 0.000001f)
    }

    private fun gradientFrame(width: Int, height: Int, base: Float): LinearRgbFrame {
        val size = width * height
        val red = FloatArray(size)
        val green = FloatArray(size)
        val blue = FloatArray(size)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                val value = (base + (x + y) * 0.02f).coerceIn(0f, 1f)
                red[i] = (value + 0.01f).coerceIn(0f, 1f)
                green[i] = value
                blue[i] = (value - 0.01f).coerceIn(0f, 1f)
            }
        }

        return LinearRgbFrame(width, height, red, green, blue)
    }
}
