plugins {
    id("leica.jvm.library")
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(project(":common-test"))
}
