package com.leica.cam.sensor_hal.sensor.profiles

/**
 * Runtime registry that maps detected sensor identifiers to their tuning profiles.
 *
 * Detection uses the HAL sensor identifier string from CameraCharacteristics
 * (e.g., "s5khm6_aac_main_mipi_raw") and falls back to pixel array dimensions
 * when the identifier is unavailable.
 *
 * Thread-safe: the catalogue is built once at construction and never mutated.
 */
class SensorProfileRegistry {

    private val profiles: Map<String, SensorProfile> = buildMap {
        put("s5khm6", buildS5KHM6())
        put("ov64b40", buildOV64B40())
        put("ov50d40", buildOV50D40())
        put("ov16a1q", buildOV16A1Q())
        put("gc16b3", buildGC16B3())
        put("ov08d10", buildOV08D10())
        put("sc202cs", buildSC202CS())
        put("sc202pcs", buildSC202PCS())
    }

    /**
     * Resolve sensor profile from the HAL sensor identifier string.
     *
     * The HAL string typically contains the sensor model as a prefix
     * (e.g., "s5khm6_aac_main_mipi_raw", "ov64b40_ofilm_main2_mipi_raw").
     * We match against known sensor IDs case-insensitively.
     *
     * @param halSensorString Full HAL sensor identifier or INFO_VERSION string
     * @return Matching [SensorProfile], or null if unrecognised
     */
    fun resolve(halSensorString: String): SensorProfile? {
        val lower = halSensorString.lowercase()
        return profiles.entries.firstOrNull { (key, _) -> lower.contains(key) }?.value
    }

    /**
     * Resolve sensor profile by pixel array dimensions as a fallback.
     *
     * @param width  Pixel array width from CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE
     * @param height Pixel array height
     * @return Best-matching [SensorProfile], or null if no match
     */
    fun resolveByDimensions(width: Int, height: Int): SensorProfile? = when {
        width == 12000 && height == 9000 -> profiles["s5khm6"]
        width == 9248 && height == 6944 -> profiles["ov64b40"]
        width == 8192 && height == 6144 -> profiles["ov50d40"]
        width >= 4608 && height >= 3456 && width <= 4672 -> profiles["ov16a1q"] // ~16MP front
        width == 3264 && height == 2448 -> profiles["ov08d10"] // 8MP UW
        width in 1600..1920 && height in 1200..1080 -> {
            // 2MP sensors — disambiguate by role if possible
            null // Caller should use halSensorString for 2MP sensors
        }
        else -> null
    }

    /**
     * Returns all registered profiles. Used for diagnostic logging at startup.
     */
    fun allProfiles(): Collection<SensorProfile> = profiles.values

    /**
     * Extract the lens variant suffix from a HAL sensor string.
     *
     * Examples:
     * - "s5khm6_aac_main_mipi_raw" → "aac"
     * - "ov64b40_ofilm_main2_mipi_raw" → "ofilm"
     * - "sc202cs_sunny2_depth_mipi_raw" → "sunny2"
     *
     * The `_cn` suffix indicates China-market variants and is stripped for
     * RAW-domain processing (identical sensor physics).
     *
     * @return Lens variant identifier, or "unknown" if not parseable
     */
    fun extractLensVariant(halSensorString: String): String {
        val lower = halSensorString.lowercase()
        val knownVariants = listOf("aac", "sunny2", "sunny", "ofilm", "semco")
        return knownVariants.firstOrNull { lower.contains("_${it}_") || lower.contains("_${it}") }
            ?: "unknown"
    }

    /**
     * Whether the `_cn` suffix is present (China market variant).
     * Functionally identical to non-`_cn` for RAW-domain processing.
     */
    fun isChinaMarketVariant(halSensorString: String): Boolean =
        halSensorString.lowercase().contains("_cn")

    // ─────────────────────────────────────────────────────────────────────
    // Per-sensor profile builders
    // ─────────────────────────────────────────────────────────────────────

