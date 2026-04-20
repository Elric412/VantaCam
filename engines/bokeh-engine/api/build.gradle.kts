plugins {
    id("leica.engine.module")
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":common"))
    implementation(project(":depth-engine:api"))
    implementation(project(":face-engine:api"))
}
