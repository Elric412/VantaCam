plugins {
    id("leica.jvm.library")
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":common"))
}
