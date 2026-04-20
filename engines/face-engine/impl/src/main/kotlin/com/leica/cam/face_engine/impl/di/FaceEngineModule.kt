package com.leica.cam.face_engine.impl.di

import com.leica.cam.face_engine.api.IFaceEngine
import com.leica.cam.face_engine.impl.ExpressionClassifier
import com.leica.cam.face_engine.impl.FaceEngine
import com.leica.cam.face_engine.impl.FaceMeshEngine
import com.leica.cam.face_engine.impl.SkinZoneMapper
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object FaceEngineModule {
    @Provides
    @Singleton
    fun provideFaceMeshEngine(): FaceMeshEngine = FaceMeshEngine()

    @Provides
    @Singleton
    fun provideSkinZoneMapper(): SkinZoneMapper = SkinZoneMapper()

    @Provides
    @Singleton
    fun provideExpressionClassifier(): ExpressionClassifier = ExpressionClassifier()

    @Provides
    @Singleton
    fun provideFaceEngine(
        meshEngine: FaceMeshEngine,
        skinZoneMapper: SkinZoneMapper,
        expressionClassifier: ExpressionClassifier,
    ): IFaceEngine = FaceEngine(meshEngine, skinZoneMapper, expressionClassifier)
}
