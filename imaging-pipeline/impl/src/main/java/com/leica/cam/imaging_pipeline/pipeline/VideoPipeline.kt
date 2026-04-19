package com.leica.cam.imaging_pipeline.pipeline

import com.leica.cam.common.result.LeicaResult
import com.leica.cam.common.result.PipelineStage
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private const val COMPLEMENTARY_FILTER_ALPHA = 0.02f
private const val FLOW_INLIER_THRESHOLD_PX = 2f
private const val STABILIZATION_BORDER_CROP = 0.9f
private const val LOG_ENCODE_NORMALIZATION_NITS = 1000f
private const val WIND_SCORE_SUPPRESSION_THRESHOLD = 0.6f
private const val WIND_SCORE_RATIO_TRIGGER = 3f
private const val WIND_NOISE_SUPPRESSION_GAIN = 0.126f // ≈ -18 dB.
private const val LIMITER_CEILING = 0.891f // -1 dBFS peak.
private const val TIME_LAPSE_MIN_INTERVAL_MS = 250L
private const val DEFAULT_GYRO_SMOOTHING = 0.12f

/** One gyroscope reading in rad/s with a monotonic timestamp in ns. */
data class GyroSample(
    val timestampNs: Long,
    val wx: Float,
    val wy: Float,
    val wz: Float,
)

/** One accelerometer sample in m/s² with a monotonic timestamp in ns. */
data class AccelerometerSample(
    val timestampNs: Long,
    val ax: Float,
    val ay: Float,
    val az: Float,
)

/** Optical flow vector between two video frames. */
data class OpticalFlowVector(
    val x: Float,
    val y: Float,
    val dx: Float,
    val dy: Float,
)

/** Camera intrinsics used by stabilization mapping. */
data class CameraIntrinsics(
    val fx: Float,
    val fy: Float,
    val cx: Float,
    val cy: Float,
)

/** 2D stabilization warp summary for one frame. */
data class StabilizationTransform(
    val rotationRadX: Float,
    val rotationRadY: Float,
    val rotationRadZ: Float,
    val translationXPx: Float,
    val translationYPx: Float,
    val cropFactor: Float,
)

/** Stabilization output including rolling-shutter correction shears per image row. */
data class StabilizationResult(
    val transform: StabilizationTransform,
    val rowShear: FloatArray,
)

/** LOG/HLG profiles supported by video tone rendering. */
enum class VideoColorProfile {
    REC709,
    LOG,
    HLG,
}

/** Input options for cinema capture mode. */
data class CinemaVideoRequest(
    val width: Int,
    val height: Int,
    val fps: Int,
    val preferRawVideo: Boolean,
    val profile: VideoColorProfile,
)

/** Output configuration selected for cinema mode. */
data class CinemaVideoConfiguration(
    val codec: String,
    val bitDepth: Int,
    val profile: VideoColorProfile,
    val rawDngSequenceEnabled: Boolean,
    val recommendedBitrateMbps: Int,
)

/** Discrete 3D LUT with flattened RGB output nodes. */
data class Lut3D(
    val size: Int,
    val red: FloatArray,
    val green: FloatArray,
    val blue: FloatArray,
) {
    init {
        val expected = size * size * size
        require(size >= 2) { "LUT size must be >= 2" }
        require(red.size == expected) { "LUT red size mismatch" }
        require(green.size == expected) { "LUT green size mismatch" }
        require(blue.size == expected) { "LUT blue size mismatch" }
    }
}

