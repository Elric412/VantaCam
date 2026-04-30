package com.leica.cam.capture.budget

import com.leica.cam.common.logging.LeicaLogger
import com.leica.cam.neural_isp.api.ThermalTier
import kotlin.math.max
import kotlin.math.min

/**
 * Processing Budget System — Implementation.md Chapter 12
 *
 * Dynamically allocates compute time and feature availability based on
 * real-time device resource measurements:
 *
 *   1. Available memory: Runtime.freeMemory + system availMem
 *   2. Thermal headroom: PowerManager.getThermalHeadroom(30)
 *   3. CPU load: /proc/loadavg
 *   4. GPU utilisation: device-specific sysfs path
 *
 * The budget controls:
 *   - Total pipeline processing time (ms)
 *   - Neural ISP enable/disable
 *   - Super resolution enable/disable
 *   - Depth estimation enable/disable
 *   - HDR frame count (9 / 5 / 3 / 1)
 *   - Noise reduction model quality (FULL / FAST / NONE)
 *
 * Thermal throttling response (Section 12.3):
 *   LIGHT:    Disable SR, reduce HDR to 5, AI inference to 1fps
 *   MODERATE: Disable Neural ISP, reduce HDR to 3, video bitrate -20%
 *   SEVERE:   Disable all AI, single-frame only, cap video 25Mbps
 *   CRITICAL: Force stop video, pause camera, show warning dialog
 *
 * Reference: Implementation.md — Performance, Memory & Thermal Management (Chapter 12)
 */
