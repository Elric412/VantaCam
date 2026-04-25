plugins {
    id("leica.android.library")
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.leica.cam.capture.orchestrator"
}

dependencies {
    implementation(project(":common"))
    implementation(project(":hardware-contracts"))
    implementation(project(":photon-matrix:api"))
    implementation(project(":smart-imaging:api"))
    implementation(project(":imaging-pipeline:api"))
    implementation(project(":color-science:api"))
    implementation(project(":hypertone-wb:api"))
    implementation(project(":ai-engine:api"))
    implementation(project(":depth-engine:api"))
    implementation(project(":face-engine:api"))
    implementation(project(":motion-engine:api"))
    implementation(project(":bokeh-engine:api"))
    implementation(project(":neural-isp:api"))
    implementation(project(":sensor-hal"))
}
