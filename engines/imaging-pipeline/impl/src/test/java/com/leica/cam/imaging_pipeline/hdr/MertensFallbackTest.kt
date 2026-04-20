package com.leica.cam.imaging_pipeline.hdr

import com.leica.cam.common.result.LeicaResult
import com.leica.cam.imaging_pipeline.pipeline.PipelineFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the Laplacian pyramid Mertens fusion.
 *
 * Key correctness assertion: for **identical input frames**, the
 * Laplacian-blended output equals the input within epsilon = 1e-2.
 * This verifies round-trip correctness of the pyramid build/collapse.
 */
class MertensFallbackTest {

    private val mertens = MertensFallback()

    @Test
    fun `identical frames produce output equal to input within epsilon`() {
        val w = 64; val h = 64; val size = w * h
        // Create a gradient test frame
        val r = FloatArray(size) { i -> (i % w).toFloat() / w }
        val g = FloatArray(size) { i -> (i / w).toFloat() / h }
        val b = FloatArray(size) { 0.5f }

        val frame1 = PipelineFrame(w, h, r.copyOf(), g.copyOf(), b.copyOf())
        val frame2 = PipelineFrame(w, h, r.copyOf(), g.copyOf(), b.copyOf())

        val result = mertens.fuse(listOf(frame1, frame2))
        assertTrue("Fusion should succeed", result is LeicaResult.Success)

        val output = (result as LeicaResult.Success).value
        val epsilon = 1e-2f
        for (i in 0 until size) {
            assertEquals("Red channel pixel $i", r[i], output.red[i], epsilon)
            assertEquals("Green channel pixel $i", g[i], output.green[i], epsilon)
            assertEquals("Blue channel pixel $i", b[i], output.blue[i], epsilon)
        }
    }

    @Test
    fun `single frame returns itself`() {
        val frame = createTestFrame(32, 32, 0.5f)
        val result = mertens.fuse(listOf(frame))
        assertTrue(result is LeicaResult.Success)
        assertEquals(frame, (result as LeicaResult.Success).value)
    }

    @Test
    fun `empty frames returns failure`() {
        val result = mertens.fuse(emptyList())
        assertTrue(result is LeicaResult.Failure)
    }

    private fun createTestFrame(w: Int, h: Int, value: Float): PipelineFrame {
        val size = w * h
        return PipelineFrame(w, h, FloatArray(size) { value }, FloatArray(size) { value }, FloatArray(size) { value })
    }
}
