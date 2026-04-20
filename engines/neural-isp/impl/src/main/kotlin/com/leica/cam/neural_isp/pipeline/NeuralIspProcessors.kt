package com.leica.cam.neural_isp.pipeline

import com.leica.cam.common.Logger

private const val MIN_NEURAL_BUDGET_MS = 800
private const val MAX_NEURAL_RESOLUTION_MP = 50f
private const val MAX_GPU_TEMP_C = 75f

/** Full phase-6 neural ISP processor chaining all four neural stages. */
class NeuralIspProcessor(
    private val stage0: RawDenoiseStage,
    private val stage1: LearnedDemosaicStage,
    private val stage2: ColorToneStage,
    private val stage3: SemanticEnhancementStage,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) : ImagePipelineProcessor {
    override fun process(request: ImagePipelineRequest): ImagePipelineResult {
        val start = clockMillis()
        val denoisedRaw = stage0.run(request.raw, request.noiseProfile)
        val linearRgb = stage1.run(denoisedRaw)
        val colorMapped = stage2.run(linearRgb, request.sceneCctKelvin, request.sceneType)
        val enhanced = stage3.run(colorMapped, request.segmentationMask)
        return ImagePipelineResult(
            output = enhanced,
            processor = IspProcessorType.NEURAL,
            latencyMs = (clockMillis() - start).coerceAtLeast(0L),
        )
    }
}

/** Traditional deterministic ISP fallback for thermal, latency, or mode constraints. */
class TraditionalIspProcessor(
    private val stage1: LearnedDemosaicStage,
    private val stage2: ColorToneStage,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) : ImagePipelineProcessor {
    override fun process(request: ImagePipelineRequest): ImagePipelineResult {
        val start = clockMillis()
        val demosaiced = stage1.run(request.raw)
        val mapped = stage2.run(demosaiced, request.sceneCctKelvin, request.sceneType)
        return ImagePipelineResult(
            output = mapped,
            processor = IspProcessorType.TRADITIONAL,
            latencyMs = (clockMillis() - start).coerceAtLeast(0L),
        )
    }
}

/** Routing layer that transparently chooses neural ISP or traditional fallback. */
class IspRoutingProcessor(
    private val neuralProcessor: ImagePipelineProcessor,
    private val traditionalProcessor: ImagePipelineProcessor,
) : ImagePipelineProcessor {
    override fun process(request: ImagePipelineRequest): ImagePipelineResult {
        val shouldUseNeural = shouldUseNeuralIsp(request.routing)
        val processor = if (shouldUseNeural) neuralProcessor else traditionalProcessor
        val result = processor.process(request)
        runCatching {
            Logger.d(
                TAG,
                "Routed ISP to ${result.processor} for soc=${request.routing.socModel}, " +
                    "temp=${request.routing.thermal.gpuTemperatureCelsius}, budgetMs=${request.routing.processingBudgetMs}",
            )
        }
        return result
    }

    private fun shouldUseNeuralIsp(routing: IspRoutingContext): Boolean {
        if (routing.isFastModeEnabled) return false
        if (routing.isVideoMode) return false
        if (routing.thermal.isThermalThrottled) return false
        if (routing.thermal.gpuTemperatureCelsius >= MAX_GPU_TEMP_C) return false
        if (routing.processingBudgetMs <= MIN_NEURAL_BUDGET_MS) return false
        if (routing.resolutionMegapixels > MAX_NEURAL_RESOLUTION_MP) return false
        return isSupportedSoc(routing.socModel)
    }

    private fun isSupportedSoc(model: String): Boolean {
        val normalized = model.lowercase()
        return normalized.contains("snapdragon 8 gen 2") ||
            normalized.contains("snapdragon 8 gen 3") ||
            normalized.contains("snapdragon 8 elite") ||
            normalized.contains("dimensity 9200") ||
            normalized.contains("dimensity 9300")
    }

    private companion object {
        private const val TAG = "IspRoutingProcessor"
    }
}
