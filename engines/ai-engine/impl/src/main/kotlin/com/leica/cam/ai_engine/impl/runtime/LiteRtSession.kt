package com.leica.cam.ai_engine.impl.runtime

import com.google.ai.edge.litert.Interpreter
import com.google.ai.edge.litert.InterpreterOptions
import com.google.ai.edge.litert.gpu.GpuDelegateFactory
import com.leica.cam.common.result.LeicaResult
import com.leica.cam.common.result.PipelineStage
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

/**
 * Thin wrapper around a single LiteRT interpreter session.
 */
class LiteRtSession private constructor(
    private val interpreterHandle: Any,
    private val delegateHandle: Any?,
    val delegateKind: DelegateKind,
    private val runFn: (input: ByteBuffer, output: ByteBuffer) -> Unit,
    private val closeFn: () -> Unit,
) : AutoCloseable {

    private val closed = AtomicBoolean(false)

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
            if (t is CancellationException || t is InterruptedException) throw t
            LeicaResult.Failure.Pipeline(
                stage = PipelineStage.AI_ENGINE,
                message = "LiteRT inference failed on ${delegateKind.name}: ${t.message}",
                cause = t,
            )
        }
    }

    fun outputTensorTypeName(outputIndex: Int = 0): String? =
        (interpreterHandle as? Interpreter)
            ?.getOutputTensor(outputIndex)
            ?.dataType()
            ?.toString()

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        runCatching { closeFn() }
            .onFailure { error ->
                System.err.println(
                    "LiteRtSession: close failed for ${delegateKind.name}: ${error.message}",
                )
            }
    }

    enum class DelegateKind { MTK_APU, QNN_DSP, ENN_NPU, GPU, XNNPACK_CPU }

    companion object {
        fun open(
            modelBuffer: ByteBuffer,
            priority: List<DelegateKind>,
        ): LeicaResult<LiteRtSession> {
            require(modelBuffer.isDirect) {
                "LiteRtSession requires a direct ByteBuffer (use ByteBuffer.allocateDirect)"
            }

            for (kind in priority) {
                val attemptBuffer = modelBuffer.duplicate().apply { rewind() }
                val attempt = runCatching { buildWithDelegate(attemptBuffer, kind) }
                if (attempt.isSuccess) {
                    return LeicaResult.Success(attempt.getOrThrow())
                }
                System.err.println(
                    "LiteRtSession: delegate ${kind.name} failed, falling through: ${attempt.exceptionOrNull()?.message}",
                )
            }
            return LeicaResult.Failure.Pipeline(
                PipelineStage.AI_ENGINE,
                "No LiteRT delegate in priority list $priority could be built.",
            )
        }

        fun mock(
            delegateKind: DelegateKind = DelegateKind.XNNPACK_CPU,
            mockRun: (ByteBuffer, ByteBuffer) -> Unit = { _, _ -> },
        ): LiteRtSession = LiteRtSession(
            interpreterHandle = Any(),
            delegateHandle = null,
            delegateKind = delegateKind,
            runFn = mockRun,
            closeFn = {},
        )

        private fun buildWithDelegate(
            modelBuffer: ByteBuffer,
            kind: DelegateKind,
        ): LiteRtSession {
            val options = InterpreterOptions()
            val delegateHandle = when (kind) {
                DelegateKind.GPU -> GpuDelegateFactory.create().also { options.addDelegate(it) }
                DelegateKind.XNNPACK_CPU -> {
                    options.setNumThreads(Runtime.getRuntime().availableProcessors().coerceAtMost(4))
                    options.setUseXNNPACK(true)
                    null
                }
                DelegateKind.MTK_APU,
                DelegateKind.QNN_DSP,
                DelegateKind.ENN_NPU,
                -> VendorDelegateLoader.attach(options, kind)
            }

            val interpreter = Interpreter(modelBuffer, options)
            return LiteRtSession(
                interpreterHandle = interpreter,
                delegateHandle = delegateHandle,
                delegateKind = kind,
                runFn = { input, output -> interpreter.run(input, output) },
                closeFn = {
                    interpreter.close()
                    if (delegateHandle is AutoCloseable) {
                        delegateHandle.close()
                    }
                },
            )
        }
    }
}