class ProcessingBudgetManager(
    private val logger: LeicaLogger,
) {

    /**
     * Compute the processing budget based on current device state.
     *
     * @param deviceState Current device resource measurements
     * @return Processing budget with feature enablement decisions
     */
    fun computeBudget(deviceState: DeviceResourceState): ProcessingBudget {
        val thermalTier = classifyThermalState(deviceState)

        logger.info(TAG, "Budget computation: memory=${deviceState.availableMemoryMb}MB, " +
            "thermal=${thermalTier}, gpu=${deviceState.gpuTemperatureCelsius}°C, " +
            "cpu=${deviceState.cpuLoadAvg}")

        // Base budget from thermal tier
        val baseBudget = when (thermalTier) {
            ThermalTier.FULL -> BUDGET_FULL
            ThermalTier.REDUCED -> BUDGET_REDUCED
            ThermalTier.MINIMAL -> BUDGET_MINIMAL
            ThermalTier.EMERGENCY_STOP -> BUDGET_EMERGENCY
        }

        // Adjust for available memory
        val memoryPressure = computeMemoryPressure(deviceState.availableMemoryMb)

        // Determine feature availability
        val enableNeuralIsp = thermalTier == ThermalTier.FULL &&
            deviceState.gpuTemperatureCelsius < GPU_TEMP_NEURAL_ISP_LIMIT &&
            deviceState.availableMemoryMb > MEMORY_MIN_NEURAL_ISP_MB &&
            !deviceState.isFastModeEnabled

        val enableSuperResolution = thermalTier == ThermalTier.FULL &&
            !memoryPressure &&
            !deviceState.isVideoMode

        val enableDepthEstimation = thermalTier != ThermalTier.EMERGENCY_STOP &&
            thermalTier != ThermalTier.MINIMAL

        val hdrFrameCount = computeHdrFrameCount(thermalTier, deviceState, baseBudget)

        val nrQuality = computeNrQuality(thermalTier, baseBudget)

        val aiInferenceRateHz = when (thermalTier) {
            ThermalTier.FULL -> AI_INFERENCE_RATE_FULL
            ThermalTier.REDUCED -> AI_INFERENCE_RATE_REDUCED
            ThermalTier.MINIMAL -> AI_INFERENCE_RATE_MINIMAL
            ThermalTier.EMERGENCY_STOP -> 0f
        }

        val videoBitrateMultiplier = when (thermalTier) {
            ThermalTier.FULL -> 1f
            ThermalTier.REDUCED -> 0.8f
            ThermalTier.MINIMAL -> 0.6f
            ThermalTier.EMERGENCY_STOP -> 0f
        }

        val budget = ProcessingBudget(
            totalTimeMs = baseBudget.totalTimeMs,
            thermalTier = thermalTier,
            enableNeuralIsp = enableNeuralIsp,
            enableSuperResolution = enableSuperResolution,
            enableDepthEstimation = enableDepthEstimation,
            hdrFrameCount = hdrFrameCount,
            nrModelQuality = nrQuality,
            aiInferenceRateHz = aiInferenceRateHz,
            videoBitrateMultiplier = videoBitrateMultiplier,
            maxVideoBitrateMbps = if (thermalTier == ThermalTier.MINIMAL) 25f else 100f,
            shouldPauseCamera = thermalTier == ThermalTier.EMERGENCY_STOP,
            shouldStopVideoRecording = thermalTier == ThermalTier.EMERGENCY_STOP,
            userWarningRequired = thermalTier == ThermalTier.MINIMAL ||
                thermalTier == ThermalTier.EMERGENCY_STOP,
            warningMessage = when (thermalTier) {
                ThermalTier.MINIMAL -> "Camera performance reduced to prevent overheating."
                ThermalTier.EMERGENCY_STOP -> "Camera paused due to critical temperature. " +
                    "Please wait for the device to cool down."
                else -> null
            },
        )

        logger.info(TAG, "Budget result: time=${budget.totalTimeMs}ms, neural=${budget.enableNeuralIsp}, " +
            "SR=${budget.enableSuperResolution}, HDR=${budget.hdrFrameCount}, NR=${budget.nrModelQuality}")

        return budget
    }

    /**
     * Update the budget based on real-time feedback during processing.
     * Called periodically during the capture pipeline to adapt to changing conditions.
     *
     * @param currentBudget Current processing budget
     * @param elapsedMs Time elapsed so far in the pipeline
     * @param currentGpuTemp Current GPU temperature reading
     * @return Updated budget (may disable features if overrunning)
     */
    fun updateBudgetRealtime(
        currentBudget: ProcessingBudget,
        elapsedMs: Long,
        currentGpuTemp: Float,
    ): ProcessingBudget {
        val remainingMs = currentBudget.totalTimeMs - elapsedMs.toInt()

        // If we're running out of time, disable expensive features
        if (remainingMs < BUDGET_CRITICAL_REMAINING_MS) {
            logger.warn(TAG, "Budget critical: ${remainingMs}ms remaining, disabling heavy features")
            return currentBudget.copy(
                enableNeuralIsp = false,
                enableSuperResolution = false,
                nrModelQuality = NRQuality.FAST,
            )
        }

        // If GPU temp spiked during processing
        if (currentGpuTemp > GPU_TEMP_CRITICAL) {
            logger.warn(TAG, "GPU temp critical: ${currentGpuTemp}°C, reducing pipeline")
            return currentBudget.copy(
                enableNeuralIsp = false,
                enableSuperResolution = false,
            )
        }

        return currentBudget
    }

    // ──────────────────────────────────────────────────────────────────
    // Internal Classification
    // ──────────────────────────────────────────────────────────────────

    private fun classifyThermalState(state: DeviceResourceState): ThermalTier {
        return when {
            state.thermalHeadroom < THERMAL_HEADROOM_CRITICAL ||
                state.gpuTemperatureCelsius > GPU_TEMP_CRITICAL ->
                ThermalTier.EMERGENCY_STOP

            state.thermalHeadroom < THERMAL_HEADROOM_SEVERE ||
                state.gpuTemperatureCelsius > GPU_TEMP_SEVERE ->
                ThermalTier.MINIMAL

            state.thermalHeadroom < THERMAL_HEADROOM_MODERATE ||
                state.gpuTemperatureCelsius > GPU_TEMP_MODERATE ->
                ThermalTier.REDUCED

            else -> ThermalTier.FULL
        }
    }

    private fun computeMemoryPressure(availableMb: Int): Boolean {
        return availableMb < MEMORY_PRESSURE_THRESHOLD_MB
    }

    private fun computeHdrFrameCount(
        tier: ThermalTier,
        state: DeviceResourceState,
        budget: BudgetTemplate,
    ): Int = when (tier) {
        ThermalTier.FULL -> {
            if (budget.totalTimeMs > 2500) 9 else 5
        }
        ThermalTier.REDUCED -> 5
        ThermalTier.MINIMAL -> 3
        ThermalTier.EMERGENCY_STOP -> 1
    }

    private fun computeNrQuality(tier: ThermalTier, budget: BudgetTemplate): NRQuality = when {
        tier == ThermalTier.EMERGENCY_STOP -> NRQuality.NONE
        tier == ThermalTier.MINIMAL -> NRQuality.FAST
        budget.totalTimeMs > 3000 -> NRQuality.FULL
        else -> NRQuality.FAST
    }

    companion object {
        private const val TAG = "ProcessingBudgetManager"

        // ── Thermal Headroom Thresholds ──────────────────────────────
        private const val THERMAL_HEADROOM_CRITICAL = 0.1f
        private const val THERMAL_HEADROOM_SEVERE = 0.2f
        private const val THERMAL_HEADROOM_MODERATE = 0.4f

        // ── GPU Temperature Thresholds (°C) ──────────────────────────
        private const val GPU_TEMP_CRITICAL = 90f
        private const val GPU_TEMP_SEVERE = 80f
        private const val GPU_TEMP_MODERATE = 70f
        private const val GPU_TEMP_NEURAL_ISP_LIMIT = 75f

        // ── Memory Thresholds (MB) ───────────────────────────────────
        private const val MEMORY_PRESSURE_THRESHOLD_MB = 256
        private const val MEMORY_MIN_NEURAL_ISP_MB = 384

        // ── AI Inference Rates (Hz) ──────────────────────────────────
        private const val AI_INFERENCE_RATE_FULL = 10f
        private const val AI_INFERENCE_RATE_REDUCED = 4f
        private const val AI_INFERENCE_RATE_MINIMAL = 1f

        // ── Budget Time Guard ────────────────────────────────────────
        private const val BUDGET_CRITICAL_REMAINING_MS = 500

        // ── Budget Templates ─────────────────────────────────────────
        private val BUDGET_FULL = BudgetTemplate(totalTimeMs = 5000)
        private val BUDGET_REDUCED = BudgetTemplate(totalTimeMs = 3500)
        private val BUDGET_MINIMAL = BudgetTemplate(totalTimeMs = 2000)
        private val BUDGET_EMERGENCY = BudgetTemplate(totalTimeMs = 800)
    }
}

