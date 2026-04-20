plugins {
    alias(libs.plugins.kotlinx.binary.compatibility.validator)
}

apiValidation {
    ignoredProjects += listOf("app")
}
