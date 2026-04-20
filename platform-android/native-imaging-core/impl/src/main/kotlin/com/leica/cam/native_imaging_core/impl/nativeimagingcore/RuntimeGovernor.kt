package com.leica.cam.nativeimagingcore

import kotlin.math.max

/**
 * Capability + thermal aware quality governor.
 * This class intentionally keeps policy in Kotlin so product tuning can ship quickly.
 */
class RuntimeGovernor(
    private val downgradePreviewLatencyMs: Double = 16.0,
    private val downgradeCaptureLatencyMs: Double = 220.0,
    private val thermalThrottleLevel: Int = 6,
    private val maxNativeHeapBytes: Long = 768L * 1024L * 1024L,
) {
    fun classifyTier(profile: DeviceProfile): DeviceTier {
        return when {
            profile.vulkanLevel >= 1 && profile.bigCoreCount >= 4 && profile.totalRamGb >= 10 -> DeviceTier.TIER_A
            profile.vulkanLevel >= 1 && profile.bigCoreCount >= 2 && profile.totalRamGb >= 6 -> DeviceTier.TIER_B
            else -> DeviceTier.TIER_C
        }
    }

    fun adapt(
        currentTier: DeviceTier,
        telemetry: RuntimeTelemetry,
        burstLength: Int,
    ): QualityDecision {
        val mustDowngrade =
            telemetry.p95PreviewLatencyMs > downgradePreviewLatencyMs ||
                telemetry.p95CaptureLatencyMs > downgradeCaptureLatencyMs ||
                telemetry.thermalLevel >= thermalThrottleLevel ||
                telemetry.nativeHeapBytes > maxNativeHeapBytes

        if (!mustDowngrade) {
            return QualityDecision(currentTier, burstLength, false, "Stable telemetry")
        }

        val downgradedTier = when (currentTier) {
            DeviceTier.TIER_A -> DeviceTier.TIER_B
            DeviceTier.TIER_B -> DeviceTier.TIER_C
            DeviceTier.TIER_C -> DeviceTier.TIER_C
        }
        return QualityDecision(
            nextTier = downgradedTier,
            nextBurstLength = max(2, burstLength - 1),
            disableExpensiveRefinements = true,
            reason = "Latency/thermal/memory threshold exceeded",
        )
    }
}

data class DeviceProfile(
    val socFamily: String,
    val gpuFamily: String,
    val vulkanLevel: Int,
    val bigCoreCount: Int,
    val totalRamGb: Int,
    val hasNnapiOrDsp: Boolean,
)

data class QualityDecision(
    val nextTier: DeviceTier,
    val nextBurstLength: Int,
    val disableExpensiveRefinements: Boolean,
    val reason: String,
)
