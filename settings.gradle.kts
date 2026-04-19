pluginManagement {
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
