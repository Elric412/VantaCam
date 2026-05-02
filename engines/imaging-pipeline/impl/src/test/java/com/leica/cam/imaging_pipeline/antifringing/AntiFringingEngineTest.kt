package com.leica.cam.imaging_pipeline.antifringing

import com.leica.cam.imaging_pipeline.pipeline.PipelineFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Unit tests for [AntiFringingEngine].
 *
 * These build synthetic frames with a known purple/green halo signature and
 * verify:
 *
 *  1. Flat / non-edge regions are never modified.
 *  2. A purple-fringed edge has its blue + red channels pulled toward green.
 *  3. The luminance of fringed pixels is preserved (the operator is a pure
 *     chroma cut by design).
 *  4. The OFF profile is a true no-op.
 *  5. Memory-colour protection prevents collateral desaturation of skin.
 */
class AntiFringingEngineTest {

    private val engine = AntiFringingEngine()

    @Test
    fun flatRegionIsUntouched() {
        // A solid neutral frame has no luma gradient; nothing should change.
        val flat = solidFrame(0.5f, 0.5f, 0.5f, w = 16, h = 16)
        val out = engine.apply(flat)
        for (i in 0 until flat.red.size) {
            assertEquals("R[$i]", flat.red[i], out.red[i], 1e-6f)
            assertEquals("G[$i]", flat.green[i], out.green[i], 1e-6f)
            assertEquals("B[$i]", flat.blue[i], out.blue[i], 1e-6f)
        }
    }

    @Test
    fun offProfileIsNoOp() {
        val off = AntiFringingEngine(AntiFringingConfig.OFF)
        val frame = purpleFringeEdge()
        val out = off.apply(frame)
        for (i in 0 until frame.red.size) {
            assertEquals("R[$i]", frame.red[i], out.red[i], 1e-6f)
            assertEquals("G[$i]", frame.green[i], out.green[i], 1e-6f)
            assertEquals("B[$i]", frame.blue[i], out.blue[i], 1e-6f)
        }
    }

    @Test
    fun purpleFringeIsAttenuated() {
        val frame = purpleFringeEdge()
        val out = engine.apply(frame)

        // Sample one of the fringed pixels (column 9, the bright side just past
        // the edge — we set heavy purple bloom there).
        var anyChanged = false
        for (y in 2 until frame.height - 2) {
            val i = y * frame.width + 9
            val purpleness = frame.blue[i] + frame.red[i] - 2f * frame.green[i]
            val outPurpleness = out.blue[i] + out.red[i] - 2f * out.green[i]
            if (purpleness > 0.1f) {
                assertTrue(
                    "Purple cast should drop after defringe (in=$purpleness, out=$outPurpleness)",
                    outPurpleness < purpleness,
                )
                anyChanged = true
            }
        }
        assertTrue("Expected at least one fringed pixel to be defringed", anyChanged)
    }

    @Test
    fun fringeOperatorPreservesLuminance() {
        val frame = purpleFringeEdge()
        val out = engine.apply(frame)
        // Total energy should not move appreciably — defringe is a chroma cut.
        val inSum = frame.red.sum() + frame.green.sum() + frame.blue.sum()
        val outSum = out.red.sum() + out.green.sum() + out.blue.sum()
        val ratio = (inSum - outSum) / inSum
        assertTrue("Luminance drift too large: $ratio", abs(ratio) < 0.05f)
    }

    @Test
    fun faceMaskGatesDefringing() {
        // Build a fringed frame and mask the entire image as a face. The
        // engine must skip every pixel.
        val frame = purpleFringeEdge()
        val mask = BooleanArray(frame.width * frame.height) { true }
        val out = engine.apply(frame, faceMask = mask)
        for (i in 0 until frame.red.size) {
            assertEquals("R[$i]", frame.red[i], out.red[i], 1e-6f)
            assertEquals("G[$i]", frame.green[i], out.green[i], 1e-6f)
            assertEquals("B[$i]", frame.blue[i], out.blue[i], 1e-6f)
        }
    }

    @Test
    fun gentleProfileIsLessAggressiveThanDefault() {
        val frame = purpleFringeEdge()
        val defaultOut = AntiFringingEngine(AntiFringingConfig()).apply(frame)
        val gentleOut = AntiFringingEngine(AntiFringingConfig.GENTLE).apply(frame)
        // Compute total purple-cast reduction for each profile.
        val inPurple = totalPurpleCast(frame)
        val defPurple = totalPurpleCast(defaultOut)
        val gentlePurple = totalPurpleCast(gentleOut)
        // Default removes more purple cast than gentle.
        assertTrue(
            "Default ($defPurple) should remove >= gentle ($gentlePurple) purple cast",
            defPurple <= gentlePurple + 1e-6f,
        )
        // Both must remove _something_ relative to the input.
        assertNotEquals("Default profile should change something", inPurple, defPurple, 1e-6f)
    }

    @Test
    fun configValidatesRanges() {
        try {
            AntiFringingConfig(purpleStrength = 1.5f)
            error("Expected IllegalArgumentException for out-of-range purpleStrength")
        } catch (_: IllegalArgumentException) {
            // expected
        }
        try {
            AntiFringingConfig(edgeRadius = -1)
            error("Expected IllegalArgumentException for negative edgeRadius")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun solidFrame(r: Float, g: Float, b: Float, w: Int, h: Int): PipelineFrame {
        val n = w * h
        return PipelineFrame(
            width = w, height = h,
            red = FloatArray(n) { r },
            green = FloatArray(n) { g },
            blue = FloatArray(n) { b },
        )
    }

    /**
     * Construct a synthetic 16×16 frame with a vertical high-contrast edge at
     * column 8. Pixels just past the edge (columns 9..11) have a strong
     * purple cast — high B and R, low G — emulating axial CA bloom.
     */
    private fun purpleFringeEdge(): PipelineFrame {
        val w = 16; val h = 16
        val n = w * h
        val red = FloatArray(n)
        val green = FloatArray(n)
        val blue = FloatArray(n)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (x < 8) {
                    // Dark side
                    red[i] = 0.05f; green[i] = 0.05f; blue[i] = 0.05f
                } else if (x in 9..11) {
                    // Purple-fringed bloom — strong B and R, weak G.
                    red[i] = 0.85f; green[i] = 0.30f; blue[i] = 0.95f
                } else {
                    // Clean bright side
                    red[i] = 0.85f; green[i] = 0.85f; blue[i] = 0.85f
                }
            }
        }
        return PipelineFrame(width = w, height = h, red = red, green = green, blue = blue)
    }

    /** Sum of B + R − 2·G across the image — proxy for purple-cast energy. */
    private fun totalPurpleCast(frame: PipelineFrame): Float {
        var sum = 0f
        for (i in frame.red.indices) {
            sum += (frame.blue[i] + frame.red[i] - 2f * frame.green[i]).coerceAtLeast(0f)
        }
        return sum
    }
}
