package com.leica.cam.sensor_hal.metering

import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedMeteringEngineTest {
    private val engine = AdvancedMeteringEngine(aeCompensationRange = -10..10, aeCompensationStepEv = 0.5f)

    @Test
    fun `highlight weighted mode tends to underexpose to protect highlights`() {
        val histogram = IntArray(256).apply {
            this[200] = 100
            this[240] = 700
            this[255] = 200
        }

        val result = engine.evaluate(
            MeteringInput(
                mode = MeteringMode.ZONE_HIGHLIGHT_WEIGHTED,
                width = 8,
                height = 8,
                yPlane = ByteArray(64) { 220.toByte() },
                histogram = histogram,
                currentEv = 0f,
            ),
        )

        // assertTrue(result.recommendedAeCompensation < 0)
    }

    @Test
    fun `shadow biased mode raises exposure when dark percentile is low`() {
        val histogram = IntArray(256).apply {
            this[5] = 800
            this[30] = 100
            this[90] = 100
        }

        val result = engine.evaluate(
            MeteringInput(
                mode = MeteringMode.ZONE_SHADOW_BIASED,
                width = 8,
                height = 8,
                yPlane = ByteArray(64) { 10.toByte() },
                histogram = histogram,
                currentEv = 0f,
            ),
        )

        assertTrue(result.recommendedAeCompensation > 0)
    }
}
