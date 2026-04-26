plugins {
    id("leica.android.library")
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.leica.cam.photon_matrix.impl"
}

dependencies {
    implementation(project(":common"))
    implementation(project(":photon-matrix:api"))
    implementation(project(":gpu-compute"))
    implementation(project(":native-imaging-core:api"))
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
}
