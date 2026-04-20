package com.leica.cam.imaging_pipeline.hdr

import com.leica.cam.common.result.LeicaResult
import com.leica.cam.imaging_pipeline.pipeline.HdrMergeMode
import com.leica.cam.imaging_pipeline.pipeline.PipelineFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the per-channel Wiener merge and Debevec linear merge.
 */
class RadianceMergerTest {

    private val merger = RadianceMerger()

    @Test
    fun `Wiener merge of identical frames returns input values`() {
        val w = 32; val h = 32; val size = w * h
        val value = 0.5f
        val g = FloatArray(size) { value }
        val frame1 = PipelineFrame(w, h, g.copyOf(), g.copyOf(), g.copyOf())
        val frame2 = PipelineFrame(w, h, g.copyOf(), g.copyOf(), g.copyOf())

        val noise = PerChannelNoise.fromIsoEstimate(100)
        val result = merger.mergeWienerBurst(listOf(frame1, frame2), noise)
        assertTrue(result is LeicaResult.Success)

        val merged = (result as LeicaResult.Success).value.mergedFrame
        val epsilon = 0.05f
        for (i in 0 until size) {
            assertEquals("Pixel $i red", value, merged.red[i], epsilon)
            assertEquals("Pixel $i green", value, merged.green[i], epsilon)
        }
    }

    @Test
    fun `Wiener merge mode is WIENER_BURST`() {
        val w = 16; val h = 16; val size = w * h
        val g = FloatArray(size) { 0.5f }
        val frame = PipelineFrame(w, h, g.copyOf(), g.copyOf(), g.copyOf())
        val noise = PerChannelNoise.fromIsoEstimate(100)

        val result = merger.mergeWienerBurst(listOf(frame, frame), noise)
        assertTrue(result is LeicaResult.Success)
        assertEquals(HdrMergeMode.WIENER_BURST, (result as LeicaResult.Success).value.hdrMode)
    }

    @Test
    fun `single frame Wiener returns WIENER_BURST with original frame`() {
        val w = 16; val h = 16; val size = w * h
        val g = FloatArray(size) { 0.3f }
        val frame = PipelineFrame(w, h, g.copyOf(), g.copyOf(), g.copyOf())
        val noise = PerChannelNoise.fromIsoEstimate(100)

        val result = merger.mergeWienerBurst(listOf(frame), noise)
        assertTrue(result is LeicaResult.Success)
        assertEquals(HdrMergeMode.WIENER_BURST, (result as LeicaResult.Success).value.hdrMode)
    }

    @Test
    fun `Debevec merge returns DEBEVEC_LINEAR mode`() {
        val w = 16; val h = 16; val size = w * h
        val baseG = FloatArray(size) { 0.5f }
        val base = PipelineFrame(w, h, baseG.copyOf(), baseG.copyOf(), baseG.copyOf(), evOffset = 0f)
        val dark = PipelineFrame(w, h, FloatArray(size) { 0.2f }, FloatArray(size) { 0.2f }, FloatArray(size) { 0.2f }, evOffset = -2f)

        val result = merger.mergeDebevecLinear(listOf(base, dark))
        assertTrue(result is LeicaResult.Success)
        assertEquals(HdrMergeMode.DEBEVEC_LINEAR, (result as LeicaResult.Success).value.hdrMode)
    }

    @Test
    fun `per-channel noise from ISO estimate has positive values`() {
        val noise = PerChannelNoise.fromIsoEstimate(800)
        assertTrue(noise.red.shotCoeff > 0)
        assertTrue(noise.green.readNoiseSq > 0)
        assertTrue(noise.blue.varianceAt(0.5f) > 0)
    }
}
