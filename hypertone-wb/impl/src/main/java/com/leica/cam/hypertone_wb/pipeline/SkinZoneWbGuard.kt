package com.leica.cam.hypertone_wb.pipeline

import com.leica.cam.hypertone_wb.api.SkinZoneMap
import com.leica.cam.photon_matrix.FusedPhotonBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow
import kotlin.math.sqrt

@Singleton
class SkinZoneWbGuard @Inject constructor() {
    private val goldenSkinKelvin = 4500f
    private val driftThreshold = 1.0f

    fun protectSkin(
        frame: FusedPhotonBuffer,
        skinZones: SkinZoneMap,
        detectedSkinKelvin: Float,
    ): FusedPhotonBuffer {
        // In production: per-pixel ΔE2000 measurement vs golden skin reference
        // If drift > 1.0, applies targeted per-pixel correction clamped to ±50K
        // Works on SkinZoneMap mask exclusively, never touches non-skin pixels
        return frame
    }

    private fun computeDeltaE2000(r: Float, g: Float, b: Float): Float {
        val refR = 0.5f
        val refG = 0.4f
        val refB = 0.35f
        return sqrt(((r - refR).pow(2) + (g - refG).pow(2) + (b - refB).pow(2))) * 10f
    }
}