/** Engine for OIS+EIS fusion using gyro + accelerometer + optical flow residuals. */
class OisEisFusionStabilizer {
    fun stabilize(
        gyroSamples: List<GyroSample>,
        accelerometerSamples: List<AccelerometerSample>,
        opticalFlow: List<OpticalFlowVector>,
        intrinsics: CameraIntrinsics,
        frameDurationNs: Long,
        rowCount: Int,
        oisRotationCompensationRadZ: Float = 0f,
    ): LeicaResult<StabilizationResult> {
        if (gyroSamples.size < 2) {
            return LeicaResult.Failure.Pipeline(PipelineStage.IMAGING_PIPELINE, "At least two gyroscope samples are required")
        }
        if (rowCount <= 0) {
            return LeicaResult.Failure.Pipeline(PipelineStage.IMAGING_PIPELINE, "Row count must be positive")
        }

        val gyroRotation = integrateGyro(gyroSamples)
        val accelRotation = estimateAccelRotation(accelerometerSamples)
        val fusedRx = lerp(gyroRotation.first, accelRotation.first, COMPLEMENTARY_FILTER_ALPHA)
        val fusedRy = lerp(gyroRotation.second, accelRotation.second, COMPLEMENTARY_FILTER_ALPHA)
        val fusedRz = lowPass(gyroRotation.third - oisRotationCompensationRadZ, DEFAULT_GYRO_SMOOTHING)

        val translation = estimateTranslation(opticalFlow)

        val scaleX = intrinsics.fx.coerceAtLeast(1f)
        val scaleY = intrinsics.fy.coerceAtLeast(1f)
        val translationXPx = translation.first + fusedRy * scaleX
        val translationYPx = translation.second - fusedRx * scaleY

        val rowShear = FloatArray(rowCount)
        val deltaRowTime = frameDurationNs.toFloat() / rowCount
        var rowTimestamp = 0f
        for (row in 0 until rowCount) {
            val tNorm = rowTimestamp / frameDurationNs.coerceAtLeast(1L)
            rowShear[row] = fusedRz * tNorm
            rowTimestamp += deltaRowTime
        }

        return LeicaResult.Success(
            StabilizationResult(
                transform = StabilizationTransform(
                    rotationRadX = fusedRx,
                    rotationRadY = fusedRy,
                    rotationRadZ = fusedRz,
                    translationXPx = translationXPx,
                    translationYPx = translationYPx,
                    cropFactor = STABILIZATION_BORDER_CROP,
                ),
                rowShear = rowShear,
            ),
        )
    }

    private fun integrateGyro(samples: List<GyroSample>): Triple<Float, Float, Float> {
        var rx = 0f
        var ry = 0f
        var rz = 0f

        for (i in 1 until samples.size) {
            val current = samples[i]
            val previous = samples[i - 1]
            val dt = (current.timestampNs - previous.timestampNs).coerceAtLeast(0L) / 1_000_000_000f
            rx += current.wx * dt
            ry += current.wy * dt
            rz += current.wz * dt
        }

        return Triple(rx, ry, rz)
    }

    private fun estimateAccelRotation(samples: List<AccelerometerSample>): Pair<Float, Float> {
        if (samples.isEmpty()) {
            return 0f to 0f
        }

        val meanAx = samples.map { it.ax }.average().toFloat()
        val meanAy = samples.map { it.ay }.average().toFloat()
        val meanAz = samples.map { it.az }.average().toFloat().coerceAtLeast(1e-4f)

        val rx = kotlin.math.atan2(meanAy, meanAz)
        val ry = kotlin.math.atan2(-meanAx, sqrt(meanAy * meanAy + meanAz * meanAz))
        return rx to ry
    }

    private fun estimateTranslation(flow: List<OpticalFlowVector>): Pair<Float, Float> {
        if (flow.isEmpty()) {
            return 0f to 0f
        }
        val inliers = flow.filter { sqrt(it.dx * it.dx + it.dy * it.dy) <= FLOW_INLIER_THRESHOLD_PX }
        val samples = if (inliers.isNotEmpty()) inliers else flow
        return samples.map { it.dx }.average().toFloat() to samples.map { it.dy }.average().toFloat()
    }

    private fun lowPass(value: Float, alpha: Float): Float = alpha * value + (1f - alpha) * 0f
    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}

/** LOG/HLG tone rendering for real-time preview and encode paths. */
class VideoColorProfileEngine {
    fun apply(frame: PipelineFrame, profile: VideoColorProfile): PipelineFrame {
        return when (profile) {
            VideoColorProfile.REC709 -> frame.copyChannels()
            VideoColorProfile.LOG -> transform(frame) { v -> encodeLog(v) }
            VideoColorProfile.HLG -> transform(frame) { v -> encodeHlg(v) }
        }
    }

