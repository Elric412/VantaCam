package com.leica.cam.ai_engine.impl.di

import android.content.Context
import com.leica.cam.ai_engine.api.IAiEngine
import com.leica.cam.ai_engine.impl.models.AwbModelRunner
import com.leica.cam.ai_engine.impl.models.FaceLandmarkerRunner
import com.leica.cam.ai_engine.impl.models.MicroIspRunner
import com.leica.cam.ai_engine.impl.models.SceneClassifierRunner
import com.leica.cam.ai_engine.impl.models.SemanticSegmenterRunner
import com.leica.cam.ai_engine.impl.pipeline.AiEngineOrchestrator
import com.leica.cam.ai_engine.impl.registry.ModelRegistry
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AiEngineModule {
    @Provides
    @Singleton
    @Named("modelDir")
    fun provideModelDirectory(): File = File("models")

    @Provides
    @Singleton
    fun provideModelRegistry(
        @Named("modelDir") modelDir: File,
    ): ModelRegistry {
        val registry = ModelRegistry(
            modelDir = modelDir,
            logger = { level, tag, message ->
                android.util.Log.println(
                    when (level) {
                        ModelRegistry.LogLevel.DEBUG -> android.util.Log.DEBUG
                        ModelRegistry.LogLevel.INFO -> android.util.Log.INFO
                        ModelRegistry.LogLevel.WARN -> android.util.Log.WARN
                        ModelRegistry.LogLevel.ERROR -> android.util.Log.ERROR
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
    ): (String) -> ByteBuffer = { path ->
        context.assets.open(path).use { stream ->
            val bytes = stream.readBytes()
            ByteBuffer.allocateDirect(bytes.size)
                .order(ByteOrder.nativeOrder())
                .put(bytes)
                .apply { rewind() }
        }
    }

    @Provides
    @Singleton
    fun provideAiEngine(orchestrator: AiEngineOrchestrator): IAiEngine = orchestrator

    @Provides
    @Singleton
    fun provideAwbModelRunner(
        registry: ModelRegistry,
        @Named("assetBytes") assetBytes: (String) -> ByteBuffer,
    ): AwbModelRunner = AwbModelRunner(registry, assetBytes)

    @Provides
    @Singleton
    fun provideFaceLandmarkerRunner(
        @ApplicationContext context: Context,
    ): FaceLandmarkerRunner = FaceLandmarkerRunner(context)

    @Provides
    @Singleton
    fun provideSceneClassifierRunner(
        registry: ModelRegistry,
        @Named("assetBytes") assetBytes: (String) -> ByteBuffer,
    ): SceneClassifierRunner = SceneClassifierRunner(registry, assetBytes)

    @Provides
    @Singleton
    fun provideSemanticSegmenterRunner(
        registry: ModelRegistry,
        @Named("assetBytes") assetBytes: (String) -> ByteBuffer,
    ): SemanticSegmenterRunner = SemanticSegmenterRunner(registry, assetBytes)

    @Provides
    @Singleton
    fun provideMicroIspRunner(
        registry: ModelRegistry,
        @Named("assetBytes") assetBytes: (String) -> ByteBuffer,
    ): MicroIspRunner = MicroIspRunner(registry, assetBytes)
}
