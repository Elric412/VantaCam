package com.leica.cam.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure

internal const val LeicaCompileSdk = 35
internal const val LeicaMinSdk = 29
internal const val LeicaTargetSdk = 35

internal fun Project.configureKotlinCompilation() {
    extensions.configure<JavaPluginExtension> {
        toolchain { languageVersion.set(org.gradle.jvm.toolchain.JavaLanguageVersion.of(17)) }
    }
    tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class.java).configureEach {
        kotlinOptions {
            jvmTarget = JavaVersion.VERSION_17.toString()
        }
    }
}

internal fun Project.configureQualityPlugins() {
    extensions.configure<DetektExtension> {
        buildUponDefaultConfig = true
        allRules = false
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    }
}

internal fun Project.configureAndroidLibrary() {
    extensions.configure<LibraryExtension> {
        compileSdk = LeicaCompileSdk
        defaultConfig {
            minSdk = LeicaMinSdk
        }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
    }
}

internal fun Project.configureAndroidApplication() {
    extensions.configure<ApplicationExtension> {
        compileSdk = LeicaCompileSdk
        defaultConfig {
            minSdk = LeicaMinSdk
            targetSdk = LeicaTargetSdk
        }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
    }
}

internal fun Project.configureJvmLibrary() {
    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

internal fun Project.configureKaptAndHilt() {
    pluginManager.withPlugin("org.jetbrains.kotlin.kapt") {
        extensions.findByName("kapt")?.let { kaptExtension ->
            kaptExtension.javaClass.methods
                .firstOrNull { it.name == "setCorrectErrorTypes" && it.parameterCount == 1 }
                ?.invoke(kaptExtension, true)
        }
    }
    pluginManager.withPlugin("com.google.dagger.hilt.android") {
        extensions.findByName("hilt")?.let { hiltExtension ->
            hiltExtension.javaClass.methods
                .firstOrNull { it.name == "setEnableAggregatingTask" && it.parameterCount == 1 }
                ?.invoke(hiltExtension, true)
        }
    }
}
