package com.leica.cam.hardware.contracts.photon

import java.nio.ShortBuffer

sealed class PhotonBuffer(
    val width: Int,
    val height: Int,
    val bitDepth: BitDepth,
    protected val planes: List<ShortArray>,
) {
    init {
        require(width > 0) { "width must be positive" }
        require(height > 0) { "height must be positive" }
        require(planes.isNotEmpty()) { "PhotonBuffer must have at least one plane" }
        val expectedSize = width * height
        require(planes.all { it.size == expectedSize }) {
            "Each plane must have width*height samples"
        }
    }

    fun planeCount(): Int = planes.size

    fun planeView(index: Int): ShortBuffer = ShortBuffer.wrap(planes[index].copyOf()).asReadOnlyBuffer()

    enum class BitDepth { BITS_10, BITS_12, BITS_16 }

    class Immutable internal constructor(
        width: Int,
        height: Int,
        bitDepth: BitDepth,
        planes: List<ShortArray>,
    ) : PhotonBuffer(width, height, bitDepth, planes)

    companion object {
        fun create16Bit(
            width: Int,
            height: Int,
            planes: List<ShortArray>,
        ): PhotonBuffer = Immutable(width, height, BitDepth.BITS_16, planes.map { it.copyOf() })
    }
}
