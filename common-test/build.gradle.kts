plugins {
    alias(libs.plugins.kotlin.jvm)
}


dependencies {
    api(project(":hardware-contracts"))
}


tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class.java).configureEach {
    kotlinOptions.jvmTarget = "21"
}
