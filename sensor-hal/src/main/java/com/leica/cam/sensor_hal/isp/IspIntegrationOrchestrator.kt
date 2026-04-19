package com.leica.cam.sensor_hal.isp

import com.leica.cam.sensor_hal.soc.SoCProfile
import com.leica.cam.sensor_hal.soc.SoCVendor

/**
 * ISP integration layer: queries Camera2 TotalCaptureResult metadata to determine
 * which processing stages have already been applied by the hardware ISP,
 * then signals the software pipeline to skip equivalent LUMO passes.
 *
 * This prevents double-processing (e.g., applying software lens shading correction
 * after the ISP has already corrected it), which degrades image quality.
 *
 * Supported ISPs:
 * - MediaTek Imagiq (this device's ISP)
 * - Qualcomm Spectra
 * - Samsung Exynos ISP
 *
 * References:
 * - Android Camera2 API: CaptureResult keys for querying applied processing
 * - Dimension 6 specification: ISP integration optimisations
 */
class IspIntegrationOrchestrator(
    private val socProfile: SoCProfile,
) {
    /**
     * Snapshot of which hardware ISP stages have been applied to the current frame.
     * Derived from TotalCaptureResult metadata.
     *
     * When a flag is true, the corresponding LUMO software pass MUST be skipped.
     */
    data class HalAppliedStages(
        /** Lens shading correction already applied — skip VignetteCompensator. */
        val lensShadingApplied: Boolean = false,
        /** Noise reduction already applied — skip software shadow denoiser pre-pass. */
        val noiseReductionApplied: Boolean = false,
        /** Geometric distortion already corrected — skip LensCorrectionSuite.DistortionModel. */
        val distortionCorrectionApplied: Boolean = false,
        /** Hot pixel correction already applied — skip software hot pixel map. */
        val hotPixelCorrectionApplied: Boolean = false,
        /** Hardware AWB is active — reduce HyperTone WB to single-pass lightweight mode. */
        val hardwareAwbActive: Boolean = false,
        /** Hardware face detection results available — skip MediaPipe face inference. */
        val hardwareFaceDetectionActive: Boolean = false,
        /** Hardware MFNR is active — skip LUMO software shadow denoising pre-pass. */
        val hardwareMfnrActive: Boolean = false,
        /** Hardware histogram available — use for metering instead of GPU histogram. */
        val hardwareHistogramActive: Boolean = false,
        /** EIS is active and modifying SCALER_CROP_REGION per frame. */
        val eisActive: Boolean = false,
        /** Current frame's crop region (for EIS-adjusted alignment offset). */
        val scalerCropRegion: IntArray? = null,
    )

    /**
     * Analyse CaptureResult-equivalent metadata fields to determine which
     * hardware ISP stages have been applied to this frame.
     *
     * In production, [captureResultProxy] is populated from the actual
     * TotalCaptureResult keys. Here we model the keys as named fields.
     *
     * @param captureResultProxy Named fields extracted from TotalCaptureResult
     * @return [HalAppliedStages] snapshot for the current frame
     */
    fun detectAppliedStages(captureResultProxy: CaptureResultProxy): HalAppliedStages {
        return when (socProfile.vendor) {
            SoCVendor.MEDIATEK -> detectMediaTekStages(captureResultProxy)
            SoCVendor.QUALCOMM -> detectSnapdragonStages(captureResultProxy)
            SoCVendor.SAMSUNG  -> detectExynosStages(captureResultProxy)
            SoCVendor.UNKNOWN  -> detectGenericStages(captureResultProxy)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // MediaTek Imagiq ISP
    // ─────────────────────────────────────────────────────────────────────

    /**
     * MediaTek Imagiq ISP stage detection.
     *
     * On Dimensity devices, RAW_SENSOR images from ZSL may already have
     * HAL-applied processing. We check TotalCaptureResult keys before
     * applying any equivalent LUMO passes.
     *
     * Key checks per Dimension 6 spec:
     * - LENS_SHADING_CORRECTION_MODE → if FULL, skip VignetteCompensator
     * - NOISE_REDUCTION_MODE → if HIGH_QUALITY, skip shadow denoiser pre-pass
     * - STATISTICS_HOT_PIXEL_MAP_MODE → if ON, skip software hot pixel removal
     * - CONTROL_AWB_MODE → if != OFF, Imagiq AWB already modified RAW;
     *   disable HyperTone multi-modal fusion, use single-pass correction
     * - STATISTICS_FACE_DETECT_MODE_FULL → skip MediaPipe if hardware ROIs available
     * - DISTORTION_CORRECTION_MODE HIGH_QUALITY → skip DistortionModel in lens-model
     * - Dimensity 9200+: YUV_REPROCESSING capability → use Strategy B ZSL
     */
    private fun detectMediaTekStages(r: CaptureResultProxy): HalAppliedStages {
        val hardwareAwbActive = r.controlAwbMode != ControlAwbMode.OFF
        val lensShadingApplied = r.lensShadingCorrectionMode == LensShadingMode.FULL
        val noiseReductionApplied = r.noiseReductionMode == NoiseReductionMode.HIGH_QUALITY ||
            r.noiseReductionMode == NoiseReductionMode.ZERO_SHUTTER_LAG
        val hotPixelApplied = r.hotPixelMapMode == HotPixelMapMode.ON
        val distortionApplied = r.distortionCorrectionMode == DistortionCorrectionMode.HIGH_QUALITY
        // Hardware face detection: skip MediaPipe (~25ms saving) when hardware ROIs available
        val faceDetectActive = r.faceDetectMode == FaceDetectMode.FULL && r.faceCount > 0

        return HalAppliedStages(
            lensShadingApplied = lensShadingApplied,
            noiseReductionApplied = noiseReductionApplied,
            distortionCorrectionApplied = distortionApplied,
            hotPixelCorrectionApplied = hotPixelApplied,
            // Imagiq AWB conflict: when active, WB is already applied to RAW data
            hardwareAwbActive = hardwareAwbActive,
            hardwareFaceDetectionActive = faceDetectActive,
            hardwareMfnrActive = noiseReductionApplied,
            hardwareHistogramActive = false, // Imagiq does not expose hardware histogram
            eisActive = r.eisActive,
            scalerCropRegion = r.scalerCropRegion,
        )
    }

    // ─────────────────────────────────────────────────────────────────────
    // Qualcomm Spectra ISP
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Qualcomm Spectra ISP stage detection.
     *
     * Key specialties:
     * - SENSOR_NOISE_PROFILE from Spectra is factory-calibrated — always use it.
     * - NOISE_REDUCTION_MODE_HIGH_QUALITY with ZSL active → Spectra hardware MFNR.
     *   Use for pre-fusion denoising; skip LUMO software shadow denoising.
     * - EIS active → SCALER_CROP_REGION is modified per-frame.
     *   READ crop region per-frame to correct FusionLM alignment offset.
     * - Snapdragon 8 Gen 3+: hardware ToF depth via DEPTH_OUTPUT stream.
     */
    private fun detectSnapdragonStages(r: CaptureResultProxy): HalAppliedStages {
        val hardwareMfnr = r.noiseReductionMode == NoiseReductionMode.HIGH_QUALITY && r.zslActive
        return HalAppliedStages(
            lensShadingApplied = r.lensShadingCorrectionMode == LensShadingMode.FULL,
            noiseReductionApplied = hardwareMfnr,
            distortionCorrectionApplied = r.distortionCorrectionMode == DistortionCorrectionMode.HIGH_QUALITY,
            hotPixelCorrectionApplied = r.hotPixelMapMode == HotPixelMapMode.ON,
            hardwareAwbActive = false, // Snapdragon: no AWB conflict (we use RAW before ISP WB)
            hardwareFaceDetectionActive = r.faceDetectMode == FaceDetectMode.FULL && r.faceCount > 0,
            hardwareMfnrActive = hardwareMfnr,
            hardwareHistogramActive = false,
            eisActive = r.eisActive,
            scalerCropRegion = r.scalerCropRegion, // Critical for EIS crop compensation
        )
    }

    // ─────────────────────────────────────────────────────────────────────
    // Samsung Exynos ISP
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Samsung Exynos ISP stage detection.
     *
     * Key specialties:
     * - Set NOISE_REDUCTION_MODE = MINIMAL and AE_MODE = OFF to bypass
     *   hardware MFNR and receive truly unprocessed Bayer for FusionLM.
     * - Hardware histogram via STATISTICS_HISTOGRAM_MODE = ON.
     *   Use in AdvancedMeteringEngine instead of GPU histogram at 30fps.
     * - Samsung vendor tags: shade/edge masks available on OneUI 4.0+.
     */
    private fun detectExynosStages(r: CaptureResultProxy): HalAppliedStages {
        return HalAppliedStages(
            lensShadingApplied = r.lensShadingCorrectionMode == LensShadingMode.FULL,
            noiseReductionApplied = r.noiseReductionMode == NoiseReductionMode.HIGH_QUALITY,
            distortionCorrectionApplied = false, // Exynos: handle in software
            hotPixelCorrectionApplied = r.hotPixelMapMode == HotPixelMapMode.ON,
            hardwareAwbActive = false,
            hardwareFaceDetectionActive = r.faceDetectMode == FaceDetectMode.FULL && r.faceCount > 0,
            hardwareMfnrActive = r.noiseReductionMode == NoiseReductionMode.HIGH_QUALITY,
            hardwareHistogramActive = r.statisticsHistogramMode == HistogramMode.ON,
            eisActive = r.eisActive,
            scalerCropRegion = r.scalerCropRegion,
        )
    }

    private fun detectGenericStages(r: CaptureResultProxy) = HalAppliedStages(
        hardwareAwbActive = r.controlAwbMode != ControlAwbMode.OFF,
        eisActive = r.eisActive,
        scalerCropRegion = r.scalerCropRegion,
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// CaptureResult proxy — models the Camera2 TotalCaptureResult fields we read
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Proxy for Camera2 TotalCaptureResult fields relevant to ISP stage detection.
 *
 * In production, populate this from the TotalCaptureResult callback using:
 *   result.get(CaptureResult.LENS_SHADING_CORRECTION_MODE)
 *   result.get(CaptureResult.NOISE_REDUCTION_MODE)
 *   etc.
 */
data class CaptureResultProxy(
    val controlAwbMode: ControlAwbMode = ControlAwbMode.AUTO,
    val lensShadingCorrectionMode: LensShadingMode = LensShadingMode.FAST,
    val noiseReductionMode: NoiseReductionMode = NoiseReductionMode.FAST,
    val hotPixelMapMode: HotPixelMapMode = HotPixelMapMode.OFF,
    val distortionCorrectionMode: DistortionCorrectionMode = DistortionCorrectionMode.OFF,
    val faceDetectMode: FaceDetectMode = FaceDetectMode.OFF,
    val faceCount: Int = 0,
    val statisticsHistogramMode: HistogramMode = HistogramMode.OFF,
    val eisActive: Boolean = false,
    val zslActive: Boolean = false,
    /** SCALER_CROP_REGION: [x, y, width, height] in pixel coordinates. */
    val scalerCropRegion: IntArray? = null,
    val sensorNoiseProfileAvailable: Boolean = false,
)

// Enums modelling Camera2 integer constants
enum class ControlAwbMode { OFF, AUTO, INCANDESCENT, FLUORESCENT, WARM_FLUORESCENT, DAYLIGHT, CLOUDY_DAYLIGHT }
enum class LensShadingMode { OFF, FAST, HIGH_QUALITY, FULL }
enum class NoiseReductionMode { OFF, FAST, HIGH_QUALITY, MINIMAL, ZERO_SHUTTER_LAG }
enum class HotPixelMapMode { OFF, ON }
enum class DistortionCorrectionMode { OFF, FAST, HIGH_QUALITY }
enum class FaceDetectMode { OFF, SIMPLE, FULL }
enum class HistogramMode { OFF, ON }
