package com.leica.cam.color_science.api

import com.leica.cam.common.result.LeicaResult
import com.leica.cam.photon_matrix.FusedPhotonBuffer

interface IColorLM2Engine {
    suspend fun mapColours(fused: FusedPhotonBuffer, scene: SceneContext): LeicaResult<ColourMappedBuffer>
}

sealed class ColourMappedBuffer {
    abstract val underlying: FusedPhotonBuffer
    data class Mapped(
        override val underlying: FusedPhotonBuffer,
        val zoneCount: Int,
    ) : ColourMappedBuffer()
}

data class SceneContext(
    val sceneLabel: String,
    val illuminantHint: IlluminantHint,
    val captureMode: String = "auto",
)

data class IlluminantHint(
    val estimatedKelvin: Float,
    val confidence: Float,
    val isMixedLight: Boolean,
)

enum class ColorZone {
    SKIN_PRIMARY,
    SKIN_SECONDARY,
    SKY,
    FOLIAGE,
    ARTIFICIAL_WARM,
    ARTIFICIAL_COOL,
    SHADOW,
    NEUTRAL,
}

enum class ZoneType {
    SKIN,
    SKY,
    ARTIFICIAL,
    NATURAL,
    SHADOW,
    NEUTRAL,
}
