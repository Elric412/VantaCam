plugins {
    id("leica.android.library")
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.leica.cam.face_engine.impl"
}

dependencies {
    implementation(project(":face-engine:api"))
    implementation(project(":ai-engine:api"))
    implementation(project(":common"))
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
}
