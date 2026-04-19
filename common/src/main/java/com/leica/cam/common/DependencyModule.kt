package com.leica.cam.common

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named

/** Dependency entry point for `common`. */
@Module
@InstallIn(SingletonComponent::class)
object CommonDependencyModule {
    @Provides
    @Named("common_module")
    fun provideModuleName(): String = "common"
}
