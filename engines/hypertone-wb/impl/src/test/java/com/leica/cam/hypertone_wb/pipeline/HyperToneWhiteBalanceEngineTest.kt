package com.leica.cam.hypertone_wb.pipeline

import com.leica.cam.common.result.LeicaResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HyperToneWhiteBalanceEngineTest {
    private val engine = HyperToneWhiteBalanceEngine(
        wb2Engine = HyperToneWB2Engine(),
        awbPredictor = null,
    )

    private val identitySensorCcm = floatArrayOf(
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 1f,
    )

    @Test
    fun processReturnsRgbFrameWithSameDimensions() = runTest {
        val frame = syntheticFrame(32, 24) { _, _ -> Triple(0.5f, 0.5f, 0.5f) }

        val result = engine.process(
            frame = frame,
            sensorToXyz3x3 = identitySensorCcm,
        )

        assertTrue(result is LeicaResult.Success)
        val output = (result as LeicaResult.Success).value
        assertEquals(frame.width, output.width)
        assertEquals(frame.height, output.height)
        assertEquals(frame.pixelCount, output.pixelCount)
    }

    private fun syntheticFrame(
        width: Int,
        height: Int,
        producer: (x: Int, y: Int) -> Triple<Float, Float, Float>,
    ): RgbFrame {
        val size = width * height
        val red = FloatArray(size)
        val green = FloatArray(size)
        val blue = FloatArray(size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val (r, g, b) = producer(x, y)
                red[index] = r
                green[index] = g
                blue[index] = b
            }
        }
        return RgbFrame(width, height, red, green, blue)
    }
}
