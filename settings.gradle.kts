pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "LeicaCam"

include(
    ":app",
    ":feature:camera",
    ":feature:gallery",
    ":feature:settings",
    ":camera-core:api",
    ":camera-core:impl",
    ":native-imaging-core:api",
    ":native-imaging-core:impl",
    ":imaging-pipeline:api",
    ":imaging-pipeline:impl",
    ":color-science:api",
    ":color-science:impl",
    ":hypertone-wb:api",
    ":hypertone-wb:impl",
    ":ai-engine:api",
    ":ai-engine:impl",
    ":depth-engine:api",
    ":depth-engine:impl",
    ":face-engine:api",
    ":face-engine:impl",
    ":neural-isp:api",
    ":neural-isp:impl",
    ":photon-matrix:api",
    ":photon-matrix:impl",
    ":smart-imaging:api",
    ":smart-imaging:impl",
    ":sensor-hal",
    ":lens-model",
    ":gpu-compute",
    ":ui-components",
    ":common",
    ":common-test",
    ":hardware-contracts",
    ":bokeh-engine:api",
    ":bokeh-engine:impl",
    ":motion-engine:api",
    ":motion-engine:impl",
)

project(":feature:camera").projectDir = file("features/camera")
project(":feature:gallery").projectDir = file("features/gallery")
project(":feature:settings").projectDir = file("features/settings")
project(":camera-core:api").projectDir = file("core/camera-core/api")
project(":camera-core:impl").projectDir = file("core/camera-core/impl")
project(":native-imaging-core:api").projectDir = file("platform-android/native-imaging-core/api")
project(":native-imaging-core:impl").projectDir = file("platform-android/native-imaging-core/impl")
project(":imaging-pipeline:api").projectDir = file("engines/imaging-pipeline/api")
project(":imaging-pipeline:impl").projectDir = file("engines/imaging-pipeline/impl")
project(":color-science:api").projectDir = file("core/color-science/api")
project(":color-science:impl").projectDir = file("core/color-science/impl")
project(":hypertone-wb:api").projectDir = file("engines/hypertone-wb/api")
project(":hypertone-wb:impl").projectDir = file("engines/hypertone-wb/impl")
project(":ai-engine:api").projectDir = file("engines/ai-engine/api")
project(":ai-engine:impl").projectDir = file("engines/ai-engine/impl")
project(":depth-engine:api").projectDir = file("engines/depth-engine/api")
project(":depth-engine:impl").projectDir = file("engines/depth-engine/impl")
project(":face-engine:api").projectDir = file("engines/face-engine/api")
project(":face-engine:impl").projectDir = file("engines/face-engine/impl")
project(":neural-isp:api").projectDir = file("engines/neural-isp/api")
project(":neural-isp:impl").projectDir = file("engines/neural-isp/impl")
project(":photon-matrix:api").projectDir = file("core/photon-matrix/api")
project(":photon-matrix:impl").projectDir = file("core/photon-matrix/impl")
project(":smart-imaging:api").projectDir = file("engines/smart-imaging/api")
project(":smart-imaging:impl").projectDir = file("engines/smart-imaging/impl")
project(":sensor-hal").projectDir = file("platform-android/sensor-hal")
project(":lens-model").projectDir = file("core/lens-model")
project(":gpu-compute").projectDir = file("platform-android/gpu-compute")
project(":ui-components").projectDir = file("platform-android/ui-components")
project(":common").projectDir = file("platform/common")
project(":common-test").projectDir = file("platform/common-test")
project(":hardware-contracts").projectDir = file("platform/hardware-contracts")
project(":bokeh-engine:api").projectDir = file("engines/bokeh-engine/api")
project(":bokeh-engine:impl").projectDir = file("engines/bokeh-engine/impl")
project(":motion-engine:api").projectDir = file("engines/motion-engine/api")
project(":motion-engine:impl").projectDir = file("engines/motion-engine/impl")
