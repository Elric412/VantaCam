package com.leica.cam.feature.gallery.di

import com.leica.cam.feature.gallery.ui.GalleryMetadataEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

/** Dependency entry point for `feature/gallery`. */
@Module
@InstallIn(SingletonComponent::class)
object FeatureGalleryModule {
    @Provides
    @Named("feature_gallery_module")
    fun provideModuleName(): String = "feature/gallery"

    @Provides
    @Singleton
    fun provideGalleryMetadataEngine(): GalleryMetadataEngine = GalleryMetadataEngine()
}
