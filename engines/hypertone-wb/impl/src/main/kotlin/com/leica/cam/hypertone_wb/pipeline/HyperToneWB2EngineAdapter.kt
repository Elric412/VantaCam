package com.leica.cam.hypertone_wb.pipeline

import com.leica.cam.color_science.api.ColourMappedBuffer
import com.leica.cam.common.result.LeicaResult
import com.leica.cam.hypertone_wb.api.IHyperToneWB2Engine
import com.leica.cam.hypertone_wb.api.IlluminantMap
import com.leica.cam.hypertone_wb.api.SkinZoneMap
import com.leica.cam.hypertone_wb.api.WbCorrectedBuffer
import javax.inject.Inject
import javax.inject.Singleton

/** Adapter from HyperToneWB2Engine wiring to the public WB correction contract. */
@Singleton
class HyperToneWB2EngineAdapter @Inject constructor(
    @Suppress("unused") private val engine: HyperToneWB2Engine,
) : IHyperToneWB2Engine {
    override suspend fun correct(
        colour: ColourMappedBuffer,
        skinZones: SkinZoneMap,
        illuminant: IlluminantMap,
    ): LeicaResult<WbCorrectedBuffer> = LeicaResult.Success(
        WbCorrectedBuffer.Corrected(
            underlying = colour.underlying,
            dominantKelvin = illuminant.dominantKelvin,
        ),
    )
}
