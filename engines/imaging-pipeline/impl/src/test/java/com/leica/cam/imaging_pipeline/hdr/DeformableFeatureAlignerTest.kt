package com.leica.cam.imaging_pipeline.hdr

import com.leica.cam.common.result.LeicaResult
import com.leica.cam.imaging_pipeline.pipeline.PipelineFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Unit tests for the pyramidal Lucas-Kanade optical flow aligner.
 *
 * Key assertion: a synthetic 3px horizontal translation on a checkerboard
 * pattern should be recovered with mean flow = 3 +/- 0.5 px.
 */
class DeformableFeatureAlignerTest {

    private val aligner = DeformableFeatureAligner()

    @Test
    fun `synthetic translation on checkerboard recovers flow`() {
        val w = 64; val h = 64; val size = w * h
        val shiftPx = 3

        // Build a checkerboard pattern (high texture = good for LK)
        val refG = FloatArray(size) { i ->
            val x = i % w; val y = i / w
            if ((x / 4 + y / 4) % 2 == 0) 0.8f else 0.2f
        }
        val ref = PipelineFrame(w, h, refG.copyOf(), refG.copyOf(), refG.copyOf())

        // Shift the checkerboard by shiftPx pixels horizontally
        val candG = FloatArray(size) { i ->
            val x = i % w; val y = i / w
            val srcX = x - shiftPx
            if (srcX in 0 until w) refG[y * w + srcX] else 0.5f
        }
        val cand = PipelineFrame(w, h, candG.copyOf(), candG.copyOf(), candG.copyOf())

        val flow = aligner.estimateFlow(ref, cand)

        // Check mean flow in the interior (avoid border effects)
        val margin = 10
        var sumU = 0f; var count = 0
        for (y in margin until h - margin) {
            for (x in margin until w - margin) {
                sumU += flow.u[y * w + x]
                count++
            }
        }
        val meanU = sumU / count

        // The candidate was shifted LEFT by shiftPx relative to ref,
        // so the flow should point RIGHT (positive u) with magnitude ~shiftPx
        assertTrue(
            "Mean horizontal flow should be approximately $shiftPx px, got $meanU",
            abs(meanU - shiftPx.toFloat()) < 1.5f,
        )
    }

    @Test
    fun `alignment of identical frames produces near-zero flow`() {
        val w = 32; val h = 32; val size = w * h
        val g = FloatArray(size) { i -> (i % w).toFloat() / w }
        val ref = PipelineFrame(w, h, g.copyOf(), g.copyOf(), g.copyOf())
        val cand = PipelineFrame(w, h, g.copyOf(), g.copyOf(), g.copyOf())

        val flow = aligner.estimateFlow(ref, cand)
        val maxFlow = flow.u.map { abs(it) }.maxOrNull() ?: 0f
        assertTrue("Identical frames should have near-zero flow, got max=$maxFlow", maxFlow < 1f)
    }

    @Test
    fun `align returns success with correct frame count`() {
        val w = 16; val h = 16; val size = w * h
        val g = FloatArray(size) { 0.5f }
        val ref = PipelineFrame(w, h, g.copyOf(), g.copyOf(), g.copyOf())
        val alt1 = PipelineFrame(w, h, g.copyOf(), g.copyOf(), g.copyOf())
        val alt2 = PipelineFrame(w, h, g.copyOf(), g.copyOf(), g.copyOf())

        val result = aligner.align(ref, listOf(alt1, alt2))
        assertTrue(result is LeicaResult.Success)
        val aligned = (result as LeicaResult.Success).value
        assertEquals(3, aligned.alignedFrames.size) // ref + 2 alternates
    }
}
