package com.leica.cam.ai_engine.impl.runtime

import com.leica.cam.common.result.LeicaResult
import com.leica.cam.common.result.PipelineStage
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thin wrapper around a single LiteRT Interpreter session.
 *
 * Ownership: this class OWNS the underlying interpreter and any delegate
 * handles it built. Callers MUST invoke [close] to release native memory;
 * never rely on GC.
 *
 * Thread safety: NOT thread-safe. One [LiteRtSession] per worker coroutine.
 * If you need concurrent inference, construct N sessions, not one shared.
 *
 * The wrapper abstracts the LiteRT API so callers never import vendor-specific
 * classes. In production, the actual `com.google.ai.edge.litert.Interpreter`
 * is loaded; in tests, a mock session can be injected via [LiteRtSession.mock].
 */
class LiteRtSession private constructor(
    private val interpreterHandle: Any,
    private val delegateHandle: Any?,
    val delegateKind: DelegateKind,
    private val runFn: (input: ByteBuffer, output: ByteBuffer) -> Unit,
    private val closeFn: () -> Unit,
) : AutoCloseable {

    private val closed = AtomicBoolean(false)

    /**
     * Run a single inference. Input and output [ByteBuffer]s must be direct
     * and sized to the exact tensor byte count declared by the model metadata.
     */
    fun run(input: ByteBuffer, output: ByteBuffer): LeicaResult<Unit> {
        if (closed.get()) {
            return LeicaResult.Failure.Pipeline(
                stage = PipelineStage.AI_ENGINE,
                message = "LiteRtSession already closed",
            )
        }
        return try {
            runFn(input, output)
            LeicaResult.Success(Unit)
        } catch (t: Throwable) {
            // CancellationException must never be swallowed (LUMO Law 8).
            if (t is InterruptedException || t.javaClass.name.contains("CancellationException")) throw t
            LeicaResult.Failure.Pipeline(
                stage = PipelineStage.AI_ENGINE,
                message = "LiteRT inference failed on ${delegateKind.name}: ${t.message}",
                cause = t,
            )
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { closeFn() }
        }
    }

    /**
     * SoC-specific ML delegate kinds, ordered from fastest to most portable.
     *
     * - [MTK_APU]: MediaTek APU (NeuroPilot) for Dimensity SoCs.
     * - [QNN_DSP]: Qualcomm QNN / Hexagon DSP for Snapdragon.
     * - [ENN_NPU]: Samsung ENN / Eden NPU for Exynos.
     * - [GPU]: LiteRT GPU delegate (Vulkan / OpenCL) -- cross-vendor.
     * - [XNNPACK_CPU]: XNNPACK optimised CPU -- always available, always correct.
     */
    enum class DelegateKind { MTK_APU, QNN_DSP, ENN_NPU, GPU, XNNPACK_CPU }

    companion object {

        /**
         * Build a session for the given model bytes. Falls through delegates
         * in priority order until one builds successfully.
         *
         * @param modelBuffer Direct [ByteBuffer] holding the raw .tflite file.
         *                    MUST be allocated with [ByteBuffer.allocateDirect]
         *                    and flipped to read mode.
         * @param priority    Ordered list of delegates to try.
         * @return A live session on success; typed [LeicaResult.Failure] if
         *         no delegate in the priority list could be built.
         */
        fun open(
            modelBuffer: ByteBuffer,
            priority: List<DelegateKind>,
        ): LeicaResult<LiteRtSession> {
            require(modelBuffer.isDirect) {
                "LiteRtSession requires a direct ByteBuffer (use ByteBuffer.allocateDirect)"
            }

            for (kind in priority) {
                val attempt = runCatching { buildWithDelegate(modelBuffer, kind) }
                if (attempt.isSuccess) {
                    return LeicaResult.Success(attempt.getOrThrow())
                }
                // Log fallthrough -- in production, this becomes WARN level.
                System.err.println(
                    "LiteRtSession: delegate ${kind.name} failed, falling through: " +
                        "${attempt.exceptionOrNull()?.message}",
                )
            }
            return LeicaResult.Failure.Pipeline(
                PipelineStage.AI_ENGINE,
                "No LiteRT delegate in priority list $priority could be built.",
            )
        }

        /**
         * Create a mock session for unit testing. The [mockRun] lambda
         * receives input/output buffers and can fill the output.
         */
        fun mock(
            delegateKind: DelegateKind = DelegateKind.XNNPACK_CPU,
            mockRun: (ByteBuffer, ByteBuffer) -> Unit = { _, _ -> },
        ): LiteRtSession = LiteRtSession(
            interpreterHandle = "mock",
            delegateHandle = null,
            delegateKind = delegateKind,
            runFn = mockRun,
            closeFn = {},
        )

        /**
         * Attempts to construct an interpreter session with the specified delegate.
         *
         * Uses reflection to load the LiteRT classes so the module compiles
         * even when the LiteRT SDK is not on the compile classpath (e.g., in
         * pure-JVM unit test configurations).
         */
        private fun buildWithDelegate(
            modelBuffer: ByteBuffer,
            kind: DelegateKind,
        ): LiteRtSession {
            // Attempt reflective load of LiteRT Interpreter.
            val interpClass = Class.forName("com.google.ai.edge.litert.Interpreter")
            val optsClass = Class.forName("com.google.ai.edge.litert.InterpreterOptions")
            val opts = optsClass.getDeclaredConstructor().newInstance()

            var delegateObj: Any? = null

            when (kind) {
                DelegateKind.GPU -> {
                    val gpuFactoryClass = Class.forName("com.google.ai.edge.litert.gpu.GpuDelegateFactory")
                    val createMethod = gpuFactoryClass.getMethod("create")
                    delegateObj = createMethod.invoke(null)
                    val addDelegateMethod = optsClass.getMethod("addDelegate", Class.forName("com.google.ai.edge.litert.Delegate"))
                    addDelegateMethod.invoke(opts, delegateObj)
                }
                DelegateKind.XNNPACK_CPU -> {
                    val setThreads = optsClass.getMethod("setNumThreads", Int::class.java)
                    setThreads.invoke(opts, Runtime.getRuntime().availableProcessors().coerceAtMost(4))
                    val setXnn = optsClass.getMethod("setUseXNNPACK", Boolean::class.java)
                    setXnn.invoke(opts, true)
                }
                DelegateKind.MTK_APU, DelegateKind.QNN_DSP, DelegateKind.ENN_NPU -> {
                    VendorDelegateLoader.attach(opts, optsClass, kind)
                }
            }

            val interpCtor = interpClass.getConstructor(ByteBuffer::class.java, optsClass)
            val interp = interpCtor.newInstance(modelBuffer, opts)

            val runMethod = interpClass.getMethod("run", Any::class.java, Any::class.java)
            val closeMethod = interpClass.getMethod("close")
            val delegateCloseMethod = delegateObj?.javaClass?.getMethod("close")

            return LiteRtSession(
                interpreterHandle = interp,
                delegateHandle = delegateObj,
                delegateKind = kind,
                runFn = { input, output -> runMethod.invoke(interp, input, output) },
                closeFn = {
                    runCatching { closeMethod.invoke(interp) }
                    runCatching { delegateCloseMethod?.invoke(delegateObj) }
                },
            )
        }
    }
}
