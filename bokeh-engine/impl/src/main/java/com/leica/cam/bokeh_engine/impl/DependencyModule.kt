package com.leica.cam.bokeh_engine.impl

import com.leica.cam.bokeh_engine.api.IBokehEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object BokehEngineDependencyModule {
    @Provides @Singleton fun provideSubjectSpaceComputer(): SubjectSpaceComputer = SubjectSpaceComputer()
    @Provides @Singleton fun provideSpatialReconstructionRenderer(): SpatialReconstructionRenderer = SpatialReconstructionRenderer()
    @Provides @Singleton fun provideLensFlare3dRestorer(): LensFlare3dRestorer = LensFlare3dRestorer()

    @Provides
    @Singleton
    fun provideBokehEngine(
        subjectSpaceComputer: SubjectSpaceComputer,
        spatialRenderer: SpatialReconstructionRenderer,
        lensFlareRestorer: LensFlare3dRestorer,
    ): IBokehEngine = BokehEngineOrchestrator(subjectSpaceComputer, spatialRenderer, lensFlareRestorer)
}
