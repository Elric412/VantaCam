package com.leica.cam.capture.isp

import com.leica.cam.ai_engine.api.CaptureMode
import com.leica.cam.neural_isp.api.ThermalTier
import com.leica.cam.sensor_hal.isp.IspIntegrationOrchestrator

/**
 * Capture-time ISP routing engine that decides which processing stages
 * to apply based on hardware ISP detection, thermal state, and capture mode.
 *
 * This engine prevents double-processing by checking what the hardware ISP
 * has already applied (via IspIntegrationOrchestrator) and routing the
 * software pipeline accordingly.
 *
 * Key decisions:
 * - Neural ISP vs Traditional ISP (based on SoC, thermal, resolution)
 * - Skip software denoise when hardware MFNR is active
 * - Reduce HyperTone WB to single-pass when hardware AWB is active
 * - Skip software face detection when hardware ROIs are available
 * - Skip lens shading correction when ISP has applied it
 *
 * Reference: Implementation.md — ISP Integration / Neural ISP Routing
 */
class CaptureTimeIspRouter {

    /**
     * Route the capture pipeline based on hardware detection and runtime constraints.
     *
     * @param captureMode Current capture mode
     * @param thermalTier Current thermal tier from RuntimeGovernor
     * @param halStages Hardware ISP stages already applied to this frame
     * @param socModel SoC model string for neural ISP compatibility check
     * @param gpuTemperature Current GPU temperature in Celsius
     * @param processingBudgetMs Available processing time budget
     * @param resolutionMp Resolution in megapixels
     * @return Routing decision for the software pipeline
     */
    fun route(
        captureMode: CaptureMode = CaptureMode.AUTO,
        thermalTier: ThermalTier = ThermalTier.FULL,
        halStages: IspIntegrationOrchestrator.HalAppliedStages = IspIntegrationOrchestrator.HalAppliedStages(),
        socModel: String = "",
        gpuTemperature: Float = 45f,
        processingBudgetMs: Long = 500L,
        resolutionMp: Float = 12f,
    ): IspRoutingDecision {
        // ── Neural ISP eligibility ───────────────────────────────────
        val useNeuralIsp = shouldUseNeuralIsp(
            captureMode, thermalTier, socModel, gpuTemperature, processingBudgetMs, resolutionMp,
        )

        // ── Software stage skip decisions ────────────────────────────
        val skipSoftwareDenoise = halStages.noiseReductionApplied || halStages.hardwareMfnrActive
        val skipLensShading = halStages.lensShadingApplied
        val skipDistortionCorrection = halStages.distortionCorrectionApplied
        val skipHotPixel = halStages.hotPixelCorrectionApplied
        val skipSoftwareFaceDetect = halStages.hardwareFaceDetectionActive
        val reduceWbToSinglePass = halStages.hardwareAwbActive
        val useHardwareHistogram = halStages.hardwareHistogramActive

        // ── EIS compensation ─────────────────────────────────────────
        val eisCropCompensation = if (halStages.eisActive) {
            halStages.scalerCropRegion
        } else {
            null
        }

        // ── Thermal degradation ──────────────────────────────────────
        val thermalDegradation = when (thermalTier) {
            ThermalTier.FULL -> ThermalDegradation.NONE
            ThermalTier.REDUCED -> ThermalDegradation.SKIP_SEMANTIC_ENHANCEMENT
            ThermalTier.MINIMAL -> ThermalDegradation.SKIP_NEURAL_STAGES
            ThermalTier.EMERGENCY_STOP -> ThermalDegradation.FAST_PATH_ONLY
        }

        return IspRoutingDecision(
            useNeuralIsp = useNeuralIsp,
            skipSoftwareDenoise = skipSoftwareDenoise,
            skipLensShading = skipLensShading,
            skipDistortionCorrection = skipDistortionCorrection,
            skipHotPixel = skipHotPixel,
            skipSoftwareFaceDetect = skipSoftwareFaceDetect,
            reduceWbToSinglePass = reduceWbToSinglePass,
            useHardwareHistogram = useHardwareHistogram,
            eisCropCompensation = eisCropCompensation,
            thermalDegradation = thermalDegradation,
        )
    }

    private fun shouldUseNeuralIsp(
        captureMode: CaptureMode,
        thermalTier: ThermalTier,
        socModel: String,
        gpuTemperature: Float,
        processingBudgetMs: Long,
        resolutionMp: Float,
    ): Boolean {
        // Video mode always uses traditional ISP for latency
        if (captureMode == CaptureMode.VIDEO) return false

        // Thermal constraints
        if (thermalTier == ThermalTier.MINIMAL || thermalTier == ThermalTier.EMERGENCY_STOP) return false
        if (gpuTemperature >= MAX_GPU_TEMP_FOR_NEURAL) return false

        // Budget constraints
        if (processingBudgetMs < MIN_NEURAL_BUDGET_MS) return false

        // Resolution constraints
        if (resolutionMp > MAX_NEURAL_RESOLUTION_MP) return false

        // SoC compatibility
        if (socModel.isNotEmpty() && !isSupportedSoc(socModel)) return false

        return true
    }

    private fun isSupportedSoc(model: String): Boolean {
        val normalized = model.lowercase()
        return SUPPORTED_SOCS.any { normalized.contains(it) }
    }

    /**
     * ISP routing decision containing all skip/enable flags for pipeline stages.
     */
    data class IspRoutingDecision(
        /** Use neural ISP (4-stage) or fall back to traditional (2-stage) */
        val useNeuralIsp: Boolean,
        /** Skip software denoise (hardware MFNR active) */
        val skipSoftwareDenoise: Boolean,
        /** Skip vignette/lens-shading compensation (hardware applied) */
        val skipLensShading: Boolean,
        /** Skip geometric distortion correction (hardware applied) */
        val skipDistortionCorrection: Boolean,
        /** Skip hot pixel removal (hardware applied) */
        val skipHotPixel: Boolean,
        /** Skip MediaPipe face detection (hardware ROIs available) */
        val skipSoftwareFaceDetect: Boolean,
        /** Reduce HyperTone WB to single-pass (hardware AWB active) */
        val reduceWbToSinglePass: Boolean,
        /** Use hardware histogram for metering */
        val useHardwareHistogram: Boolean,
        /** EIS crop region to compensate in alignment (null = no EIS) */
        val eisCropCompensation: IntArray?,
        /** Thermal degradation level */
        val thermalDegradation: ThermalDegradation,
    )

    enum class ThermalDegradation {
        NONE,
        SKIP_SEMANTIC_ENHANCEMENT,
        SKIP_NEURAL_STAGES,
        FAST_PATH_ONLY,
    }

    companion object {
        private const val MAX_GPU_TEMP_FOR_NEURAL = 75f
        private const val MIN_NEURAL_BUDGET_MS = 800L
        private const val MAX_NEURAL_RESOLUTION_MP = 50f

        private val SUPPORTED_SOCS = listOf(
            "snapdragon 8 gen 2",
            "snapdragon 8 gen 3",
            "snapdragon 8 elite",
            "dimensity 9200",
            "dimensity 9300",
            "dimensity 9400",
            "exynos 2400",
        )
    }
}
