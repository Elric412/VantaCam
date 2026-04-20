package com.leica.cam.sensor_hal.sensor.profiles

import kotlin.math.abs

/**
 * Immutable per-sensor tuning profile for the LUMO imaging pipeline.
 *
 * Each physical sensor on the device has a distinct pixel architecture, noise floor,
 * colour response, and optical characteristics. This profile encodes those differences
 * so every pipeline stage can adapt its behaviour automatically.
 *
 * @param sensorId      Canonical identifier (e.g., "s5khm6", "ov64b40")
 * @param manufacturer  Sensor silicon manufacturer
 * @param resolution    Native resolution in megapixels
 * @param pixelPitchUm  Native pixel pitch in micrometres
 * @param sensorFormatInch Optical format (e.g., "1/1.67")
 * @param cameraRole    Functional role of this camera on the device
 * @param noiseProfile  ISO-calibrated noise model priors
 * @param colourProfile Per-sensor colour correction hints
 * @param fpnCorrection Fixed-pattern noise correction configuration
 * @param burstConfig   FusionLM burst depth recommendations per ISO bracket
 * @param alignmentConfig Alignment constraints (integer-only for binned modes, etc.)
 * @param specialModes  Sensor-specific modes (Smart-ISO Pro, ALS, staggered HDR)
 */
data class SensorProfile(
    val sensorId: String,
    val manufacturer: SensorManufacturer,
    val resolution: SensorResolution,
    val pixelPitchUm: Float,
    val sensorFormatInch: String,
    val cameraRole: CameraRole,
    val noiseProfile: NoiseProfilePrior,
    val colourProfile: ColourProfile,
    val fpnCorrection: FpnCorrectionConfig,
    val burstConfig: BurstDepthConfig,
    val alignmentConfig: AlignmentConfig,
    val specialModes: SpecialModes,
    val wbConfig: WbSensorConfig,
    val distortionConfig: DistortionConfig,
    val sharpenConfig: SharpenConfig,
) {
    /**
     * Whether Gr/Gb green-channel-split correction should be applied before
     * RAW→linear RGB conversion. ISOCELL sensors (S5KHM6) have negligible split
     * and should skip this step; OmniVision PureCel Plus-S sensors require it.
     *
     * @param grGbDeltaDn Measured |mean(Gr) − mean(Gb)| in digital numbers
     * @return true if correction is needed
     */
    fun requiresGrGbCorrection(grGbDeltaDn: Float): Boolean =
        fpnCorrection.grGbCorrectionThresholdDn > 0f && grGbDeltaDn > fpnCorrection.grGbCorrectionThresholdDn
}

// ─────────────────────────────────────────────────────────────────────────────
// Component data classes
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Sensor silicon manufacturer.
 */
enum class SensorManufacturer {
    SAMSUNG_ISOCELL,
    OMNIVISION,
    GALAXYCORE,
    SMARTSENS,
}

/**
 * Functional role of the camera in the multi-camera system.
 */
enum class CameraRole {
    MAIN_PRIMARY,
    MAIN_SECONDARY,
    MAIN_TERTIARY,
    ULTRA_WIDE,
    FRONT_PRIMARY,
    FRONT_SECONDARY,
    DEPTH,
    MACRO,
}

/**
 * Native and binned resolution modes.
 *
 * @param nativeMp    Full native megapixel count
 * @param binnedMp    Default output megapixel count after binning
 * @param binFactor   Pixel binning ratio (e.g., 9 for Nonacell 9-in-1)
 * @param nativeWidth  Full sensor width in pixels
 * @param nativeHeight Full sensor height in pixels
 */
data class SensorResolution(
    val nativeMp: Int,
    val binnedMp: Int,
    val binFactor: Int,
    val nativeWidth: Int,
    val nativeHeight: Int,
) {
    /** Binned output width assuming square binning groups. */
    val binnedWidth: Int = nativeWidth / (kotlin.math.sqrt(binFactor.toDouble()).toInt().coerceAtLeast(1))
    /** Binned output height assuming square binning groups. */
    val binnedHeight: Int = nativeHeight / (kotlin.math.sqrt(binFactor.toDouble()).toInt().coerceAtLeast(1))
    /** Effective pixel pitch after binning in micrometres, given original [pixelPitchUm]. */
    fun effectivePixelPitchUm(pixelPitchUm: Float): Float =
        pixelPitchUm * kotlin.math.sqrt(binFactor.toDouble()).toFloat()
}