    /** Camera 1 — Samsung S5KHM6 (108MP, Main Primary) */
    private fun buildS5KHM6() = SensorProfile(
        sensorId = "s5khm6",
        manufacturer = SensorManufacturer.SAMSUNG_ISOCELL,
        resolution = SensorResolution(
            nativeMp = 108, binnedMp = 12, binFactor = 9,
            nativeWidth = 12000, nativeHeight = 9000,
        ),
        pixelPitchUm = 0.64f,
        sensorFormatInch = "1/1.67",
        cameraRole = CameraRole.MAIN_PRIMARY,
        noiseProfile = NoiseProfilePrior(
            shotCoeffRange = 3e-6f..1.5e-5f,
            readNoiseRange = 8e-9f..6e-8f,
        ),
        colourProfile = ColourProfile(
            // Slight warm colour response bias at CCT < 3000K — attenuate red
            // in tungsten zones per Dimension 5 spec. // HM6 ISOCELL 2.0 sensor characterisation
            warmBiasRedAttenuation = 0.97f,
            blueSkyBoostFactor = 1.0f,
            // CCT interpolation in mireds — HM6's warm bias is non-linear in Kelvin
            cctInterpolationInMireds = true,
        ),
        fpnCorrection = FpnCorrectionConfig(
            // ISOCELL 2.0: Gr/Gb split typically < 1 DN — no correction needed
            grGbCorrectionThresholdDn = 0f,
            rowFpnIsoThreshold = Int.MAX_VALUE, // Not needed for ISOCELL
        ),
        burstConfig = BurstDepthConfig(
            lowIsoMax = 400, lowIsoBurstDepth = 3..5,
            midIsoMax = 1600, midIsoBurstDepth = 5..7,
            highIsoBurstDepth = 9..9,
        ),
        alignmentConfig = AlignmentConfig(
            // In Nonacell 12MP binned mode: integer-pixel alignment only
            integerPixelOnlyInBinnedMode = true,
            maxAlignmentFrames = 9,
            useGyroForMotionPenalty = true,
        ),
        specialModes = SpecialModes(
            hasSmartIsoPro = true,
            hasStaggeredHdr = true,
            hasNonacellBinning = true,
        ),
        wbConfig = WbSensorConfig(
            skinAnchorConfidenceWeight = 0.25f,
            mixedLightCctSpreadThreshold = 1500f,
        ),
        distortionConfig = DistortionConfig(modelOrder = 5, caKernelRadiusMultiplier = 1.0f, vignetteOrder = 2),
        sharpenConfig = SharpenConfig(sharpenAmount = 0.5f),
    )

    /** Camera 2 — OmniVision OV64B40 (64MP, Main Secondary) */
    private fun buildOV64B40() = SensorProfile(
        sensorId = "ov64b40",
        manufacturer = SensorManufacturer.OMNIVISION,
        resolution = SensorResolution(
            nativeMp = 64, binnedMp = 16, binFactor = 4,
            nativeWidth = 9248, nativeHeight = 6944,
        ),
        pixelPitchUm = 0.7f,
        sensorFormatInch = "1/2.0",
        cameraRole = CameraRole.MAIN_SECONDARY,
        noiseProfile = NoiseProfilePrior(
            shotCoeffRange = 3e-6f..1.5e-5f,
            readNoiseRange = 3e-8f..2e-7f, // Higher read noise vs ISOCELL
        ),
        colourProfile = ColourProfile(
            warmBiasRedAttenuation = 1.0f,
            // Blue underresponse at high CCT (> 6000K) — boost sky zone blue
            blueSkyBoostFactor = 1.03f,
        ),
        fpnCorrection = FpnCorrectionConfig(
            grGbCorrectionThresholdDn = 2.0f, // OV: correct if |Gr-Gb| > 2 DN
            rowFpnIsoThreshold = 800,          // Row FPN at ISO > 800
        ),
        burstConfig = BurstDepthConfig(
            lowIsoMax = 400, lowIsoBurstDepth = 5..5,
            midIsoMax = 1600, midIsoBurstDepth = 7..7,
            highIsoBurstDepth = 9..9, // Higher read noise → deeper stacking
        ),
        alignmentConfig = AlignmentConfig(
            integerPixelOnlyInBinnedMode = true,
            maxAlignmentFrames = 9,
            useGyroForMotionPenalty = true,
        ),
        specialModes = SpecialModes(hasStaggeredHdr = true),
        wbConfig = WbSensorConfig(skinAnchorConfidenceWeight = 0.25f, mixedLightCctSpreadThreshold = 1500f),
        distortionConfig = DistortionConfig(
            modelOrder = 5,
            caKernelRadiusMultiplier = 1.1f, // OFILM lenses: 10% more lateral CA
            vignetteOrder = 2,
        ),
        sharpenConfig = SharpenConfig(sharpenAmount = 0.5f),
    )

