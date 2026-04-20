plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt)
}
android {
    namespace = "com.leica.cam.motion_engine.impl"
    compileSdk = 35
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":common"))
    implementation(project(":motion-engine:api"))
    implementation(project(":photon-matrix:api"))
    implementation(project(":gpu-compute"))
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
}
