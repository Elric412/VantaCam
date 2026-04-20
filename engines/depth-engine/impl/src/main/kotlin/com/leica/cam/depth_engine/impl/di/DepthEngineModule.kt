package com.leica.cam.depth_engine.impl.di

import com.leica.cam.depth_engine.api.IDepthEngine
import com.leica.cam.depth_engine.impl.DepthAtlasLookup
import com.leica.cam.depth_engine.impl.DepthKalmanFilter
import com.leica.cam.depth_engine.impl.DepthSensingFusion
import com.leica.cam.depth_engine.impl.EdgeRefinementEngine
import com.leica.cam.depth_engine.impl.MonocularDepthEstimator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DepthEngineModule {
    @Provides
    @Singleton
    fun provideMonocularDepthEstimator(): MonocularDepthEstimator = MonocularDepthEstimator()

    @Provides
    @Singleton
    fun provideDepthKalmanFilter(): DepthKalmanFilter = DepthKalmanFilter()

    @Provides
    @Singleton
    fun provideEdgeRefinementEngine(): EdgeRefinementEngine = EdgeRefinementEngine()

    @Provides
    @Singleton
    fun provideDepthAtlasLookup(): DepthAtlasLookup = DepthAtlasLookup()

    @Provides
    @Singleton
    fun provideDepthSensingFusion(
        monocularEstimator: MonocularDepthEstimator,
        kalmanFilter: DepthKalmanFilter,
        edgeRefiner: EdgeRefinementEngine,
        atlasLookup: DepthAtlasLookup,
    ): IDepthEngine = DepthSensingFusion(monocularEstimator, kalmanFilter, edgeRefiner, atlasLookup)
}
