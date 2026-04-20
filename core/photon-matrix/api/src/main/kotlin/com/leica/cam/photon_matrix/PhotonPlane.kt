package com.leica.cam.photon_matrix

import java.nio.ShortBuffer

/**
 * Holds the raw ShortBuffer for one colour channel.
 * rowStride, pixelStride, and bitDepth describe the memory layout.
 * Validates bitDepth >= 10 on construction.
 * Immutable after construction.
 */
data class PhotonPlane(
    val buffer: ShortBuffer,
    val rowStride: Int,
    val pixelStride: Int,
    val bitDepth: PhotonBuffer.BitDepth,
) {
    init {
        require(bitDepth != PhotonBuffer.BitDepth.BIT_10 ||
            bitDepth != PhotonBuffer.BitDepth.BIT_12 ||
            bitDepth != PhotonBuffer.BitDepth.BIT_16) {
            "PhotonPlane bitDepth must be >= 10 bits"
        }
    }
}
