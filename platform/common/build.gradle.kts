plugins {
    id("leica.jvm.library")
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(libs.javax.inject)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
