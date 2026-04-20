package com.leica.cam.hypertone_wb.pipeline

import com.leica.cam.ai_engine.pipeline.SceneClassification
import com.leica.cam.ai_engine.pipeline.SceneType
import com.leica.cam.common.result.LeicaResult
import com.leica.cam.hardware.contracts.TrueColourHardwareSensor
import com.leica.cam.hardware.contracts.TrueColourRawReading
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.junit.MockitoJUnitRunner

@RunWith(MockitoJUnitRunner::class)
class Phase15Test {

    @Mock
    lateinit var mockHardwareSensor: TrueColourHardwareSensor

    private lateinit var partitionedCTSensor: PartitionedCTSensor
    private lateinit var fusion: MultiModalIlluminantFusion
    private lateinit var classifier: IlluminantClassifier

    @Before
    fun setup() {
        classifier = object : IlluminantClassifier {
            override fun classify(kelvin: Float): IlluminantClass = IlluminantClass.DAYLIGHT_DIRECT
        }
        partitionedCTSensor = PartitionedCTSensor(mockHardwareSensor, classifier)
        fusion = MultiModalIlluminantFusion()
    }

    @Test
    fun testPartitionedCTSensor_Returns16Tiles() {
        val readings = (0 until 16).map {
            TrueColourRawReading(it / 4, it % 4, 5500f, 100f, 0.9f)
        }
        `when`(mockHardwareSensor.readFullGrid()).thenReturn(readings)

        val result = partitionedCTSensor.sensePartitions()
        assertTrue(result is LeicaResult.Success)
        assertEquals(16, (result as LeicaResult.Success).value.size)
    }

    @Test
    fun testMultiModalFusion_WeightsCorrectly() {
        val hwEstimates = (0 until 16).map {
            TileCTEstimate(it / 4, it % 4, 5000f, 0.9f, IlluminantClass.DAYLIGHT_DIRECT)
        }
        val aiClassification = SceneClassification(SceneType.LANDSCAPE, 0.8f, emptyMap())

        val fused = fusion.fuse(hwEstimates, aiClassification)

        // HW confidence 0.9 > 0.8 => HW weight 0.7, AI weight 0.3
        // Landscape maps to 5500f
        // Fused Kelvin = 5000 * 0.7 + 5500 * 0.3 = 3500 + 1650 = 5150
        assertEquals(5150f, fused.tiles[0].kelvin, 1f)
    }
}
