package com.leica.cam.sensor_hal.di

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
object SensorHalModule {
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

    @Provides
    @Singleton
    fun provideCameraSelector(): com.leica.cam.sensor_hal.session.CameraSelector =
        com.leica.cam.sensor_hal.session.DefaultCameraSelector()

    @Provides
    @Singleton
    fun provideCamera2CameraController(
        @dagger.hilt.android.qualifiers.ApplicationContext appContext: android.content.Context,
    ): com.leica.cam.sensor_hal.session.Camera2CameraController =
        com.leica.cam.sensor_hal.session.Camera2CameraController(appContext)

    @Provides
    @Singleton
    fun provideCameraController(
        impl: com.leica.cam.sensor_hal.session.Camera2CameraController,
    ): com.leica.cam.sensor_hal.session.CameraController = impl

    @Provides
    @Singleton
    fun provideCameraSessionManager(
        stateMachine: com.leica.cam.sensor_hal.session.CameraSessionStateMachine,
        controller: com.leica.cam.sensor_hal.session.CameraController,
        selector: com.leica.cam.sensor_hal.session.CameraSelector,
    ): com.leica.cam.sensor_hal.session.CameraSessionManager =
        com.leica.cam.sensor_hal.session.CameraSessionManager(stateMachine, controller, selector)
}
