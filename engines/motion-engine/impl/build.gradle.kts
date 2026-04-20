plugins {
    id("leica.android.library")
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.leica.cam.motion_engine.impl"
}

dependencies {
    implementation(project(":common"))
    implementation(project(":motion-engine:api"))
    implementation(project(":photon-matrix:api"))
    implementation(project(":gpu-compute"))
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
}
