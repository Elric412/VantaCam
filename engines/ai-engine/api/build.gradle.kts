plugins {
    id("leica.engine.module")
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":photon-matrix:api"))
    implementation(project(":common"))
}
