package com.leica.cam.imaging_pipeline.hdr

import com.leica.cam.common.result.LeicaResult
import com.leica.cam.imaging_pipeline.api.UserHdrMode
import com.leica.cam.imaging_pipeline.pipeline.HdrMergeMode
import com.leica.cam.imaging_pipeline.pipeline.HdrMergeResult
import com.leica.cam.imaging_pipeline.pipeline.PipelineFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ProXdrOrchestratorUserOverrideTest {
    private val orchestrator = ProXdrOrchestrator()

    @Test
    fun offReturnsFirstFrameUntouched() {
        val first = frame(evOffset = 0f, seed = 0.2f)
        val second = frame(evOffset = 1f, seed = 0.6f)

        val result = orchestrator.process(
            frames = listOf(first, second),
            userHdrMode = UserHdrMode.OFF,
        )

        result.assertSingleFrame(first)
    }

    @Test
    fun singleFrameOnStillReturnsSingleFrame() {
        val single = frame(evOffset = 0f, seed = 0.4f)

        val result = orchestrator.process(
            frames = listOf(single),
            userHdrMode = UserHdrMode.ON,
        )

        result.assertSingleFrame(single)
    }

    @Test
    fun thermalSeverePinsSingleFrameEvenForProXdr() {
        val first = frame(evOffset = 0f, seed = 0.3f)
        val second = frame(evOffset = 2f, seed = 0.8f)
        val scene = SceneDescriptor(
            luminanceHistogram = SceneDescriptor.uniformHistogram(),
            facePresent = false,
            faceMeanLuma = 0f,
            thermalLevel = 4,
        )

        val result = orchestrator.process(
            frames = listOf(first, second),
            scene = scene,
            userHdrMode = UserHdrMode.PRO_XDR,
        )

        result.assertSingleFrame(first)
    }

    private fun LeicaResult<HdrMergeResult>.assertSingleFrame(
        expected: PipelineFrame,
    ) {
        when (this) {
            is LeicaResult.Success -> {
                assertSame(expected, value.mergedFrame)
                assertEquals(HdrMergeMode.SINGLE_FRAME, value.hdrMode)
            }
            is LeicaResult.Failure -> error("Expected success but was $this")
        }
    }

    private fun frame(evOffset: Float, seed: Float): PipelineFrame {
        val channel = FloatArray(16) { seed + it * 0.01f }
        return PipelineFrame(
            width = 4,
            height = 4,
            red = channel.copyOf(),
            green = channel.copyOf(),
            blue = channel.copyOf(),
            evOffset = evOffset,
            isoEquivalent = 100,
        )
    }
}