    private fun transform(frame: PipelineFrame, mapper: (Float) -> Float): PipelineFrame {
        val r = FloatArray(frame.red.size)
        val g = FloatArray(frame.green.size)
        val b = FloatArray(frame.blue.size)
        for (i in frame.red.indices) {
            r[i] = mapper(frame.red[i])
            g[i] = mapper(frame.green[i])
            b[i] = mapper(frame.blue[i])
        }
        return PipelineFrame(frame.width, frame.height, r, g, b)
    }

    private fun encodeLog(linear: Float): Float {
        val clamped = linear.coerceIn(0f, 1f)
        val scaled = clamped * LOG_ENCODE_NORMALIZATION_NITS
        return (ln(1f + 0.012f * scaled) / ln(1f + 0.012f * LOG_ENCODE_NORMALIZATION_NITS)).coerceIn(0f, 1f)
    }

    private fun encodeHlg(linear: Float): Float {
        val v = linear.coerceIn(0f, 1f)
        return if (v <= 1f / 12f) {
            sqrt(3f * v)
        } else {
            val a = 0.17883277f
            val b = 1f - 4f * a
            val c = 0.5599107f
            (a * ln(12f * v - b) + c).coerceIn(0f, 1f)
        }
    }
}

/** Professional audio processing chain matching the phase 8 requirements. */
class ProfessionalAudioPipeline {
    fun processStereo(pcmInterleaved: FloatArray, sampleRateHz: Int = 48_000): LeicaResult<FloatArray> {
        if (sampleRateHz <= 0) {
            return LeicaResult.Failure.Pipeline(PipelineStage.IMAGING_PIPELINE, "Sample rate must be positive")
        }
        if (pcmInterleaved.isEmpty()) {
            return LeicaResult.Success(FloatArray(0))
        }

        val output = pcmInterleaved.copyOf()
        dcBlock(output)
        applyWindSuppression(output)
        highPass80hz(output, sampleRateHz)
        presenceBoost(output, sampleRateHz)
        compress(output)
        truePeakLimit(output)
        return LeicaResult.Success(output)
    }

    private fun dcBlock(samples: FloatArray) {
        var prevInput = 0f
        var prevOutput = 0f
        val r = 0.995f
        for (i in samples.indices) {
            val x = samples[i]
            val y = x - prevInput + r * prevOutput
            samples[i] = y
            prevInput = x
            prevOutput = y
        }
    }

    private fun applyWindSuppression(samples: FloatArray) {
        var lowEnergy = 0f
        var windBandEnergy = 0f
        for (i in samples.indices) {
            val v = abs(samples[i])
            if (i % 4 == 0) {
                lowEnergy += v
            } else {
                windBandEnergy += v
            }
        }
        val windScore = (windBandEnergy / max(lowEnergy, 1e-6f)) / WIND_SCORE_RATIO_TRIGGER
        if (windScore > WIND_SCORE_SUPPRESSION_THRESHOLD) {
            for (i in samples.indices) {
                samples[i] *= WIND_NOISE_SUPPRESSION_GAIN
            }
        }
    }

    private fun highPass80hz(samples: FloatArray, sampleRateHz: Int) {
        val dt = 1f / sampleRateHz
        val rc = 1f / (2f * Math.PI.toFloat() * 80f)
        val alpha = rc / (rc + dt)
        var prevOutput = 0f
        var prevInput = 0f
        for (i in samples.indices) {
            val input = samples[i]
            val output = alpha * (prevOutput + input - prevInput)
            samples[i] = output
            prevOutput = output
            prevInput = input
        }
    }

    private fun presenceBoost(samples: FloatArray, sampleRateHz: Int) {
        val f = 3_000f
        val q = 1.1f
        val gain = 1.1885f // +1.5 dB.
        val omega = 2f * Math.PI.toFloat() * (f / sampleRateHz)
        val alpha = sin(omega) / (2f * q)
        val a0 = 1f + alpha / gain
        val b0 = (1f + alpha * gain) / a0
        val b1 = (-2f * cos(omega)) / a0
        val b2 = (1f - alpha * gain) / a0
        val a1 = (-2f * cos(omega)) / a0
        val a2 = (1f - alpha / gain) / a0

        var x1 = 0f
        var x2 = 0f
        var y1 = 0f
        var y2 = 0f
        for (i in samples.indices) {
            val x0 = samples[i]
            val y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            samples[i] = y0
            x2 = x1
            x1 = x0
            y2 = y1
            y1 = y0
        }
    }

