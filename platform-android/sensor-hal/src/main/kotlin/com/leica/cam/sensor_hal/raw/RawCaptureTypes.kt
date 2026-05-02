package com.leica.cam.sensor_hal.raw

import java.nio.ByteBuffer

/**
 * Bayer Color Filter Array layout — matches `CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT`.
 *
 * Layout indices follow the Camera2 enum exactly so that the int returned from
 * `CameraCharacteristics` can be passed straight through `BayerPattern.fromOrdinal`.
 */
enum class BayerPattern(val ordinal_: Int) {
    RGGB(0),
    GRBG(1),
    GBRG(2),
    BGGR(3),
    RGB(4); // mono / non-Bayer fallback

    companion object {
        fun fromOrdinal(value: Int): BayerPattern =
            entries.firstOrNull { it.ordinal_ == value } ?: RGGB
    }
}

/**
 * Static per-camera metadata carried alongside a RAW16 burst.
 *
 * Mirrors the `CameraMetaPojo` consumed by the upstream ProXDR v3 JNI bridge —
 * we keep field names compatible so the native call-site stays trivial.
 */
data class RawCameraMeta(
    val sensorWidth: Int,
    val sensorHeight: Int,
    /** Effective bit depth of the RAW16 stream (typically 10-14 on real sensors). */
    val rawBits: Int = 12,
    val bayer: BayerPattern = BayerPattern.RGGB,
    val hasDcg: Boolean = false,
    val hasOis: Boolean = false,
    /** Row-major 3×3 colour-correction matrix (camera RGB → linear sRGB). */
    val ccm: FloatArray = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
)

/**
 * Per-frame metadata that travels with each RAW16 buffer in a burst.
 *
 * Field names again mirror the upstream `FrameMetaPojo` so the native bridge
 * does not need any translation step. All optional sensor signals (gyro,
 * OIS, faces) are intentionally collapsed into scalar summaries here — the
 * full samples can be passed via the JNI side-channel when the orchestrator
 * decides to opt-in.
 */
data class RawFrameMeta(
    val tsNs: Long,
    val exposureMs: Float,
    val analogGain: Float,
    val digitalGain: Float = 1f,
    val whiteLevel: Float = 4095f,
    val focalMm: Float = 4f,
    val fNumber: Float = 1.8f,
    val dcgLong: Boolean = false,
    val dcgShort: Boolean = false,
    val dcgRatio: Float = 1f,
    /** Gyro-derived global motion magnitude in pixels per millisecond. */
    val motionPxMs: Float = 0f,
    /** Laplacian variance / AF metric. Higher = sharper. */
    val sharpness: Float = 1f,
    /** Per-CFA black levels: R, Gr, Gb, B (raw counts). */
    val blackLevels: FloatArray = floatArrayOf(0f, 0f, 0f, 0f),
    /** Per-CFA white-balance gains: R, Gr, Gb, B. */
    val wbGains: FloatArray = floatArrayOf(1f, 1f, 1f, 1f),
    /** Per-CFA shot-noise scale in σ²(I) = scale·I + offset. */
    val noiseScale: FloatArray = floatArrayOf(0f, 0f, 0f, 0f),
    /** Per-CFA read-noise floor. */
    val noiseOffset: FloatArray = floatArrayOf(0f, 0f, 0f, 0f),
    /** EV bias relative to the burst's reference frame. 0 for a same-exposure burst. */
    val evOffset: Float = 0f,
    /** ISO equivalent (analogGain * 100 if no other source available). */
    val isoEquivalent: Int = 100,
)

/**
 * One immutable RAW16 frame plus its metadata.
 *
 * The buffer must be a **direct** `ByteBuffer` of `width * height * 2` bytes
 * containing 16-bit unsigned little-endian samples (the standard Android
 * `ImageFormat.RAW_SENSOR` layout). Heap-backed buffers force a JNI copy on
 * every frame and are explicitly forbidden — the native engine asserts on this.
 */
data class RawFrame(
    val width: Int,
    val height: Int,
    val raw16: ByteBuffer,
    val meta: RawFrameMeta,
) {
    init {
        require(raw16.isDirect) {
            "RawFrame.raw16 must be a direct ByteBuffer (heap-backed buffers force JNI copies)"
        }
        require(raw16.capacity() >= width * height * 2) {
            "RawFrame.raw16 capacity (${raw16.capacity()}) < expected ${width * height * 2}"
        }
    }
}

/**
 * The full burst payload handed off to the imaging pipeline.
 *
 * A bundle is **always** safe to feed into the v3 native engine when
 * [frames] is non-empty and all frames share the same [RawFrame.width] and
 * [RawFrame.height] — the pipeline asserts on those invariants.
 */
data class RawBurstBundle(
    val frames: List<RawFrame>,
    val camera: RawCameraMeta,
    /** Index of the chosen reference frame; -1 = auto-pick. */
    val referenceIdx: Int = -1,
    /** User EV bias, applied at tone-mapping time. */
    val userEvBias: Float = 0f,
) {
    init {
        require(frames.isNotEmpty()) { "RawBurstBundle must contain at least one frame" }
        val w = frames.first().width
        val h = frames.first().height
        require(frames.all { it.width == w && it.height == h }) {
            "All frames in a RawBurstBundle must share the same dimensions"
        }
    }

    val width: Int get() = frames.first().width
    val height: Int get() = frames.first().height
    val size: Int get() = frames.size
}
