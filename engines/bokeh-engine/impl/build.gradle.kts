plugins {
    id("leica.android.library")
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.leica.cam.bokeh_engine.impl"
}

dependencies {
    implementation(project(":common"))
    implementation(project(":bokeh-engine:api"))
    implementation(project(":depth-engine:api"))
    implementation(project(":face-engine:api"))
    implementation(project(":gpu-compute"))
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
}
