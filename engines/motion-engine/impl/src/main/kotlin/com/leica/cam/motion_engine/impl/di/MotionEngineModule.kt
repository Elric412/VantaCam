package com.leica.cam.motion_engine.impl.di

import com.leica.cam.motion_engine.api.IMotionEngine
import com.leica.cam.motion_engine.impl.FusionQualityArbiter
import com.leica.cam.motion_engine.impl.LightningSnapArbiter
import com.leica.cam.motion_engine.impl.MotionDeblurEngine
import com.leica.cam.motion_engine.impl.MotionEngine
import com.leica.cam.motion_engine.impl.RawFrameAligner
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MotionEngineModule {
    @Provides @Singleton fun provideRawFrameAligner(): RawFrameAligner = RawFrameAligner()
    @Provides @Singleton fun provideFusionQualityArbiter(): FusionQualityArbiter = FusionQualityArbiter()
    @Provides @Singleton fun provideMotionDeblurEngine(): MotionDeblurEngine = MotionDeblurEngine()
    @Provides @Singleton fun provideLightningSnapArbiter(): LightningSnapArbiter = LightningSnapArbiter()

    @Provides
    @Singleton
    fun provideMotionEngine(
        aligner: RawFrameAligner,
        qualityArbiter: FusionQualityArbiter,
    ): IMotionEngine = MotionEngine(aligner, qualityArbiter)
}