    /** Camera 3 — OmniVision OV50D40 (50MP, Main Tertiary) */
    private fun buildOV50D40() = SensorProfile(
        sensorId = "ov50d40",
        manufacturer = SensorManufacturer.OMNIVISION,
        resolution = SensorResolution(
            nativeMp = 50, binnedMp = 12, binFactor = 4,
            nativeWidth = 8192, nativeHeight = 6144,
        ),
        pixelPitchUm = 0.612f, // Smallest pixels on this device
        sensorFormatInch = "1/2.88",
        cameraRole = CameraRole.MAIN_TERTIARY,
        noiseProfile = NoiseProfilePrior(
            shotCoeffRange = 3e-6f..1.5e-5f,
            readNoiseRange = 4e-8f..2.5e-7f, // Slightly higher than OV64B40
        ),
        colourProfile = ColourProfile(
            warmBiasRedAttenuation = 1.0f,
            blueSkyBoostFactor = 1.03f,
        ),
        fpnCorrection = FpnCorrectionConfig(
            grGbCorrectionThresholdDn = 2.0f, // Same PureCel Plus-S as OV64B40
            rowFpnIsoThreshold = 800,
        ),
        burstConfig = BurstDepthConfig(
            lowIsoMax = 400, lowIsoBurstDepth = 5..7, // Small pixels need more
            midIsoMax = 1600, midIsoBurstDepth = 9..9,
            highIsoBurstDepth = 9..9,
            spatialWienerSecondPass = true,
            spatialGamma = 0.3f,
        ),
        alignmentConfig = AlignmentConfig(
            integerPixelOnlyInBinnedMode = true,
            maxAlignmentFrames = 9,
            useGyroForMotionPenalty = true,
        ),
        specialModes = SpecialModes(hasAlwaysOnAls = true, hasStaggeredHdr = true),
        wbConfig = WbSensorConfig(skinAnchorConfidenceWeight = 0.25f, mixedLightCctSpreadThreshold = 1500f),
        distortionConfig = DistortionConfig(modelOrder = 5, caKernelRadiusMultiplier = 1.0f, vignetteOrder = 2),
        sharpenConfig = SharpenConfig(sharpenAmount = 0.5f),
    )

    /** Camera 4 — OmniVision OV16A1Q (16MP, Front Primary) */
    private fun buildOV16A1Q() = SensorProfile(
        sensorId = "ov16a1q",
        manufacturer = SensorManufacturer.OMNIVISION,
        resolution = SensorResolution(
            nativeMp = 16, binnedMp = 16, binFactor = 1,
            nativeWidth = 4672, nativeHeight = 3504,
        ),
        pixelPitchUm = 0.7f,
        sensorFormatInch = "1/3.06",
        cameraRole = CameraRole.FRONT_PRIMARY,
        noiseProfile = NoiseProfilePrior(
            shotCoeffRange = 3e-6f..1.5e-5f,
            readNoiseRange = 3e-8f..2e-7f,
        ),
        colourProfile = ColourProfile(),
        fpnCorrection = FpnCorrectionConfig(grGbCorrectionThresholdDn = 2.0f, rowFpnIsoThreshold = 800),
        burstConfig = BurstDepthConfig(
            lowIsoMax = 400, lowIsoBurstDepth = 3..5,
            midIsoMax = 1600, midIsoBurstDepth = 5..7,
            highIsoBurstDepth = 7..9,
        ),
        alignmentConfig = AlignmentConfig(
            integerPixelOnlyInBinnedMode = false,
            maxAlignmentFrames = 7,
            // Front camera: no OIS, no gyro for motion penalty
            useGyroForMotionPenalty = false,
        ),
        specialModes = SpecialModes(isFrontCamera = true),
        wbConfig = WbSensorConfig(
            skinAnchorConfidenceWeight = 0.40f, // Front: skin priority highest
            mixedLightCctSpreadThreshold = 1000f, // Display reflections → aggressive mixed-light
        ),
        distortionConfig = DistortionConfig(modelOrder = 5, caKernelRadiusMultiplier = 1.0f, vignetteOrder = 2),
        sharpenConfig = SharpenConfig(sharpenAmount = 0.5f),
    )

