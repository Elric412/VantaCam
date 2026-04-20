package com.leica.cam.photon_matrix

/**
 * Represents a buffer that has been fused from multiple frames.
 * Required by Layer 3 specialist engines.
 */
data class FusedPhotonBuffer(
    override val id: String,
    override val width: Int,
    override val height: Int,
    override val planes: List<PhotonPlane>,
    override val timestamp: Long
) : PhotonBuffer
