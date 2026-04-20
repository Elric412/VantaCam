package com.leica.cam.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project

class LeicaAndroidLibraryPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        pluginManager.apply("io.gitlab.arturbosch.detekt")
        pluginManager.apply("org.jlleitschuh.gradle.ktlint")

        configureAndroidLibrary()
        configureKotlinCompilation()
        configureQualityPlugins()
        configureKaptAndHilt()
    }
}