    /** Camera 5 — GalaxyCore GC16B3 (16MP, Front Secondary) */
    private fun buildGC16B3() = SensorProfile(
        sensorId = "gc16b3",
        manufacturer = SensorManufacturer.GALAXYCORE,
        resolution = SensorResolution(
            nativeMp = 16, binnedMp = 16, binFactor = 1,
            nativeWidth = 4672, nativeHeight = 3504,
        ),
        pixelPitchUm = 0.7f,
        sensorFormatInch = "1/3.10",
        cameraRole = CameraRole.FRONT_SECONDARY,
        noiseProfile = NoiseProfilePrior(
            shotCoeffRange = 3e-6f..1.5e-5f,
            readNoiseRange = 3e-8f..2.5e-7f, // Budget OV-level read noise
        ),
        colourProfile = ColourProfile(),
        fpnCorrection = FpnCorrectionConfig(
            grGbCorrectionThresholdDn = 2.0f,
            rowFpnIsoThreshold = 400, // Lower threshold: higher FPN than OV
        ),
        burstConfig = BurstDepthConfig(
            lowIsoMax = 400, lowIsoBurstDepth = 3..5,
            midIsoMax = 1600, midIsoBurstDepth = 5..7,
            highIsoBurstDepth = 7..9,
        ),
        alignmentConfig = AlignmentConfig(
            integerPixelOnlyInBinnedMode = false,
            maxAlignmentFrames = 7,
            useGyroForMotionPenalty = false, // Front camera
        ),
        specialModes = SpecialModes(
            isFrontCamera = true,
            hasHardwarePdaf = false, // GC16B3: contrast AF only
        ),
        wbConfig = WbSensorConfig(
            skinAnchorConfidenceWeight = 0.40f,
            mixedLightCctSpreadThreshold = 1000f,
            // GC16B3: green tint at indoor CCT → elevate Finlayson gamut method
            wbMethodWeights = WbMethodWeights(
                sensorAwb = 0.15f,
                cnnIlluminant = 0.35f,
                finlaysonGamut = 0.40f,
                chromaticity = 0.10f,
            ),
        ),
        distortionConfig = DistortionConfig(modelOrder = 5, caKernelRadiusMultiplier = 1.0f, vignetteOrder = 2),
        sharpenConfig = SharpenConfig(sharpenAmount = 0.5f),
    )

    /** Camera 6 — OmniVision OV08D10 (8MP, Ultra-wide) */
    private fun buildOV08D10() = SensorProfile(
        sensorId = "ov08d10",
        manufacturer = SensorManufacturer.OMNIVISION,
        resolution = SensorResolution(
            nativeMp = 8, binnedMp = 8, binFactor = 1,
            nativeWidth = 3264, nativeHeight = 2448,
        ),
        pixelPitchUm = 1.12f,
        sensorFormatInch = "1/4.0",
        cameraRole = CameraRole.ULTRA_WIDE,
        noiseProfile = NoiseProfilePrior(
            shotCoeffRange = 3e-6f..1.5e-5f,
            readNoiseRange = 3e-8f..2e-7f,
        ),
        colourProfile = ColourProfile(),
        fpnCorrection = FpnCorrectionConfig(
            grGbCorrectionThresholdDn = 2.0f,
            rowFpnIsoThreshold = 400,
            columnFpnEnabled = true, // OV08D10: pronounced column FPN
        ),
        burstConfig = BurstDepthConfig(
            lowIsoMax = 400, lowIsoBurstDepth = 3..5,
            midIsoMax = 1600, midIsoBurstDepth = 3..5,
            highIsoBurstDepth = 5..5, // UW rarely benefits from deep stacking
        ),
        alignmentConfig = AlignmentConfig(
            integerPixelOnlyInBinnedMode = false,
            maxAlignmentFrames = 5,
            useGyroForMotionPenalty = true,
        ),
        specialModes = SpecialModes(),
        wbConfig = WbSensorConfig(skinAnchorConfidenceWeight = 0.25f, mixedLightCctSpreadThreshold = 1500f),
        distortionConfig = DistortionConfig(
            modelOrder = 6,                    // 6+ Brown-Conrady for severe UW distortion
            caKernelRadiusMultiplier = 1.2f,   // 20% more CA on ultra-wide
            vignetteOrder = 4,                  // 4th-order for f/2.2 UW
        ),
        sharpenConfig = SharpenConfig(sharpenAmount = 0.5f),
    )

