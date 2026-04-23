plugins {
    id("leica.android.application")
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.leica.cam"

    defaultConfig {
        applicationId = "com.leica.cam"
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            buildConfigField("Boolean", "ENABLE_STRICT_MODE", "true")
            buildConfigField("Boolean", "ENABLE_LEAK_CANARY", "true")
        }
        create("staging") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            isMinifyEnabled = true
            buildConfigField("Boolean", "ENABLE_STRICT_MODE", "false")
            buildConfigField("Boolean", "ENABLE_LEAK_CANARY", "false")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            ndk {
                abiFilters += listOf("arm64-v8a")
            }
            buildConfigField("Boolean", "ENABLE_STRICT_MODE", "false")
            buildConfigField("Boolean", "ENABLE_LEAK_CANARY", "false")
        }
    }

    flavorDimensions += "runtime"
    productFlavors {
        create("dev") {
            dimension = "runtime"
            ndk {
                abiFilters += listOf("arm64-v8a", "x86_64")
            }
        }
        create("prod") {
            dimension = "runtime"
            ndk {
                abiFilters += listOf("arm64-v8a")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }
}

// ── Model asset pipeline ─────────────────────────────────────────────────
// Copies /Model/<Role>/*.tflite and *.task into assets/models/ at build time.
// ONNX files are intentionally skipped — we ship TFLite only on device.
tasks.register<Copy>("copyOnDeviceModels") {
    from(rootProject.file("Model")) {
        include("**/*.tflite")
        include("**/*.task")
        exclude("**/Temp/**")
    }
    into(layout.projectDirectory.dir("src/main/assets/models"))
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}

tasks.named("preBuild").configure {
    dependsOn("copyOnDeviceModels")
}

dependencies {
    implementation(project(":ai-engine:impl"))
    implementation(project(":imaging-pipeline:impl"))
    implementation(project(":hypertone-wb:impl"))
    implementation(project(":color-science:impl"))
    implementation(project(":depth-engine:impl"))
    implementation(project(":face-engine:impl"))
    implementation(project(":neural-isp:impl"))
    implementation(project(":photon-matrix:impl"))
    implementation(project(":smart-imaging:impl"))
    implementation(project(":bokeh-engine:impl"))
    implementation(project(":motion-engine:impl"))
    implementation(project(":native-imaging-core:impl"))
    implementation(project(":camera-core:impl"))
    implementation(libs.androidx.navigation.compose)
    implementation(libs.accompanist.permissions)
    implementation(project(":feature:camera"))
    implementation(project(":feature:gallery"))
    implementation(project(":feature:settings"))
    implementation(project(":ui-components"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    debugImplementation(libs.leakcanary.android)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
