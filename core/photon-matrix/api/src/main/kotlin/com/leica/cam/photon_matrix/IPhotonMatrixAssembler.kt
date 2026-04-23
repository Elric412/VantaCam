package com.leica.cam.photon_matrix

import com.leica.cam.common.result.LeicaResult
import com.leica.cam.hardware.contracts.photon.PhotonBuffer

interface IPhotonMatrixAssembler {
    suspend fun assemble(
        enhanced: PhotonBuffer,
        outputMode: ProXdrOutputMode,
        metadata: OutputMetadata,
    ): LeicaResult<PhotonAssemblyOutput>

    data class OutputMetadata(
        val xmp: ByteArray = byteArrayOf(),
        val exif: ByteArray = byteArrayOf(),
        val dngTags: Map<String, String> = emptyMap(),
    )
}

data class PhotonAssemblyOutput(
    val finalBuffer: PhotonBuffer,
    val outputMode: ProXdrOutputMode,
    val toneProfile: String,
    val metadata: CaptureMetadata,
) {
    data class CaptureMetadata(
        val iso: Int,
        val exposureTimeNs: Long,
        val focalLengthMm: Float,
        val whiteBalanceKelvin: Float,
        val timestampNs: Long,
    )
}
