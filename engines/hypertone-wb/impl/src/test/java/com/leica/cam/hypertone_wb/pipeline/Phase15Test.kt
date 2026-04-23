package com.leica.cam.hypertone_wb.pipeline

import com.leica.cam.common.result.LeicaResult
import com.leica.cam.hardware.contracts.TrueColourHardwareSensor
import com.leica.cam.hardware.contracts.TrueColourRawReading
import com.leica.cam.hypertone_wb.api.IlluminantClass
import com.leica.cam.hypertone_wb.api.TileCTEstimate
import com.leica.cam.hardware.contracts.photon.PhotonBuffer
import com.leica.cam.photon_matrix.FusedPhotonBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class Phase15Test {
    private lateinit var partitionedCTSensor: PartitionedCTSensor
    private lateinit var fusion: MultiModalIlluminantFusion

    @Before
    fun setup() {
        val hardwareSensor = object : TrueColourHardwareSensor {
            override fun readFullGrid(): List<TrueColourRawReading> = (0 until 16).map {
                TrueColourRawReading(it / 4, it % 4, 5500f, 100f, 0.9f)
            }

            override fun getConfidence(): Float = 0.9f
        }
        val classifier = object : IlluminantClassifier {
            override fun classify(kelvin: Float): IlluminantClass = IlluminantClass.DAYLIGHT_DIRECT
        }
        partitionedCTSensor = PartitionedCTSensor(hardwareSensor, classifier)
        fusion = MultiModalIlluminantFusion()
    }

    @Test
    fun partitionedCtSensorReturnsConfiguredGrid() {
        val result = partitionedCTSensor.estimateTiledCT(dummyFusedBuffer())

        assertTrue(result is LeicaResult.Success)
        assertEquals(16, (result as LeicaResult.Success).value.size)
    }

    @Test
    fun multiModalFusionWeightsHardwareAgainstAiKelvin() {
        val hwEstimates = (0 until 16).map {
            TileCTEstimate(it / 4, it % 4, 5000f, 0.9f, IlluminantClass.DAYLIGHT_DIRECT)
        }

        val fused = fusion.fuse(hwEstimates, aiKelvin = 5500f)

        assertEquals(5150f, fused.tiles.first().kelvin, 1f)
    }

    private fun dummyFusedBuffer(): FusedPhotonBuffer = FusedPhotonBuffer(
        underlying = PhotonBuffer.create16Bit(
            width = 1,
            height = 1,
            planes = listOf(shortArrayOf(1), shortArrayOf(1), shortArrayOf(1)),
        ),
        fusionQuality = 1f,
        frameCount = 1,
        motionMagnitude = 0f,
    )
}
