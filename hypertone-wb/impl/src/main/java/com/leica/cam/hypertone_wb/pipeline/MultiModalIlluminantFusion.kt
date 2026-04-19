package com.leica.cam.hypertone_wb.pipeline

import com.leica.cam.hypertone_wb.api.FusedIlluminantMap
import com.leica.cam.hypertone_wb.api.IlluminantMap
import com.leica.cam.hypertone_wb.api.TileCTEstimate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MultiModalIlluminantFusion @Inject constructor() {
    fun fuseWithHardware(
        hwEstimates: List<TileCTEstimate>,
        illuminantMap: IlluminantMap,
    ): FusedIlluminantMap {
        val aiKelvin = illuminantMap.dominantKelvin

        val fusedTiles = hwEstimates.map { hw ->
            val hwConfidence = hw.confidence

            val (hwWeight, aiWeight) = when {
                hwConfidence > 0.8f -> 0.7f to 0.3f
                hwConfidence > 0.5f -> 0.6f to 0.4f
                else -> 0.3f to 0.7f
            }

            val fusedKelvin = (hw.kelvin * hwWeight + aiKelvin * aiWeight)
            hw.copy(
                kelvin = fusedKelvin,
                confidence = (hw.confidence * hwWeight + 0.7f * aiWeight).coerceIn(0f, 1f),
            )
        }

        return FusedIlluminantMap(fusedTiles)
    }

    fun fuse(hwEstimates: List<TileCTEstimate>, aiKelvin: Float): FusedIlluminantMap {
        return fuseWithHardware(hwEstimates, IlluminantMap(emptyList(), aiKelvin))
    }
}
