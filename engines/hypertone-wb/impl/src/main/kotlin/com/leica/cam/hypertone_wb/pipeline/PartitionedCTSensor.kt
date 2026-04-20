package com.leica.cam.hypertone_wb.pipeline

import com.leica.cam.common.result.LeicaResult
import com.leica.cam.hardware.contracts.TrueColourHardwareSensor
import com.leica.cam.hypertone_wb.api.IlluminantClass
import com.leica.cam.hypertone_wb.api.TileCTEstimate
import com.leica.cam.photon_matrix.FusedPhotonBuffer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PartitionedCTSensor @Inject constructor(
    private val hardwareSensor: TrueColourHardwareSensor,
    private val illuminantClassifier: IlluminantClassifier,
) {
    companion object {
        const val DEFAULT_GRID_ROWS = 4
        const val DEFAULT_GRID_COLS = 4
        const val MIN_TILE_CONFIDENCE = 0.35f
    }

    fun estimateTiledCT(
        frame: FusedPhotonBuffer,
        gridRows: Int = DEFAULT_GRID_ROWS,
        gridCols: Int = DEFAULT_GRID_COLS,
    ): LeicaResult<List<TileCTEstimate>> {
        val estimates = mutableListOf<TileCTEstimate>()
        val rawReadings = hardwareSensor.readFullGrid()

        for (row in 0 until gridRows) {
            for (col in 0 until gridCols) {
                val hw = rawReadings.find { it.row == row && it.col == col }
                val kelvin = if (hw != null && hw.confidence >= MIN_TILE_CONFIDENCE) {
                    hw.kelvin
                } else if (hw != null) {
                    estimates.takeLast(3).map { it.kelvin }.average().toFloat()
                } else {
                    6500f
                }

                val cls = illuminantClassifier.classify(kelvin)
                val confidence = hw?.confidence ?: 0.3f
                estimates += TileCTEstimate(row, col, kelvin, confidence, cls)
            }
        }

        return LeicaResult.Success(estimates)
    }

    fun getDominantKelvin(estimates: List<TileCTEstimate>): Float {
        if (estimates.isEmpty()) return 6500f
        val totalWeight = estimates.sumOf { it.confidence.toDouble() }.toFloat()
        if (totalWeight < 0.1f) return estimates.first().kelvin
        return estimates.sumOf { (it.kelvin * it.confidence).toDouble() }.toFloat() / totalWeight
    }
}

interface IlluminantClassifier {
    fun classify(kelvin: Float): IlluminantClass
}
