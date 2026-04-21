package com.leica.cam.feature.settings.di

import com.leica.cam.feature.settings.preferences.CameraPreferencesRepository
import com.leica.cam.feature.settings.preferences.SharedPreferencesCameraStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

/** Dependency entry point for `feature/settings`. */
@Module
@InstallIn(SingletonComponent::class)
object FeatureSettingsModule {
    @Provides
    @Named("feature_settings_module")
    fun provideModuleName(): String = "feature/settings"

    @Provides
    @Singleton
    fun provideCameraPreferencesRepository(
        store: SharedPreferencesCameraStore,
    ): CameraPreferencesRepository = CameraPreferencesRepository(store)
}
