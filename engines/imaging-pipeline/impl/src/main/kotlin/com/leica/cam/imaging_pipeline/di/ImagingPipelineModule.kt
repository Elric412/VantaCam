package com.leica.cam.imaging_pipeline.di

import com.leica.cam.ai_engine.api.NeuralIspRefiner
import com.leica.cam.ai_engine.api.SemanticSegmenter
import com.leica.cam.imaging_pipeline.hdr.ProXdrOrchestrator
import com.leica.cam.imaging_pipeline.pipeline.AstrophotographyEngine
import com.leica.cam.imaging_pipeline.pipeline.CinemaVideoModeEngine
import com.leica.cam.imaging_pipeline.pipeline.CinematicSCurveEngine
import com.leica.cam.imaging_pipeline.pipeline.ComputationalModesOrchestrator
import com.leica.cam.imaging_pipeline.pipeline.DngMetadataComposer
import com.leica.cam.imaging_pipeline.pipeline.DurandBilateralToneMappingEngine
import com.leica.cam.imaging_pipeline.pipeline.FfdNetNoiseReductionEngine
import com.leica.cam.imaging_pipeline.pipeline.HeicProfileSelector
import com.leica.cam.imaging_pipeline.pipeline.ImagingPipeline
import com.leica.cam.imaging_pipeline.pipeline.ImagingPipelineOrchestrator
import com.leica.cam.imaging_pipeline.pipeline.InMemoryPrivacyAuditLog
import com.leica.cam.imaging_pipeline.pipeline.LuminositySharpener
import com.leica.cam.imaging_pipeline.pipeline.MacroModeEngine
import com.leica.cam.imaging_pipeline.pipeline.NightModeEngine
import com.leica.cam.imaging_pipeline.pipeline.OisEisFusionStabilizer
import com.leica.cam.imaging_pipeline.pipeline.PortraitModeEngine
import com.leica.cam.imaging_pipeline.pipeline.PrivacyMetadataPolicy
import com.leica.cam.imaging_pipeline.pipeline.ProfessionalAudioPipeline
import com.leica.cam.imaging_pipeline.pipeline.RealTimeLutPreviewEngine
import com.leica.cam.imaging_pipeline.pipeline.SeamlessZoomEngine
import com.leica.cam.imaging_pipeline.pipeline.ShadowDenoiseEngine
import com.leica.cam.imaging_pipeline.pipeline.SuperResolutionEngine
import com.leica.cam.imaging_pipeline.pipeline.TimeLapseEngine
import com.leica.cam.imaging_pipeline.pipeline.VideoColorProfileEngine
import com.leica.cam.imaging_pipeline.pipeline.XmpMetadataComposer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ImagingPipelineModule {
    @Provides
    @Named("imaging_pipeline_module")
    fun provideModuleName(): String = "imaging-pipeline"

    @Provides
    @Singleton
    fun provideProXdrOrchestrator(): ProXdrOrchestrator = ProXdrOrchestrator()

    @Provides
    @Singleton
    fun provideDurandBilateralToneMappingEngine(): DurandBilateralToneMappingEngine =
        DurandBilateralToneMappingEngine()

    @Provides
    @Singleton
    fun provideCinematicSCurveEngine(): CinematicSCurveEngine = CinematicSCurveEngine()

    @Provides
    @Singleton
    fun provideShadowDenoiseEngine(): ShadowDenoiseEngine = ShadowDenoiseEngine()

    @Provides
    @Singleton
    fun provideLuminositySharpener(): LuminositySharpener = LuminositySharpener()

    @Provides
    @Singleton
    fun provideFfdNetNoiseReductionEngine(
        shadowDenoiseEngine: ShadowDenoiseEngine,
    ): FfdNetNoiseReductionEngine = FfdNetNoiseReductionEngine(shadowDenoiseEngine)

    @Provides
    @Singleton
    fun provideImagingPipeline(
        proXdrOrchestrator: ProXdrOrchestrator,
        toneMappingEngine: DurandBilateralToneMappingEngine,
        sCurveEngine: CinematicSCurveEngine,
        shadowDenoiser: ShadowDenoiseEngine,
        luminositySharpener: LuminositySharpener,
        neuralIspRefiner: NeuralIspRefiner,
        semanticSegmenter: SemanticSegmenter,
    ): ImagingPipeline = ImagingPipeline(
        proXdrOrchestrator = proXdrOrchestrator,
        toneMappingEngine = toneMappingEngine,
        sCurveEngine = sCurveEngine,
        shadowDenoiser = shadowDenoiser,
        luminositySharpener = luminositySharpener,
        microIspRefiner = neuralIspRefiner,
        semanticSegmenter = semanticSegmenter,
    )

    @Provides
    @Singleton
    fun provideImagingPipelineOrchestrator(
        pipeline: ImagingPipeline,
    ): ImagingPipelineOrchestrator = ImagingPipelineOrchestrator(pipeline)

    @Provides
    @Singleton
    fun providePortraitModeEngine(): PortraitModeEngine = PortraitModeEngine()

    @Provides
    @Singleton
    fun provideAstrophotographyEngine(): AstrophotographyEngine = AstrophotographyEngine()

    @Provides
    @Singleton
    fun provideSuperResolutionEngine(): SuperResolutionEngine = SuperResolutionEngine()

    @Provides
    @Singleton
    fun provideMacroModeEngine(
        superResolutionEngine: SuperResolutionEngine,
    ): MacroModeEngine = MacroModeEngine(superResolutionEngine)

    @Provides
    @Singleton
    fun provideNightModeEngine(
        denoisingEngine: FfdNetNoiseReductionEngine,
    ): NightModeEngine = NightModeEngine(denoisingEngine)

    @Provides
    @Singleton
    fun provideSeamlessZoomEngine(): SeamlessZoomEngine = SeamlessZoomEngine()

    @Provides
    @Singleton
    fun provideComputationalModesOrchestrator(
        portraitModeEngine: PortraitModeEngine,
        astrophotographyEngine: AstrophotographyEngine,
        macroModeEngine: MacroModeEngine,
        nightModeEngine: NightModeEngine,
        seamlessZoomEngine: SeamlessZoomEngine,
        superResolutionEngine: SuperResolutionEngine,
    ): ComputationalModesOrchestrator =
        ComputationalModesOrchestrator(
            portraitModeEngine = portraitModeEngine,
            astrophotographyEngine = astrophotographyEngine,
            macroModeEngine = macroModeEngine,
            nightModeEngine = nightModeEngine,
            seamlessZoomEngine = seamlessZoomEngine,
            superResolutionEngine = superResolutionEngine,
        )

    @Provides
    @Singleton
    fun provideOisEisFusionStabilizer(): OisEisFusionStabilizer = OisEisFusionStabilizer()

    @Provides
    @Singleton
    fun provideVideoColorProfileEngine(): VideoColorProfileEngine = VideoColorProfileEngine()

    @Provides
    @Singleton
    fun provideProfessionalAudioPipeline(): ProfessionalAudioPipeline = ProfessionalAudioPipeline()

    @Provides
    @Singleton
    fun provideRealTimeLutPreviewEngine(): RealTimeLutPreviewEngine = RealTimeLutPreviewEngine()

    @Provides
    @Singleton
    fun provideTimeLapseEngine(): TimeLapseEngine = TimeLapseEngine()

    @Provides
    @Singleton
    fun provideCinemaVideoModeEngine(): CinemaVideoModeEngine = CinemaVideoModeEngine()

    @Provides
    @Singleton
    fun provideDngMetadataComposer(): DngMetadataComposer = DngMetadataComposer()

    @Provides
    @Singleton
    fun provideHeicProfileSelector(): HeicProfileSelector = HeicProfileSelector()

    @Provides
    @Singleton
    fun provideXmpMetadataComposer(): XmpMetadataComposer = XmpMetadataComposer()

    @Provides
    @Singleton
    fun providePrivacyAuditLog(): InMemoryPrivacyAuditLog = InMemoryPrivacyAuditLog()

    @Provides
    @Singleton
    fun providePrivacyMetadataPolicy(
        auditLog: InMemoryPrivacyAuditLog,
    ): PrivacyMetadataPolicy = PrivacyMetadataPolicy(auditLog)
}
