package com.leica.cam.lens_model.calibration

/**
 * Per-lens-variant calibration data for the lens correction pipeline.
 *
 * Different lens manufacturers (AAC, Sunny, OFILM, SEMCO) ship modules with
 * the same sensor die but different optical elements, resulting in distinct
 * distortion, chromatic aberration, vignetting, and sharpness profiles.
 *
 * Each entry is keyed by (sensorId, lensVariant) — e.g., ("s5khm6", "aac").
 *
 * The `_cn` suffix indicates China-market variants of the same module —
 * they use identical hardware but may have different HAL tuning for the
 * Chinese market. For RAW-domain processing, `_cn` variants are treated
 * as functionally identical to their non-`_cn` counterparts.
 */
data class LensCalibrationData(
    val sensorId: String,
    val lensVariant: String,
    val distortionCoefficients: ExtendedDistortionCoefficients,
    val chromaticAberration: LensChromaticAberrationProfile,
    val vignetteProfile: VignetteProfile,
    val sharpnessFalloff: SharpnessFalloffProfile,
)

/**
 * Extended Brown-Conrady distortion model supporting up to 6 radial coefficients.
 *
 * Ultra-wide lenses (OV08D10) require at least 6 coefficients (k1..k4 + p1,p2)
 * for acceptable corner correction. A 2-coefficient model leaves visible
 * barrel distortion at frame corners.
 *
 * Standard model:
 *   x' = x · (1 + k1·r² + k2·r⁴ + k3·r⁶ + k4·r⁸) + 2·p1·x·y + p2·(r² + 2x²)
 *   y' = y · (1 + k1·r² + k2·r⁴ + k3·r⁶ + k4·r⁸) + p1·(r² + 2y²) + 2·p2·x·y
 */
data class ExtendedDistortionCoefficients(
    val k1: Float = 0f,
    val k2: Float = 0f,
    val k3: Float = 0f,
    val k4: Float = 0f,
    val p1: Float = 0f,
    val p2: Float = 0f,
) {
    /** Number of active radial coefficients (non-zero k values). */
    val activeRadialOrder: Int
        get() = listOf(k1, k2, k3, k4).indexOfLast { it != 0f } + 1
}

/**
 * Per-lens chromatic aberration profile.
 *
 * @param redRadialShift   Normalised radial shift for red channel
 * @param blueRadialShift  Normalised radial shift for blue channel
 * @param kernelRadiusMultiplier  Correction kernel radius relative to default.
 *                                 OFILM lenses: 1.1x. Ultra-wide: 1.2x.
 */
data class LensChromaticAberrationProfile(
    val redRadialShift: Float = 0f,
    val blueRadialShift: Float = 0f,
    val kernelRadiusMultiplier: Float = 1.0f,
)

/**
 * Vignette (lens shading) compensation profile.
 *
 * @param polynomialOrder  Order of the radial polynomial fit.
 *                          Main cameras: 2. Ultra-wide: 4.
 * @param coefficients     Radial polynomial coefficients [c0, c1, ..., cn]
 *                          where gain(r) = c0 + c1·r² + c2·r⁴ + ...
 */
data class VignetteProfile(
    val polynomialOrder: Int = 2,
    val coefficients: FloatArray = floatArrayOf(1.0f, 0f, 0f),
)

/**
 * Corner sharpness falloff characterisation.
 *
 * @param cornerMtf50Ratio  MTF50 at corner relative to centre (0.0–1.0).
 *                           Lower values → more falloff → stronger correction needed.
 */
data class SharpnessFalloffProfile(
    val cornerMtf50Ratio: Float = 0.85f,
)

/**
 * Registry of lens calibration data for all (sensorId, lensVariant) combinations.
 *
 * Populated with factory-calibrated values. In production, these would be loaded
 * from a calibration database; this class provides compile-time defaults.
 */
class LensCalibrationRegistry {

    private val calibrations: Map<String, LensCalibrationData> = buildMap {
        // ── S5KHM6 variants ──
        register("s5khm6", "aac", mainCameraDefaults("s5khm6", "aac"))
        register("s5khm6", "semco", mainCameraDefaults("s5khm6", "semco"))

        // ── OV64B40 variants ──
        register("ov64b40", "ofilm", mainCameraDefaults("ov64b40", "ofilm").copy(
            chromaticAberration = LensChromaticAberrationProfile(
                redRadialShift = 0.0008f,
                blueRadialShift = -0.0012f,
                kernelRadiusMultiplier = 1.1f, // OFILM: 10% more lateral CA
            ),
        ))

        // ── OV50D40 variants ──
        register("ov50d40", "sunny", mainCameraDefaults("ov50d40", "sunny"))

        // ── OV16A1Q front variants ──
        register("ov16a1q", "aac", frontCameraDefaults("ov16a1q", "aac"))
        register("ov16a1q", "sunny", frontCameraDefaults("ov16a1q", "sunny"))

        // ── GC16B3 front variants ──
        register("gc16b3", "aac", frontCameraDefaults("gc16b3", "aac"))
        register("gc16b3", "sunny", frontCameraDefaults("gc16b3", "sunny"))

        // ── OV08D10 ultra-wide variants ──
        register("ov08d10", "aac", ultraWideDefaults("ov08d10", "aac"))
        register("ov08d10", "sunny", ultraWideDefaults("ov08d10", "sunny"))

        // ── SC202CS depth variants ──
        register("sc202cs", "aac", depthMacroDefaults("sc202cs", "aac"))
        register("sc202cs", "sunny", depthMacroDefaults("sc202cs", "sunny"))
        register("sc202cs", "sunny2", depthMacroDefaults("sc202cs", "sunny2"))

        // ── SC202PCS macro variants ──
        register("sc202pcs", "aac", depthMacroDefaults("sc202pcs", "aac"))
        register("sc202pcs", "sunny", depthMacroDefaults("sc202pcs", "sunny"))
    }

