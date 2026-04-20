package com.leica.cam.camera_core

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named

/** Dependency entry point for `camera-core`. */
@Module
@InstallIn(SingletonComponent::class)
object CameraCoreDependencyModule {
    @Provides
    @Named("camera_core_module")
    fun provideModuleName(): String = "camera-core"
}
