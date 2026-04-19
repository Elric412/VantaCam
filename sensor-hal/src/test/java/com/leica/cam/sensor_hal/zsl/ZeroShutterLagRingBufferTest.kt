package com.leica.cam.sensor_hal.zsl

import org.junit.Assert.assertEquals
import org.junit.Test

class ZeroShutterLagRingBufferTest {
    @Test
    fun `retains only newest frames within capacity`() {
        val buffer = ZeroShutterLagRingBuffer<Int>(capacity = 3)
        buffer.push(1)
        buffer.push(2)
        buffer.push(3)
        buffer.push(4)

        assertEquals(listOf(2, 3, 4), buffer.snapshot().map { it.payload })
    }
}
