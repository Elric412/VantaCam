package com.leica.cam.gpu_compute.di

import com.leica.cam.gpu_compute.GpuBackend
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

/** Dependency entry point for `gpu-compute`. */
@Module
@InstallIn(SingletonComponent::class)
object GpuComputeModule {
    @Provides
    @Named("gpu_compute_module")
    fun provideModuleName(): String = "gpu-compute"

    @Provides
    @Singleton
    fun provideGpuBackend(): GpuBackend = GpuBackend.CpuFallbackBackend()
}
