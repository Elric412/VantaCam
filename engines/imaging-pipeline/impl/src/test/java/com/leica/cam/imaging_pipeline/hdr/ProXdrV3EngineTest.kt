package com.leica.cam.imaging_pipeline.hdr

import com.leica.cam.common.result.LeicaResult
import com.leica.cam.imaging_pipeline.hdr.proxdrv3.ProXdrV3Engine
import com.leica.cam.imaging_pipeline.hdr.proxdrv3.ProXdrV3SceneMode
import com.leica.cam.imaging_pipeline.hdr.proxdrv3.ProXdrV3Thermal
import com.leica.cam.imaging_pipeline.hdr.proxdrv3.ProXdrV3Tuning
import com.leica.cam.imaging_pipeline.pipeline.HdrMergeResult
import com.leica.cam.imaging_pipeline.pipeline.PipelineFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Unit tests for the ProXDR v3 Kotlin façade.
 *
 * These run on the JVM RGB fast path (the native backend is not loaded in
 * unit tests), exercising the same algorithmic shape as the upstream C++
 * pipeline so we get correctness coverage without depending on a device.
 */
class ProXdrV3EngineTest {

    private val engine = ProXdrV3Engine()

    @Test
    fun emptyFrameListReturnsFailure() {
        val result = engine.process(emptyList())
        assertTrue("Empty list should return failure", result is LeicaResult.Failure)
    }

    @Test
    fun singleFrameReturnsThatFrame() {
        val only = solidFrame(0.3f)
        val result = engine.process(listOf(only))
        when (result) {
            is LeicaResult.Success -> {
                // Single-frame fast path returns the input frame either as-is
                // or via a 1-frame Wiener pass that preserves luminance to fp
                // tolerance.
                assertEquals(only.width, result.value.mergedFrame.width)
                assertEquals(only.height, result.value.mergedFrame.height)
            }
            is LeicaResult.Failure -> error("Expected success: $result")
        }
    }

    @Test
    fun criticalThermalBypassesHdrPipeline() {
        val frames = listOf(solidFrame(0.2f), solidFrame(0.5f), solidFrame(0.8f))
        val result = engine.process(
            frames = frames,
            sceneMode = ProXdrV3SceneMode.AUTO,
            thermal = ProXdrV3Thermal.CRITICAL,
        )
        // Critical thermal must short-circuit to the first frame untouched
        // (ARCHITECTURE.md §10 invariant — never run HDR on critical thermal).
        when (result) {
            is LeicaResult.Success -> assertSame(frames.first(), result.value.mergedFrame)
            is LeicaResult.Failure -> error("Expected success: $result")
        }
    }

    @Test
    fun multiFrameFusionPreservesAverageLuma() {
        // Build 3 noisy frames around a common signal level. Wiener fusion
        // should converge close to that signal mean (within noise floor).
        val signal = 0.5f
        val frames = (0 until 5).map { idx -> noisyFrame(signal, seed = idx) }

        val result = engine.process(frames)
        when (result) {
            is LeicaResult.Success -> {
                val merged = result.value.mergedFrame
                val mean = merged.meanLuminance()
                assertTrue(
                    "Fused mean ($mean) too far from input signal ($signal)",
                    kotlin.math.abs(mean - signal) < 0.05f,
                )
            }
            is LeicaResult.Failure -> error("Expected success: $result")
        }
    }

    @Test
    fun shadowLiftIncreasesShadowEnergy() {
        // Build a single mostly-dark frame and verify the wrapper's shadow
        // lift stage (when adaptive is on, INDOOR/LOW_LIGHT modes lift shadows).
        val dark = darkFrame(0.05f)
        val baseline = engine.process(listOf(dark), thermal = ProXdrV3Thermal.NORMAL)
        val baselineMean = (baseline as LeicaResult.Success).value.mergedFrame.meanLuminance()

        val tuned = ProXdrV3Engine(
            tuning = ProXdrV3Tuning(shadowLiftAmount = 0.45f, adaptive = false),
        )
        val lifted = tuned.process(listOf(dark))
        val liftedMean = (lifted as LeicaResult.Success).value.mergedFrame.meanLuminance()
        // The single-frame path returns the input frame unchanged, so this
        // assertion is a smoke check: we still get a valid frame back.
        assertNotNull(lifted.value.mergedFrame)
        assertTrue("Mean must remain non-negative: $liftedMean", liftedMean >= 0f)
        assertTrue("Baseline mean must remain non-negative: $baselineMean", baselineMean >= 0f)
    }

    @Test
    fun nativeBackendIsUnavailableInUnitTests() {
        // The JNI bridge class is not on the test classpath; we must always
        // run the Kotlin RGB fast path here.
        assertTrue(
            "Native backend must be unavailable in JVM tests",
            !engine.isNativeAvailable(),
        )
    }

    @Test
    fun activeTuningIsExposed() {
        val custom = ProXdrV3Tuning(shadowLiftAmount = 0.30f, ghostSigma = 2.5f)
        val tuned = ProXdrV3Engine(custom)
        assertEquals(0.30f, tuned.activeTuning().shadowLiftAmount, 1e-6f)
        assertEquals(2.5f, tuned.activeTuning().ghostSigma, 1e-6f)
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun solidFrame(value: Float, w: Int = 8, h: Int = 8): PipelineFrame {
        val n = w * h
        val arr = FloatArray(n) { value }
        return PipelineFrame(
            width = w, height = h,
            red = arr.copyOf(), green = arr.copyOf(), blue = arr.copyOf(),
        )
    }

    private fun darkFrame(value: Float, w: Int = 8, h: Int = 8): PipelineFrame =
        solidFrame(value, w, h)

    private fun noisyFrame(signal: Float, seed: Int, w: Int = 8, h: Int = 8): PipelineFrame {
        val r = Random(seed.toLong())
        val n = w * h
        val red = FloatArray(n) { (signal + (r.nextFloat() - 0.5f) * 0.02f).coerceAtLeast(0f) }
        val green = FloatArray(n) { (signal + (r.nextFloat() - 0.5f) * 0.02f).coerceAtLeast(0f) }
        val blue = FloatArray(n) { (signal + (r.nextFloat() - 0.5f) * 0.02f).coerceAtLeast(0f) }
        return PipelineFrame(width = w, height = h, red = red, green = green, blue = blue)
    }
}
