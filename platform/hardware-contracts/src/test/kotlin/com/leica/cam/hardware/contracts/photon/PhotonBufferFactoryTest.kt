package com.leica.cam.hardware.contracts.photon

import com.leica.cam.test.builders.PhotonBufferBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotonBufferFactoryTest {
    @Test
    fun `create16Bit creates immutable 16-bit buffer`() {
        val buffer = PhotonBufferBuilder.create16Bit(width = 2, height = 2, planeCount = 3)

        assertEquals(PhotonBuffer.BitDepth.BITS_16, buffer.bitDepth)
        assertEquals(3, buffer.planeCount())
        assertTrue(buffer.planeView(0).isReadOnly)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `create16Bit rejects empty planes`() {
        PhotonBuffer.create16Bit(width = 2, height = 2, planes = emptyList())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `create16Bit rejects invalid plane size`() {
        PhotonBuffer.create16Bit(
            width = 2,
            height = 2,
            planes = listOf(shortArrayOf(1, 2, 3)),
        )
    }
}
