package com.leica.cam.photon_matrix

import com.leica.cam.common.result.LeicaResult
import com.leica.cam.common.types.NonEmptyList

interface IPhotonMatrixIngestor {
    suspend fun ingest(frames: NonEmptyList<Any>): LeicaResult<PhotonBuffer>
}
