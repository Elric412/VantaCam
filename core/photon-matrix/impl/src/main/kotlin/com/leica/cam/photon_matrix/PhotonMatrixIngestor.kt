package com.leica.cam.photon_matrix

import com.leica.cam.common.result.LeicaResult
import com.leica.cam.common.types.NonEmptyList
import com.leica.cam.hardware.contracts.photon.PhotonBuffer

class PhotonMatrixIngestor : IPhotonMatrixIngestor {
    override suspend fun ingest(frames: NonEmptyList<Any>): LeicaResult<PhotonBuffer> {
        val width = 4032
        val height = 3024
        val size = width * height
        val planes = List(3) { ShortArray(size) }
        return LeicaResult.Success(
            PhotonBuffer.create16Bit(
                width = width,
                height = height,
                planes = planes,
            ),
        )
    }
}
