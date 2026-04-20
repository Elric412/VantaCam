package com.leica.cam.imaging_pipeline

import com.leica.cam.imaging_pipeline.pipeline.AstrophotographyEngine
import com.leica.cam.imaging_pipeline.pipeline.CinemaVideoModeEngine
import com.leica.cam.imaging_pipeline.pipeline.ComputationalModesOrchestrator
import com.leica.cam.imaging_pipeline.pipeline.DngMetadataComposer
import com.leica.cam.imaging_pipeline.pipeline.HeicProfileSelector
import com.leica.cam.imaging_pipeline.pipeline.InMemoryPrivacyAuditLog
import com.leica.cam.imaging_pipeline.pipeline.PrivacyMetadataPolicy
import com.leica.cam.imaging_pipeline.pipeline.XmpMetadataComposer
import com.leica.cam.imaging_pipeline.pipeline.FfdNetNoiseReductionEngine
import com.leica.cam.imaging_pipeline.pipeline.FrameAlignmentEngine
import com.leica.cam.imaging_pipeline.pipeline.HdrMergeEngine
import com.leica.cam.imaging_pipeline.pipeline.ImagingPipelineOrchestrator
import com.leica.cam.imaging_pipeline.pipeline.MacroModeEngine
import com.leica.cam.imaging_pipeline.pipeline.NightModeEngine
import com.leica.cam.imaging_pipeline.pipeline.OisEisFusionStabilizer
import com.leica.cam.imaging_pipeline.pipeline.PerceptualToneMappingEngine
import com.leica.cam.imaging_pipeline.pipeline.PortraitModeEngine
import com.leica.cam.imaging_pipeline.pipeline.ProfessionalAudioPipeline
import com.leica.cam.imaging_pipeline.pipeline.PsfDeconvolutionSharpeningEngine
import com.leica.cam.imaging_pipeline.pipeline.RealTimeLutPreviewEngine
import com.leica.cam.imaging_pipeline.pipeline.SeamlessZoomEngine
import com.leica.cam.imaging_pipeline.pipeline.SuperResolutionEngine
import com.leica.cam.imaging_pipeline.pipeline.TimeLapseEngine
import com.leica.cam.imaging_pipeline.pipeline.VideoColorProfileEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

/** Dependency entry point for `imaging-pipeline`. */
@Module
@InstallIn(SingletonComponent::class)
object ImagingPipelineDependencyModule {
    @Provides
    @Named("imaging_pipeline_module")
    fun provideModuleName(): String = "imaging-pipeline"

    @Provides
    @Singleton
    fun provideFrameAlignmentEngine(): FrameAlignmentEngine = FrameAlignmentEngine()

    @Provides
    @Singleton
    fun provideHdrMergeEngine(): HdrMergeEngine = HdrMergeEngine()

    @Provides
    @Singleton
    fun providePerceptualToneMappingEngine(): PerceptualToneMappingEngine = PerceptualToneMappingEngine()

    @Provides
    @Singleton
    fun providePsfDeconvolutionSharpeningEngine(): PsfDeconvolutionSharpeningEngine =
        PsfDeconvolutionSharpeningEngine()

    @Provides
    @Singleton
    fun provideFfdNetNoiseReductionEngine(): FfdNetNoiseReductionEngine = FfdNetNoiseReductionEngine()

    @Provides
    @Singleton
    fun provideImagingPipelineOrchestrator(
        alignmentEngine: FrameAlignmentEngine,
        hdrMergeEngine: HdrMergeEngine,
        toneMappingEngine: PerceptualToneMappingEngine,
        sharpeningEngine: PsfDeconvolutionSharpeningEngine,
        denoisingEngine: FfdNetNoiseReductionEngine,
    ): ImagingPipelineOrchestrator =
        ImagingPipelineOrchestrator(
            alignmentEngine = alignmentEngine,
            hdrMergeEngine = hdrMergeEngine,
            toneMappingEngine = toneMappingEngine,
            sharpeningEngine = sharpeningEngine,
            denoisingEngine = denoisingEngine,
        )

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
    fun provideMacroModeEngine(superResolutionEngine: SuperResolutionEngine): MacroModeEngine =
        MacroModeEngine(superResolutionEngine)

    @Provides
    @Singleton
    fun provideNightModeEngine(denoisingEngine: FfdNetNoiseReductionEngine): NightModeEngine =
        NightModeEngine(denoisingEngine)

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
    fun providePrivacyMetadataPolicy(auditLog: InMemoryPrivacyAuditLog): PrivacyMetadataPolicy =
        PrivacyMetadataPolicy(auditLog)
}
