package com.leica.cam.imaging_pipeline.pipeline

import com.leica.cam.common.result.LeicaResult
import com.leica.cam.common.result.PipelineStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class ComputationalModesPhase7Test {
    private val portraitEngine = PortraitModeEngine()
    private val astroEngine = AstrophotographyEngine()
    private val srEngine = SuperResolutionEngine()
    private val macroEngine = MacroModeEngine(srEngine)
    private val nrEngine = FfdNetNoiseReductionEngine()
    private val nightEngine = NightModeEngine(nrEngine)
    private val zoomEngine = SeamlessZoomEngine()

    @Test
    fun `portrait mode selects best depth source and blurs background`() {
        val frame = syntheticFrame(10, 10, 0.5f)
        val candidates = mapOf(
            DepthSource.TOF to FloatArray(100) { 2.0f },
            DepthSource.MONOCULAR to FloatArray(100) { 2.1f }
        )

        val result = portraitEngine.render(frame, candidates, 1.0f, 1.8f, 50f)

        assertTrue(result is LeicaResult.Success)
        val value = (result as LeicaResult.Success).value
        assertEquals(DepthSource.TOF, value.depthSource)
        assertTrue(value.averageBlurRadius > 0f)
    }

    @Test
    fun `astrophotography stack succeeds with bright star field`() {
        val frames = List(6) { frameIndex ->
            syntheticStarFrame(width = 20, height = 20, shift = frameIndex)
        }

        val result = astroEngine.stack(frames)

        assertTrue(result is LeicaResult.Success)
        val value = (result as LeicaResult.Success).value
        assertTrue(value.starsMatched >= 5)
    }

    @Test
    fun `macro mode runs focus stacking and 2x output`() {
        val near = syntheticFrame(10, 10, 0.2f)
        val mid = syntheticFrame(10, 10, 0.3f)
        val far = syntheticFrame(10, 10, 0.4f)
        val frames = listOf(near, mid, far)

        val result = macroEngine.process(frames)

        assertTrue(result is LeicaResult.Success)
        val value = (result as LeicaResult.Success).value
        assertEquals(20, value.stacked.width)
    }

    @Test
    fun `night mode performs MFNR stacking and denoising`() {
        val frames = List(4) { syntheticFrame(8, 8, 0.1f) }

        val result = nightEngine.process(frames)

        assertTrue(result is LeicaResult.Success)
        val frame = (result as LeicaResult.Success).value
        assertEquals(64, frame.red.size)
        assertTrue(frame.red.all { it in 0f..1f })
        assertTrue(frame.green.all { it in 0f..1f })
        assertTrue(frame.blue.all { it in 0f..1f })
    }

    @Test
    fun `seamless zoom calculates correct crop and camera selection`() {
        val ranges = mapOf("WIDE" to 1f..2f, "TELE" to 2f..5f)

        val result = zoomEngine.selectCamera(3.5f, ranges)

        assertTrue(result is LeicaResult.Success)
        val value = (result as LeicaResult.Success).value
        assertEquals("TELE", value.id)
    }

    @Test
    fun `seamless zoom fails when selected camera range width is zero`() {
        val ranges = mapOf("BROKEN" to 2f..2f)

        val result = zoomEngine.selectCamera(2f, ranges)

        assertTrue(result is LeicaResult.Failure)
    }

    @Test
    fun `super resolution upscales by 2x factor`() {
        val frame = syntheticFrame(8, 8, 0.5f)

        val result = srEngine.upscale(frame, scale = 2, srEnabled = true, estimatedBrisque = 20f)

        assertTrue(result is LeicaResult.Success)
        val value = (result as LeicaResult.Success).value
        assertEquals(16, value.output.width)
        assertEquals(16, value.output.height)
        assertEquals(256, value.output.red.size)
    }

    private fun syntheticFrame(width: Int, height: Int, offset: Float): PipelineFrame {
        val size = width * height
        return PipelineFrame(
            width, height,
            FloatArray(size) { offset },
            FloatArray(size) { offset },
            FloatArray(size) { offset }
        )
    }

    private fun syntheticStarFrame(width: Int, height: Int, shift: Int): PipelineFrame {
        val base = syntheticFrame(width, height, 0.02f)
        val red = base.red.copyOf()
        val green = base.green.copyOf()
        val blue = base.blue.copyOf()
        val starCoords = listOf(
            2 to 2,
            4 to 5,
            7 to 3,
            9 to 10,
            12 to 5,
            14 to 7,
            16 to 4,
            5 to 15,
            11 to 14,
            17 to 17,
        )

        starCoords.forEachIndexed { index, (x, y) ->
            val sx = (x + (shift + index) % 2).coerceIn(0, width - 1)
            val sy = (y + (shift + index) % 2).coerceIn(0, height - 1)
            val i = sy * width + sx
            red[i] = 1f
            green[i] = 1f
            blue[i] = 1f
        }

        return PipelineFrame(width, height, red, green, blue)
    }
}
