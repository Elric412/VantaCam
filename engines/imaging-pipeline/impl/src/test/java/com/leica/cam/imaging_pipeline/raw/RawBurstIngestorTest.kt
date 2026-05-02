package com.leica.cam.imaging_pipeline.raw

import com.leica.cam.common.result.LeicaResult
import com.leica.cam.imaging_pipeline.hdr.proxdrv3.ProXdrV3Engine
import com.leica.cam.sensor_hal.raw.BayerPattern
import com.leica.cam.sensor_hal.raw.RawBurstBundle
import com.leica.cam.sensor_hal.raw.RawCameraMeta
import com.leica.cam.sensor_hal.raw.RawFrame
import com.leica.cam.sensor_hal.raw.RawFrameMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Unit tests for [RawBurstIngestor]. The native bridge is not loaded in the
 * JVM test environment, so every test exercises the JVM fallback path
 * (RawDemosaicEngine + ProXdrV3Engine RGB fast-path).
 */
class RawBurstIngestorTest {

    private fun makeBundle(width: Int = 32, height: Int = 32, frames: Int = 3): RawBurstBundle {
        val raws = (0 until frames).map { idx ->
            val buf = ByteBuffer.allocateDirect(width * height * 2).order(ByteOrder.nativeOrder())
            // Fill with a deterministic mid-grey + small frame-dependent perturbation.
            val s = buf.asShortBuffer()
            for (i in 0 until width * height) {
                s.put((2048 + (i * idx % 20)).toShort())
            }
            buf.rewind()
            RawFrame(
                width = width,
                height = height,
                raw16 = buf,
                meta = RawFrameMeta(
                    tsNs = idx.toLong() * 33_000_000L,
                    exposureMs = 16f,
                    analogGain = 1f,
                    whiteLevel = 4095f,
                    blackLevels = floatArrayOf(0f, 0f, 0f, 0f),
                    wbGains = floatArrayOf(1f, 1f, 1f, 1f),
                    isoEquivalent = 100,
                ),
            )
        }
        return RawBurstBundle(
            frames = raws,
            camera = RawCameraMeta(
                sensorWidth = width,
                sensorHeight = height,
                bayer = BayerPattern.RGGB,
                ccm = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            ),
        )
    }

    @Test
    fun `native bridge unavailable in JVM tests`() {
        val ing = RawBurstIngestor()
        assertFalse("ProXDRBridge should not load in JVM tests", ing.isNativeAvailable())
    }

    @Test
    fun `JVM fallback returns success for valid burst`() {
        val ing = RawBurstIngestor()
        val bundle = makeBundle()
        val result = ing.process(bundle)
        assertTrue(result is LeicaResult.Success)
        val out = (result as LeicaResult.Success).value.mergedFrame
        assertEquals(bundle.width, out.width)
        assertEquals(bundle.height, out.height)
    }

    @Test
    fun `single frame burst falls through demosaic and merge`() {
        val ing = RawBurstIngestor()
        val bundle = makeBundle(width = 24, height = 24, frames = 1)
        val result = ing.process(bundle)
        assertTrue(result is LeicaResult.Success)
    }

    @Test
    fun `eV bias is applied even on JVM fallback`() {
        val ing = RawBurstIngestor()
        val bundle = makeBundle()
        val baseline = (ing.process(bundle) as LeicaResult.Success).value.mergedFrame
        val biased = (ing.process(bundle, userBias = 1.0f) as LeicaResult.Success).value.mergedFrame
        assertTrue(
            "EV +1 should brighten output: ${biased.meanLuminance()} > ${baseline.meanLuminance()}",
            biased.meanLuminance() > baseline.meanLuminance(),
        )
    }
}
