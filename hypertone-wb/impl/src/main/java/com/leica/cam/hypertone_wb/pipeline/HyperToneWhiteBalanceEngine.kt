package com.leica.cam.hypertone_wb.pipeline

import com.leica.cam.common.result.LeicaResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * End-to-end Phase 15 HyperTone WB orchestrator.
 * Delegates to HyperToneWB2Engine for advanced white balance logic.
 */
@Singleton
class HyperToneWhiteBalanceEngine @Inject constructor(
    private val wb2Engine: HyperToneWB2Engine,
) {
    /**
     * Executes white balance processing.
     */
    suspend fun process(
        frame: RgbFrame,
        sensorToXyz3x3: FloatArray,
        sceneContext: SceneContext? = null,
        skinMask: BooleanArray? = null
    ): LeicaResult<RgbFrame> {
        return wb2Engine.process(frame, sensorToXyz3x3, sceneContext, skinMask)
    }

    /**
     * Legacy entry point for backward compatibility during migration.
     */
    @Deprecated("Use process() for Phase 15 pipeline")
    fun estimate(
        frame: RgbFrame,
        sensorToXyz3x3: FloatArray,
        sceneContext: SceneContext? = null,
        isVideoFrame: Boolean = false,
        skinMask: BooleanArray? = null,
    ): WhiteBalanceResult {
        // Simple fallback mapping for legacy API
        return WhiteBalanceResult(
            cctKelvin = 6500f,
            tint = 0f,
            confidence = 0.5f,
            mixedLightDetected = false,
            recommendedAwbAutoFallback = false,
            colorCorrectionMatrix3x3 = sensorToXyz3x3,
            methodEstimates = emptyList()
        )
    }
}
