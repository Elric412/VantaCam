package com.leica.cam.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project

class LeicaJvmLibraryPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("java-library")
        pluginManager.apply("io.gitlab.arturbosch.detekt")
        pluginManager.apply("org.jlleitschuh.gradle.ktlint")

        configureJvmLibrary()
        configureKotlinCompilation()
        configureQualityPlugins()
    }
}
