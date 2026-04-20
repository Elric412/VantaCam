package com.leica.cam.hardware.contracts

data class GpuCapabilities(
    val backend: String,
    val maxTextureSize: Int,
    val supportsFp16: Boolean,
)
