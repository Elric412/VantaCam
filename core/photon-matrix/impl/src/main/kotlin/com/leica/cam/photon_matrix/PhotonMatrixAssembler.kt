package com.leica.cam.photon_matrix

import com.leica.cam.common.result.LeicaResult
import com.leica.cam.hardware.contracts.photon.PhotonBuffer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PhotonMatrixAssembler @Inject constructor() : IPhotonMatrixAssembler {
    override suspend fun assemble(
        enhanced: PhotonBuffer,
        outputMode: ProXdrOutputMode,
        metadata: IPhotonMatrixAssembler.OutputMetadata,
    ): LeicaResult<PhotonAssemblyOutput> {
        return LeicaResult.Success(
            PhotonAssemblyOutput(
                finalBuffer = enhanced,
                outputMode = outputMode,
                toneProfile = "leica_authentic",
                metadata = PhotonAssemblyOutput.CaptureMetadata(
                    iso = DEFAULT_ISO,
                    exposureTimeNs = DEFAULT_EXPOSURE_TIME_NS,
                    focalLengthMm = 50f,
                    whiteBalanceKelvin = 6500f,
                    timestampNs = System.nanoTime(),
                ),
            ),
        )
    }

    private companion object {
        const val DEFAULT_ISO = 100
        const val DEFAULT_EXPOSURE_TIME_NS = 10_000_000L
    }
}
