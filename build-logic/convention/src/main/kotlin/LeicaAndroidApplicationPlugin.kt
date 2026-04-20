package com.leica.cam.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project

class LeicaAndroidApplicationPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        pluginManager.apply("io.gitlab.arturbosch.detekt")
        pluginManager.apply("org.jlleitschuh.gradle.ktlint")

        configureAndroidApplication()
        configureKotlinCompilation()
        configureQualityPlugins()
        configureKaptAndHilt()
    }
}