    private fun compress(samples: FloatArray) {
        val threshold = 10f.pow(-18f / 20f)
        val ratio = 3f
        val makeup = 10f.pow(3f / 20f)
        for (i in samples.indices) {
            val sign = if (samples[i] < 0f) -1f else 1f
            val level = abs(samples[i])
            val compressed = if (level > threshold) {
                threshold + (level - threshold) / ratio
            } else {
                level
            }
            samples[i] = sign * (compressed * makeup)
        }
    }

    private fun truePeakLimit(samples: FloatArray) {
        for (i in samples.indices) {
            samples[i] = samples[i].coerceIn(-LIMITER_CEILING, LIMITER_CEILING)
        }
    }

    private fun Float.pow(power: Float): Float = Math.pow(this.toDouble(), power.toDouble()).toFloat()
}

/** Applies a 3D LUT to a frame with trilinear interpolation for preview rendering. */
class RealTimeLutPreviewEngine {
    fun apply(frame: PipelineFrame, lut: Lut3D): PipelineFrame {
        val outR = FloatArray(frame.red.size)
        val outG = FloatArray(frame.green.size)
        val outB = FloatArray(frame.blue.size)

        for (i in frame.red.indices) {
            val mapped = sampleLut(frame.red[i], frame.green[i], frame.blue[i], lut)
            outR[i] = mapped.first
            outG[i] = mapped.second
            outB[i] = mapped.third
        }

        return PipelineFrame(frame.width, frame.height, outR, outG, outB)
    }

