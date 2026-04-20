package com.leica.cam.feature.settings.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named

/** Dependency entry point for `feature/settings`. */
@Module
@InstallIn(SingletonComponent::class)
object FeatureSettingsModule {
    @Provides
    @Named("feature_settings_module")
    fun provideModuleName(): String = "feature/settings"
}
