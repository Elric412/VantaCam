package com.leica.cam.photon_matrix

data class FrameMetadata(
    val iso: Int,
    val exposureTimeNs: Long,
    val whiteLevel: Int,
    val blackLevel: Int,
    val sensorModel: String,
)
