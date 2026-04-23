plugins {
    id("leica.android.library")
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.leica.cam.feature.camera"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }
}

dependencies {
    implementation(libs.accompanist.permissions)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(project(":common"))
    implementation(project(":feature:settings"))
    implementation(project(":camera-core:api"))
    implementation(project(":native-imaging-core:api"))
    implementation(project(":imaging-pipeline:api"))
    implementation(project(":color-science:api"))
    implementation(project(":hypertone-wb:api"))
    implementation(project(":ai-engine:api"))
    implementation(project(":depth-engine:api"))
    implementation(project(":face-engine:api"))
    implementation(project(":motion-engine:api"))
    implementation(project(":bokeh-engine:api"))
    implementation(project(":neural-isp:api"))
    implementation(project(":smart-imaging:api"))
    implementation(project(":sensor-hal"))
    implementation(project(":ui-components"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.hilt.navigation.compose)

    testImplementation(libs.junit)
}
