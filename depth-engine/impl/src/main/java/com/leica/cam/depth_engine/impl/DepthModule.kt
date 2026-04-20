package com.leica.cam.depth_engine.impl

import com.leica.cam.depth_engine.api.DepthMap
import com.leica.cam.depth_engine.api.IDepthEngine
import com.leica.cam.depth_engine.api.DepthConfig
import com.leica.cam.common.result.LeicaResult
import com.leica.cam.photon_matrix.FusedPhotonBuffer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DepthEngineDependencyModule {
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
