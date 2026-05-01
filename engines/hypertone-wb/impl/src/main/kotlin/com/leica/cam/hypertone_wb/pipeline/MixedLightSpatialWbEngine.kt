package com.leica.cam.hypertone_wb.pipeline

import com.leica.cam.hypertone_wb.api.FusedIlluminantMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Three-illuminant decomposition using a spectral dictionary of 96 real-world illuminants.
 * For each tile: finds the closest 3 illuminant basis vectors.
 * Computes tile CCM as a weighted sum.
 */
@Singleton
class MixedLightSpatialWbEngine @Inject constructor(
    private val estimators: IlluminantEstimators,
    private val kelvinToCcmConverter: KelvinToCcmConverter,
) {
    // Spectral dictionary placeholder (simulated for Phase 15)
    private val spectralDictionary = (2000..12000 step 100).map { it.toFloat() } // 101 basis vectors

    fun estimateMap(frame: RgbFrame, fusedMap: FusedIlluminantMap): SpatialWbMap {
        val width = frame.width
        val height = frame.height
        val outputMap = FloatArray(width * height)

        // For each 4x4 tile
        for (row in 0 until 4) {
            for (col in 0 until 4) {
                val tileIdx = row * 4 + col
                val fusedTile = fusedMap.tiles[tileIdx]

                // Find closest 3 illuminants from dictionary
                val basis = findClosestBasis(fusedTile.kelvin, 3)

                // Compute weighted sum (simplified for now as we don't have real spectral data)
                val tileKelvin = basis.average().toFloat()

                // Fill spatial map for this tile
                val tileWidth = width / 4
                val tileHeight = height / 4
                val startX = col * tileWidth
                val startY = row * tileHeight
                val endX = if (col == 3) width else (col + 1) * tileWidth
                val endY = if (row == 3) height else (row + 1) * tileHeight

                for (y in startY until endY) {
                    for (x in startX until endX) {
                        outputMap[y * width + x] = tileKelvin
                    }
                }
            }
        }

        return SpatialWbMap(width, height, outputMap)
    }

    /**
     * Finds the n closest basis vectors from the spectral dictionary.
     */
    private fun findClosestBasis(targetKelvin: Float, n: Int): List<Float> {
        return spectralDictionary
            .sortedBy { abs(it - targetKelvin) }
            .take(n)
    }

    /**
     * Computes the per-tile CCM based on decomposed illuminants.
     */
    fun computeTileCcm(kelvin: Float, sensorToXyz3x3: FloatArray): FloatArray {
        // In a real implementation, this would use the 3 basis vectors weights.
        // For Phase 15, we use the fused Kelvin.
        return kelvinToCcmConverter.computeFinalCcm(kelvin, 0f, sensorToXyz3x3)
    }
}
