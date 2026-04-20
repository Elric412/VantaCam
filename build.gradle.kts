plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.kapt) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.kotlinx.binary.compatibility.validator)
}

apiValidation {
    ignoredProjects += listOf("app")
}

subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        allRules = false
        config.setFrom("$rootDir/config/detekt/detekt.yml")
    }

    extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        filter {
            exclude { it.file.path.contains("src/test/java") }
            exclude { it.file.path.contains("ai-engine") }
            exclude { it.file.path.contains("camera-core") }
            exclude { it.file.path.contains("common") }
            exclude { it.file.path.contains("sensor-hal") }
            exclude { it.file.path.contains("imaging-pipeline") }
            exclude { it.file.path.contains("hypertone-wb") }
            exclude { it.file.path.contains("lens-model") }
            exclude { it.file.path.contains("neural-isp") }
            exclude { it.file.path.contains("color-science") }
            exclude { it.file.path.contains("gpu-compute") }
            exclude { it.file.path.contains("feature/camera") }
            exclude { it.file.path.contains("feature/gallery") }
            exclude { it.file.path.contains("feature/settings") }
            exclude { it.file.path.contains("ui-components") }
            exclude { it.file.path.contains("app") }
        }
    }
}
