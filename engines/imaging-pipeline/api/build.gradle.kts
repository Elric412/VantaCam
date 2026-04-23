plugins {
    id("leica.engine.module")
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(project(":common"))
}
