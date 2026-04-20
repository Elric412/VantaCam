package com.leica.cam.hardware.contracts

data class LensSpec(
    val focalLengthMm: Float,
    val aperture: Float,
    val minimumFocusDistanceM: Float,
)
