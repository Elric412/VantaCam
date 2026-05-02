package com.leica.cam.imaging_pipeline.hdr.proxdrv3

/**
 * Tunable knobs for the ProXDR v3 wrapper.
 *
 * Defaults mirror the upstream `ProXDRCfg` golden defaults documented in
 * `proxdr/docs/TUNING.md`. **Always start from defaults**; per the upstream
 * tuning workflow you should override only the 2–3 fields that matter for
 * the look you're after.
 *
 * #### Mapping to upstream cfg fields
 *
 * | Field here                  | Upstream `ProXDRCfg.*`              |
 * |-----------------------------|-------------------------------------|
 * | `shadowLiftAmount`          | `shadow.lift_amount` (0.18)         |
 * | `shadowThreshold`           | `shadow.lift_threshold` (0.15)      |
 * | `highlightClip`             | `highlight.clip_threshold` (0.95)   |
 * | `highlightSoftBand`         | `highlight.soft_band` (0.04)        |
 * | `highlightRolloffStart`     | `highlight.roll_off_start` (0.85)   |
 * | `highlightRecoveryStrength` | `highlight.recovery_strength` (1.0) |
 * | `ghostSigma`                | `fusion.ghost_sigma` (3.0)          |
 * | `refPickMotionWeight`       | `fusion.ref_pick_motion_w` (0.6)    |
 * | `protectFace`               | `highlight.protect_face` (true)     |
 * | `adaptive`                  | `adaptive_mode` (true)              |
 *
 * `shotCoeff` / `readNoiseSq` are the Poisson-Gaussian noise model
 * coefficients; in production these come from
 * `CameraCharacteristics.SENSOR_NOISE_PROFILE`. The defaults below match the
 * `PerChannelNoise.fromIsoEstimate(100)` fallback already used elsewhere in
 * the LeicaCam pipeline.
 */
data class ProXdrV3Tuning(
    /** Shadow log-lift coefficient α. Range 0.0 – 0.5. Default 0.18. */
    val shadowLiftAmount: Float = 0.18f,
    /** Threshold below which lift is applied. */
    val shadowThreshold: Float = 0.15f,

    /** Hard-clip threshold (highlight). */
    val highlightClip: Float = 0.95f,
    /** Soft-knee transition half-width (smooth approach to clip). */
    val highlightSoftBand: Float = 0.04f,
    /** Where the soft roll-off begins. */
    val highlightRolloffStart: Float = 0.85f,
    /** Spectral-ratio recovery strength (mix between original / reconstructed). */
    val highlightRecoveryStrength: Float = 1.0f,
    /** Master switch for the highlight-recovery stage. */
    val highlightRecoveryEnabled: Boolean = true,

    /** Wiener-merge ghost rejection σ (frames > σ from ref are dropped). */
    val ghostSigma: Float = 3.0f,
    /** Weighting of motion vs sharpness in reference frame pick. */
    val refPickMotionWeight: Float = 0.6f,

    /** Skip spectral ratio on face zones (face zone plumbing lives upstream). */
    val protectFace: Boolean = true,

    /** Enable per-shot adaptive scene-mode tuning. */
    val adaptive: Boolean = true,

    /** Poisson-Gaussian shot-noise coefficient A in σ²(x) = A·x + B. */
    val shotCoeff: Float = 2e-4f,
    /** Read-noise floor B in σ²(x) = A·x + B. */
    val readNoiseSq: Float = 4e-6f,
)

/** Scene-mode override (matches `ProXDR::SceneMode`). */
enum class ProXdrV3SceneMode {
    AUTO,
    BRIGHT_DAY,
    DAYLIGHT,
    INDOOR,
    LOW_LIGHT,
    NIGHT,
    NIGHT_EXTREME,
    GOLDEN_HOUR,
    BACKLIT,
    PORTRAIT,
    SPORTS,
    TRIPOD,
    MACRO,
}

/** Mirror of `android.os.PowerManager.THERMAL_STATUS_*` mapped onto v3 cfg. */
enum class ProXdrV3Thermal {
    NORMAL, LIGHT, MODERATE, SEVERE, CRITICAL,
}
