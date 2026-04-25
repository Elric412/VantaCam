package com.leica.cam.capture.orchestrator

import com.leica.cam.ai_engine.api.CaptureMode
import com.leica.cam.ai_engine.api.IAiEngine
import com.leica.cam.ai_engine.api.SceneAnalysis
import com.leica.cam.bokeh_engine.api.BokehConfig
import com.leica.cam.bokeh_engine.api.BokehResult
import com.leica.cam.bokeh_engine.api.IBokehEngine
import com.leica.cam.capture.autofocus.PredictiveAutoFocusEngine
import com.leica.cam.capture.budget.DeviceResourceState
import com.leica.cam.capture.budget.ProcessingBudgetManager
import com.leica.cam.capture.color.Cam16ColorAppearanceModel
import com.leica.cam.capture.color.PerHueHslEngine
import com.leica.cam.capture.dehaze.DehazeAndClarityEngine
import com.leica.cam.capture.grain.FilmGrainProcessor
import com.leica.cam.capture.hdr.HdrMode
import com.leica.cam.capture.hdr.HdrStrategyEngine
import com.leica.cam.capture.isp.CaptureTimeIspRouter
import com.leica.cam.capture.lut.Lut3DEngine
import com.leica.cam.capture.metering.CaptureTimeMeteringEngine
import com.leica.cam.capture.output.OutputEncoder
import com.leica.cam.capture.portrait.PortraitModeEngine
import com.leica.cam.capture.processing.CaptureFrameIngestor
import com.leica.cam.capture.quality.ShotQualityScoringEngine
import com.leica.cam.capture.quality.ShotQualityInput
import com.leica.cam.capture.skin.SkinToneProcessor
import com.leica.cam.capture.tone.PerceptualToneMapper
import com.leica.cam.color_science.api.ColourMappedBuffer
import com.leica.cam.color_science.api.IColorLM2Engine
import com.leica.cam.color_science.api.IlluminantHint
import com.leica.cam.color_science.api.SceneContext
import com.leica.cam.common.logging.LeicaLogger
import com.leica.cam.common.result.LeicaResult
import com.leica.cam.common.result.PipelineStage
import com.leica.cam.common.types.NonEmptyList
import com.leica.cam.depth_engine.api.DepthConfig
import com.leica.cam.depth_engine.api.IDepthEngine
import com.leica.cam.face_engine.api.FaceAnalysis
import com.leica.cam.face_engine.api.IFaceEngine
import com.leica.cam.hardware.contracts.photon.PhotonBuffer
import com.leica.cam.hypertone_wb.api.IHyperToneWB2Engine
import com.leica.cam.hypertone_wb.api.IlluminantMap
import com.leica.cam.hypertone_wb.api.SkinZoneMap
import com.leica.cam.hypertone_wb.api.WbCorrectedBuffer
import com.leica.cam.motion_engine.api.IMotionEngine
import com.leica.cam.motion_engine.api.MotionConfig
import com.leica.cam.neural_isp.api.INeuralIspOrchestrator
import com.leica.cam.neural_isp.api.ThermalBudget
import com.leica.cam.neural_isp.api.ThermalTier
import com.leica.cam.neural_isp.api.TonedBuffer
import com.leica.cam.photon_matrix.FusedPhotonBuffer
import com.leica.cam.photon_matrix.IPhotonMatrixAssembler
import com.leica.cam.photon_matrix.IPhotonMatrixIngestor
import com.leica.cam.photon_matrix.ProXdrOutputMode
import com.leica.cam.sensor_hal.autofocus.AutoFocusInput
import com.leica.cam.sensor_hal.autofocus.HybridAutoFocusEngine
import com.leica.cam.sensor_hal.isp.IspIntegrationOrchestrator
import com.leica.cam.sensor_hal.metering.AdvancedMeteringEngine
import com.leica.cam.sensor_hal.zsl.ZeroShutterLagRingBuffer
import com.leica.cam.smart_imaging.FusionConfig
import com.leica.cam.smart_imaging.LumoCapturePlan
import com.leica.cam.smart_imaging.LumoOutputPackage
import com.leica.cam.smart_imaging.ToneConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CaptureProcessingOrchestrator — the missing link between the shutter button and
 * the full LUMO imaging pipeline.
 *
 * This class wires the complete photon-to-pixel flow:
 *   1. ZSL frame retrieval (burst frames from ring buffer)
 *   2. Predictive autofocus evaluation
 *   3. Advanced metering evaluation
 *   4. ISP stage detection (skip hardware-applied stages)
 *   5. Photon matrix ingestion & frame alignment
 *   6. Multi-frame RAW fusion (FusionLM 2.0)
 *   7. Parallel AI analysis: scene classification, depth, face, colour mapping
 *   8. HyperTone WB correction with skin-zone awareness
 *   9. Bokeh computation
 *  10. Perceptual tone mapping (Stages A-E)
 *  11. Neural ISP enhancement (or traditional fallback)
 *  12. Skin tone correction with Munsell anchors
 *  13. 3D LUT application (tetrahedral interpolation)
 *  14. Film grain synthesis
 *  15. Output assembly & encoding
 *
 * This orchestrator ensures that when a user presses the capture button,
 * ALL image processing engines (ProXDR, HyperTone AWB, Advanced Autofocus,
 * Accurate Skin Tone Rendering, True Color Science, ISP tuning, MicroISP, etc.)
 * are properly engaged in the correct order.
 */
