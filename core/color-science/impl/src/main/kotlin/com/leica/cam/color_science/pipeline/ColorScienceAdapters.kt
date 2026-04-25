package com.leica.cam.color_science.pipeline

import com.leica.cam.color_science.api.SceneContext
import com.leica.cam.hardware.contracts.photon.PhotonBuffer
import com.leica.cam.photon_matrix.FusedPhotonBuffer

/**
 * Scene-label → ColorProfile routing table.
 *
 * Matches free-form scene labels (from `:ai-engine:api SceneClassifier`) to a
 * concrete in-app [ColorProfile]. The default is [ColorProfile.HASSELBLAD_NATURAL]
 * — the universal HNCS-style profile used when no specific semantic cue is present.
 *
 * [ColorProfile.LEICA_M_CLASSIC] is selected for street / portrait / low-light
 * scenes which benefit from the cinematic micro-contrast roll-off and warmer skin
 * rendering.
 *
 * Mapping is case-insensitive. Unknown labels resolve to the default.
 */
object ColorProfileFromSceneLabel {
    /**
     * Resolve a scene label to a [ColorProfile].
     *
     * @param label Free-form label string from the on-device scene classifier.
     * @return Matching profile; [ColorProfile.HASSELBLAD_NATURAL] if no match.
     */
    fun resolve(label: String): ColorProfile {
        val l = label.lowercase()
        return when {
            "portrait" in l || "face" in l || "street" in l -> ColorProfile.LEICA_M_CLASSIC
            "landscape" in l || "nature" in l || "foliage" in l -> ColorProfile.HASSELBLAD_NATURAL
            "night" in l || "low_light" in l || "lowlight" in l -> ColorProfile.LEICA_M_CLASSIC
            "monochrome" in l || "bw" in l || "black_white" in l -> ColorProfile.HP5_BW
            "travel" in l || "outdoor" in l -> ColorProfile.VELVIA_FILM
            else -> ColorProfile.HASSELBLAD_NATURAL
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// FusedPhotonBuffer ↔ ColorFrame bridge
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Maximum output value for the [PhotonBuffer] bit-depth used to normalise
 * short values to the [0, 1] float range expected by [ColorSciencePipeline].
 */
private const val SHORT_MAX_16BIT = 65535f
private const val SHORT_MAX_12BIT = 4095f
private const val SHORT_MAX_10BIT = 1023f

/**
 * Convert a [FusedPhotonBuffer] into the internal linear [ColorFrame].
 *
 * The [FusedPhotonBuffer.underlying] [PhotonBuffer] is expected to carry
 * interleaved or planar f32-equivalent data encoded as 16-bit shorts:
 *   - Plane 0 → Red channel
 *   - Plane 1 → Green channel
 *   - Plane 2 → Blue channel
 *
 * Single-plane buffers (e.g. packed Bayer) are expanded to three identical
 * planes as a best-effort fallback — this case should not occur in the live
 * path, where the photon-matrix assembler always produces a 3-plane output.
 *
 * Short values are normalised to [0, 1] using the buffer's declared [PhotonBuffer.BitDepth].
 */
internal fun FusedPhotonBuffer.toColorFrame(): ColorFrame {
    val buf = this.underlying
    val w = buf.width
    val h = buf.height
    val size = w * h
    val scale = when (buf.bitDepth) {
        PhotonBuffer.BitDepth.BITS_10 -> SHORT_MAX_10BIT
        PhotonBuffer.BitDepth.BITS_12 -> SHORT_MAX_12BIT
        PhotonBuffer.BitDepth.BITS_16 -> SHORT_MAX_16BIT
    }

    val planeCount = buf.planeCount()
    return when {
        planeCount >= 3 -> {
            // Normal 3-plane RGB path (standard photon-matrix output)
            val rPlane = buf.planeView(0)
            val gPlane = buf.planeView(1)
            val bPlane = buf.planeView(2)
            val r = FloatArray(size) { i -> (rPlane.get(i).toInt() and 0xFFFF) / scale }
            val g = FloatArray(size) { i -> (gPlane.get(i).toInt() and 0xFFFF) / scale }
            val b = FloatArray(size) { i -> (bPlane.get(i).toInt() and 0xFFFF) / scale }
            ColorFrame(width = w, height = h, red = r, green = g, blue = b)
        }
        planeCount == 1 -> {
            // Single-plane fallback: assume it's luminance — replicate to all channels.
            // This should not occur in the production live path.
            val lum = buf.planeView(0)
            val c = FloatArray(size) { i -> (lum.get(i).toInt() and 0xFFFF) / scale }
            ColorFrame(width = w, height = h, red = c.copyOf(), green = c.copyOf(), blue = c)
        }
        else -> {
            // Insufficient planes — return a black frame to avoid crash.
            // The upstream assembler contract violation should be caught by the caller.
            ColorFrame(
                width = w, height = h,
                red = FloatArray(size),
                green = FloatArray(size),
                blue = FloatArray(size),
            )
        }
    }
}

/**
 * Write a processed [ColorFrame] back into a fresh [FusedPhotonBuffer] derived
 * from [template] (carries metadata such as fusionQuality, frameCount, motionMagnitude).
 *
 * The float values in [0, 1] are scaled back to 16-bit shorts for consistency
 * with the downstream HEIC/DNG encoding path.
 */
internal fun ColorFrame.intoFusedPhotonBuffer(template: FusedPhotonBuffer): FusedPhotonBuffer {
    val size = width * height
    // Encode processed float channels back to 16-bit shorts
    val rShorts = ShortArray(size) { i -> (red[i].coerceIn(0f, 1f) * SHORT_MAX_16BIT).toInt().toShort() }
    val gShorts = ShortArray(size) { i -> (green[i].coerceIn(0f, 1f) * SHORT_MAX_16BIT).toInt().toShort() }
    val bShorts = ShortArray(size) { i -> (blue[i].coerceIn(0f, 1f) * SHORT_MAX_16BIT).toInt().toShort() }

    val newBuffer = PhotonBuffer.create16Bit(
        width = width,
        height = height,
        planes = listOf(rShorts, gShorts, bShorts),
    )
    return FusedPhotonBuffer(
        underlying = newBuffer,
        fusionQuality = template.fusionQuality,
        frameCount = template.frameCount,
        motionMagnitude = template.motionMagnitude,
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// SceneContext extension helpers
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Default per-hue HSL adjustments — all bands at neutral (no user adjustments).
 *
 * In production, user adjustments come from `CameraPreferencesRepository`
 * and are passed into [SceneContext] via a higher-level adapter in the
 * imaging-pipeline orchestrator. The no-op default is safe and preserves
 * exact pipeline behaviour when no user controls have been touched.
 */
internal fun SceneContext.hueAdjustments(): PerHueAdjustmentSet = PerHueAdjustmentSet()

/**
 * Default vibrance amount — 0.0 means no perceptual saturation boost.
 *
 * Non-zero values are supplied by the user vibrance slider in
 * `CameraPreferencesRepository`. This default is safe for all profiles.
 */
internal fun SceneContext.vibrance(): Float = 0f

/**
 * Default frame index for deterministic film grain synthesis.
 *
 * In preview mode the actual frame counter is injected by the preview path;
 * in capture mode 0 is correct (grain is baked into the single capture frame).
 */
internal fun SceneContext.frameIndex(): Int = 0

/**
 * Default zone mask — null means baseline CCM only (no per-zone blending).
 *
 * In production, the zone mask is derived from `SemanticSegmenter` output
 * (DeepLabv3 `Model/Scene Understanding/deeplabv3.tflite`) and passed in via
 * the imaging-pipeline orchestrator. When null, `PerZoneCcmEngine` applies the
 * baseline dual-illuminant CCM without creative zone deltas.
 */
internal fun SceneContext.zoneMask(): Array<Map<ColourZone, Float>>? = null
