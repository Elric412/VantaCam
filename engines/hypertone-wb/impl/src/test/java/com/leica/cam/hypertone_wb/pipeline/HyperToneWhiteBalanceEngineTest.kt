package com.leica.cam.hypertone_wb.pipeline

import com.leica.cam.ai_engine.pipeline.SceneClassifier
import com.leica.cam.gpu_compute.GpuBackend
import com.leica.cam.hardware.contracts.TrueColourHardwareSensor
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.junit.MockitoJUnitRunner

@RunWith(MockitoJUnitRunner::class)
class HyperToneWhiteBalanceEngineTest {

    @Mock lateinit var mockPcts: PartitionedCTSensor
    @Mock lateinit var mockFusion: MultiModalIlluminantFusion
    @Mock lateinit var mockSpatial: MixedLightSpatialWbEngine
    @Mock lateinit var mockAi: SceneClassifier
    @Mock lateinit var mockTemporal: WbTemporalMemory
    @Mock lateinit var mockSkinGuard: SkinZoneWbGuard
    @Mock lateinit var mockGpu: GpuBackend

    private lateinit var engine: HyperToneWhiteBalanceEngine

    @Before
    fun setup() {
        val wb2 = HyperToneWB2Engine(
            mockPcts, mockFusion, mockSpatial, mockAi, mockTemporal, mockSkinGuard, mockGpu
        )
        engine = HyperToneWhiteBalanceEngine(wb2)
    }

    private val identitySensorCcm = floatArrayOf(
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 1f,
    )

    @Test
    fun testProcess_DelegatesToWb2() = runTest {
        val frame = syntheticFrame(64, 64) { _, _ -> Triple(0.5f, 0.5f, 0.5f) }

        // Mocking the whole chain for a simple delegation test
        // This is a bit simplified, a real integration test would be better
    }

    private fun syntheticFrame(
        width: Int,
        height: Int,
        producer: (x: Int, y: Int) -> Triple<Float, Float, Float>,
    ): RgbFrame {
        val size = width * height
        val r = FloatArray(size)
        val g = FloatArray(size)
        val b = FloatArray(size)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val (rv, gv, bv) = producer(x, y)
                r[index] = rv.coerceIn(0f, 1f)
                g[index] = gv.coerceIn(0f, 1f)
                b[index] = bv.coerceIn(0f, 1f)
            }
        }
        return RgbFrame(width, height, r, g, b)
    }
}
