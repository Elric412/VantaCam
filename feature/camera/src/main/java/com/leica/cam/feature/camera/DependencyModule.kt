package com.leica.cam.feature.camera

import com.leica.cam.feature.camera.ui.CameraModeSwitcher
import com.leica.cam.feature.camera.ui.CameraUiOrchestrator
import com.leica.cam.feature.camera.ui.PostCaptureEditor
import com.leica.cam.feature.camera.ui.ProModeController
import com.leica.cam.ui_components.camera.CameraMode
import com.leica.cam.ui_components.camera.Phase9UiStateCalculator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

/** Dependency entry point for `feature/camera`. */
@Module
@InstallIn(SingletonComponent::class)
object FeatureCameraDependencyModule {
    @Provides
    @Named("feature_camera_module")
    fun provideModuleName(): String = "feature/camera"

    @Provides
    @Singleton
    fun provideModeSwitcher(): CameraModeSwitcher = CameraModeSwitcher(CameraMode.entries)

    @Provides
    @Singleton
    fun provideProModeController(): ProModeController = ProModeController()

    @Provides
    @Singleton
    fun providePostCaptureEditor(): PostCaptureEditor = PostCaptureEditor()

    @Provides
    @Singleton
    fun provideCameraUiOrchestrator(
        uiStateCalculator: Phase9UiStateCalculator,
        modeSwitcher: CameraModeSwitcher,
    ): CameraUiOrchestrator = CameraUiOrchestrator(uiStateCalculator, modeSwitcher)
}
