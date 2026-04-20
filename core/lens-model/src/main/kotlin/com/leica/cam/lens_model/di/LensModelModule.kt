package com.leica.cam.lens_model.di

import com.leica.cam.lens_model.correction.LensCorrectionSuite
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

/** Dependency entry point for `lens-model`. */
@Module
@InstallIn(SingletonComponent::class)
object LensModelModule {
    @Provides
    @Named("lens_model_module")
    fun provideModuleName(): String = "lens-model"

    @Provides
    @Singleton
    fun provideLensCorrectionSuite(): LensCorrectionSuite = LensCorrectionSuite()
}
