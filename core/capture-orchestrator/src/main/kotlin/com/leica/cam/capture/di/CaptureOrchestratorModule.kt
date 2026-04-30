package com.leica.cam.capture.di

import com.leica.cam.ai_engine.api.IAiEngine
import com.leica.cam.bokeh_engine.api.IBokehEngine
import com.leica.cam.capture.autofocus.PredictiveAutoFocusEngine
import com.leica.cam.capture.budget.ProcessingBudgetManager
import com.leica.cam.capture.color.Cam16ColorAppearanceModel
import com.leica.cam.capture.color.PerHueHslEngine
import com.leica.cam.capture.dehaze.DehazeAndClarityEngine
import com.leica.cam.capture.grain.FilmGrainProcessor
import com.leica.cam.capture.hdr.HdrStrategyEngine
import com.leica.cam.capture.isp.CaptureTimeIspRouter
import com.leica.cam.capture.lut.Lut3DEngine
import com.leica.cam.capture.metering.CaptureTimeMeteringEngine
import com.leica.cam.capture.orchestrator.CaptureProcessingOrchestrator
import com.leica.cam.capture.output.OutputEncoder
import com.leica.cam.capture.portrait.PortraitModeEngine
import com.leica.cam.capture.processing.CaptureFrameIngestor
import com.leica.cam.capture.quality.ShotQualityScoringEngine
import com.leica.cam.capture.skin.SkinToneProcessor
import com.leica.cam.capture.tone.PerceptualToneMapper
import com.leica.cam.color_science.api.IColorLM2Engine
import com.leica.cam.common.logging.LeicaLogger
import com.leica.cam.depth_engine.api.IDepthEngine
import com.leica.cam.face_engine.api.IFaceEngine
import com.leica.cam.hypertone_wb.api.IHyperToneWB2Engine
import com.leica.cam.hypertone_wb.pipeline.HyperToneWhiteBalanceEngine
import com.leica.cam.imaging_pipeline.hdr.ProXdrOrchestrator
import com.leica.cam.imaging_pipeline.pipeline.FusionLM2Engine
import com.leica.cam.imaging_pipeline.pipeline.ToneLM2Engine
import com.leica.cam.motion_engine.api.IMotionEngine
import com.leica.cam.neural_isp.api.INeuralIspOrchestrator
import com.leica.cam.photon_matrix.IPhotonMatrixAssembler
import com.leica.cam.photon_matrix.IPhotonMatrixIngestor
import com.leica.cam.sensor_hal.autofocus.HybridAutoFocusEngine
import com.leica.cam.sensor_hal.isp.IspIntegrationOrchestrator
import com.leica.cam.sensor_hal.soc.SoCProfile
import com.leica.cam.sensor_hal.zsl.ZeroShutterLagRingBuffer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Named
import javax.inject.Singleton

/**
 * Hilt DI module for the capture-orchestrator module.
 *
 * Provides all components required for the full capture processing pipeline:
 * - CaptureProcessingOrchestrator (main entry point)
 * - PredictiveAutoFocusEngine (Kalman-filtered AF)
 * - CaptureTimeMeteringEngine (5-mode metering)
 * - CaptureTimeIspRouter (hardware ISP detection + routing)
 * - PerceptualToneMapper (Stages A-E)
 * - SkinToneProcessor (Fitzpatrick anchor correction)
 * - Lut3DEngine (65³ tetrahedral interpolation)
 * - FilmGrainProcessor (blue-noise film grain)
 * - OutputEncoder (HEIC/DNG/HDR10/ProXDR)
 * - CaptureFrameIngestor (ZSL frame retrieval)
 * - ZeroShutterLagRingBuffer (burst frame retention)
 * - IspIntegrationOrchestrator (hardware ISP stage detection)
 * - HdrStrategyEngine (4-mode HDR strategy selection + variance-based merge)
 * - DehazeAndClarityEngine (DCP dehaze + mid-tone clarity)
 * - ShotQualityScoringEngine (6-component shot quality + burst ranking)
 * - PortraitModeEngine (physically-based bokeh + alpha matting)
 * - ProcessingBudgetManager (thermal-aware resource budgeting)
 * - PerHueHslEngine (8-range Gaussian-envelope HSL control)
 * - Cam16ColorAppearanceModel (CAM16 vibrance + saturation)
 */
@Module
@InstallIn(SingletonComponent::class)
object CaptureOrchestratorModule {

    @Provides
    @Named("capture_orchestrator_module")
    fun provideModuleName(): String = "capture-orchestrator"

    @Provides
    @Singleton
    fun provideZeroShutterLagRingBuffer(): ZeroShutterLagRingBuffer<Any> =
        ZeroShutterLagRingBuffer(capacity = ZSL_BUFFER_CAPACITY)

    @Provides
    @Singleton
    fun providePredictiveAutoFocusEngine(): PredictiveAutoFocusEngine =
        PredictiveAutoFocusEngine()

    @Provides
    @Singleton
    fun provideCaptureTimeMeteringEngine(): CaptureTimeMeteringEngine =
        CaptureTimeMeteringEngine()

    @Provides
    @Singleton
    fun provideCaptureTimeIspRouter(): CaptureTimeIspRouter =
        CaptureTimeIspRouter()

    @Provides
    @Singleton
    fun providePerceptualToneMapper(): PerceptualToneMapper =
        PerceptualToneMapper()

    @Provides
    @Singleton
    fun provideSkinToneProcessor(): SkinToneProcessor =
        SkinToneProcessor()

