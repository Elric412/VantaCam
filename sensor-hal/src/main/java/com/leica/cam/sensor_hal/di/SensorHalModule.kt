package com.leica.cam.sensor_hal.di

import com.leica.cam.sensor_hal.isp.IspIntegrationOrchestrator
import com.leica.cam.sensor_hal.sensor.profiles.SensorProfileRegistry
import com.leica.cam.sensor_hal.soc.SoCProfile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt DI module for sensor-hal layer.
 *
 * Dimension 1 compliance: all @Module files live in `di/` subpackages.
 * This module provides singleton instances of:
 * - [SensorProfileRegistry]: 8-sensor profile catalogue
 * - [SoCProfile]: runtime SoC detection and compute routing
 * - [IspIntegrationOrchestrator]: hardware ISP stage detection
 */
@Module
@InstallIn(SingletonComponent::class)
object SensorHalModule {

    @Provides
    @Singleton
    fun provideSensorProfileRegistry(): SensorProfileRegistry = SensorProfileRegistry()

    @Provides
    @Singleton
    fun provideSoCProfile(): SoCProfile {
        // In production, read from android.os.Build.HARDWARE and /proc/cpuinfo
        // For now, default to MediaTek Dimensity (this device's SoC)
        return SoCProfile.detect(
            hardwareString = android.os.Build.HARDWARE,
        )
    }

    @Provides
    @Singleton
    fun provideIspIntegrationOrchestrator(
        socProfile: SoCProfile,
    ): IspIntegrationOrchestrator = IspIntegrationOrchestrator(socProfile)
}