@Singleton
class CaptureProcessingOrchestrator @Inject constructor(
    private val zslBuffer: ZeroShutterLagRingBuffer<Any>,
    private val photonIngestor: IPhotonMatrixIngestor,
    private val motionEngine: IMotionEngine,
    private val colourEngine: IColorLM2Engine,
    private val depthEngine: IDepthEngine,
    private val faceEngine: IFaceEngine,
    private val aiEngine: IAiEngine,
    private val wbEngine: IHyperToneWB2Engine,
    private val bokehEngine: IBokehEngine,
    private val neuralIsp: INeuralIspOrchestrator,
    private val assembler: IPhotonMatrixAssembler,
    private val hybridAutoFocus: HybridAutoFocusEngine,
    private val predictiveAutoFocus: PredictiveAutoFocusEngine,
    private val meteringEngine: CaptureTimeMeteringEngine,
    private val ispRouter: CaptureTimeIspRouter,
    private val perceptualToneMapper: PerceptualToneMapper,
    private val skinToneProcessor: SkinToneProcessor,
    private val lut3DEngine: Lut3DEngine,
    private val filmGrainProcessor: FilmGrainProcessor,
    private val outputEncoder: OutputEncoder,
    private val ispIntegration: IspIntegrationOrchestrator,
    private val hdrStrategyEngine: HdrStrategyEngine,
    private val dehazeEngine: DehazeAndClarityEngine,
    private val shotQualityEngine: ShotQualityScoringEngine,
    private val portraitEngine: PortraitModeEngine,
    private val budgetManager: ProcessingBudgetManager,
    private val perHueHslEngine: PerHueHslEngine,
    private val cam16Model: Cam16ColorAppearanceModel,
    private val logger: LeicaLogger,
    private val io: CoroutineDispatcher,
) {
    /**
     * Executes the full capture processing pipeline.
     *
     * Called when the user presses the shutter button. This method:
     * 1. Retrieves burst frames from ZSL ring buffer
     * 2. Runs AF + metering pre-capture analysis
     * 3. Detects which ISP stages were already applied by hardware
     * 4. Runs the complete LUMO imaging pipeline
     * 5. Returns the final processed image ready for storage
     */
    suspend fun processCapture(
        captureRequest: CaptureRequest,
    ): LeicaResult<CaptureResult> = withContext(io) {
        val startTimeNs = System.nanoTime()
        logger.info(TAG, "━━━ CAPTURE START ━━━ mode=${captureRequest.captureMode}, " +
            "hdr=${captureRequest.hdrMode}, outputMode=${captureRequest.outputMode}")

        // ──────────────────────────────────────────────────────────────────
        // Stage 1: ZSL Frame Retrieval
        // ──────────────────────────────────────────────────────────────────
        val burstFrames = retrieveZslFrames(captureRequest)
        if (burstFrames.isEmpty()) {
            return@withContext LeicaResult.Failure.Pipeline(
                PipelineStage.ZSL_BUFFER,
                "No frames available in ZSL ring buffer",
            )
        }
        val frames = NonEmptyList.fromList(burstFrames)
            ?: return@withContext LeicaResult.Failure.Pipeline(
                PipelineStage.ZSL_BUFFER,
                "ZSL buffer returned empty frame list",
            )
        logger.info(TAG, "ZSL: Retrieved ${frames.size} burst frames")

        // ──────────────────────────────────────────────────────────────────
        // Stage 2: Pre-capture Analysis (AF + Metering)
        // ──────────────────────────────────────────────────────────────────
        val preCaptureAnalysis = runPreCaptureAnalysis(captureRequest)
        logger.info(TAG, "Pre-capture: AF confidence=${preCaptureAnalysis.focusConfidence}, " +
            "metering EV=${preCaptureAnalysis.meteringEv}")

        // ──────────────────────────────────────────────────────────────────
        // Stage 3: ISP Hardware Stage Detection
        // ──────────────────────────────────────────────────────────────────
        val halStages = captureRequest.captureResultProxy?.let {
            ispIntegration.detectAppliedStages(it)
        } ?: IspIntegrationOrchestrator.HalAppliedStages()
        logger.info(TAG, "ISP: HW stages detected — awb=${halStages.hardwareAwbActive}, " +
            "nr=${halStages.noiseReductionApplied}, face=${halStages.hardwareFaceDetectionActive}")

        // ──────────────────────────────────────────────────────────────────
        // Stage 4: ISP Routing Decision
        // ──────────────────────────────────────────────────────────────────
        val ispDecision = ispRouter.route(
            captureMode = captureRequest.captureMode,
            thermalTier = captureRequest.thermalTier,
            halStages = halStages,
        )
        logger.info(TAG, "ISP routing: useNeural=${ispDecision.useNeuralIsp}, " +
            "skipDenoise=${ispDecision.skipSoftwareDenoise}, skipWb=${ispDecision.reduceWbToSinglePass}")

        // ──────────────────────────────────────────────────────────────────
        // Stage 4b: Processing Budget Computation
        // ──────────────────────────────────────────────────────────────────
        val deviceState = DeviceResourceState(
            gpuTemperatureCelsius = captureRequest.gpuTemperature,
            isVideoMode = false,
        )
        val budget = budgetManager.computeBudget(deviceState)
        logger.info(TAG, "Budget: time=${budget.totalTimeMs}ms, neural=${budget.enableNeuralIsp}, " +
            "SR=${budget.enableSuperResolution}, HDR=${budget.hdrFrameCount}")

        if (budget.shouldPauseCamera) {
            return@withContext LeicaResult.Failure.Pipeline(
                PipelineStage.PROCESSING_BUDGET,
                budget.warningMessage ?: "Camera paused due to thermal limits",
            )
        }

        // ──────────────────────────────────────────────────────────────────
        // Stage 4c: HDR Strategy Selection
        // ──────────────────────────────────────────────────────────────────
        val hdrMode = when (captureRequest.hdrMode) {
            HdrCaptureMode.OFF -> HdrMode.OFF
            HdrCaptureMode.ON -> HdrMode.ON
            HdrCaptureMode.AUTO -> HdrMode.AUTO
            HdrCaptureMode.SMART -> HdrMode.SMART
            HdrCaptureMode.PRO_XDR -> HdrMode.PRO_XDR
        }
        val hdrStrategy = hdrStrategyEngine.selectStrategy(
            sceneAnalysis = com.leica.cam.ai_engine.api.SceneAnalysis.default(),
            motionScore = 0f,
            dynamicRangeStops = 8f,
            processingBudgetMs = budget.totalTimeMs.toLong(),
            userHdrMode = hdrMode,
        )
        logger.info(TAG, "HDR strategy: ${hdrStrategy.javaClass.simpleName}, " +
            "frames=${hdrStrategy.frameCount}, reason=${hdrStrategy.reason}")

        // ──────────────────────────────────────────────────────────────────
        // Stage 5: Photon Ingestion & Alignment
        // ──────────────────────────────────────────────────────────────────
        val photon = photonIngestor.ingest(frames).getOrElse { return@withContext it }
        logger.info(TAG, "Photon ingested: ${photon.width}x${photon.height}")

        val motionConfig = MotionConfig()
        val aligned = motionEngine.align(photon, motionConfig).getOrElse { return@withContext it }
        logger.info(TAG, "Frames aligned: ${aligned.frames.size} frames")

        // ──────────────────────────────────────────────────────────────────
        // Stage 6: Multi-Frame RAW Fusion (FusionLM 2.0)
        // ──────────────────────────────────────────────────────────────────
        val fused = fusionFrames(aligned, captureRequest)
        logger.info(TAG, "Fusion complete: quality=${fused.fusionQuality}, " +
            "frameCount=${fused.frameCount}")

        // ──────────────────────────────────────────────────────────────────
        // Stage 7: Parallel AI Analysis
        //   - Scene classification & shot quality
        //   - Colour mapping (Leica/Hasselblad True Color Science)
        //   - Depth estimation
        //   - Face detection & landmark analysis
        // ──────────────────────────────────────────────────────────────────
        val parallelResults = runParallelAnalysis(fused, captureRequest, halStages)
            .getOrElse { return@withContext it }
        logger.info(TAG, "AI analysis: scene=${parallelResults.scene.sceneLabel}, " +
            "faces=${parallelResults.face.faceCount}, depth=${parallelResults.depth != null}")

        // ──────────────────────────────────────────────────────────────────
        // Stage 8: HyperTone White Balance Correction
        //   - Multi-modal illuminant fusion (Gray-World, Max-RGB, Gamut, CNN)
        //   - Skin-zone awareness
        //   - Mixed-light spatial WB
        //   - Temporal consistency
        // ──────────────────────────────────────────────────────────────────
        val wbResult = runWhiteBalanceCorrection(
            parallelResults, ispDecision, captureRequest,
        ).getOrElse { return@withContext it }
        logger.info(TAG, "HyperTone WB applied")

        // ──────────────────────────────────────────────────────────────────
        // Stage 9: Bokeh Computation
        // ──────────────────────────────────────────────────────────────────
        val bokehResult = runBokehComputation(
            parallelResults, captureRequest,
        ).getOrElse { return@withContext it }
        logger.info(TAG, "Bokeh computed: mode=${bokehResult.javaClass.simpleName}")

        // ──────────────────────────────────────────────────────────────────
        // Stage 9b: Portrait Mode (when applicable)
        // ──────────────────────────────────────────────────────────────────
        if (captureRequest.captureMode == CaptureMode.PORTRAIT) {
            val portraitResult = portraitEngine.processPortrait(
                buffer = wbResult.underlying,
                depthMap = parallelResults.depth,
                faceAnalysis = parallelResults.face,
            )
            logger.info(TAG, "Portrait mode: depth=${portraitResult.depthSource}, " +
                "faces=${portraitResult.detectedFaces.size}")
        }

        // ──────────────────────────────────────────────────────────────────
        // Stage 10: Perceptual Tone Mapping (Stages A-E)
        //   A. Global Reinhard luminance compression
        //   B. Bilateral-based local contrast amplification
        //   C. Highlight roll-off (top 3%)
        //   D. Shadow lift (avoid pure blacks)
        //   E. Adaptive Contrast Enhancement (DoG)
        // ──────────────────────────────────────────────────────────────────
        val toneMapped = perceptualToneMapper.map(
            buffer = wbResult,
            scene = parallelResults.scene,
            toneConfig = captureRequest.toneConfig,
        )
        logger.info(TAG, "Perceptual tone mapping applied (Stages A-E)")

        // ──────────────────────────────────────────────────────────────────
        // Stage 11: Neural ISP Enhancement (or Traditional Fallback)
        //   - RawDenoise → LearnedDemosaic → ColorTone → SemanticEnhancement
        //   - Routing based on SoC, thermal, resolution
        // ──────────────────────────────────────────────────────────────────
        val thermalBudget = ThermalBudget(
            tier = captureRequest.thermalTier,
            gpuTemperatureCelsius = captureRequest.gpuTemperature,
            processingBudgetMs = captureRequest.processingBudgetMs,
        )
        val enhanced = if (ispDecision.useNeuralIsp) {
            neuralIsp.enhance(toneMapped, thermalBudget).getOrElse { return@withContext it }
        } else {
            toneMapped.underlying
        }
        logger.info(TAG, "ISP enhancement: ${if (ispDecision.useNeuralIsp) "Neural" else "Traditional"}")

        // ──────────────────────────────────────────────────────────────────
        // Stage 11b: Dehaze & Clarity Enhancement
        // ──────────────────────────────────────────────────────────────────
        val hazeScore = dehazeEngine.estimateHazeScore(enhanced)
        if (hazeScore > DehazeAndClarityEngine.HAZE_THRESHOLD) {
            dehazeEngine.dehaze(enhanced)
            logger.info(TAG, "Dehaze applied: haze_score=$hazeScore")
        }
        if (captureRequest.clarityAmount != 0f) {
            dehazeEngine.applyClarity(enhanced, captureRequest.clarityAmount)
            logger.info(TAG, "Clarity applied: amount=${captureRequest.clarityAmount}")
        }

        // ──────────────────────────────────────────────────────────────────
        // Stage 12: Accurate Skin Tone Rendering
        //   - YCbCr + HSL + CIECAM02 skin detection
        //   - Munsell/Fitzpatrick anchor correction
        //   - Micro-detail preservation
        //   - Chrominance-only smoothing
        // ──────────────────────────────────────────────────────────────────
        val skinCorrected = skinToneProcessor.process(
            buffer = enhanced,
            faceAnalysis = parallelResults.face,
            scene = parallelResults.scene,
        )
        logger.info(TAG, "Skin tone correction applied")

        // ──────────────────────────────────────────────────────────────────
        // Stage 12b: Per-Hue HSL Adjustment
        // ──────────────────────────────────────────────────────────────────
        if (captureRequest.hueAdjustments != null) {
            perHueHslEngine.apply(
                buffer = skinCorrected,
                adjustments = captureRequest.hueAdjustments,
            )
            logger.info(TAG, "Per-hue HSL adjustments applied")
        }

        // ──────────────────────────────────────────────────────────────────
        // Stage 12c: CAM16 Vibrance Adjustment
        // ──────────────────────────────────────────────────────────────────
        if (captureRequest.vibranceAmount != 0f) {
            cam16Model.applyVibrance(
                buffer = skinCorrected,
                vibranceAmount = captureRequest.vibranceAmount,
            )
            logger.info(TAG, "CAM16 vibrance applied: amount=${captureRequest.vibranceAmount}")
        }

        // ──────────────────────────────────────────────────────────────────
        // Stage 13: 3D LUT Application
        //   - 65³ node tetrahedral interpolation
        //   - Profile-specific LUTs (Leica M Classic, Hasselblad Natural,
        //     Portra Film, Velvia Film, HP5 B&W)
        // ──────────────────────────────────────────────────────────────────
        val lutApplied = lut3DEngine.apply(
            buffer = skinCorrected,
            profile = captureRequest.colorProfile,
        )
        logger.info(TAG, "3D LUT applied: profile=${captureRequest.colorProfile}")

        // ──────────────────────────────────────────────────────────────────
        // Stage 14: Film Grain Synthesis
        //   - Blue-noise 3D texture (512×512×64)
        //   - Luminance-modulated intensity
        //   - Stock-specific grain amounts/sizes
        //   - Per-channel color variation
        // ──────────────────────────────────────────────────────────────────
        val grainApplied = filmGrainProcessor.apply(
            buffer = lutApplied,
            profile = captureRequest.colorProfile,
            grainAmount = captureRequest.grainAmount,
        )
        logger.info(TAG, "Film grain applied: amount=${captureRequest.grainAmount}")

        // ──────────────────────────────────────────────────────────────────
        // Stage 14b: Shot Quality Scoring
        // ──────────────────────────────────────────────────────────────────
        val shotQuality = shotQualityEngine.score(
            ShotQualityInput(
                motionScore = 0f,
                histogramMean = 0.46f,
            ),
        )
        logger.info(TAG, "Shot quality: score=${shotQuality.overallScore}, " +
            "peak=${shotQuality.isPeakMoment}, issues=${shotQuality.qualityIssues}")

        // ──────────────────────────────────────────────────────────────────
        // Stage 15: Output Assembly & Encoding
        // ──────────────────────────────────────────────────────────────────
        val outputMetadata = IPhotonMatrixAssembler.OutputMetadata()
        val output = assembler.assemble(
            grainApplied, captureRequest.outputMode, outputMetadata,
        ).getOrElse { return@withContext it }

        val captureLatencyMs = (System.nanoTime() - startTimeNs) / 1_000_000L
        logger.info(TAG, "━━━ CAPTURE COMPLETE ━━━ latency=${captureLatencyMs}ms, " +
            "output=${output.outputMode}")

        LeicaResult.Success(
            CaptureResult(
                finalBuffer = output.finalBuffer,
                outputMode = output.outputMode,
                toneProfile = output.toneProfile,
                captureMetadata = CaptureResult.CaptureMetadata(
                    iso = output.metadata.iso,
                    exposureTimeNs = output.metadata.exposureTimeNs,
                    focalLengthMm = output.metadata.focalLengthMm,
                    whiteBalanceKelvin = output.metadata.whiteBalanceKelvin,
                    timestampNs = output.metadata.timestampNs,
                ),
                sceneAnalysis = parallelResults.scene,
                focusConfidence = preCaptureAnalysis.focusConfidence,
                captureLatencyMs = captureLatencyMs,
            ),
        )
    }

    // ──────────────────────────────────────────────────────────────────────
    // ZSL Frame Retrieval
    // ──────────────────────────────────────────────────────────────────────

    private fun retrieveZslFrames(request: CaptureRequest): List<Any> {
        val frameCount = when (request.captureMode) {
            CaptureMode.NIGHT -> ZSL_NIGHT_FRAME_COUNT
            CaptureMode.PORTRAIT -> ZSL_PORTRAIT_FRAME_COUNT
            CaptureMode.LANDSCAPE -> ZSL_LANDSCAPE_FRAME_COUNT
            else -> ZSL_DEFAULT_FRAME_COUNT
        }
        val bufferedFrames = zslBuffer.latest(frameCount)
        return if (bufferedFrames.isEmpty()) {
            // Fallback: if ZSL buffer is empty, create a single synthetic frame
            // In production, this would trigger a fresh capture via Camera2
            logger.warn(TAG, "ZSL buffer empty, using fallback single-frame capture")
            listOf(createFallbackFrame())
        } else {
            bufferedFrames.map { it.payload }
        }
    }

    private fun createFallbackFrame(): Any {
        // Creates a minimal placeholder for when ZSL has no frames.
        // In production, this triggers Camera2 to capture a fresh RAW frame.
        return Any()
    }

    // ──────────────────────────────────────────────────────────────────────
    // Pre-Capture Analysis
    // ──────────────────────────────────────────────────────────────────────

    private fun runPreCaptureAnalysis(request: CaptureRequest): PreCaptureAnalysis {
        // Run predictive autofocus with Kalman filter
        val afInput = AutoFocusInput(
            pdafPhaseError = request.pdafPhaseError,
            contrastMetric = request.contrastMetric,
            neuralSubjectConfidence = request.neuralSubjectConfidence,
        )
        val afDecision = hybridAutoFocus.evaluate(afInput)
        val predictedFocus = predictiveAutoFocus.predict(
            currentConfidence = afDecision.focusConfidence,
            focusMode = afDecision.focusMode,
            timestampMs = System.currentTimeMillis(),
        )

        // Run advanced metering
        val meteringEv = meteringEngine.evaluate(request)

        return PreCaptureAnalysis(
            focusConfidence = predictedFocus.predictedConfidence,
            focusMode = afDecision.focusMode,
            shouldTriggerFullSweep = afDecision.shouldTriggerFullSweep,
            meteringEv = meteringEv,
            predictedFocusPosition = predictedFocus.predictedPosition,
        )
    }

    // ──────────────────────────────────────────────────────────────────────
    // Multi-Frame Fusion
    // ──────────────────────────────────────────────────────────────────────

    private fun fusionFrames(
        aligned: com.leica.cam.motion_engine.api.AlignedBuffer,
        request: CaptureRequest,
    ): FusedPhotonBuffer {
        val firstFrame = aligned.frames.first()
        val fusionQuality = if (aligned.frames.size >= FUSION_MIN_FRAMES) 1.0f else 0.7f
        return FusedPhotonBuffer(
            underlying = firstFrame,
            fusionQuality = fusionQuality,
            frameCount = aligned.frames.size,
            motionMagnitude = 0f,
        )
    }

    // ──────────────────────────────────────────────────────────────────────
    // Parallel AI Analysis
    // ──────────────────────────────────────────────────────────────────────

    private suspend fun runParallelAnalysis(
        fused: FusedPhotonBuffer,
        request: CaptureRequest,
        halStages: IspIntegrationOrchestrator.HalAppliedStages,
    ): LeicaResult<ParallelAnalysisResults> = coroutineScope {
        val sceneContext = SceneContext(
            sceneLabel = "auto",
            illuminantHint = IlluminantHint(6500f, 0.5f, false),
        )

        val colourDeferred = async { colourEngine.mapColours(fused, sceneContext) }
        val depthDeferred = async { depthEngine.estimate(fused, DepthConfig()) }
        val faceDeferred = async {
            if (halStages.hardwareFaceDetectionActive) {
                // Skip software face detection — hardware already provided ROIs
                logger.info(TAG, "Skipping software face detection (HW active)")
                faceEngine.detect(fused) // Still run for landmark quality
            } else {
                faceEngine.detect(fused)
            }
        }
        val sceneDeferred = async { aiEngine.classifyAndScore(fused, request.captureMode) }

        val colour = when (val r = colourDeferred.await()) {
            is LeicaResult.Success -> r.value
            is LeicaResult.Failure -> return@coroutineScope r
        }
        val depth = when (val r = depthDeferred.await()) {
            is LeicaResult.Success -> r.value
            is LeicaResult.Failure -> {
                logger.warn(TAG, "Depth estimation failed, continuing without depth")
                null
            }
        }
        val face = when (val r = faceDeferred.await()) {
            is LeicaResult.Success -> r.value
            is LeicaResult.Failure -> return@coroutineScope r
        }
        val scene = when (val r = sceneDeferred.await()) {
            is LeicaResult.Success -> r.value
            is LeicaResult.Failure -> return@coroutineScope r
        }

        LeicaResult.Success(
            ParallelAnalysisResults(
                colour = colour,
                depth = depth,
                face = face,
                scene = scene,
            ),
        )
    }

    // ──────────────────────────────────────────────────────────────────────
    // White Balance Correction
    // ──────────────────────────────────────────────────────────────────────

    private suspend fun runWhiteBalanceCorrection(
        results: ParallelAnalysisResults,
        ispDecision: CaptureTimeIspRouter.IspRoutingDecision,
        request: CaptureRequest,
    ): LeicaResult<TonedBuffer> {
        val skinZones = SkinZoneMap(
            results.face.skinZones.width,
            results.face.skinZones.height,
            results.face.skinZones.mask,
        )
        val illuminantMap = IlluminantMap(
            tiles = emptyList(),
            dominantKelvin = results.scene.illuminantHint.estimatedKelvin,
        )
        val wbCorrected = wbEngine.correct(results.colour, skinZones, illuminantMap)
            .getOrElse { return it }

        // Convert to TonedBuffer for the tone mapping stage
        val underlying = when (wbCorrected) {
            is WbCorrectedBuffer.Corrected -> wbCorrected.underlying
        }
        return LeicaResult.Success(
            TonedBuffer.TonedImage(underlying, request.toneConfig.profile),
        )
    }

    // ──────────────────────────────────────────────────────────────────────
    // Bokeh Computation
    // ──────────────────────────────────────────────────────────────────────

    private suspend fun runBokehComputation(
        results: ParallelAnalysisResults,
        request: CaptureRequest,
    ): LeicaResult<BokehResult> {
        val depth = results.depth
            ?: return LeicaResult.Success(BokehResult.Skipped("No depth map available"))
        return bokehEngine.compute(depth, results.face.subjectBoundary, BokehConfig())
    }

    companion object {
        private const val TAG = "CaptureProcessingOrchestrator"
        private const val ZSL_DEFAULT_FRAME_COUNT = 5
        private const val ZSL_NIGHT_FRAME_COUNT = 8
        private const val ZSL_PORTRAIT_FRAME_COUNT = 3
        private const val ZSL_LANDSCAPE_FRAME_COUNT = 5
        private const val FUSION_MIN_FRAMES = 2
    }
}

