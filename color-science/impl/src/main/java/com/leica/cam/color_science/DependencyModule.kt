package com.leica.cam.color_science

import com.leica.cam.color_science.pipeline.ColorAccuracyBenchmark
import com.leica.cam.color_science.pipeline.ColorSciencePipeline
import com.leica.cam.color_science.pipeline.FilmGrainSynthesizer
import com.leica.cam.color_science.pipeline.PerHueHslEngine
import com.leica.cam.color_science.pipeline.SkinToneProtectionPipeline
import com.leica.cam.color_science.pipeline.TetrahedralLutEngine
import com.leica.cam.color_science.pipeline.ComputeBackend
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

/** Dependency entry point for `color-science`. */
@Module
@InstallIn(SingletonComponent::class)
object ColorScienceDependencyModule {
    @Provides
    @Named("color_science_module")
    fun provideModuleName(): String = "color-science"

    @Provides
    @Singleton
    fun provideTetrahedralLutEngine(): TetrahedralLutEngine =
        TetrahedralLutEngine(
            preferredBackend = ComputeBackend.VULKAN,
            fallbackBackend = ComputeBackend.RENDERSCRIPT,
        )

    @Provides
    @Singleton
    fun providePerHueHslEngine(): PerHueHslEngine = PerHueHslEngine()

    @Provides
    @Singleton
    fun provideSkinToneProtectionPipeline(): SkinToneProtectionPipeline = SkinToneProtectionPipeline()

    @Provides
    @Singleton
    fun provideFilmGrainSynthesizer(): FilmGrainSynthesizer = FilmGrainSynthesizer()

    @Provides
    @Singleton
    fun provideColorSciencePipeline(
        lutEngine: TetrahedralLutEngine,
        hueEngine: PerHueHslEngine,
        skinToneProtectionPipeline: SkinToneProtectionPipeline,
        grainSynthesizer: FilmGrainSynthesizer,
    ): ColorSciencePipeline =
        ColorSciencePipeline(
            lutEngine = lutEngine,
            hueEngine = hueEngine,
            skinPipeline = skinToneProtectionPipeline,
            grainSynthesizer = grainSynthesizer,
        )

    @Provides
    @Singleton
    fun provideColorAccuracyBenchmark(pipeline: ColorSciencePipeline): ColorAccuracyBenchmark =
        ColorAccuracyBenchmark(pipeline)
}
