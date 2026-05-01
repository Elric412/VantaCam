package com.leica.cam.ai_engine.impl.runtime

import org.tensorflow.lite.Interpreter

/**
 * Reflection-based attach for vendor-specific ML accelerator delegates. These SDKs
 * are not published on Maven, so delegate discovery remains reflective while the
 * rest of the LiteRT stack is wired through typed APIs.
 */
internal object VendorDelegateLoader {
    fun attach(
        options: Interpreter.Options,
        kind: LiteRtSession.DelegateKind,
    ): Any? {
        val className = when (kind) {
            LiteRtSession.DelegateKind.MTK_APU -> "com.mediatek.neuropilot.TfLiteApuDelegate"
            LiteRtSession.DelegateKind.QNN_DSP -> "com.qualcomm.qti.qnn.QnnDelegate"
            LiteRtSession.DelegateKind.ENN_NPU -> "com.samsung.eden.EnnDelegate"
            else -> return null
        }

        return runCatching {
            val delegateClass = Class.forName(className)
            val delegate = delegateClass.getDeclaredConstructor().newInstance()
            val addDelegate = options.javaClass.getMethod(
                "addDelegate",
                Class.forName("org.tensorflow.lite.Delegate"),
            )
            addDelegate.invoke(options, delegate)
            delegate
        }.getOrElse { error ->
            System.err.println(
                "VendorDelegateLoader: ${kind.name} ($className) not available: ${error.message}",
            )
            null
        }
    }
}
