package com.leica.cam.neural_isp.pipeline

import com.leica.cam.common.result.LeicaResult
import com.leica.cam.hardware.contracts.photon.PhotonBuffer
import com.leica.cam.neural_isp.api.INeuralIspOrchestrator
import com.leica.cam.neural_isp.api.ThermalBudget
import com.leica.cam.neural_isp.api.ThermalTier
import com.leica.cam.neural_isp.api.TonedBuffer
import javax.inject.Inject
import javax.inject.Singleton

/** Default neural-ISP facade. Heavy RAW-domain routing remains tracked in known issues. */
@Singleton
class NeuralIspOrchestratorImpl @Inject constructor() : INeuralIspOrchestrator {
    override suspend fun enhance(
        toned: TonedBuffer,
        budget: ThermalBudget,
    ): LeicaResult<PhotonBuffer> = when (budget.tier) {
        ThermalTier.EMERGENCY_STOP -> LeicaResult.Success(toned.underlying)
        ThermalTier.FULL, ThermalTier.REDUCED, ThermalTier.MINIMAL -> LeicaResult.Success(toned.underlying)
    }
}
