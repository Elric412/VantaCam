plugins {
    `java-gradle-plugin`
    `kotlin-dsl`
}

group = "com.leica.cam.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(libs.android.gradle.plugin)
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.detekt.gradle.plugin)
    implementation(libs.ktlint.gradle.plugin)
    implementation(libs.binary.compatibility.validator.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("leicaAndroidLibrary") {
            id = "leica.android.library"
            implementationClass = "com.leica.cam.buildlogic.LeicaAndroidLibraryPlugin"
        }
        register("leicaAndroidApplication") {
            id = "leica.android.application"
            implementationClass = "com.leica.cam.buildlogic.LeicaAndroidApplicationPlugin"
        }
        register("leicaJvmLibrary") {
            id = "leica.jvm.library"
            implementationClass = "com.leica.cam.buildlogic.LeicaJvmLibraryPlugin"
        }
        register("leicaEngineModule") {
            id = "leica.engine.module"
            implementationClass = "com.leica.cam.buildlogic.LeicaEngineModulePlugin"
        }
    }
}