    @Provides
    @Singleton
    fun provideLut3DEngine(): Lut3DEngine =
        Lut3DEngine()

    @Provides
    @Singleton
    fun provideFilmGrainProcessor(): FilmGrainProcessor =
        FilmGrainProcessor()

    @Provides
    @Singleton
    fun provideOutputEncoder(): OutputEncoder =
        OutputEncoder()

    @Provides
    @Singleton
    fun provideCaptureFrameIngestor(
        logger: LeicaLogger,
    ): CaptureFrameIngestor =
        CaptureFrameIngestor(logger)

    @Provides
    @Singleton
    fun provideIspIntegrationOrchestrator(): IspIntegrationOrchestrator =
        IspIntegrationOrchestrator(
            socProfile = SoCProfile.detect(
                hardwareString = "mt",
                cpuInfo = "dimensity 9300",
            ),
        )

    @Provides
    @Singleton
    fun providePerHueHslEngine(): PerHueHslEngine =
        PerHueHslEngine()

    @Provides
    @Singleton
    fun provideCam16ColorAppearanceModel(): Cam16ColorAppearanceModel =
        Cam16ColorAppearanceModel()

    @Provides
    @Singleton
    fun provideHdrStrategyEngine(
        logger: LeicaLogger,
    ): HdrStrategyEngine =
        HdrStrategyEngine(logger)

    @Provides
    @Singleton
    fun provideDehazeAndClarityEngine(): DehazeAndClarityEngine =
        DehazeAndClarityEngine()

    @Provides
    @Singleton
    fun provideShotQualityScoringEngine(): ShotQualityScoringEngine =
        ShotQualityScoringEngine()

    @Provides
    @Singleton
    fun providePortraitModeEngine(
        logger: LeicaLogger,
    ): PortraitModeEngine =
        PortraitModeEngine(logger)

    @Provides
    @Singleton
    fun provideProcessingBudgetManager(
        logger: LeicaLogger,
    ): ProcessingBudgetManager =
        ProcessingBudgetManager(logger)

    @Provides
    @Named("capture_io_dispatcher")
    fun provideCaptureIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Singleton
    fun provideCaptureProcessingOrchestrator(
        zslBuffer: ZeroShutterLagRingBuffer<Any>,
        photonIngestor: IPhotonMatrixIngestor,
        motionEngine: IMotionEngine,
        colourEngine: IColorLM2Engine,
        depthEngine: IDepthEngine,
        faceEngine: IFaceEngine,
        aiEngine: IAiEngine,
        wbEngine: IHyperToneWB2Engine,
        bokehEngine: IBokehEngine,
        neuralIsp: INeuralIspOrchestrator,
        assembler: IPhotonMatrixAssembler,
        hybridAutoFocus: HybridAutoFocusEngine,
        predictiveAutoFocus: PredictiveAutoFocusEngine,
        meteringEngine: CaptureTimeMeteringEngine,
        ispRouter: CaptureTimeIspRouter,
        perceptualToneMapper: PerceptualToneMapper,
        skinToneProcessor: SkinToneProcessor,
        lut3DEngine: Lut3DEngine,
        filmGrainProcessor: FilmGrainProcessor,
        outputEncoder: OutputEncoder,
        ispIntegration: IspIntegrationOrchestrator,
        hdrStrategyEngine: HdrStrategyEngine,
        dehazeEngine: DehazeAndClarityEngine,
        shotQualityEngine: ShotQualityScoringEngine,
        portraitEngine: PortraitModeEngine,
        budgetManager: ProcessingBudgetManager,
        perHueHslEngine: PerHueHslEngine,
        cam16Model: Cam16ColorAppearanceModel,
        fusionEngine: FusionLM2Engine,
        toneEngine: ToneLM2Engine,
        proXdrOrchestrator: ProXdrOrchestrator,
        hyperToneWbEngine: HyperToneWhiteBalanceEngine,
        logger: LeicaLogger,
        @Named("capture_io_dispatcher") io: CoroutineDispatcher,
    ): CaptureProcessingOrchestrator =
        CaptureProcessingOrchestrator(
            zslBuffer = zslBuffer,
            photonIngestor = photonIngestor,
            motionEngine = motionEngine,
            colourEngine = colourEngine,
            depthEngine = depthEngine,
            faceEngine = faceEngine,
            aiEngine = aiEngine,
            wbEngine = wbEngine,
            bokehEngine = bokehEngine,
            neuralIsp = neuralIsp,
            assembler = assembler,
            hybridAutoFocus = hybridAutoFocus,
            predictiveAutoFocus = predictiveAutoFocus,
            meteringEngine = meteringEngine,
            ispRouter = ispRouter,
            perceptualToneMapper = perceptualToneMapper,
            skinToneProcessor = skinToneProcessor,
            lut3DEngine = lut3DEngine,
            filmGrainProcessor = filmGrainProcessor,
            outputEncoder = outputEncoder,
            ispIntegration = ispIntegration,
            hdrStrategyEngine = hdrStrategyEngine,
            dehazeEngine = dehazeEngine,
            shotQualityEngine = shotQualityEngine,
            portraitEngine = portraitEngine,
            budgetManager = budgetManager,
            perHueHslEngine = perHueHslEngine,
            cam16Model = cam16Model,
            fusionEngine = fusionEngine,
            toneEngine = toneEngine,
            proXdrOrchestrator = proXdrOrchestrator,
            hyperToneWbEngine = hyperToneWbEngine,
            logger = logger,
            io = io,
        )

    private const val ZSL_BUFFER_CAPACITY = 15
}
