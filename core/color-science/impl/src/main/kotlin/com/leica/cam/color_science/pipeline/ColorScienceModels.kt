package com.leica.cam.color_science.pipeline

import kotlin.math.PI

/** Supported in-app color profiles for phase 3 color science. */
enum class ColorProfile {
    LEICA_M_CLASSIC,
    HASSELBLAD_NATURAL,
    PORTRA_FILM,
    VELVIA_FILM,
    HP5_BW,
}

/** Transfer function for display-referred output. */
enum class OutputTransferFunction {
    SRGB,
    HLG,
}

/** Preferred compute backend for color transforms. */
enum class ComputeBackend {
    VULKAN,
    RENDERSCRIPT,
    CPU,
}

/**
 * Linear-light RGB frame in normalized range [0, 1].
 *
 * The frame is immutable by convention; processing stages return new frames.
 */
data class ColorFrame(
    val width: Int,
    val height: Int,
    val red: FloatArray,
    val green: FloatArray,
    val blue: FloatArray,
) {
    init {
        val expected = width * height
        require(width > 0 && height > 0) { "Frame dimensions must be positive" }
        require(red.size == expected) { "Red channel size mismatch" }
        require(green.size == expected) { "Green channel size mismatch" }
        require(blue.size == expected) { "Blue channel size mismatch" }
    }

    fun size(): Int = width * height

    fun copyChannels(): ColorFrame = ColorFrame(width, height, red.copyOf(), green.copyOf(), blue.copyOf())
}

/** User-facing per-hue controls (8 base hue groups). */
data class HueBandAdjustment(
    val hueShiftDegrees: Float = 0f,
    val saturationScale: Float = 0f,
    val luminanceScale: Float = 0f,
)

/** Complete 8-band HSL control set. */
data class PerHueAdjustmentSet(
    val red: HueBandAdjustment = HueBandAdjustment(),
    val orange: HueBandAdjustment = HueBandAdjustment(),
    val yellow: HueBandAdjustment = HueBandAdjustment(),
    val green: HueBandAdjustment = HueBandAdjustment(),
    val aqua: HueBandAdjustment = HueBandAdjustment(),
    val blue: HueBandAdjustment = HueBandAdjustment(),
    val purple: HueBandAdjustment = HueBandAdjustment(),
    val magenta: HueBandAdjustment = HueBandAdjustment(),
)

/** Grain configuration derived from film stock targets. */
data class FilmGrainSettings(
    val amount: Float,
    val grainSizePx: Float,
    val colorVariation: Float,
)

/** Calibration patch pair for color accuracy benchmark. */
data class ColorPatch(
    val name: String,
    val referenceXyz: FloatArray,
    val measuredRgb: FloatArray,
)

/** Result of color-accuracy verification. */
data class ColorAccuracyReport(
    val meanDeltaE00: Float,
    val maxDeltaE00: Float,
    val percentile90DeltaE00: Float,
    val passed: Boolean,
)

/** Parameters that characterize each profile's rendering look. */
data class ProfileLook(
    val shadowLift: Float,
    val shoulderStrength: Float,
    val globalSaturationScale: Float,
    val greenDesaturation: Float,
    val redContrastBoost: Float,
    val warmShiftKelvinEquivalent: Float,
    val defaultGrain: FilmGrainSettings,
)

/**
 * Skin-tone Munsell anchor approximation in CIELAB.
 *
 * L* in [0, 100], a* and b* in conventional CIELAB domain.
 */
data class SkinAnchor(val l: Float, val a: Float, val b: Float)

internal object HueBands {
    val centers: FloatArray = floatArrayOf(0f, 30f, 60f, 120f, 180f, 240f, 280f, 320f)
    val sigmaDegrees: Float = 30f
    fun circularDistanceDegrees(a: Float, b: Float): Float {
        val diff = kotlin.math.abs(a - b)
        return kotlin.math.min(diff, 360f - diff)
    }
    fun toRadians(degrees: Float): Double = degrees / 180.0 * PI
}
