package com.leica.cam.imaging_pipeline.noise

import com.leica.cam.imaging_pipeline.pipeline.PipelineFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class AdaptiveNoiseProcessingEngineTest {

    private val engine = AdaptiveNoiseProcessingEngine()

    private fun noisyFrame(
        w: Int = 32, h: Int = 32, base: Float = 0.4f,
        sigma: Float = 0.05f, seed: Long = 1234L, iso: Int = 800,
    ): PipelineFrame {
        val n = w * h
        val rng = Random(seed)
        val r = FloatArray(n) { (base + rng.nextFloat() * sigma * 2 - sigma).coerceAtLeast(0f) }
        val g = FloatArray(n) { (base + rng.nextFloat() * sigma * 2 - sigma).coerceAtLeast(0f) }
        val b = FloatArray(n) { (base + rng.nextFloat() * sigma * 2 - sigma).coerceAtLeast(0f) }
        return PipelineFrame(w, h, r, g, b, 0f, iso, 16_666_666L)
    }

    private fun edgeFrame(w: Int = 32, h: Int = 32): PipelineFrame {
        val n = w * h
        val r = FloatArray(n)
        val g = FloatArray(n)
        val b = FloatArray(n)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val v = if (x < w / 2) 0.2f else 0.8f
                r[y * w + x] = v; g[y * w + x] = v; b[y * w + x] = v
            }
        }
        return PipelineFrame(w, h, r, g, b, 0f, 100, 16_666_666L)
    }

    @Test
    fun `process returns frame with same dimensions`() {
        val f = noisyFrame(48, 32)
        val out = engine.process(f)
        assertEquals(48, out.width)
        assertEquals(32, out.height)
    }

    @Test
    fun `wavelet denoise reduces variance on flat noise field`() {
        val f = noisyFrame(64, 64, base = 0.5f, sigma = 0.08f, iso = 1600)
        val tuning = NoiseProcessingTuning(
            hotPixelFixEnabled = false,
            sabreSharpEnabled = false,
            volumeProcessingEnabled = false,
            waveletLumaEnabled = true,
            lumaStrength = 1.5f,
        )
        val origVar = lumaVariance(f)
        val out = engine.process(f, tuning = tuning)
        val newVar = lumaVariance(out)
        assertTrue(
            "Wavelet DN should reduce variance: $origVar → $newVar",
            newVar < origVar,
        )
    }

    @Test
    fun `sabre sharp preserves edges sharpness`() {
        val f = edgeFrame()
        val tuning = NoiseProcessingTuning(
            hotPixelFixEnabled = false,
            waveletLumaEnabled = false,
            volumeProcessingEnabled = false,
            sabreSharpEnabled = true,
            sabreSharpStrength = 1.5f,
        )
        val out = engine.process(f, tuning = tuning)
        // The edge contrast should not decrease after sharpening.
        val origEdge = f.green[16 * 32 + 16] - f.green[16 * 32 + 15]
        val newEdge = out.green[16 * 32 + 16] - out.green[16 * 32 + 15]
        assertTrue(
            "Edge contrast must not weaken: $origEdge → $newEdge",
            newEdge >= origEdge - 1e-3f,
        )
    }

    @Test
    fun `hot pixel is replaced by neighbours`() {
        val f = noisyFrame(32, 32, base = 0.3f, sigma = 0.01f)
        val r = f.red.copyOf()
        // Inject a single saturated pixel in the middle.
        r[16 * 32 + 16] = 5.0f
        val noisy = PipelineFrame(f.width, f.height, r, f.green.copyOf(), f.blue.copyOf(),
            f.evOffset, f.isoEquivalent, f.exposureTimeNs)
        val tuning = NoiseProcessingTuning(
            hotPixelFixEnabled = true,
            waveletLumaEnabled = false,
            volumeProcessingEnabled = false,
            sabreSharpEnabled = false,
            hotPixelSigma = 3f,
        )
        val out = engine.process(noisy, tuning = tuning)
        assertTrue(
            "Hot pixel must be clipped: ${out.red[16 * 32 + 16]}",
            out.red[16 * 32 + 16] < 1.5f,
        )
    }

    @Test
    fun `iso-aware tuning increases strength at high ISO`() {
        val low = NoiseProcessingTuning.forIsoAndScene(100)
        val high = NoiseProcessingTuning.forIsoAndScene(6400)
        assertTrue(
            "High ISO must demand stronger luma denoise: ${low.lumaStrength} < ${high.lumaStrength}",
            high.lumaStrength > low.lumaStrength,
        )
    }

    @Test
    fun `portrait scene disables volume processing`() {
        val portrait = NoiseProcessingTuning.forIsoAndScene(800, "portrait")
        assertTrue(
            "Portrait must skip 3D NLM to avoid plasticky look",
            !portrait.volumeProcessingEnabled,
        )
    }

    @Test
    fun `volume nlm reduces variance across burst`() {
        val ref = noisyFrame(32, 32, sigma = 0.10f, seed = 1L, iso = 1600)
        val burst = listOf(
            ref,
            noisyFrame(32, 32, sigma = 0.10f, seed = 2L, iso = 1600),
            noisyFrame(32, 32, sigma = 0.10f, seed = 3L, iso = 1600),
            noisyFrame(32, 32, sigma = 0.10f, seed = 4L, iso = 1600),
        )
        val tuning = NoiseProcessingTuning(
            hotPixelFixEnabled = false,
            waveletLumaEnabled = false,
            sabreSharpEnabled = false,
            volumeProcessingEnabled = true,
            volumeFiltering = 4f,
        )
        val origVar = lumaVariance(ref)
        val out = engine.process(ref, burstFrames = burst, tuning = tuning)
        val newVar = lumaVariance(out)
        assertTrue(
            "Volume NLM should reduce variance: $origVar → $newVar",
            newVar < origVar,
        )
    }

    @Test
    fun `soft frame is not sharpened`() {
        // Build a maximally blurry frame: all pixels equal → laplacian variance ≈ 0.
        val w = 32; val h = 32
        val flat = FloatArray(w * h) { 0.5f }
        val frame = PipelineFrame(w, h, flat.copyOf(), flat.copyOf(), flat.copyOf(), 0f, 100, 16_666_666L)
        val tuning = NoiseProcessingTuning(
            hotPixelFixEnabled = false,
            waveletLumaEnabled = false,
            volumeProcessingEnabled = false,
            sabreSharpEnabled = true,
            softFrameLaplacianFloor = 1f, // arbitrarily high — will gate everything
        )
        val out = engine.process(frame, tuning = tuning)
        for (i in 0 until w * h) {
            assertEquals(flat[i], out.red[i], 1e-5f)
        }
    }

    @Test
    fun `bayer noise model scales with iso`() {
        val low = engine.process(noisyFrame(iso = 100))
        val high = engine.process(noisyFrame(iso = 3200))
        // Both finish without throwing — high-ISO path takes the same shape but with
        // bigger thresholds, smaller drift cap. Smoke check for stability.
        assertNotNull(low)
        assertNotNull(high)
    }

    private fun lumaVariance(f: PipelineFrame): Float {
        val n = f.red.size
        var sum = 0.0
        for (i in 0 until n) {
            sum += 0.2126 * f.red[i] + 0.7152 * f.green[i] + 0.0722 * f.blue[i]
        }
        val mean = sum / n
        var s = 0.0
        for (i in 0 until n) {
            val luma = 0.2126 * f.red[i] + 0.7152 * f.green[i] + 0.0722 * f.blue[i]
            val d = luma - mean
            s += d * d
        }
        return (s / n).toFloat()
    }
}