// ──────────────────────────────────────────────────────────────────────
// Data Models
// ──────────────────────────────────────────────────────────────────────

/**
 * Current device resource measurements taken at capture time.
 */
data class DeviceResourceState(
    /** Available memory in MB (app + system) */
    val availableMemoryMb: Int = 512,
    /** Thermal headroom from PowerManager (0..1, lower = hotter) */
    val thermalHeadroom: Float = 0.8f,
    /** Current CPU load average (1-minute) */
    val cpuLoadAvg: Float = 0.5f,
    /** GPU temperature in °C */
    val gpuTemperatureCelsius: Float = 45f,
    /** GPU utilisation percentage [0..100] */
    val gpuUtilisationPercent: Float = 30f,
    /** Whether the user has enabled "Fast mode" in settings */
    val isFastModeEnabled: Boolean = false,
    /** Whether recording video */
    val isVideoMode: Boolean = false,
    /** Current sensor resolution in megapixels */
    val resolutionMegapixels: Float = 12f,
    /** SoC model identifier */
    val socModel: String = "unknown",
)

/**
 * Processing budget computed from device state.
 * Drives all downstream feature enablement decisions.
 */
data class ProcessingBudget(
    /** Maximum total pipeline processing time (ms) */
    val totalTimeMs: Int,
    /** Current thermal tier */
    val thermalTier: ThermalTier,
    /** Whether Neural ISP is enabled for this capture */
    val enableNeuralIsp: Boolean,
    /** Whether Super Resolution is enabled */
    val enableSuperResolution: Boolean,
    /** Whether depth estimation is enabled */
    val enableDepthEstimation: Boolean,
    /** Number of HDR frames to capture (1/3/5/9) */
    val hdrFrameCount: Int,
    /** Noise reduction model quality */
    val nrModelQuality: NRQuality,
    /** AI inference rate (Hz) for real-time analysis */
    val aiInferenceRateHz: Float,
    /** Video bitrate multiplier (1.0 = full, 0.8 = 80%, etc.) */
    val videoBitrateMultiplier: Float,
    /** Maximum video bitrate in Mbps */
    val maxVideoBitrateMbps: Float,
    /** Whether the camera should be paused (critical thermal) */
    val shouldPauseCamera: Boolean,
    /** Whether video recording should be stopped */
    val shouldStopVideoRecording: Boolean,
    /** Whether a user-visible warning is required */
    val userWarningRequired: Boolean,
    /** Warning message to display to the user (null if none) */
    val warningMessage: String?,
)

enum class NRQuality {
    /** Full multi-stage noise reduction (FFDNet + optional MPRNet) */
    FULL,
    /** Fast single-pass noise reduction (FFDNet only) */
    FAST,
    /** No noise reduction */
    NONE,
}

internal data class BudgetTemplate(
    val totalTimeMs: Int,
)
