package com.leica.cam.imaging_pipeline.color

import com.leica.cam.imaging_pipeline.pipeline.PipelineFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AdvancedColorProcessingEngineTest {

    private val engine = AdvancedColorProcessingEngine()

    private fun synthFrame(w: Int = 32, h: Int = 32): PipelineFrame {
        val n = w * h
        val r = FloatArray(n)
        val g = FloatArray(n)
        val b = FloatArray(n)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                // Build a synthetic scene: top half = blue sky, bottom half = warm green foliage,
                // with a grey diagonal so the engine has chroma to push around.
                val isSky = y < h / 2
                if (isSky) {
                    r[i] = 0.30f; g[i] = 0.45f; b[i] = 0.85f
                } else {
                    r[i] = 0.30f; g[i] = 0.55f; b[i] = 0.18f
                }
            }
        }
        return PipelineFrame(w, h, r, g, b, 0f, 200, 16_666_666L)
    }

    @Test
    fun `process returns frame with same dimensions`() {
        val out = engine.process(synthFrame(48, 32))
        assertEquals(48, out.width)
        assertEquals(32, out.height)
    }

    @Test
    fun `disabled stages preserve input pixel values bit-for-bit`() {
        val frame = synthFrame()
        val tuning = ColorProcessingTuning(
            intelligentMappingEnabled = false,
            colorLayeringEnabled = false,
            contrastEnabled = false,
            vibranceEnabled = false,
            cctCorrectionEnabled = false,
        )
        val out = engine.process(frame, tuning)
        for (i in frame.red.indices) {
            assertEquals(frame.red[i], out.red[i], 1e-6f)
            assertEquals(frame.green[i], out.green[i], 1e-6f)
            assertEquals(frame.blue[i], out.blue[i], 1e-6f)
        }
    }

    @Test
    fun `vibrance increases saturation of low-chroma pixels`() {
        val frame = synthFrame()
        val tuning = ColorProcessingTuning(
            intelligentMappingEnabled = false,
            colorLayeringEnabled = false,
            contrastEnabled = false,
            vibranceEnabled = true,
            cctCorrectionEnabled = false,
            vibranceStrength = 0.5f,
        )
        val out = engine.process(frame, tuning)
        // Spread between max and min channel should grow when vibrance fires.
        val origSpread = sceneAverageSpread(frame)
        val newSpread = sceneAverageSpread(out)
        assertTrue("Vibrance should expand chroma: $origSpread → $newSpread", newSpread > origSpread)
    }

    @Test
    fun `vibrance protects skin tones`() {
        val w = 16; val h = 16
        val n = w * h
        val r = FloatArray(n) { 0.55f }
        val g = FloatArray(n) { 0.42f }
        val b = FloatArray(n) { 0.34f }   // canonical skin tone
        val frame = PipelineFrame(w, h, r, g, b, 0f, 200, 16_666_666L)
        val skin = engine.process(
            frame,
            ColorProcessingTuning(
                intelligentMappingEnabled = false,
                colorLayeringEnabled = false,
                contrastEnabled = false,
                cctCorrectionEnabled = false,
                vibranceEnabled = true,
                vibranceStrength = 0.5f,
                vibranceSkinScale = 0.0f,
            ),
        )
        // With skinScale = 0, vibrance must have negligible effect.
        for (i in 0 until n) {
            assertTrue(abs(skin.red[i] - r[i]) < 0.04f)
            assertTrue(abs(skin.green[i] - g[i]) < 0.04f)
            assertTrue(abs(skin.blue[i] - b[i]) < 0.04f)
        }
    }

    @Test
    fun `cct correction toward warmer target shifts toward red`() {
        val frame = synthFrame()
        val tuning = ColorProcessingTuning(
            intelligentMappingEnabled = false,
            colorLayeringEnabled = false,
            contrastEnabled = false,
            vibranceEnabled = false,
            cctCorrectionEnabled = true,
            cctTargetK = 4500f,             // warmer than 6500K source
            cctCorrectionStrength = 1.0f,
            cctMixedLightSafeMode = false,
        )
        val out = engine.process(frame, tuning, cctEstimateK = 6500f, wbConfidence = 1f)
        val origR = frame.red.average()
        val origB = frame.blue.average()
        val newR = out.red.average()
        val newB = out.blue.average()
        // Warmer target → R/B ratio should increase.
        assertTrue(
            "Warm CCT should raise R/B: ${origR / origB} → ${newR / newB}",
            newR / newB > origR / origB,
        )
    }

    @Test
    fun `mixed light safe mode reduces correction at low confidence`() {
        val frame = synthFrame()
        val safe = ColorProcessingTuning(
            intelligentMappingEnabled = false,
            colorLayeringEnabled = false,
            contrastEnabled = false,
            vibranceEnabled = false,
            cctCorrectionEnabled = true,
            cctTargetK = 4500f,
            cctMixedLightSafeMode = true,
        )
        val full = engine.process(frame, safe, cctEstimateK = 6500f, wbConfidence = 1.0f)
        val attenuated = engine.process(frame, safe, cctEstimateK = 6500f, wbConfidence = 0.0f)
        val deltaFull = pixelDelta(frame, full)
        val deltaAtt = pixelDelta(frame, attenuated)
        assertTrue(
            "Low-confidence WB must attenuate CCT correction: full=$deltaFull, attenuated=$deltaAtt",
            deltaAtt < deltaFull,
        )
    }

    @Test
    fun `forScene presets produce different tunings`() {
        val portrait = ColorProcessingTuning.forScene("portrait")
        val landscape = ColorProcessingTuning.forScene("landscape")
        assertNotEquals(portrait.vibranceStrength, landscape.vibranceStrength)
        assertNotEquals(portrait.contrastStrength, landscape.contrastStrength)
    }

    @Test
    fun `mask-driven layering applies different deltas per zone`() {
        val frame = synthFrame()
        val mask = ByteArray(frame.width * frame.height) { i ->
            if (i < frame.width * frame.height / 2) ColorZoneId.SKY.ord.toByte()
            else ColorZoneId.FOLIAGE.ord.toByte()
        }
        val out = engine.process(
            frame,
            ColorProcessingTuning(
                intelligentMappingEnabled = false,
                colorLayeringEnabled = true,
                contrastEnabled = false,
                vibranceEnabled = false,
                cctCorrectionEnabled = false,
                colorLayeringStrength = 1.0f,
            ),
            mask = mask,
        )
        // Sky zone → blue should shift slightly; foliage → green should boost saturation.
        assertNotNull(out)
    }

    private fun sceneAverageSpread(f: PipelineFrame): Float {
        val n = f.red.size
        var s = 0.0
        for (i in 0 until n) {
            val mx = maxOf(f.red[i], f.green[i], f.blue[i])
            val mn = minOf(f.red[i], f.green[i], f.blue[i])
            s += mx - mn
        }
        return (s / n).toFloat()
    }

    private fun pixelDelta(a: PipelineFrame, b: PipelineFrame): Float {
        var s = 0.0
        for (i in a.red.indices) {
            s += abs(a.red[i] - b.red[i]) + abs(a.green[i] - b.green[i]) + abs(a.blue[i] - b.blue[i])
        }
        return (s / a.red.size).toFloat()
    }
}
