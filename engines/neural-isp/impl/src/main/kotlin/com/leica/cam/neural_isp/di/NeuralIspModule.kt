package com.leica.cam.neural_isp.di

import com.leica.cam.neural_isp.pipeline.ColorToneStage
import com.leica.cam.neural_isp.pipeline.ImagePipelineProcessor
import com.leica.cam.neural_isp.pipeline.IspRoutingProcessor
import com.leica.cam.neural_isp.pipeline.LearnedDemosaicStage
import com.leica.cam.neural_isp.pipeline.NeuralIspOrchestratorImpl
import com.leica.cam.neural_isp.pipeline.NeuralIspProcessor
import com.leica.cam.neural_isp.pipeline.RawDenoiseStage
import com.leica.cam.neural_isp.pipeline.SemanticEnhancementStage
import com.leica.cam.neural_isp.pipeline.TraditionalIspProcessor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

/** Dependency entry points for the phase-6 neural ISP stack and routing facade. */
@Module
@InstallIn(SingletonComponent::class)
object NeuralIspModule {
    @Provides
    @Named("neural_isp_module")
    fun provideModuleName(): String = "neural-isp"

    @Provides
    @Singleton
    fun provideRawDenoiseStage(): RawDenoiseStage = RawDenoiseStage()

    @Provides
    @Singleton
    fun provideLearnedDemosaicStage(): LearnedDemosaicStage = LearnedDemosaicStage()

    @Provides
    @Singleton
    fun provideColorToneStage(): ColorToneStage = ColorToneStage()

    @Provides
    @Singleton
    fun provideSemanticEnhancementStage(): SemanticEnhancementStage = SemanticEnhancementStage()

    @Provides
    @Singleton
    @Named("neural_processor")
    fun provideNeuralProcessor(
        stage0: RawDenoiseStage,
        stage1: LearnedDemosaicStage,
        stage2: ColorToneStage,
        stage3: SemanticEnhancementStage,
    ): ImagePipelineProcessor = NeuralIspProcessor(stage0, stage1, stage2, stage3)

    @Provides
    @Singleton
    @Named("traditional_processor")
    fun provideTraditionalProcessor(
        stage1: LearnedDemosaicStage,
        stage2: ColorToneStage,
    ): ImagePipelineProcessor = TraditionalIspProcessor(stage1, stage2)

    @Provides
    @Singleton
    fun provideRoutingProcessor(
        @Named("neural_processor") neuralProcessor: ImagePipelineProcessor,
        @Named("traditional_processor") traditionalProcessor: ImagePipelineProcessor,
    ): ImagePipelineProcessor = IspRoutingProcessor(neuralProcessor, traditionalProcessor)

    @Provides
    @Singleton
    fun provideNeuralIspOrchestrator(
        impl: NeuralIspOrchestratorImpl,
    ): com.leica.cam.neural_isp.api.INeuralIspOrchestrator = impl
}
