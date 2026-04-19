package com.leica.cam.test.builders

import com.leica.cam.hardware.contracts.photon.PhotonBuffer

object PhotonBufferBuilder {
    fun create16Bit(
        width: Int = 4,
        height: Int = 4,
        planeCount: Int = 3,
    ): PhotonBuffer {
        val samples = width * height
        val planes = (0 until planeCount).map { plane ->
            ShortArray(samples) { index -> (plane * 100 + index).toShort() }
        }
        return PhotonBuffer.create16Bit(width, height, planes)
    }
}
