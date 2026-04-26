package com.leica.cam.neural_isp.api

import com.leica.cam.common.result.LeicaResult
import com.leica.cam.hardware.contracts.photon.PhotonBuffer

interface INeuralIspOrchestrator {
    suspend fun enhance(toned: TonedBuffer, budget: ThermalBudget): LeicaResult<PhotonBuffer>
}

sealed class TonedBuffer {
    abstract val underlying: PhotonBuffer

    data class TonedImage(
        override val underlying: PhotonBuffer,
        val toneProfile: String,
    ) : TonedBuffer()
}

data class ThermalBudget(
    val tier: ThermalTier,
    val gpuTemperatureCelsius: Float,
    val processingBudgetMs: Long,
)

enum class ThermalTier {
    FULL,
    REDUCED,
    MINIMAL,
    EMERGENCY_STOP,
}
