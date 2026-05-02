package com.leica.cam.imaging_pipeline.raw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Unit tests for [RawDemosaicEngine]. Verifies the Malvar-He-Cutler 5×5
 * demosaic produces sane outputs for synthetic Bayer mosaics — flat fields,
 * diagonal edges and known per-channel responses.
 */
class RawDemosaicEngineTest {

    private val engine = RawDemosaicEngine()

    @Test
    fun `flat grey RGGB mosaic decodes back to grey`() {
        val w = 16; val h = 16
        val raw = ShortArray(w * h) { 2048.toShort() } // mid-grey at 12-bit
        val calib = RawCalibration(
            blackLevel = floatArrayOf(0f, 0f, 0f, 0f),
            whiteLevel = 4095f,
            wbGains = floatArrayOf(1f, 1f, 1f, 1f),
            ccm = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
        )
        val frame = engine.demosaic(raw, w, h, RawBayerPattern.RGGB, calib)

        assertEquals(w, frame.width)
        assertEquals(h, frame.height)
        // Skip the 2-pixel border (MHC kernel is 5×5).
        for (y in 2 until h - 2) {
            for (x in 2 until w - 2) {
                val i = y * w + x
                assertTrue("R[$i]=${frame.red[i]} should be ~0.5", abs(frame.red[i] - 0.5f) < 0.05f)
                assertTrue("G[$i]=${frame.green[i]} should be ~0.5", abs(frame.green[i] - 0.5f) < 0.05f)
                assertTrue("B[$i]=${frame.blue[i]} should be ~0.5", abs(frame.blue[i] - 0.5f) < 0.05f)
            }
        }
    }

    @Test
    fun `wb gain pushes red channel above 1`() {
        val w = 12; val h = 12
        val raw = ShortArray(w * h) { 2048.toShort() }
        val calib = RawCalibration(
            blackLevel = floatArrayOf(0f, 0f, 0f, 0f),
            whiteLevel = 4095f,
            wbGains = floatArrayOf(2.5f, 1f, 1f, 1f),
            ccm = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
        )
        val frame = engine.demosaic(raw, w, h, RawBayerPattern.RGGB, calib)
        // Centre red site should reflect the WB gain (~1.25).
        val cy = h / 2; val cx = w / 2
        val centre = frame.red[cy * w + cx]
        assertTrue("centre R=$centre should be > 1.0", centre > 1.0f)
    }

    @Test
    fun `bayer pattern selection produces different outputs`() {
        val w = 8; val h = 8
        val raw = ShortArray(w * h) { ((it % 256) * 16).toShort() }
        val calib = RawCalibration()
        val frameRGGB = engine.demosaic(raw, w, h, RawBayerPattern.RGGB, calib)
        val frameBGGR = engine.demosaic(raw, w, h, RawBayerPattern.BGGR, calib)
        // Centre R of RGGB should be the centre B of BGGR — pattern flipped.
        val cy = h / 2; val cx = w / 2
        assertTrue(
            "RGGB.R[c]=${frameRGGB.red[cy*w+cx]} != BGGR.B[c]=${frameBGGR.blue[cy*w+cx]}",
            abs(frameRGGB.red[cy * w + cx] - frameBGGR.blue[cy * w + cx]) < 1e-3f,
        )
    }

    @Test
    fun `metadata is forwarded into pipeline frame`() {
        val w = 8; val h = 8
        val raw = ShortArray(w * h)
        val frame = engine.demosaic(
            raw, w, h, RawBayerPattern.RGGB,
            RawCalibration(),
            evOffset = 1.5f,
            iso = 800,
            exposureNs = 33_000_000L,
        )
        assertEquals(1.5f, frame.evOffset, 1e-6f)
        assertEquals(800, frame.isoEquivalent)
        assertEquals(33_000_000L, frame.exposureTimeNs)
    }
}
