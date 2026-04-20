plugins {
    id("leica.engine.module")
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":common"))
}
