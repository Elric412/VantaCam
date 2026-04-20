package com.leica.cam.imaging_pipeline.di

import com.leica.cam.imaging_pipeline.pipeline.AcesToneMapper
import com.leica.cam.imaging_pipeline.pipeline.CinematicSCurveEngine
import com.leica.cam.imaging_pipeline.pipeline.DurandBilateralToneMappingEngine
import com.leica.cam.imaging_pipeline.pipeline.FusionLM2Engine
import com.leica.cam.imaging_pipeline.pipeline.ImagingPipeline
import com.leica.cam.imaging_pipeline.pipeline.LuminositySharpener
import com.leica.cam.imaging_pipeline.pipeline.MultiScaleFrameAligner
import com.leica.cam.imaging_pipeline.pipeline.ShadowDenoiseEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt DI module for the imaging pipeline layer.
 *
 * Dimension 1 compliance: @Module in `di/` subpackage.
 * Provides all stateless engine instances used by [ImagingPipeline].
 *
 * All engines are singletons — they are pure and stateless, so sharing
 * a single instance across sessions is safe and memory-efficient.
 */
@Module
@InstallIn(SingletonComponent::class)
object ImagingPipelineModule {

    @Provides
    @Singleton
    fun provideFusionLM2Engine(): FusionLM2Engine = FusionLM2Engine()

    @Provides
    @Singleton
    fun provideMultiScaleFrameAligner(): MultiScaleFrameAligner = MultiScaleFrameAligner()

    @Provides
    @Singleton
    fun provideShadowDenoiseEngine(): ShadowDenoiseEngine = ShadowDenoiseEngine()

    @Provides
    @Singleton
    fun provideDurandToneMapping(): DurandBilateralToneMappingEngine =
        DurandBilateralToneMappingEngine()

    @Provides
    @Singleton
    fun provideCinematicSCurve(): CinematicSCurveEngine = CinematicSCurveEngine()

    @Provides
    @Singleton
    fun provideLuminositySharpener(): LuminositySharpener = LuminositySharpener()
}
