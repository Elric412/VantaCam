package com.leica.cam.photon_matrix

import com.leica.cam.common.result.LeicaResult
import com.leica.cam.hardware.contracts.photon.PhotonBuffer

class PhotonMatrixAssembler : IPhotonMatrixAssembler {
    override suspend fun assemble(
        enhanced: PhotonBuffer,
        outputMode: ProXdrOutputMode,
        metadata: IPhotonMatrixAssembler.OutputMetadata,
    ): LeicaResult<com.leica.cam.smart_imaging.LumoOutputPackage> {
        return LeicaResult.Success(
            com.leica.cam.smart_imaging.LumoOutputPackage.Complete(
                finalBuffer = enhanced,
                bokehMask = null,
                captureMetadata = com.leica.cam.smart_imaging.LumoOutputPackage.CaptureMetadata(
                    iso = DEFAULT_ISO,
                    exposureTimeNs = DEFAULT_EXPOSURE_TIME_NS,
                    focalLengthMm = 50f,
                    whiteBalanceKelvin = 6500f,
                    timestampNs = System.nanoTime(),
                ),
                outputMode = outputMode,
                toneProfile = "leica_authentic",
            ),
        )
    }

    private companion object {
        const val DEFAULT_ISO = 100
        const val DEFAULT_EXPOSURE_TIME_NS = 10_000_000L
    }
}
