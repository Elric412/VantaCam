package com.leica.cam.imaging_pipeline.hdr

import com.leica.cam.common.ThermalState
import com.leica.cam.imaging_pipeline.api.UserHdrMode
import com.leica.cam.imaging_pipeline.pipeline.HdrMergeMode
import com.leica.cam.imaging_pipeline.pipeline.PipelineFrame

/**
 * Shared data types for the HDR sub-package.
 * Extracted from the monolithic `ProXdrHdrEngine.kt` per D2.1.
 */

/**
 * Lightweight scene descriptor computed from the ZSL preview frame.
 * Used by [BracketSelector] to select the optimal EV schedule.
 */
data class SceneDescriptor(
    /** 256-bin luminance histogram normalised to sum = 1.0. */
    val luminanceHistogram: FloatArray,
    /** True if a face was detected in the preview frame. */
    val facePresent: Boolean,
    /** Mean luminance of the largest detected face (0.0 if none). */
    val faceMeanLuma: Float,
    /** Android ThermalStatus ordinal (0=NONE .. 6=CRITICAL). */
    val thermalLevel: Int,
    /** AI-predicted scene class (used for category-specific bracket tweaks). */
    val sceneCategory: SceneCategory = SceneCategory.GENERAL,
) {
    companion object {
        fun uniformHistogram(): FloatArray = FloatArray(256) { 1f / 256f }
    }
}

enum class SceneCategory {
    GENERAL, PORTRAIT, LANDSCAPE, NIGHT, STAGE, SNOW, BACKLIT_PORTRAIT,
}

/**
 * Metadata describing the frame set available for HDR processing.
 * Used by [HdrModePicker] to select the algorithm path.
 */
data class HdrFrameSetMetadata(
    val evSpread: Float,
    val allFramesClipped: Boolean,
    val rawPathUnavailable: Boolean,
    val thermalSevere: Boolean,
)

/**
 * Selects the appropriate HDR algorithm path based on input metadata.
 */
object HdrModePicker {
    fun pick(metadata: HdrFrameSetMetadata): HdrMergeMode = when {
        metadata.thermalSevere -> HdrMergeMode.SINGLE_FRAME
        metadata.allFramesClipped || metadata.rawPathUnavailable -> HdrMergeMode.MERTENS_FUSION
        metadata.evSpread < 0.5f -> HdrMergeMode.WIENER_BURST
        else -> HdrMergeMode.DEBEVEC_LINEAR
    }

    fun pickWithUserOverride(
        metadata: HdrFrameSetMetadata,
        userMode: UserHdrMode,
    ): HdrMergeMode {
        if (metadata.thermalSevere) return HdrMergeMode.SINGLE_FRAME
        return when (userMode) {
            UserHdrMode.OFF -> HdrMergeMode.SINGLE_FRAME
            UserHdrMode.ON -> {
                if (metadata.rawPathUnavailable || metadata.allFramesClipped) {
                    HdrMergeMode.MERTENS_FUSION
                } else {
                    HdrMergeMode.WIENER_BURST
                }
            }
            UserHdrMode.SMART -> pick(metadata)
            UserHdrMode.PRO_XDR -> when {
                metadata.allFramesClipped || metadata.rawPathUnavailable -> HdrMergeMode.MERTENS_FUSION
                metadata.evSpread < 0.5f -> HdrMergeMode.WIENER_BURST
                else -> HdrMergeMode.DEBEVEC_LINEAR
            }
        }
    }
}

/**
 * Per-channel noise model extracted from `CameraCharacteristics.SENSOR_NOISE_PROFILE`.
 *
 * On standard RGGB sensors, index 0 = R, 1 = Gr, 2 = Gb, 3 = B.
 * For OV sensors where `grGbSplitCorrection = true`, Gr and Gb are kept separate.
 * Otherwise, Gr and Gb are averaged into a single green channel noise.
 *
 * D2.5: Replaces the buggy luminance-only sigma^2 that was used for all channels.
 */
data class PerChannelNoise(
    val red: ChannelNoise,
    val green: ChannelNoise,
    val blue: ChannelNoise,
) {
    companion object {
        /**
         * Build from SENSOR_NOISE_PROFILE metadata.
         *
         * @param noiseProfile Array of [S, O] pairs per CFA channel (S=shot, O=read).
         * @param grGbSplit If true, keep Gr/Gb separate; if false, average them.
         */
        fun fromSensorNoiseProfile(
            noiseProfile: FloatArray,
            grGbSplit: Boolean = false,
        ): PerChannelNoise {
            // Standard RGGB layout: [R_S, R_O, Gr_S, Gr_O, Gb_S, Gb_O, B_S, B_O]
            require(noiseProfile.size >= 8) { "SENSOR_NOISE_PROFILE needs >= 8 floats (4 channels x 2)" }
            val rShot = noiseProfile[0]; val rRead = noiseProfile[1]
            val grShot = noiseProfile[2]; val grRead = noiseProfile[3]
            val gbShot = noiseProfile[4]; val gbRead = noiseProfile[5]
            val bShot = noiseProfile[6]; val bRead = noiseProfile[7]

            val greenNoise = if (grGbSplit) {
                // OV sensors: Gr/Gb split is significant (1-2 DN difference per sensor-profiles.md).
                // Use Gr as representative (Gr has slightly better SNR due to denser sampling).
                ChannelNoise(grShot, grRead)
            } else {
                // Average Gr and Gb for sensors without significant split.
                ChannelNoise((grShot + gbShot) / 2f, (grRead + gbRead) / 2f)
            }
            return PerChannelNoise(
                red = ChannelNoise(rShot, rRead),
                green = greenNoise,
                blue = ChannelNoise(bShot, bRead),
            )
        }

        /**
         * Fallback estimation when SENSOR_NOISE_PROFILE is unavailable.
         */
        fun fromIsoEstimate(iso: Int): PerChannelNoise {
            val normalizedIso = iso.coerceIn(50, 12800).toFloat() / 100f
            val shot = 0.0002f * normalizedIso * normalizedIso
            val read = (4e-6f * normalizedIso).coerceIn(1e-6f, 5e-4f)
            val ch = ChannelNoise(shot, read)
            return PerChannelNoise(ch, ch, ch)
        }
    }
}

/** Shot + read noise coefficients for a single CFA channel. */
data class ChannelNoise(
    /** Shot noise coefficient A (signal-dependent): sigma^2 = A * x + B. */
    val shotCoeff: Float,
    /** Read noise floor B (signal-independent). */
    val readNoiseSq: Float,
) {
    /** Total noise variance at signal level [x]. */
    fun varianceAt(x: Float): Float = shotCoeff * maxOf(x, 0f) + readNoiseSq
}

/** Output of the HDR merge including merge metadata. */
data class HdrProcessingResult(
    val mergedFrame: PipelineFrame,
    val ghostMask: FloatArray,
    val hdrMode: HdrMergeMode,
)
