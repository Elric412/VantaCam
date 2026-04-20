package com.leica.cam.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project

class LeicaEngineModulePlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("leica.jvm.library")
    }
}
