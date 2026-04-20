package com.leica.cam.photon_matrix

import com.leica.cam.common.result.LeicaResult

interface IPhotonMatrixAssembler {
    suspend fun assemble(
        enhanced: PhotonBuffer,
        outputMode: ProXdrOutputMode,
        metadata: OutputMetadata,
    ): LeicaResult<LumoOutputPackage>

    data class OutputMetadata(
        val xmp: ByteArray = byteArrayOf(),
        val exif: ByteArray = byteArrayOf(),
        val dngTags: Map<String, String> = emptyMap(),
    )
}

typealias LumoOutputPackage = com.leica.cam.smart_imaging.LumoOutputPackage
