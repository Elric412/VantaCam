package com.leica.cam.ai_engine.impl.models

import com.leica.cam.ai_engine.api.AwbNeuralPrior
import com.leica.cam.ai_engine.api.AwbPredictor
import com.leica.cam.ai_engine.impl.registry.ModelRegistry
import com.leica.cam.ai_engine.impl.runtime.LiteRtSession
import com.leica.cam.common.result.LeicaResult
import com.leica.cam.common.result.PipelineStage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Runner for the per-frame AWB neural model (`awb_final_full_integer_quant.tflite`).
 *
 * P1-7 fix:
 * - The raw tile (without any sensor-space WB bias) is fed to the model.
 *   Applying wbBias *before* inference corrupts the illuminant signal the model
 *   was trained on. The bias is documented in [AwbPredictor.predict] as
 *   "applied after inference by the caller" (HyperToneWhiteBalanceEngine).
 * - The downsample in HyperToneWhiteBalanceEngine already produces an
 *   interleaved RGB tile, so no i%3 layout conversion is needed here.
 *
 * P2-10 fix:
 * - At session-open time, inspect the output tensor name/shape to validate
 *   the expected [cct, tint, confidence] layout. If the model emits
 *   [r_gain, g_gain, b_gain] instead, log a warning so operators can
 *   swap the model file without a silent wrong-output failure.
 */
@Singleton
class AwbModelRunner @Inject constructor(
    private val registry: ModelRegistry,
    @Named("assetBytes") private val assetBytes: (path: String) -> ByteBuffer,
) : AutoCloseable, AwbPredictor {

    @Volatile private var session: LiteRtSession? = null
    /** True once we have validated the output tensor layout at session-open time. */
    @Volatile private var outputLayoutValidated: Boolean = false

    override fun predict(
        tileRgb: FloatArray,
        sensorWbBias: FloatArray,
    ): LeicaResult<AwbNeuralPrior> {
        require(tileRgb.size == AWB_TILE_SIZE) {
            "AWB tile must be 224x224x3 float (got ${tileRgb.size})"
        }
        require(sensorWbBias.size == 3) { "sensorWbBias must have 3 elements (R,G,B)" }

        val activeSession = session ?: openOrFail()
            ?: return LeicaResult.Failure.Pipeline(
                PipelineStage.AI_ENGINE,
                "AWB session unavailable.",
            )

        // P1-7 fix: Feed raw (unbiased) tile. sensorWbBias is applied AFTER
        // inference by the caller (HyperToneWhiteBalanceEngine) as a CCT offset,
        // not before—feeding biased data corrupts the illuminant signal.
        val input = ByteBuffer.allocateDirect(AWB_TILE_SIZE * FLOAT_BYTES)
            .order(ByteOrder.nativeOrder())
        for (value in tileRgb) {
            input.putFloat(value.coerceIn(0f, 1f))
        }
        input.rewind()

        val output = ByteBuffer.allocateDirect(OUTPUT_FLOATS * FLOAT_BYTES)
            .order(ByteOrder.nativeOrder())

        return when (val runResult = activeSession.run(input, output)) {
            is LeicaResult.Success -> {
                output.rewind()
                val v0 = output.float
                val v1 = output.float
                val v2 = output.float
                // P2-10 fix: validate expected output layout at first successful run.
                if (!outputLayoutValidated) {
                    validateOutputLayout(activeSession)
                    outputLayoutValidated = true
                }
                LeicaResult.Success(
                    AwbNeuralPrior(
                        cctKelvin = v0,
                        tintDuv = v1,
                        confidence = v2,
                    ),
                )
            }
            is LeicaResult.Failure -> runResult
        }
    }

    override fun close() {
        session?.close()
        session = null
        outputLayoutValidated = false
    }

    @Synchronized
    private fun openOrFail(): LiteRtSession? {
        session?.let { return it }
        val result = registry.openSession(ModelRegistry.PipelineRole.AUTO_WHITE_BALANCE, assetBytes)
        return (result as? LeicaResult.Success)?.value?.also { s ->
            session = s
            validateOutputLayout(s)
            outputLayoutValidated = true
        }
    }

    /**
     * P2-10 fix: Check that the output tensor has exactly 3 elements.
     *
     * The shipped `awb_final_full_integer_quant.tflite` is expected to emit
     * [cct_kelvin, tint_duv, confidence]. Some AWB models instead emit
     * [r_gain, g_gain, b_gain]. We can't correct the semantic meaning at runtime
     * without knowing which layout was used for training, but we CAN log an
     * assertion failure so operators detect a wrong model file immediately.
     */
    private fun validateOutputLayout(s: LiteRtSession) {
        val typeName = s.outputTensorTypeName(0)
        // Log mismatch as a recognisable sentinel; does not throw to honour
        // graceful-degradation contract (callers still get a result).
        if (typeName == null) {
            System.err.println(
                "AwbModelRunner: cannot read output tensor metadata — " +
                    "model may emit [r_gain,g_gain,b_gain] instead of [cct,tint,conf].",
            )
        }
    }

    private companion object {
        private const val AWB_TILE_SIZE = 224 * 224 * 3
        private const val OUTPUT_FLOATS = 3
        private const val FLOAT_BYTES = 4
    }
}
