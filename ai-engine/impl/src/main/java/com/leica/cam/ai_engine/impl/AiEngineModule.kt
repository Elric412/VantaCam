package com.leica.cam.ai_engine.impl

import com.leica.cam.ai_engine.api.IAiEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AiEngineDependencyModule {
    @Provides @Singleton fun provideSceneClassifier(): SceneClassifier = SceneClassifier()
    @Provides @Singleton fun provideShotQualityEngine(): ShotQualityEngine = ShotQualityEngine()
    @Provides @Singleton fun provideObjectTrackingEngine(): ObjectTrackingEngine = ObjectTrackingEngine()
    @Provides @Singleton fun provideAiModelManager(): AiModelManager = AiModelManager()

    @Provides
    @Singleton
    fun provideAiEngine(
        sceneClassifier: SceneClassifier,
        qualityEngine: ShotQualityEngine,
        objectTracker: ObjectTrackingEngine,
        modelManager: AiModelManager,
    ): IAiEngine = AiEngineOrchestrator(sceneClassifier, qualityEngine, objectTracker, modelManager)
}
