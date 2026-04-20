package com.leica.cam.hardware.contracts

data class NpuCapabilities(
    val present: Boolean,
    val peakTops: Float,
    val supportsInt8: Boolean,
)
