package com.leica.cam.ai_engine.impl.di

import android.content.Context
import android.util.Log
import com.leica.cam.ai_engine.api.AwbPredictor
import com.leica.cam.ai_engine.api.FaceLandmarker
import com.leica.cam.ai_engine.api.IAiEngine
import com.leica.cam.ai_engine.api.NeuralIspRefiner
import com.leica.cam.ai_engine.api.SceneClassifier
import com.leica.cam.ai_engine.api.SemanticSegmenter
import com.leica.cam.ai_engine.impl.models.FaceLandmarkerRunner
import com.leica.cam.ai_engine.impl.models.MicroIspRunner
import com.leica.cam.ai_engine.impl.models.SceneClassifierRunner
import com.leica.cam.ai_engine.impl.models.SemanticSegmenterRunner
import com.leica.cam.ai_engine.impl.models.AwbModelRunner
import com.leica.cam.ai_engine.impl.pipeline.AiEngineOrchestrator
import com.leica.cam.ai_engine.impl.registry.ModelRegistry
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AiEngineModule {
    @Provides
    @Singleton
    fun provideModelRegistry(
        @ApplicationContext context: Context,
    ): ModelRegistry {
        val registry = ModelRegistry.fromAssets(
            assetManager = context.assets,
            logger = { level, tag, message ->
                Log.println(
                    when (level) {
                        ModelRegistry.LogLevel.DEBUG -> Log.DEBUG
                        ModelRegistry.LogLevel.INFO -> Log.INFO
                        ModelRegistry.LogLevel.WARN -> Log.WARN
                        ModelRegistry.LogLevel.ERROR -> Log.ERROR
                    },
                    tag,
                    message,
                )
            },
        )
        registry.logCatalogue()
        return registry
    }

    @Provides
    @Singleton
    @Named("assetBytes")
    fun provideAssetBytesLoader(
        @ApplicationContext context: Context,
    ): @JvmSuppressWildcards Function1<String, ByteBuffer> = { path ->
        context.assets.open(path).use { stream ->
            val bytes = stream.readBytes()
            ByteBuffer.allocateDirect(bytes.size)
                .order(ByteOrder.nativeOrder())
                .apply {
                    put(bytes)
                    rewind()
                }
        }
    }

    @Provides
    @Singleton
    fun provideAiEngine(orchestrator: AiEngineOrchestrator): IAiEngine = orchestrator

    @Provides
    @Singleton
    fun provideAwbPredictor(runner: AwbModelRunner): AwbPredictor = runner

    @Provides
    @Singleton
    fun provideNeuralIspRefiner(runner: MicroIspRunner): NeuralIspRefiner = runner

    @Provides
    @Singleton
    fun provideSemanticSegmenter(runner: SemanticSegmenterRunner): SemanticSegmenter = runner

    @Provides
    @Singleton
    fun provideSceneClassifier(runner: SceneClassifierRunner): SceneClassifier = runner

    @Provides
    @Singleton
    fun provideFaceLandmarker(runner: FaceLandmarkerRunner): FaceLandmarker = runner
}
