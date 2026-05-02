plugins {
    id("leica.android.library")
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.leica.cam.imaging_pipeline.impl"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    implementation(project(":imaging-pipeline:api"))
    implementation(project(":ai-engine:api"))
    implementation(project(":color-science:api"))
    implementation(project(":photon-matrix:api"))
    implementation(project(":hardware-contracts"))
    implementation(project(":common"))
    // ProXDR v3 native engine is built by :native-imaging-core:impl alongside
    // libnative_imaging_core.so. We need the .so packaged with this module so
    // System.loadLibrary("proxdr_engine") resolves at runtime.
    implementation(project(":native-imaging-core:impl"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)

    // TFLite is required by the dropped-in TfliteNeuralDelegate that ProXDR v3
    // uses for optional scene segmentation / portrait matting. Marked
    // `compileOnly` would not work because ProXDRBridge.init {} dynamically
    // loads the model at runtime; treat these as runtime-optional.
    compileOnly("org.tensorflow:tensorflow-lite:2.14.0")
    compileOnly("org.tensorflow:tensorflow-lite-gpu:2.14.0")
    compileOnly("org.tensorflow:tensorflow-lite-support:0.4.4")

    testImplementation(libs.junit)
}
