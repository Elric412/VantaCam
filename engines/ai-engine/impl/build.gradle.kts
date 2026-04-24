plugins {
    id("leica.android.library")
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.leica.cam.ai_engine.impl"
}

dependencies {
    implementation(project(":ai-engine:api"))
    implementation(project(":depth-engine:api"))
    implementation(project(":face-engine:api"))
    implementation(project(":common"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.litert)
    implementation(libs.litert.gpu)
    implementation(libs.litert.support)
    implementation(libs.mediapipe.tasks.vision)
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)

    testImplementation(libs.junit)
}
