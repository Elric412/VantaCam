package com.leica.cam.imaging_pipeline.pipeline

import com.leica.cam.common.result.LeicaResult
import com.leica.cam.common.result.PipelineStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImagingPipelineTest {
    private val alignmentEngine = FrameAlignmentEngine()
    private val hdrMergeEngine = HdrMergeEngine()
    private val toneMapper = PerceptualToneMappingEngine()
    private val sharpening = PsfDeconvolutionSharpeningEngine()
    private val denoising = FfdNetNoiseReductionEngine()

    @Test
    fun `alignment returns identity transform for identical frames`() {
        val frame = syntheticFrame(width = 12, height = 12, offset = 0f)

        val result = alignmentEngine.align(listOf(frame, frame.copyChannels()))

        assertTrue(result is LeicaResult.Success)
        val value = (result as LeicaResult.Success).value
        val transforms = value.transforms
        assertEquals(0f, transforms[1].tx, 0.001f)
        assertEquals(0f, transforms[1].ty, 0.001f)
    }

    @Test
    fun `hdr merge suppresses outlier exposure using variance weights`() {
        val base = syntheticFrame(8, 8, offset = 0f)
        val brightOutlier = syntheticFrame(8, 8, offset = 0.5f)
        val darkOutlier = syntheticFrame(8, 8, offset = -0.2f)

        val merged = hdrMergeEngine.merge(listOf(base, brightOutlier, darkOutlier))

        assertTrue(merged is LeicaResult.Success)
        val value = (merged as LeicaResult.Success).value
        val mergedFrame = value.mergedFrame
        val center = 4 * 8 + 4
        assertTrue(mergedFrame.green[center] < brightOutlier.green[center])
        assertTrue(mergedFrame.green[center] > darkOutlier.green[center])
    }

    @Test
    fun `full phase2 pipeline processes burst and returns bounded output`() {
        val orchestrator = ImagingPipelineOrchestrator(
            alignmentEngine = alignmentEngine,
            hdrMergeEngine = hdrMergeEngine,
            toneMappingEngine = toneMapper,
            sharpeningEngine = sharpening,
            denoisingEngine = denoising,
        )
        val burst = listOf(
            syntheticFrame(16, 16, offset = 0.0f),
            syntheticFrame(16, 16, offset = 0.05f),
            syntheticFrame(16, 16, offset = -0.04f),
        )

        val result = orchestrator.processBurst(burst, noiseSigma = 0.2f)

        assertTrue(result is LeicaResult.Success)
        val frame = (result as LeicaResult.Success).value
        assertEquals(16 * 16, frame.red.size)
        assertTrue(frame.red.all { it in 0f..1f })
        assertTrue(frame.green.all { it in 0f..1f })
        assertTrue(frame.blue.all { it in 0f..1f })
    }

    private fun syntheticFrame(width: Int, height: Int, offset: Float): PipelineFrame {
        val size = width * height
        val red = FloatArray(size)
        val green = FloatArray(size)
        val blue = FloatArray(size)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                val base = ((x + y).toFloat() / (width + height) + 0.2f + offset).coerceIn(0f, 1f)
                red[i] = (base + 0.03f).coerceIn(0f, 1f)
                green[i] = base
                blue[i] = (base - 0.03f).coerceIn(0f, 1f)
            }
        }

        return PipelineFrame(width, height, red, green, blue)
    }
}