    /** Camera 7 — SmartSens SC202CS (2MP, Depth) */
    private fun buildSC202CS() = SensorProfile(
        sensorId = "sc202cs",
        manufacturer = SensorManufacturer.SMARTSENS,
        resolution = SensorResolution(
            nativeMp = 2, binnedMp = 2, binFactor = 1,
            nativeWidth = 1600, nativeHeight = 1200,
        ),
        pixelPitchUm = 1.75f,
        sensorFormatInch = "1/5.0",
        cameraRole = CameraRole.DEPTH,
        noiseProfile = NoiseProfilePrior(
            shotCoeffRange = 3e-6f..1.5e-5f,
            readNoiseRange = 3e-8f..2e-7f,
        ),
        colourProfile = ColourProfile(), // Not used — depth sensor only
        fpnCorrection = FpnCorrectionConfig(),
        burstConfig = BurstDepthConfig(
            lowIsoMax = 400, lowIsoBurstDepth = 1..1,
            midIsoMax = 1600, midIsoBurstDepth = 1..1,
            highIsoBurstDepth = 1..1,
        ),
        alignmentConfig = AlignmentConfig(maxAlignmentFrames = 1),
        specialModes = SpecialModes(
            isDepthSensorOnly = true, // NEVER feed to FusionLM/ColorLM/ToneLM
        ),
        wbConfig = WbSensorConfig(), // Not used
        distortionConfig = DistortionConfig(modelOrder = 5),
        sharpenConfig = SharpenConfig(sharpenAmount = 0f), // No sharpening on depth
    )

    /** Camera 8 — SmartSens SC202PCS (2MP, Macro) */
    private fun buildSC202PCS() = SensorProfile(
        sensorId = "sc202pcs",
        manufacturer = SensorManufacturer.SMARTSENS,
        resolution = SensorResolution(
            nativeMp = 2, binnedMp = 2, binFactor = 1,
            nativeWidth = 1600, nativeHeight = 1200,
        ),
        pixelPitchUm = 1.75f,
        sensorFormatInch = "1/5.0",
        cameraRole = CameraRole.MACRO,
        noiseProfile = NoiseProfilePrior(
            shotCoeffRange = 3e-6f..1.5e-5f,
            readNoiseRange = 3e-8f..2e-7f,
        ),
        colourProfile = ColourProfile(
            textureSatBoost = 1.12f, // Macro subjects: textured surfaces
        ),
        fpnCorrection = FpnCorrectionConfig(),
        burstConfig = BurstDepthConfig(
            lowIsoMax = 400, lowIsoBurstDepth = 3..3,
            midIsoMax = 1600, midIsoBurstDepth = 3..3,
            highIsoBurstDepth = 3..3,
            macroMotionLimit = 0.5f, // N=1 if motion > 0.5f at macro distances
        ),
        alignmentConfig = AlignmentConfig(maxAlignmentFrames = 3),
        specialModes = SpecialModes(
            isMacroFixedFocus = true, // Disable AF, depth, bokeh
            hasHardwarePdaf = false,
        ),
        wbConfig = WbSensorConfig(skinAnchorConfidenceWeight = 0.25f),
        distortionConfig = DistortionConfig(modelOrder = 5),
        sharpenConfig = SharpenConfig(
            sharpenAmount = 0.3f, // Conservative: diffraction-limited at macro
        ),
    )
}
