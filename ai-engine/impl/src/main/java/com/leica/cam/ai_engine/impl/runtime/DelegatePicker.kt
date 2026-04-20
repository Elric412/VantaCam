package com.leica.cam.ai_engine.impl.runtime

import com.leica.cam.ai_engine.impl.runtime.LiteRtSession.DelegateKind

/**
 * Chooses ML delegate priority based on the device's SoC vendor.
 *
 * Detection is coarse -- uses `Build.SOC_MANUFACTURER` (API 31+) with
 * a `Build.HARDWARE` substring fallback for older devices.
 *
 * The priority list is deliberately conservative: if the vendor delegate
 * fails at runtime, GPU is the next best bet for imaging models, and
 * XNNPACK is always a working floor (guaranteed available on all devices).
 *
 * **Dimensity (this device's SoC):** APU (NeuroPilot) -> GPU -> XNNPACK CPU.
 * The APU delegate is ~3x faster than GPU for quantised INT8 models on
 * Dimensity 9200/8000-series; GPU is better for float16 models.
 */
object DelegatePicker {

    /**
     * Returns the delegate priority list for the current device.
     * First element is the most preferred delegate; last is fallback.
     */
    fun priorityForCurrentDevice(): List<DelegateKind> {
        val socManu = runCatching {
            // Build.SOC_MANUFACTURER available on API 31+
            val field = android.os.Build::class.java.getField("SOC_MANUFACTURER")
            (field.get(null) as? String)?.lowercase() ?: ""
        }.getOrDefault("")
        val hw = android.os.Build.HARDWARE.lowercase()

        return when {
            socManu.contains("mediatek") || hw.contains("mt") ->
                listOf(DelegateKind.MTK_APU, DelegateKind.GPU, DelegateKind.XNNPACK_CPU)
            socManu.contains("qualcomm") || hw.contains("qcom") ->
                listOf(DelegateKind.QNN_DSP, DelegateKind.GPU, DelegateKind.XNNPACK_CPU)
            socManu.contains("samsung") || hw.contains("exynos") ->
                listOf(DelegateKind.ENN_NPU, DelegateKind.GPU, DelegateKind.XNNPACK_CPU)
            else ->
                listOf(DelegateKind.GPU, DelegateKind.XNNPACK_CPU)
        }
    }

    /**
     * Convenience: returns the single best-priority delegate for logging.
     */
    fun preferredDelegate(): DelegateKind = priorityForCurrentDevice().first()
}