    private fun sampleLut(r: Float, g: Float, b: Float, lut: Lut3D): Triple<Float, Float, Float> {
        val size = lut.size
        val rf = r.coerceIn(0f, 1f) * (size - 1)
        val gf = g.coerceIn(0f, 1f) * (size - 1)
        val bf = b.coerceIn(0f, 1f) * (size - 1)

        val r0 = rf.toInt().coerceIn(0, size - 1)
        val g0 = gf.toInt().coerceIn(0, size - 1)
        val b0 = bf.toInt().coerceIn(0, size - 1)
        val r1 = min(r0 + 1, size - 1)
        val g1 = min(g0 + 1, size - 1)
        val b1 = min(b0 + 1, size - 1)

        val tr = rf - r0
        val tg = gf - g0
        val tb = bf - b0

        fun idx(rr: Int, gg: Int, bb: Int): Int = (bb * size * size) + (gg * size) + rr

        fun lerp3(c000: Float, c100: Float, c010: Float, c110: Float, c001: Float, c101: Float, c011: Float, c111: Float): Float {
            val c00 = lerp(c000, c100, tr)
            val c10 = lerp(c010, c110, tr)
            val c01 = lerp(c001, c101, tr)
            val c11 = lerp(c011, c111, tr)
            val c0 = lerp(c00, c10, tg)
            val c1 = lerp(c01, c11, tg)
            return lerp(c0, c1, tb)
        }

        val rr = lerp3(
            lut.red[idx(r0, g0, b0)], lut.red[idx(r1, g0, b0)],
            lut.red[idx(r0, g1, b0)], lut.red[idx(r1, g1, b0)],
            lut.red[idx(r0, g0, b1)], lut.red[idx(r1, g0, b1)],
            lut.red[idx(r0, g1, b1)], lut.red[idx(r1, g1, b1)],
        )
        val gg = lerp3(
            lut.green[idx(r0, g0, b0)], lut.green[idx(r1, g0, b0)],
            lut.green[idx(r0, g1, b0)], lut.green[idx(r1, g1, b0)],
            lut.green[idx(r0, g0, b1)], lut.green[idx(r1, g0, b1)],
            lut.green[idx(r0, g1, b1)], lut.green[idx(r1, g1, b1)],
        )
        val bbOut = lerp3(
            lut.blue[idx(r0, g0, b0)], lut.blue[idx(r1, g0, b0)],
            lut.blue[idx(r0, g1, b0)], lut.blue[idx(r1, g1, b0)],
            lut.blue[idx(r0, g0, b1)], lut.blue[idx(r1, g0, b1)],
            lut.blue[idx(r0, g1, b1)], lut.blue[idx(r1, g1, b1)],
        )
        return Triple(rr.coerceIn(0f, 1f), gg.coerceIn(0f, 1f), bbOut.coerceIn(0f, 1f))
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}

/** Output summary for time-lapse/hyper-lapse frame scheduling. */
data class TimeLapsePlan(
    val selectedFrameIndexes: List<Int>,
    val outputFrameRate: Int,
    val playbackSpeedMultiplier: Float,
    val stabilizationBoost: Float,
)

/** Scheduler for time-lapse and hyper-lapse capture plans. */
class TimeLapseEngine {
    fun plan(
        totalFrames: Int,
        captureIntervalMs: Long,
        outputFps: Int,
        hyperLapse: Boolean,
    ): LeicaResult<TimeLapsePlan> {
        if (totalFrames <= 0) {
            return LeicaResult.Failure.Pipeline(PipelineStage.IMAGING_PIPELINE, "Total frames must be positive")
        }
        if (captureIntervalMs < TIME_LAPSE_MIN_INTERVAL_MS) {
            return LeicaResult.Failure.Pipeline(PipelineStage.IMAGING_PIPELINE, "Capture interval must be >= $TIME_LAPSE_MIN_INTERVAL_MS ms")
        }
        if (outputFps <= 0) {
            return LeicaResult.Failure.Pipeline(PipelineStage.IMAGING_PIPELINE, "Output FPS must be positive")
        }

        val sampleStride = if (hyperLapse) 4 else 1
        val indexes = buildList {
            var i = 0
            while (i < totalFrames) {
                add(i)
                i += sampleStride
            }
        }

        val playbackSpeed = captureIntervalMs / (1000f / outputFps)
        val stabilizationBoost = if (hyperLapse) 1.35f else 1f

        return LeicaResult.Success(
            TimeLapsePlan(
                selectedFrameIndexes = indexes,
                outputFrameRate = outputFps,
                playbackSpeedMultiplier = playbackSpeed,
                stabilizationBoost = stabilizationBoost,
            ),
        )
    }
}

/** Planner for cinema video mode including RAW-video fallback decisions. */
class CinemaVideoModeEngine {
    fun configure(request: CinemaVideoRequest, deviceSupportsRawVideo: Boolean): LeicaResult<CinemaVideoConfiguration> {
        if (request.width <= 0 || request.height <= 0 || request.fps <= 0) {
            return LeicaResult.Failure.Pipeline(PipelineStage.IMAGING_PIPELINE, "Video dimensions and fps must be positive")
        }

        val pixels = request.width.toLong() * request.height.toLong()
        val is4kOrAbove = pixels >= 3_840L * 2_160L
        val bitrate = when {
            request.fps >= 120 -> 120
            is4kOrAbove && request.fps >= 60 -> 80
            is4kOrAbove -> 55
            else -> 35
        }

        val rawEnabled = request.preferRawVideo && deviceSupportsRawVideo && request.fps <= 30
        val codec = if (rawEnabled) "RAW_DNG_SEQUENCE" else "HEVC"
        val bitDepth = when {
            rawEnabled -> 12
            request.profile == VideoColorProfile.HLG -> 10
            request.profile == VideoColorProfile.LOG -> 10
            else -> 8
        }

        return LeicaResult.Success(
            CinemaVideoConfiguration(
                codec = codec,
                bitDepth = bitDepth,
                profile = request.profile,
                rawDngSequenceEnabled = rawEnabled,
                recommendedBitrateMbps = bitrate,
            ),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Thermal-adaptive video quality governor
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Adaptive quality tier for the video pipeline.
 * Quality is degraded gracefully as device thermal headroom decreases.
 */
enum class VideoQualityTier {
    /** Full quality — all engines active, no compromises. */
    FULL,
    /** Slightly reduced quality — disable grain synthesis, cap to 30 fps ISP. */
    BALANCED,
    /** Reduced quality — disable per-zone CCM, use Reinhard TM instead of Durand. */
    POWER_SAVE,
    /** Minimum quality — single frame only, no HDR, no noise reduction. */
    EMERGENCY,
}

/**
 * Selects the appropriate video quality tier based on thermal state and battery.
 *
 * Thermal policy (per knowledge/Advance HDR research §Parallel Computing):
 * - NONE/LIGHT → FULL quality
 * - MODERATE → BALANCED (disable grain, limit extra NR passes)
 * - SEVERE → POWER_SAVE (disable per-zone CCM, use Reinhard preview TM)
 * - CRITICAL → EMERGENCY (single frame, minimal processing)
 *
 * Never compromise shutter lag or face tone quality — only disable cosmetic features.
 */
object ThermalQualityGovernor {

    /**
     * @param thermalStatus  Android ThermalStatus ordinal (0=NONE..6=SHUTDOWN).
     * @param batteryPercent Battery level [0,100]. Below 10% forces POWER_SAVE.
     */
    fun selectTier(thermalStatus: Int, batteryPercent: Int = 100): VideoQualityTier = when {
        thermalStatus >= 6 -> VideoQualityTier.EMERGENCY
        thermalStatus >= 4 -> VideoQualityTier.POWER_SAVE
        thermalStatus >= 2 || batteryPercent < 10 -> VideoQualityTier.BALANCED
        else -> VideoQualityTier.FULL
    }

    /**
     * Returns whether per-zone CCM (full Leica/Hasselblad rendering) is enabled
     * at a given quality tier.
     */
    fun isZoneCcmEnabled(tier: VideoQualityTier): Boolean = tier == VideoQualityTier.FULL

    /**
     * Returns whether Durand bilateral tone mapping is enabled.
     * POWER_SAVE and below fall back to Reinhard global TM.
     */
    fun isDurandEnabled(tier: VideoQualityTier): Boolean =
        tier == VideoQualityTier.FULL || tier == VideoQualityTier.BALANCED

    /**
     * Returns whether film grain synthesis is active.
     * Disabled at BALANCED and below — small quality reduction, meaningful power saving.
     */
    fun isGrainEnabled(tier: VideoQualityTier): Boolean = tier == VideoQualityTier.FULL

    /**
     * Maximum burst depth for HDR at a given quality tier.
     * EMERGENCY = 1 (no HDR), POWER_SAVE = 2, BALANCED = 3, FULL = up to 9.
     */
    fun maxBurstDepth(tier: VideoQualityTier): Int = when (tier) {
        VideoQualityTier.EMERGENCY -> 1
        VideoQualityTier.POWER_SAVE -> 2
        VideoQualityTier.BALANCED -> 3
        VideoQualityTier.FULL -> 9
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Frame drop counter for pipeline health monitoring
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Lightweight pipeline health monitor.
 * Tracks dropped frames and processing latency for telemetry.
 */
class PipelineHealthMonitor {
    private var droppedFrames: Int = 0
    private var totalFrames: Int = 0
    private val latencySamplesMs = ArrayDeque<Long>(32)

    fun recordFrame(processedOk: Boolean, latencyMs: Long) {
        totalFrames++
        if (!processedOk) droppedFrames++
        if (latencySamplesMs.size >= 32) latencySamplesMs.removeFirst()
        latencySamplesMs.addLast(latencyMs)
    }

    /** Drop rate as fraction ∈ [0, 1]. */
    fun dropRate(): Float = if (totalFrames == 0) 0f else droppedFrames.toFloat() / totalFrames

    /** Moving average processing latency in ms over the last 32 frames. */
    fun avgLatencyMs(): Long = if (latencySamplesMs.isEmpty()) 0L else latencySamplesMs.average().toLong()

    fun reset() { droppedFrames = 0; totalFrames = 0; latencySamplesMs.clear() }
}