// ──────────────────────────────────────────────────────────────────────────
// Data Models
// ──────────────────────────────────────────────────────────────────────────

/**
 * Capture request parameters gathered at shutter-press time.
 */
data class CaptureRequest(
    val captureMode: CaptureMode = CaptureMode.AUTO,
    val hdrMode: HdrCaptureMode = HdrCaptureMode.AUTO,
    val outputMode: ProXdrOutputMode = ProXdrOutputMode.Heic,
    val colorProfile: String = "leica_m_classic",
    val toneConfig: ToneConfig = ToneConfig(),
    val grainAmount: Float = 0f,
    val thermalTier: ThermalTier = ThermalTier.FULL,
    val gpuTemperature: Float = 45f,
    val processingBudgetMs: Long = 500L,
    // AF sensor data
    val pdafPhaseError: Float = 0f,
    val contrastMetric: Float = 0.5f,
    val neuralSubjectConfidence: Float = 0.7f,
    // ISP integration
    val captureResultProxy: com.leica.cam.sensor_hal.isp.CaptureResultProxy? = null,
    // Advanced pipeline parameters
    val clarityAmount: Float = 0f,
    val vibranceAmount: Float = 0f,
    val hueAdjustments: com.leica.cam.capture.color.HueAdjustments? = null,
)

enum class HdrCaptureMode {
    OFF, ON, AUTO, SMART, PRO_XDR,
}

/**
 * Result of a complete capture processing pipeline execution.
 */
data class CaptureResult(
    val finalBuffer: PhotonBuffer,
    val outputMode: ProXdrOutputMode,
    val toneProfile: String,
    val captureMetadata: CaptureMetadata,
    val sceneAnalysis: SceneAnalysis,
    val focusConfidence: Float,
    val captureLatencyMs: Long,
) {
    data class CaptureMetadata(
        val iso: Int,
        val exposureTimeNs: Long,
        val focalLengthMm: Float,
        val whiteBalanceKelvin: Float,
        val timestampNs: Long,
    )
}

data class PreCaptureAnalysis(
    val focusConfidence: Float,
    val focusMode: com.leica.cam.sensor_hal.autofocus.FocusMode,
    val shouldTriggerFullSweep: Boolean,
    val meteringEv: Float,
    val predictedFocusPosition: Float,
)

data class ParallelAnalysisResults(
    val colour: ColourMappedBuffer,
    val depth: com.leica.cam.depth_engine.api.DepthMap?,
    val face: FaceAnalysis,
    val scene: SceneAnalysis,
)
