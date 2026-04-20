package com.leica.cam.photon_matrix

import com.leica.cam.hardware.contracts.photon.PhotonBuffer

/**
 * Represents a buffer fused from multiple RAW-domain frames.
 *
 * The canonical pixel container now lives in `:hardware-contracts`; this type
 * preserves the higher-level semantic guarantee that the underlying frame has
 * already passed through FusionLM.
 */
data class FusedPhotonBuffer(
    val underlying: PhotonBuffer,
    val fusionQuality: Float,
    val frameCount: Int,
    val motionMagnitude: Float,
) {
    init {
        require(fusionQuality in 0f..1f) { "fusionQuality must be in [0, 1]" }
        require(frameCount >= 1) { "frameCount must be >= 1" }
        require(motionMagnitude >= 0f) { "motionMagnitude must be >= 0" }
    }
}
