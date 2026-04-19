package com.leica.cam.ai_engine.impl.di

import com.leica.cam.ai_engine.impl.registry.ModelRegistry
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Named
import javax.inject.Singleton

/**
 * Hilt DI module for the AI engine layer.
 *
 * Dimension 1 compliance: @Module in `di/` subpackage.
 * Provides [ModelRegistry] with the device's model directory.
 */
@Module
@InstallIn(SingletonComponent::class)
object AiEngineModule {

    /**
     * Path to the model directory on the device.
     * In production: `/data/local/tmp/leica_models/` or app-internal assets.
     * During development: loaded from the `Model/` project directory.
     */
    @Provides
    @Singleton
    @Named("modelDir")
    fun provideModelDirectory(): File {
        // Default model directory — overridden by build variant or test DI
        return File("/data/local/tmp/leica_models")
    }

    @Provides
    @Singleton
    fun provideModelRegistry(
        @Named("modelDir") modelDir: File,
    ): ModelRegistry {
        val registry = ModelRegistry(
            modelDir = modelDir,
            logger = { level, tag, message ->
                // Route to Android Log in production
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
        // Log full catalogue at startup
        registry.logCatalogue()
        return registry
    }
}
