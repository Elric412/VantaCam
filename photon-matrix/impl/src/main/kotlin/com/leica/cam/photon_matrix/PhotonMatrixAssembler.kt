package com.leica.cam.photon_matrix

import com.leica.cam.common.result.LeicaResult

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
                    iso = enhanced.frameMetadata.iso,
                    exposureTimeNs = enhanced.frameMetadata.exposureTimeNs,
                    focalLengthMm = 50f,
                    whiteBalanceKelvin = 6500f,
                    timestampNs = enhanced.captureTimestampNs,
                ),
                outputMode = outputMode,
                toneProfile = "leica_authentic",
            )
        )
    }
}
