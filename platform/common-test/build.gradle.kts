plugins {
    id("leica.jvm.library")
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":hardware-contracts"))
}
