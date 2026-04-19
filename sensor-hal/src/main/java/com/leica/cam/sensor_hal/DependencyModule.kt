package com.leica.cam.sensor_hal

import com.leica.cam.sensor_hal.autofocus.HybridAutoFocusEngine
import com.leica.cam.sensor_hal.metering.AdvancedMeteringEngine
import com.leica.cam.sensor_hal.session.CameraSessionStateMachine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

/** Dependency entry point for `sensor-hal`. */
@Module
@InstallIn(SingletonComponent::class)
object SensorHalDependencyModule {
    @Provides
    @Named("sensor_hal_module")
    fun provideModuleName(): String = "sensor-hal"

    @Provides
    @Singleton
    fun provideCameraSessionStateMachine(): CameraSessionStateMachine = CameraSessionStateMachine()

    @Provides
    @Singleton
    fun provideAdvancedMeteringEngine(): AdvancedMeteringEngine =
        AdvancedMeteringEngine(aeCompensationRange = -12..12, aeCompensationStepEv = 0.3333f)

    @Provides
    @Singleton
    fun provideHybridAutoFocusEngine(): HybridAutoFocusEngine = HybridAutoFocusEngine()
}
