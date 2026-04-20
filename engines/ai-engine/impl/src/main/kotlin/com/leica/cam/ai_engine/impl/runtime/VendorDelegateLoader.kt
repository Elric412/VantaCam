package com.leica.cam.ai_engine.impl.runtime

/**
 * Reflection-based attach for vendor-specific ML accelerator delegates so their
 * SDKs are a **runtime** dependency only. If the SDK is missing on the device,
 * [attach] returns without enabling the delegate -- the caller's fall-through
 * logic in [LiteRtSession.open] kicks in and tries the next delegate.
 *
 * This keeps the `ai-engine` module free of compile-time vendor SDK dependencies,
 * which would break builds on CI runners without the vendor toolchain installed.
 *
 * Supported vendors:
 * - **MediaTek NeuroPilot** (`com.mediatek.neuropilot.TfLiteApuDelegate`) --
 *   preferred on Dimensity SoCs.
 * - **Qualcomm QNN** (`com.qualcomm.qti.qnn.QnnDelegate`) --
 *   preferred on Snapdragon (Hexagon DSP path).
 * - **Samsung ENN** (`com.samsung.eden.EnnDelegate`) --
 *   preferred on Exynos (Eden NPU path).
 */
internal object VendorDelegateLoader {

    /**
     * Attempt to reflectively construct a vendor delegate and attach it to
     * the given interpreter options.
     *
     * @param opts       The `InterpreterOptions` instance to add the delegate to.
     * @param optsClass  The `Class` object for `InterpreterOptions` (already loaded).
     * @param kind       Which vendor delegate to try.
     */
    fun attach(
        opts: Any,
        optsClass: Class<*>,
        kind: LiteRtSession.DelegateKind,
    ) {
        val className = when (kind) {
            LiteRtSession.DelegateKind.MTK_APU -> "com.mediatek.neuropilot.TfLiteApuDelegate"
            LiteRtSession.DelegateKind.QNN_DSP -> "com.qualcomm.qti.qnn.QnnDelegate"
            LiteRtSession.DelegateKind.ENN_NPU -> "com.samsung.eden.EnnDelegate"
            else -> return // GPU and XNNPACK are not vendor delegates
        }
        runCatching {
            val delegateClass = Class.forName(className)
            val ctor = delegateClass.getDeclaredConstructor()
            val delegate = ctor.newInstance()
            val delegateInterface = Class.forName("com.google.ai.edge.litert.Delegate")
            val addDelegateMethod = optsClass.getMethod("addDelegate", delegateInterface)
            addDelegateMethod.invoke(opts, delegate)
        }.onFailure { e ->
            // Expected when the vendor SDK is not present on this device.
            // Fall-through is silent -- logged at the LiteRtSession level.
            System.err.println(
                "VendorDelegateLoader: ${kind.name} ($className) not available: ${e.message}",
            )
        }
    }
}
