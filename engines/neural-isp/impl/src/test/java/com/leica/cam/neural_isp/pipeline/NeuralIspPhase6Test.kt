package com.leica.cam.neural_isp.pipeline

import com.leica.cam.ai_engine.pipeline.SceneType
import com.leica.cam.ai_engine.pipeline.SegmentationMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NeuralIspPhase6Test {
    @Test
    fun `phase 6 neural pipeline returns deterministic processor and bounded output`() {
        val processor = NeuralIspProcessor(
            stage0 = RawDenoiseStage(),
            stage1 = LearnedDemosaicStage(),
            stage2 = ColorToneStage(),
            stage3 = SemanticEnhancementStage(),
            clockMillis = deterministicClock(),
        )

        val request = createRequest(
            soc = "Snapdragon 8 Gen 3",
            budgetMs = 1_200,
            temperature = 57f,
            resolutionMp = 12f,
            fastMode = false,
            video = false,
        )

        val result = processor.process(request)

        assertEquals(IspProcessorType.NEURAL, result.processor)
        assertEquals(request.raw.width * request.raw.height, result.output.red.size)
        assertTrue(result.output.red.all { it in 0f..1f })
        assertTrue(result.output.green.all { it in 0f..1f })
        assertTrue(result.output.blue.all { it in 0f..1f })
    }

    @Test
    fun `routing selects neural isp on supported soc and thermal headroom`() {
        val router = IspRoutingProcessor(
            neuralProcessor = NeuralIspProcessor(
                stage0 = RawDenoiseStage(),
                stage1 = LearnedDemosaicStage(),
                stage2 = ColorToneStage(),
                stage3 = SemanticEnhancementStage(),
                clockMillis = deterministicClock(),
            ),
            traditionalProcessor = TraditionalIspProcessor(
                stage1 = LearnedDemosaicStage(),
                stage2 = ColorToneStage(),
                clockMillis = deterministicClock(),
            ),
        )

        val result = router.process(
            createRequest(
                soc = "Snapdragon 8 Gen 2",
                budgetMs = 1_100,
                temperature = 61f,
                resolutionMp = 24f,
                fastMode = false,
                video = false,
            ),
        )

        assertEquals(IspProcessorType.NEURAL, result.processor)
    }

    @Test
    fun `routing falls back to traditional on fast mode thermal or resolution constraints`() {
        val router = IspRoutingProcessor(
            neuralProcessor = NeuralIspProcessor(
                stage0 = RawDenoiseStage(),
                stage1 = LearnedDemosaicStage(),
                stage2 = ColorToneStage(),
                stage3 = SemanticEnhancementStage(),
                clockMillis = deterministicClock(),
            ),
            traditionalProcessor = TraditionalIspProcessor(
                stage1 = LearnedDemosaicStage(),
                stage2 = ColorToneStage(),
                clockMillis = deterministicClock(),
            ),
        )

        val fastModeResult = router.process(
            createRequest(
                soc = "Snapdragon 8 Gen 2",
                budgetMs = 1_200,
                temperature = 45f,
                resolutionMp = 12f,
                fastMode = true,
                video = false,
            ),
        )
        val thermalResult = router.process(
            createRequest(
                soc = "Snapdragon 8 Gen 3",
                budgetMs = 1_200,
                temperature = 78f,
                resolutionMp = 12f,
                fastMode = false,
                video = false,
            ),
        )
        val resolutionResult = router.process(
            createRequest(
                soc = "Dimensity 9300",
                budgetMs = 1_200,
                temperature = 58f,
                resolutionMp = 64f,
                fastMode = false,
                video = false,
            ),
        )

        assertEquals(IspProcessorType.TRADITIONAL, fastModeResult.processor)
        assertEquals(IspProcessorType.TRADITIONAL, thermalResult.processor)
        assertEquals(IspProcessorType.TRADITIONAL, resolutionResult.processor)
    }

    private fun createRequest(
        soc: String,
        budgetMs: Int,
        temperature: Float,
        resolutionMp: Float,
        fastMode: Boolean,
        video: Boolean,
    ): ImagePipelineRequest {
        val width = 12
        val height = 8
        val packedSize = (width / 2) * (height / 2)

        return ImagePipelineRequest(
            raw = RawBayerFrame(
                width = width,
                height = height,
                pattern = BayerPattern.RGGB,
                r = FloatArray(packedSize) { 0.42f + (it % 5) * 0.01f },
                gEven = FloatArray(packedSize) { 0.40f + (it % 4) * 0.01f },
                gOdd = FloatArray(packedSize) { 0.41f + (it % 3) * 0.01f },
                b = FloatArray(packedSize) { 0.39f + (it % 6) * 0.01f },
            ),
            noiseProfile = SensorNoiseProfile(a = 0.0009f, b = 0.00002f),
            sceneCctKelvin = 5_800,
            sceneType = SceneType.PORTRAIT,
            segmentationMask = SegmentationMask(
                width = width,
                height = height,
                labels = IntArray(width * height) { if (it % 11 == 0) 1 else 0 },
                confidence = FloatArray(width * height) { 0.85f },
            ),
            routing = IspRoutingContext(
                socModel = soc,
                thermal = ThermalStatus(gpuTemperatureCelsius = temperature, isThermalThrottled = false),
                processingBudgetMs = budgetMs,
                isVideoMode = video,
                isFastModeEnabled = fastMode,
                resolutionMegapixels = resolutionMp,
            ),
        )
    }

    private fun deterministicClock(): () -> Long {
        var tick = 1_000L
        return {
            tick += 5L
            tick
        }
    }
}
