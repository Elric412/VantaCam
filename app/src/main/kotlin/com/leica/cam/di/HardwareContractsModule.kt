package com.leica.cam.di

import com.leica.cam.hardware.contracts.TrueColourHardwareSensor
import com.leica.cam.hardware.contracts.TrueColourRawReading
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Default True Colour sensor implementation until vendor hardware is connected.
 * Neutral readings let HyperTone WB fall back to neural/Robertson estimates safely.
 */
@Module
@InstallIn(SingletonComponent::class)
object HardwareContractsModule {
    @Provides
    @Singleton
    fun provideTrueColourHardwareSensor(): TrueColourHardwareSensor = NoSpectralSensor
}

private object NoSpectralSensor : TrueColourHardwareSensor {
    override fun readFullGrid(): List<TrueColourRawReading> = List(16) { index ->
        TrueColourRawReading(
            row = index / 4,
            col = index % 4,
            kelvin = 6500f,
            lux = 0f,
            confidence = 0f,
        )
    }

    override fun getConfidence(): Float = 0f
}
