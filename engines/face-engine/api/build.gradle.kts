plugins {
    id("leica.engine.module")
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":common"))
    api(project(":photon-matrix:api"))
    implementation(project(":ai-engine:api"))
}