    private fun MutableMap<String, LensCalibrationData>.register(
        sensorId: String,
        variant: String,
        data: LensCalibrationData,
    ) {
        put(key(sensorId, variant), data)
    }

    /**
     * Look up calibration data for a specific sensor + lens variant.
     *
     * @param sensorId    Canonical sensor ID (e.g., "s5khm6")
     * @param lensVariant Lens manufacturer variant (e.g., "aac", "sunny")
     * @return Calibration data, or null if no calibration exists for this combination
     */
    fun lookup(sensorId: String, lensVariant: String): LensCalibrationData? =
        calibrations[key(sensorId, lensVariant)]

    /**
     * Look up calibration, treating `_cn` variants as identical to base.
     */
    fun lookupWithCnFallback(sensorId: String, lensVariant: String): LensCalibrationData? {
        val baseVariant = lensVariant.removeSuffix("_cn").removeSuffix("cn")
        return lookup(sensorId, lensVariant) ?: lookup(sensorId, baseVariant)
    }

    /** All registered calibration entries. */
    fun allCalibrations(): Collection<LensCalibrationData> = calibrations.values

    private fun key(sensorId: String, variant: String) = "${sensorId}_${variant}".lowercase()

    // ─────────────────────────────────────────────────────────────────────
    // Default profile builders
    // ─────────────────────────────────────────────────────────────────────

    private fun mainCameraDefaults(sensorId: String, variant: String) = LensCalibrationData(
        sensorId = sensorId,
        lensVariant = variant,
        distortionCoefficients = ExtendedDistortionCoefficients(
            k1 = -0.02f, k2 = 0.005f, k3 = -0.001f,
            p1 = 0.0001f, p2 = -0.0001f,
        ),
        chromaticAberration = LensChromaticAberrationProfile(
            redRadialShift = 0.0005f, blueRadialShift = -0.0008f,
            kernelRadiusMultiplier = 1.0f,
        ),
        vignetteProfile = VignetteProfile(polynomialOrder = 2, coefficients = floatArrayOf(1.0f, 0.15f, 0.05f)),
        sharpnessFalloff = SharpnessFalloffProfile(cornerMtf50Ratio = 0.85f),
    )

    private fun frontCameraDefaults(sensorId: String, variant: String) = LensCalibrationData(
        sensorId = sensorId,
        lensVariant = variant,
        distortionCoefficients = ExtendedDistortionCoefficients(
            k1 = -0.03f, k2 = 0.008f, k3 = -0.002f,
            p1 = 0.0002f, p2 = -0.0001f,
        ),
        chromaticAberration = LensChromaticAberrationProfile(
            redRadialShift = 0.0006f, blueRadialShift = -0.0009f,
        ),
        vignetteProfile = VignetteProfile(polynomialOrder = 2, coefficients = floatArrayOf(1.0f, 0.20f, 0.08f)),
        sharpnessFalloff = SharpnessFalloffProfile(cornerMtf50Ratio = 0.80f),
    )

    private fun ultraWideDefaults(sensorId: String, variant: String) = LensCalibrationData(
        sensorId = sensorId,
        lensVariant = variant,
        distortionCoefficients = ExtendedDistortionCoefficients(
            // 6-coefficient Brown-Conrady for severe barrel distortion
            k1 = -0.15f, k2 = 0.04f, k3 = -0.008f, k4 = 0.001f,
            p1 = 0.0005f, p2 = -0.0003f,
        ),
        chromaticAberration = LensChromaticAberrationProfile(
            redRadialShift = 0.0012f, blueRadialShift = -0.0018f,
            kernelRadiusMultiplier = 1.2f, // 20% larger CA correction kernel
        ),
        vignetteProfile = VignetteProfile(
            polynomialOrder = 4, // 4th-order for f/2.2 ultra-wide
            coefficients = floatArrayOf(1.0f, 0.35f, 0.15f, 0.05f, 0.02f),
        ),
        sharpnessFalloff = SharpnessFalloffProfile(cornerMtf50Ratio = 0.65f),
    )

    private fun depthMacroDefaults(sensorId: String, variant: String) = LensCalibrationData(
        sensorId = sensorId,
        lensVariant = variant,
        distortionCoefficients = ExtendedDistortionCoefficients(
            k1 = -0.05f, k2 = 0.01f, k3 = -0.002f,
            p1 = 0.0002f, p2 = -0.0001f,
        ),
        chromaticAberration = LensChromaticAberrationProfile(),
        vignetteProfile = VignetteProfile(polynomialOrder = 2, coefficients = floatArrayOf(1.0f, 0.10f, 0.03f)),
        sharpnessFalloff = SharpnessFalloffProfile(cornerMtf50Ratio = 0.90f),
    )
}
