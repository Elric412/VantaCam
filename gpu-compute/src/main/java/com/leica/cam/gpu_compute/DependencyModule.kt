package com.leica.cam.gpu_compute

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named

/** Dependency entry point for `gpu-compute`. */
@Module
@InstallIn(SingletonComponent::class)
object GpuComputeDependencyModule {
    @Provides
    @Named("gpu_compute_module")
    fun provideModuleName(): String = "gpu-compute"
}