/**
 * ISO-calibrated noise model priors.
 *
 * Noise model: σ²(x) = A · x + B (Poisson-Gaussian)
 *
 * @param shotCoeffRange  Expected range [min, max] for shot noise coefficient A
 * @param readNoiseRange  Expected range [min, max] for read noise floor B
 */
data class NoiseProfilePrior(
    val shotCoeffRange: ClosedFloatingPointRange<Float>,
    val readNoiseRange: ClosedFloatingPointRange<Float>,
)

/**
 * Per-sensor colour correction hints for the colour science pipeline.
 *
 * @param warmBiasRedAttenuation  Red channel attenuation for warm-zone tungsten
 *                                 correction (S5KHM6: 0.96–0.98). 1.0 = no correction.
 * @param blueSkyBoostFactor       Blue CCM boost for high-CCT sky zones
 *                                 (OV64B40: 1.02–1.05). 1.0 = no boost.
 * @param cctInterpolationInMireds Whether CCT interpolation should use mireds (1/T)
 *                                 instead of linear Kelvin space.
 * @param textureSatBoost          Saturation micro-boost for textured surfaces in
 *                                 ColorLM2Engine. Only applies to macro camera.
 */
data class ColourProfile(
    val warmBiasRedAttenuation: Float = 1.0f,
    val blueSkyBoostFactor: Float = 1.0f,
    val cctInterpolationInMireds: Boolean = false,
    val textureSatBoost: Float = 1.0f,
)

/**
 * Fixed-pattern noise correction configuration.
 *
 * @param grGbCorrectionThresholdDn  Gr/Gb delta threshold in DN. 0 = never correct.
 * @param rowFpnIsoThreshold         ISO above which row-based FPN correction activates.
 *                                    Int.MAX_VALUE = disabled.
 * @param columnFpnEnabled           Whether column-based FPN correction is also needed
 *                                    (ultra-wide OV08D10 only).
 */
data class FpnCorrectionConfig(
    val grGbCorrectionThresholdDn: Float = 0f,
    val rowFpnIsoThreshold: Int = Int.MAX_VALUE,
    val columnFpnEnabled: Boolean = false,
)

/**
 * FusionLM burst depth recommendations per ISO bracket.
 *
 * @param lowIsoMax        Upper bound of "low ISO" bracket
 * @param lowIsoBurstDepth Recommended burst depth at low ISO
 * @param midIsoMax        Upper bound of "mid ISO" bracket
 * @param midIsoBurstDepth Recommended burst depth at mid ISO
 * @param highIsoBurstDepth Recommended burst depth above midIsoMax
 * @param spatialWienerSecondPass Whether to enable spatial Wiener second pass at high ISO
 * @param spatialGamma     Spatial gamma for second-pass Wiener (only if second pass enabled)
 * @param macroMotionLimit Motion magnitude above which burst depth is clamped to 1
 */
data class BurstDepthConfig(
    val lowIsoMax: Int = 400,
    val lowIsoBurstDepth: IntRange = 3..5,
    val midIsoMax: Int = 1600,
    val midIsoBurstDepth: IntRange = 5..7,
    val highIsoBurstDepth: IntRange = 9..9,
    val spatialWienerSecondPass: Boolean = false,
    val spatialGamma: Float = 0f,
    val macroMotionLimit: Float = Float.MAX_VALUE,
) {
    /**
     * Returns recommended burst depth for the given ISO.
     *
     * @param iso Current ISO value
     * @return Recommended number of frames to capture
     */
    fun recommendedBurstDepth(iso: Int): Int = when {
        iso <= lowIsoMax -> lowIsoBurstDepth.last
        iso <= midIsoMax -> midIsoBurstDepth.last
        else -> highIsoBurstDepth.last
    }
}

