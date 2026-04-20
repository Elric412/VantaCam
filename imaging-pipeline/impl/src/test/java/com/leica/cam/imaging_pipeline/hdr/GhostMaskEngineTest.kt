package com.leica.cam.imaging_pipeline.hdr

import com.leica.cam.imaging_pipeline.pipeline.PipelineFrame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pre-alignment ghost mask engine.
 *
 * Key assertion: synthetic frames with a disk placed in different positions
 * produce a soft ghost mask that covers both disk positions.
 */
class GhostMaskEngineTest {

    private val engine = GhostMaskEngine()

    @Test
    fun `static frames produce near-zero ghost mask`() {
        val w = 32; val h = 32; val size = w * h
        val uniform = FloatArray(size) { 0.5f }
        val ref = PipelineFrame(w, h, uniform.copyOf(), uniform.copyOf(), uniform.copyOf())
        val alt = PipelineFrame(w, h, uniform.copyOf(), uniform.copyOf(), uniform.copyOf())

        val mask = engine.computeSoftMask(ref, listOf(alt))
        val maxVal = mask.maxOrNull() ?: 0f
        assertTrue("Static frames should produce near-zero ghost mask, got max=$maxVal", maxVal < 0.1f)
    }

    @Test
    fun `moving disk produces ghost mask in both positions`() {
        val w = 64; val h = 64; val size = w * h

        // Reference: bright disk at (16, 16)
        val refG = FloatArray(size) { i ->
            val x = i % w; val y = i / w
            val dx = x - 16; val dy = y - 16
            if (dx * dx + dy * dy < 64) 0.9f else 0.1f
        }
        val ref = PipelineFrame(w, h, refG.copyOf(), refG.copyOf(), refG.copyOf())

        // Alternate: bright disk at (48, 48)
        val altG = FloatArray(size) { i ->
            val x = i % w; val y = i / w
            val dx = x - 48; val dy = y - 48
            if (dx * dx + dy * dy < 64) 0.9f else 0.1f
        }
        val alt = PipelineFrame(w, h, altG.copyOf(), altG.copyOf(), altG.copyOf())

        val mask = engine.computeSoftMask(ref, listOf(alt), dilateRadius = 4)

        // Ghost mask should be elevated near both disk positions
        val maskAtRefDisk = mask[16 * w + 16]
        val maskAtAltDisk = mask[48 * w + 48]
        assertTrue("Ghost mask should be > 0 near reference disk position, got $maskAtRefDisk",
            maskAtRefDisk > 0.01f)
        assertTrue("Ghost mask should be > 0 near alternate disk position, got $maskAtAltDisk",
            maskAtAltDisk > 0.01f)
    }

    @Test
    fun `soft mask values are in 0 to 1 range`() {
        val w = 16; val h = 16; val size = w * h
        val ref = PipelineFrame(w, h, FloatArray(size) { 0.3f }, FloatArray(size) { 0.3f }, FloatArray(size) { 0.3f })
        val alt = PipelineFrame(w, h, FloatArray(size) { 0.7f }, FloatArray(size) { 0.7f }, FloatArray(size) { 0.7f })

        val mask = engine.computeSoftMask(ref, listOf(alt))
        assertTrue("All mask values should be in [0,1]", mask.all { it in 0f..1f })
    }
}
