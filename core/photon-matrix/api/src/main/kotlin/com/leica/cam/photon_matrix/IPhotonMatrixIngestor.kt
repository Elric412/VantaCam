package com.leica.cam.photon_matrix

import com.leica.cam.common.result.LeicaResult
import com.leica.cam.common.types.NonEmptyList
import com.leica.cam.hardware.contracts.photon.PhotonBuffer

interface IPhotonMatrixIngestor {
    suspend fun ingest(frames: NonEmptyList<Any>): LeicaResult<PhotonBuffer>
}
