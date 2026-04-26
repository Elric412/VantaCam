# Build / Gradle / NDK Known Issues

### I2026-04-26-1: Hilt/kapt missing from implementation modules
- Observed in: `core/capture-orchestrator/build.gradle.kts`, `core/photon-matrix/impl/build.gradle.kts`, `engines/smart-imaging/impl/build.gradle.kts`
- Severity: BLOCKER
- Owner: android-app-builder + Backend Enhancer
- Linked plan: `Plan.md#sub-plan-p0--build-system--gradle-plugin-gaps`
- Resolution: resolved in P0 by applying Hilt/kapt plugins and compiler dependencies.

### I2026-04-26-2: Native imaging core was not wired into AGP
- Observed in: `platform-android/native-imaging-core/impl/build.gradle.kts`
- Severity: BLOCKER
- Owner: android-app-builder
- Linked plan: `Plan.md#p32--native-code-not-wired-into-agp`
- Resolution: resolved in P3 with `externalNativeBuild`, CMake 3.22.1, and NDK r27 declarations.

### I2026-04-26-3: AGP 8.4.2 compileSdk 35 mismatch
- Observed in: `build-logic/convention/src/main/kotlin/LeicaConventionSupport.kt`
- Severity: MAJOR
- Owner: android-app-builder
- Linked plan: `Plan.md#p31--compilesdk--35-with-agp-842`
- Resolution: resolved in P3 by pinning compileSdk/targetSdk to 34.

### I2026-04-26-4: AGP 8.7+ upgrade remains deferred
- Observed in: `gradle/libs.versions.toml`
- Severity: MINOR
- Owner: Android platform
- Linked plan: `Plan.md#out-of-scope`
- Resolution: open; upgrade Kotlin/AGP together in a dedicated toolchain migration.
