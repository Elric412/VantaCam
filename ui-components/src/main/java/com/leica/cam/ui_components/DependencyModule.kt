package com.leica.cam.ui_components

import com.leica.cam.ui_components.camera.Phase9UiStateCalculator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

/** Dependency entry point for `ui-components`. */
@Module
@InstallIn(SingletonComponent::class)
object UiComponentsDependencyModule {
    @Provides
    @Named("ui_components_module")
    fun provideModuleName(): String = "ui-components"

    @Provides
    @Singleton
    fun providePhase9UiStateCalculator(): Phase9UiStateCalculator = Phase9UiStateCalculator()
}
