package com.leica.cam.photon_matrix

sealed class ProXdrOutputMode {
    data object Heic : ProXdrOutputMode()
    data object Dng : ProXdrOutputMode()
    data object Hdr10 : ProXdrOutputMode()
    data object ProXdr : ProXdrOutputMode()
}