/**
 * Alignment constraints for this sensor.
 *
 * @param integerPixelOnlyInBinnedMode Whether sub-pixel alignment is forbidden
 *                                      when the sensor delivers binned output.
 * @param maxAlignmentFrames Maximum frames to align (ultra-wide: limit to 3–5)
 * @param useGyroForMotionPenalty Whether gyroscope data is available for
 *                                 motion-penalty scoring. Front cameras lack OIS.
 */
data class AlignmentConfig(
    val integerPixelOnlyInBinnedMode: Boolean = false,
    val maxAlignmentFrames: Int = 9,
    val useGyroForMotionPenalty: Boolean = true,
)

/**
 * Sensor-specific special modes.
 *
 * @param hasSmartIsoPro         S5KHM6 Smart-ISO Pro detection needed
 * @param hasStaggeredHdr        Hardware staggered HDR support
 * @param hasAlwaysOnAls         OV50D40 always-on ambient light sensor
 * @param hasNonacellBinning     S5KHM6 Nonacell Plus 9-in-1 binning
 * @param isDepthSensorOnly      SC202CS: never feed to imaging pipeline
 * @param isMacroFixedFocus      SC202PCS: fixed-focus macro, disable AF/depth/bokeh
 * @param hasHardwarePdaf        Whether PDAF is available (GC16B3: no)
 * @param isFrontCamera          Front-facing camera
 */
data class SpecialModes(
    val hasSmartIsoPro: Boolean = false,
    val hasStaggeredHdr: Boolean = false,
    val hasAlwaysOnAls: Boolean = false,
    val hasNonacellBinning: Boolean = false,
    val isDepthSensorOnly: Boolean = false,
    val isMacroFixedFocus: Boolean = false,
    val hasHardwarePdaf: Boolean = true,
    val isFrontCamera: Boolean = false,
)

/**
 * Per-sensor white balance configuration overrides.
 *
 * @param skinAnchorConfidenceWeight  Weight of skin anchor in WB energy function.
 *                                     Front cameras: 0.40, rear: 0.25.
 * @param mixedLightCctSpreadThreshold CCT spread threshold for mixed-light detection.
 *                                     Front cameras: 1000K, rear: 1500K.
 * @param wbMethodWeights             Custom WB method weights for MultiModalIlluminantFusion.
 *                                     null = use default weights.
 */
data class WbSensorConfig(
    val skinAnchorConfidenceWeight: Float = 0.25f,
    val mixedLightCctSpreadThreshold: Float = 1500f,
    val wbMethodWeights: WbMethodWeights? = null,
)

/**
 * Custom method weights for multi-modal WB fusion.
 * Methods: A = sensor AWB, B = CNN illuminant, C = Finlayson gamut, D = chromaticity.
 */
data class WbMethodWeights(
    val sensorAwb: Float = 0.20f,
    val cnnIlluminant: Float = 0.30f,
    val finlaysonGamut: Float = 0.25f,
    val chromaticity: Float = 0.25f,
)

/**
 * Lens distortion model configuration.
 *
 * @param modelOrder  Number of distortion coefficients required.
 *                    Main cameras: 5 (k1,k2,k3,p1,p2).
 *                    Ultra-wide: 6+ (k1,k2,k3,k4,p1,p2).
 * @param caKernelRadiusMultiplier  Chromatic aberration correction kernel radius
 *                                   multiplier relative to main camera default.
 * @param vignetteOrder  Vignette compensation polynomial order.
 *                       Main cameras: 2. Ultra-wide: 4.
 */
data class DistortionConfig(
    val modelOrder: Int = 5,
    val caKernelRadiusMultiplier: Float = 1.0f,
    val vignetteOrder: Int = 2,
)

/**
 * Per-sensor sharpening configuration for ToneLM2Engine.
 *
 * @param sharpenAmount  Unsharp mask amount. Macro: 0.3f. Default: 0.5f.
 */
data class SharpenConfig(
    val sharpenAmount: Float = 0.5f,
)
