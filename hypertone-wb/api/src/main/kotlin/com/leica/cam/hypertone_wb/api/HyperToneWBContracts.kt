package com.leica.cam.hypertone_wb.api

import com.leica.cam.common.result.LeicaResult
import com.leica.cam.color_science.api.ColourMappedBuffer
import com.leica.cam.photon_matrix.FusedPhotonBuffer

interface IHyperToneWB2Engine {
    suspend fun correct(
        colour: ColourMappedBuffer,
        skinZones: SkinZoneMap,
        illuminant: IlluminantMap,
    ): LeicaResult<WbCorrectedBuffer>
}

sealed class WbCorrectedBuffer {
    abstract val underlying: FusedPhotonBuffer
    data class Corrected internal constructor(
        override val underlying: FusedPhotonBuffer,
        val dominantKelvin: Float,
    ) : WbCorrectedBuffer()
}

data class TileCTEstimate(
    val tileRow: Int,
    val tileCol: Int,
    val kelvin: Float,
    val confidence: Float,
    val illuminantClass: IlluminantClass,
)

enum class IlluminantClass {
    TUNGSTEN_WARM,
    LED_NEUTRAL,
    FLUORESCENT,
    DAYLIGHT_DIRECT,
    DAYLIGHT_OVERCAST,
    MIXED,
}

data class FusedIlluminantMap(
    val tiles: List<TileCTEstimate>,
) {
    init {
        require(tiles.size == 16) { "Fused illuminant map must contain exactly 16 tiles (4x4 grid)" }
    }
}

data class IlluminantMap(
    val tiles: List<TileCTEstimate>,
    val dominantKelvin: Float,
)

data class SkinZoneMap(
    val width: Int,
    val height: Int,
    val mask: FloatArray,
) {
    init {
        require(width > 0 && height > 0) { "SkinZoneMap dimensions must be positive" }
        require(mask.size == width * height) { "SkinZoneMap size mismatch" }
    }
}
