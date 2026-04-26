# Plan: LeicaCam — Ship-Blocking Errors, DI Breaks, Build Issues, SDK/Permission Fixes

> **Scope marker:** This plan is the authoritative, handoff-ready registry of **every issue that will prevent LeicaCam from compiling, linking, installing, launching, or capturing a photo.** It is split into **six sequential sub-plans (P0 … P5)**. The Executor must complete them **in order**, verifying the gate at the end of each before starting the next. Every sub-plan is a discrete, green-build checkpoint.
>
> **Two companion documents:**
> - `processing-problems.md` — the deep, stage-by-stage dossier of why FusionLM 2.0, ColorLM 2.0, HyperTone AWB, ProXDR, the advanced focus system, skin-tone rendering, MicroISP, Image Classifier, Scene Understanding, and the neural AWB model are **declared but not actually engaged** during capture. That file is the algorithmic counterpart to this one; this file is the engineering/build counterpart.
> - `docs/known-issues/` (to be seeded by this plan in P5) — the long-term ledger future upgrade plans draw from.
>
> **Skill / Agent ownership table** (who owns what during execution — skills live under `.agents/skills/`):
>
> | Sub-plan | Primary skill / agent | Why |
> |---|---|---|
> | **P0 — Build-system / Gradle plugin gaps** | `android-app-builder` (ARIA-X) + `Backend Enhancer` | Missing `hilt` + `kapt` plugins on modules that declare `@Module` / `@Inject`. Pure Gradle/KAPT mechanics. |
> | **P1 — DI graph (unsatisfied bindings)** | `Backend Enhancer` + `the-advisor` | Multiple `@Inject`-required interfaces (`IHyperToneWB2Engine`, `INeuralIspOrchestrator`, `LeicaLogger`, `GpuBackend`, `TrueColourHardwareSensor`) have no provider. Hilt compilation will fail. |
> | **P2 — Compilation / type-mismatch / signature drift** | `kotlin-specialist` + `critique` | `HyperToneWB2Engine` no-arg class called as 7-arg constructor; `HyperToneWhiteBalanceEngine` dead-on-arrival; stub methods shadowing real engines. |
> | **P3 — Android SDK / permissions / manifest / NDK** | `android-app-builder` (ARIA-X) | compileSdk=35 vs AGP 8.4.2 mismatch, missing `CAMERA`/media runtime checks consistency, native CMake not wired into AGP. |
> | **P4 — Runtime wiring (shutter → processing)** | `android-app-builder` + `Lumo Imaging Engineer` | `CaptureProcessingOrchestrator` never invokes `ImagingPipeline` / `ProXdrOrchestrator` / `FusionLM2Engine` / `ToneLM2Engine` / `HyperToneWhiteBalanceEngine`. Fusion is literally `frames.first()`. |
> | **P5 — Known-issues registry + verification gates** | `Advisor` + `analyzing-projects` | Seed `docs/known-issues/`, add the build/test gates this plan depends on. |
>
> The Executor should read each sub-plan end-to-end before touching any file. Do not skip the verification at the bottom of each sub-plan; the whole point of sequencing is that P2 assumes P1 is green.

---

## Context

**Project:** LeicaCam — a 33-module Kotlin 2.0 + C++/Vulkan Android computational-photography stack (see `README.md`, `project-structure.md`). Target device is a MediaTek Dimensity platform with 8 sensors. Min SDK 29, declared compileSdk 35. Runtime features: RAW-domain 16-bit pipeline, ProXDR HDR, FusionLM 2.0 multi-frame fusion, HyperTone AWB (Robertson CIE-1960, skin-anchored), ColorLM 2.0 (dual-illuminant CCM, OKLAB), Durand bilateral + cinematic S-curve tone mapping, MicroISP refinement on ultra-wide / front, five on-device AI models (AWB, Face Landmarker, Image Classifier, Semantic Segmentation, MicroISP) via LiteRT + MediaPipe Tasks.

**Current state as observed in the repo (2026-04-26, `main` @ `d3b8c0a`):**

- The build graph is declared correctly (`settings.gradle.kts` includes all 33 modules; a `build-logic` composite build exposes four convention plugins: `leica.android.library`, `leica.android.application`, `leica.jvm.library`, `leica.engine.module`).
- However, several `impl` modules that contain `@Inject constructor(...)` classes and / or Hilt `@Module` definitions **do not apply the `kotlin-kapt` and `com.google.dagger.hilt.android` plugins** — so annotation processors never run on those modules. This is a build-breaking gap.
- Several Hilt-injected interfaces (`IHyperToneWB2Engine`, `INeuralIspOrchestrator`, `LeicaLogger`, `GpuBackend`, `TrueColourHardwareSensor`) have **no `@Provides` or `@Binds` anywhere in the repo**. The DI graph is incomplete — kapt will fail with `[Dagger/MissingBinding]`.
- The "real" engines (`HyperToneWhiteBalanceEngine` with the neural AWB prior, `ImagingPipeline`, `ProXdrOrchestrator`, `FusionLM2Engine`, `ToneLM2Engine`, `AwbModelRunner`, `SemanticSegmenterRunner`, `SceneClassifierRunner`, `MicroIspRunner`) exist on disk but are **not reachable from the capture pipeline**. The capture orchestrator (`CaptureProcessingOrchestrator.processCapture`) runs a narrative of "Stage 1..15" log lines but most stages are stubs.
- `core/capture-orchestrator`, `core/photon-matrix/impl`, `engines/smart-imaging/impl` contain `@Inject` / `@Module` but **do not apply hilt/kapt**. They will not produce usable Hilt bindings even after P1 is fixed — P0 must land first.

**Stack & Assumptions (repeated verbatim so the Executor can work without reading anything else):**

- **Language/runtime:** Kotlin 1.9.24 (Android) / 1.9.24 (JVM); JVM target 17; Gradle 8.8; AGP 8.4.2.
- **Compose:** BOM `2024.06.00`; compose compiler `1.5.14`; Material3.
- **DI:** Hilt 2.51.1 via `kotlin-kapt` — Hilt's KSP support is *not* in use; do not introduce KSP in this plan.
- **Camera:** CameraX 1.3.4 (`camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view`), plus Camera2 interop on `platform-android/sensor-hal`.
- **On-device ML:** Google LiteRT `1.0.1` (`com.google.ai.edge.litert:litert`, `litert-gpu`, `litert-support`) + MediaPipe Tasks Vision `0.10.14` (`com.google.mediapipe:tasks-vision`).
- **SDK ceiling:** `compileSdk = 35`, `targetSdk = 35`, `minSdk = 29` (declared in `build-logic/convention/.../LeicaConventionSupport.kt` — do not duplicate in per-module `build.gradle.kts`).
- **ABI filters:** `arm64-v8a` (prod), `arm64-v8a, x86_64` (dev).
- **Gradle wrapper URL:** `https\://services.gradle.org/distributions/gradle-8.8-bin.zip`.

**Environment variables required:** none at build time. Runtime location EXIF is gated by `ACCESS_FINE_LOCATION` (already declared in `app/src/main/AndroidManifest.xml`).

---

## Files to create or modify

This is the full Executor checklist. Every box below will be ticked by the end of P5. File paths are repo-relative.

### Build system (P0)

- [ ] `core/capture-orchestrator/build.gradle.kts` (modify)
- [ ] `core/photon-matrix/impl/build.gradle.kts` (modify)
- [ ] `engines/smart-imaging/impl/build.gradle.kts` (modify)
- [ ] `platform-android/native-imaging-core/impl/build.gradle.kts` (modify — wire up CMake)

### DI graph (P1)

- [ ] `engines/hypertone-wb/impl/src/main/kotlin/com/leica/cam/hypertone_wb/pipeline/HyperToneWB2Engine.kt` (modify — make it `@Inject`able with the correct constructor)
- [ ] `engines/hypertone-wb/impl/src/main/kotlin/com/leica/cam/hypertone_wb/pipeline/HyperToneWB2EngineAdapter.kt` (new — binds concrete to `IHyperToneWB2Engine`)
- [ ] `engines/hypertone-wb/impl/src/main/kotlin/com/leica/cam/hypertone_wb/di/HypertoneWbModule.kt` (modify — add `@Binds`)
- [ ] `engines/neural-isp/impl/src/main/kotlin/com/leica/cam/neural_isp/pipeline/NeuralIspOrchestratorImpl.kt` (new)
- [ ] `engines/neural-isp/impl/src/main/kotlin/com/leica/cam/neural_isp/di/NeuralIspModule.kt` (modify — provide `INeuralIspOrchestrator`)
- [ ] `platform/common/src/main/kotlin/com/leica/cam/common/logging/AndroidLeicaLogger.kt` (new — `android.util.Log` backed)
- [ ] `app/src/main/kotlin/com/leica/cam/di/LoggingModule.kt` (new — `@Binds LeicaLogger`)
- [ ] `platform-android/gpu-compute/src/main/kotlin/com/leica/cam/gpu_compute/di/GpuComputeModule.kt` (modify — provide `GpuBackend`)
- [ ] `platform/hardware-contracts/src/main/kotlin/com/leica/cam/hardware/contracts/SensorSpec.kt` (review + doc)
- [ ] `app/src/main/kotlin/com/leica/cam/di/HardwareContractsModule.kt` (new — provide `TrueColourHardwareSensor` default)

### Compilation / type correctness (P2)

- [ ] `engines/hypertone-wb/impl/src/main/kotlin/com/leica/cam/hypertone_wb/pipeline/HyperToneWB2Engine.kt` (modify — replace no-arg shell with a real constructor that accepts the 7 dependencies the DI module is already passing)
- [ ] `engines/ai-engine/impl/src/main/kotlin/com/leica/cam/ai_engine/impl/registry/ModelRegistry.kt` (modify — the `internal` constructor must be accessible to `fromAssets`; currently OK but verify once DI resolves)
- [ ] `engines/ai-engine/impl/src/main/kotlin/com/leica/cam/ai_engine/impl/pipeline/AiEngineOrchestrator.kt` (modify — `downsample()` currently returns `FloatArray(w*h*3)` of zeroes; wire real tile downsampling)

### Android SDK / permissions / manifest / NDK (P3)

- [ ] `gradle/libs.versions.toml` (modify — bump `agp = "8.7.3"` and `kotlin = "2.0.21"` **only if** the team decides to upgrade; otherwise pin `compileSdk = 34`. This plan defaults to **pinning compileSdk = 34** for AGP 8.4.2.)
- [ ] `build-logic/convention/src/main/kotlin/LeicaConventionSupport.kt` (modify if compileSdk pinned)
- [ ] `app/src/main/AndroidManifest.xml` (modify — add `POST_NOTIFICATIONS` for API 33+ toasts from capture; add explicit `android:minSdkVersion`-scoped entries)
- [ ] `features/camera/src/main/kotlin/com/leica/cam/feature/camera/permissions/RequiredPermissions.kt` (review — it already scopes by SDK but confirm after P0 lands)
- [ ] `platform-android/native-imaging-core/impl/build.gradle.kts` (modify — `externalNativeBuild { cmake { path("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }` + `ndkVersion`)

### Runtime wiring (P4)

- [ ] `core/capture-orchestrator/src/main/kotlin/com/leica/cam/capture/orchestrator/CaptureProcessingOrchestrator.kt` (modify — `fusionFrames()` stub → `FusionLM2Engine.fuse(...)`; call `ProXdrOrchestrator.orchestrate(...)` under PRO_XDR mode; call `ToneLM2Engine.apply(...)` under "Stage 10"; route `HyperToneWhiteBalanceEngine` (neural-prior WB) instead of the bare `IHyperToneWB2Engine.correct` when an `AwbPredictor` is present)
- [ ] `core/capture-orchestrator/src/main/kotlin/com/leica/cam/capture/di/CaptureOrchestratorModule.kt` (modify — inject `FusionLM2Engine`, `ToneLM2Engine`, `ProXdrOrchestrator`, `HyperToneWhiteBalanceEngine`)

### Documentation / registry (P5)

- [ ] `docs/known-issues/README.md` (new)
- [ ] `docs/known-issues/build.md` (new)
- [ ] `docs/known-issues/di.md` (new)
- [ ] `docs/known-issues/processing.md` (new — cross-links `processing-problems.md`)
- [ ] `docs/known-issues/sdk.md` (new)
- [ ] `docs/known-issues/wiring.md` (new)
- [ ] `.github/workflows/ci.yml` (modify — add `:app:assembleDebug`, `:app:kaptDebugKotlin`, and module-scoped `test` jobs as hard gates)

---

# Sub-plan P0 — Build-system / Gradle plugin gaps

**Gate:** `./gradlew :app:assembleDebug` must reach the kapt phase without any `"Could not find method kapt()"` / `"Plugin with id 'dagger.hilt.android' not found"` errors. The kapt phase is allowed to fail in P0 — that will be fixed in P1. The goal here is that Gradle **configures and launches annotation processing** on every `impl` module that contains `@Inject` / `@Module`.

## The failure modes to fix

Three `impl` modules declare Hilt `@Module` or `@Inject constructor(...)` **but do not apply the `hilt` or `kotlin-kapt` plugins**. At the current HEAD they silently compile (because Kotlin does not require annotation processors), leaving the Hilt graph half-generated:

| Module | File | Symptom |
|---|---|---|
| `:core:capture-orchestrator` | `core/capture-orchestrator/build.gradle.kts` | `CaptureOrchestratorModule` is `@Module @InstallIn(SingletonComponent::class)` but kapt never runs → `CaptureProcessingOrchestrator_Factory` and module `…_Provide*Factory` classes are not generated. `:app`'s kapt will later fail with `[Dagger/MissingBinding] CaptureProcessingOrchestrator cannot be provided` |
| `:core:photon-matrix:impl` | `core/photon-matrix/impl/build.gradle.kts` | `PhotonMatrixIngestor : IPhotonMatrixIngestor` and `PhotonMatrixAssembler : IPhotonMatrixAssembler` are not `@Inject`-able. They are also not `@Provides`-ed anywhere. Without hilt/kapt applied, even adding `@Inject` would be a no-op here. |
| `:engines:smart-imaging:impl` | `engines/smart-imaging/impl/build.gradle.kts` | `SmartImagingOrchestrator` is `@Singleton class … @Inject constructor(...)` with 16 dependencies — kapt must run to generate its factory. |

A fourth module (`:platform-android:native-imaging-core:impl`) applies hilt+kapt correctly but does not wire CMake — fixed in P3.

## Steps

### P0.1 — Fix `core/capture-orchestrator/build.gradle.kts`

- [ ] Locate the block:
    ```kotlin
    plugins {
        id("leica.android.library")
        alias(libs.plugins.kotlin.android)
    }
    ```
- [ ] Replace it with:
    ```kotlin
    plugins {
        id("leica.android.library")
        alias(libs.plugins.kotlin.android)
        alias(libs.plugins.kotlin.kapt)
        alias(libs.plugins.hilt)
    }
    ```
- [ ] In the same file, extend the `dependencies { … }` block (append at the end, before the closing brace):
    ```kotlin
    dependencies {
        // … existing project(...) lines …
        implementation(libs.hilt.android)
        kapt(libs.hilt.compiler)
        implementation(libs.kotlinx.coroutines.core)
    }
    ```
    **Trap:** Do not remove any existing `implementation(project(":..."))` lines. Only append the three above if they are not already present.

### P0.2 — Fix `core/photon-matrix/impl/build.gradle.kts`

- [ ] Locate the block:
    ```kotlin
    plugins {
        id("leica.android.library")
        alias(libs.plugins.kotlin.android)
    }
    ```
- [ ] Replace it with:
    ```kotlin
    plugins {
        id("leica.android.library")
        alias(libs.plugins.kotlin.android)
        alias(libs.plugins.kotlin.kapt)
        alias(libs.plugins.hilt)
    }
    ```
- [ ] Append in `dependencies { … }`:
    ```kotlin
        implementation(libs.hilt.android)
        kapt(libs.hilt.compiler)
    ```

### P0.3 — Fix `engines/smart-imaging/impl/build.gradle.kts`

- [ ] Replace the `plugins { … }` block with the 4-plugin version above (same as P0.1/P0.2).
- [ ] Append in `dependencies { … }`:
    ```kotlin
        implementation(libs.hilt.android)
        kapt(libs.hilt.compiler)
        implementation(libs.kotlinx.coroutines.core)
    ```
- [ ] **Also add** the following project references that `SmartImagingOrchestrator` imports but the module doesn't currently depend on (this is why the module compiles today as a "ghost class" — those symbols resolve only because `:imaging-pipeline:impl` has them and Kotlin imports are lenient across the classpath, but once kapt starts walking the graph it will fail):
    ```kotlin
        implementation(project(":imaging-pipeline:impl"))
    ```
    **Trap:** The file currently only has `:imaging-pipeline:api`. `SmartImagingOrchestrator` references `FusionLM2Engine` and `ToneLM2Engine` which live in `:impl`, not `:api`. Add the `:impl` dependency explicitly.

### P0.4 — (Deferred to P3) `platform-android/native-imaging-core/impl/build.gradle.kts`

Leave this file alone in P0. The CMake wiring is a separate, self-contained fix handled in P3.2.

## P0 Verification

- [ ] Run: `./gradlew :core:capture-orchestrator:compileDebugKotlin --stacktrace`. **Expect:** exits non-zero with Dagger missing-binding errors (that's P1), **not** with Gradle plugin resolution errors.
- [ ] Run: `./gradlew :core:capture-orchestrator:kaptDebugKotlin 2>&1 | head -60`. **Expect:** the output mentions `kapt` by name (proof the plugin is now applied).
- [ ] Run: `./gradlew :engines:smart-imaging:impl:compileDebugKotlin`. **Expect:** compiles, or fails on P1-style missing bindings. Not on "Unresolved reference: FusionLM2Engine".
- [ ] Commit with message `build(p0): apply hilt + kapt plugins to capture-orchestrator, photon-matrix:impl, smart-imaging:impl`.

---

# Sub-plan P1 — DI graph (unsatisfied bindings)

**Gate:** `./gradlew :app:kaptDebugKotlin` completes successfully. Dagger's component processor must not print a `[Dagger/MissingBinding]` line for any type. Compilation of Kotlin sources may still fail (P2 fixes that) — but the binding graph must be complete.

## The failure modes to fix

The following interfaces are injected somewhere in the codebase but have **no `@Provides` or `@Binds`** in any `@Module @InstallIn(SingletonComponent::class)`:

| Interface | Injected at (representative file) | Missing provider |
|---|---|---|
| `com.leica.cam.hypertone_wb.api.IHyperToneWB2Engine` | `core/capture-orchestrator/.../CaptureProcessingOrchestrator.kt:106`, `engines/smart-imaging/impl/.../SmartImagingOrchestrator.kt:43` | No class implements the interface. `HyperToneWB2Engine` in `engines/hypertone-wb/impl/.../pipeline/HyperToneWB2Engine.kt` is a bare class with no interface. |
| `com.leica.cam.neural_isp.api.INeuralIspOrchestrator` | same two callers | The `NeuralIspModule` provides stages (`RawDenoiseStage`, `LearnedDemosaicStage`, …) and `ImagePipelineProcessor` variants, but **nothing provides `INeuralIspOrchestrator`**. |
| `com.leica.cam.common.logging.LeicaLogger` | `HypertoneWbModule`, `CaptureOrchestratorModule`, `CaptureProcessingOrchestrator`, `ProcessingBudgetManager`, `HdrStrategyEngine`, `PortraitModeEngine`, `CaptureFrameIngestor`, `OutputEncoder` | No implementation, no `@Binds`. |
| `com.leica.cam.gpu_compute.GpuBackend` | `HypertoneWbModule.provideHyperToneWB2Engine` parameter | `GpuComputeModule` only provides the module-name String. |
| `com.leica.cam.hardware.contracts.TrueColourHardwareSensor` | `HypertoneWbModule.providePartitionedCTSensor` parameter | No implementation anywhere; the test file in `Phase15Test.kt` uses an inline anonymous `object : TrueColourHardwareSensor { … }`. |

### A note on the `HyperToneWB2Engine` double-problem

`engines/hypertone-wb/impl/src/main/kotlin/com/leica/cam/hypertone_wb/pipeline/HyperToneWB2Engine.kt` currently declares:

```kotlin
class HyperToneWB2Engine {      // <-- no constructor params
    suspend fun process(frame: RgbFrame, ...): LeicaResult<RgbFrame> { ... }
}
```

But `HypertoneWbModule.provideHyperToneWB2Engine` calls it as:

```kotlin
HyperToneWB2Engine(sensor, fusion, spatial, temporal, guard, gpu, logger)
```

This is a **compile error** that only kapt-graph analysis will uncover (see P2 for the fix). We must also bind the concrete class to the `IHyperToneWB2Engine` interface since two orchestrators inject the interface. P1 adds the `@Binds`; P2 fixes the constructor.

## Steps

### P1.1 — Add `AndroidLeicaLogger` + `LoggingModule`

- [ ] Create the file `platform/common/src/main/kotlin/com/leica/cam/common/logging/AndroidLeicaLogger.kt`. **The file must compile against a JVM-only `:common` module**, so this default implementation is a `NoOpLeicaLogger` (writes nothing). The Android-backed variant lives in `:app`.
    ```kotlin
    package com.leica.cam.common.logging

    /** JVM-pure default; overridden by [AndroidLeicaLogger] in the :app module. */
    class NoOpLeicaLogger : LeicaLogger {
        override fun debug(tag: String, message: String) = Unit
        override fun info(tag: String, message: String) = Unit
        override fun warn(tag: String, message: String, cause: Throwable?) = Unit
        override fun error(tag: String, message: String, cause: Throwable?) = Unit
    }
    ```
- [ ] Create the file `app/src/main/kotlin/com/leica/cam/logging/AndroidLeicaLogger.kt` (note the `app`-module location — it needs `android.util.Log`):
    ```kotlin
    package com.leica.cam.logging

    import android.util.Log
    import com.leica.cam.common.logging.LeicaLogger
    import javax.inject.Inject
    import javax.inject.Singleton

    @Singleton
    class AndroidLeicaLogger @Inject constructor() : LeicaLogger {
        override fun debug(tag: String, message: String) { Log.d(tag, message) }
        override fun info(tag: String, message: String) { Log.i(tag, message) }
        override fun warn(tag: String, message: String, cause: Throwable?) {
            if (cause == null) Log.w(tag, message) else Log.w(tag, message, cause)
        }
        override fun error(tag: String, message: String, cause: Throwable?) {
            if (cause == null) Log.e(tag, message) else Log.e(tag, message, cause)
        }
    }
    ```
- [ ] Create the file `app/src/main/kotlin/com/leica/cam/di/LoggingModule.kt`:
    ```kotlin
    package com.leica.cam.di

    import com.leica.cam.common.logging.LeicaLogger
    import com.leica.cam.logging.AndroidLeicaLogger
    import dagger.Binds
    import dagger.Module
    import dagger.hilt.InstallIn
    import dagger.hilt.components.SingletonComponent
    import javax.inject.Singleton

    @Module
    @InstallIn(SingletonComponent::class)
    abstract class LoggingModule {
        @Binds
        @Singleton
        abstract fun bindLeicaLogger(impl: AndroidLeicaLogger): LeicaLogger
    }
    ```
    **Trap:** Do not put this module in `:common` — `:common` is a pure JVM library and must not import `android.util.Log`. The `LoggingModule` belongs in `:app` alongside `LeicaCamApp`.

### P1.2 — Provide `GpuBackend`

`GpuBackend` is a `sealed interface` in `platform-android/gpu-compute/.../GpuBackend.kt`. There are three variants: `VulkanBackend`, `OpenGlEsBackend`, `CpuFallbackBackend` (check the file for the exact ctor signatures). For HypertoneWbModule we only need *a* `GpuBackend`; pick the fall-back.

- [ ] Modify `platform-android/gpu-compute/src/main/kotlin/com/leica/cam/gpu_compute/di/GpuComputeModule.kt`. Locate:
    ```kotlin
    @Module
    @InstallIn(SingletonComponent::class)
    object GpuComputeModule {
        @Provides
        @Named("gpu_compute_module")
        fun provideModuleName(): String = "gpu-compute"
    }
    ```
- [ ] Replace it with:
    ```kotlin
    @Module
    @InstallIn(SingletonComponent::class)
    object GpuComputeModule {
        @Provides
        @Named("gpu_compute_module")
        fun provideModuleName(): String = "gpu-compute"

        /**
         * Best-effort backend pick. Full Vulkan init happens lazily inside
         * VulkanBackend on first use; if the device lacks Vulkan capability,
         * it degrades to CPU at use-site.
         *
         * GpuComputeInitializer already discovers the best backend at app
         * start — we surface its decision here via a @Singleton that asks the
         * initializer for the selected backend.
         */
        @Provides
        @Singleton
        fun provideGpuBackend(
            initializer: GpuComputeInitializer,
        ): GpuBackend = initializer.currentBackend()
    }
    ```
    **Trap:** `GpuComputeInitializer` must itself be `@Inject constructor()`-able. Check the class; if it is not, add a `@Inject constructor()` or add a `@Provides` that returns it. If `currentBackend()` does not exist, call the actual accessor (read the class). If in doubt, fall back to:
    ```kotlin
    @Provides @Singleton
    fun provideGpuBackend(): GpuBackend = GpuBackend.CpuFallbackBackend()
    ```
    and add a `// TODO: elevate to Vulkan when GpuComputeInitializer stabilises` comment.

### P1.3 — Provide `TrueColourHardwareSensor`

- [ ] Create `app/src/main/kotlin/com/leica/cam/di/HardwareContractsModule.kt`:
    ```kotlin
    package com.leica.cam.di

    import com.leica.cam.hardware.contracts.TrueColourHardwareSensor
    import dagger.Module
    import dagger.Provides
    import dagger.hilt.InstallIn
    import dagger.hilt.components.SingletonComponent
    import javax.inject.Singleton

    /**
     * Provides a default [TrueColourHardwareSensor] until per-device vendor
     * implementations are wired up (tracked in docs/known-issues/wiring.md).
     *
     * The `NoSpectralSensor` implementation returns empty/neutral readings —
     * the downstream HyperTone WB pipeline correctly blends against the
     * neural CCT prior and the Robertson histogram estimator in its absence.
     */
    @Module
    @InstallIn(SingletonComponent::class)
    object HardwareContractsModule {
        @Provides
        @Singleton
        fun provideTrueColourHardwareSensor(): TrueColourHardwareSensor =
            NoSpectralSensor
    }

    private object NoSpectralSensor : TrueColourHardwareSensor {
        // Fill in every member of TrueColourHardwareSensor with neutral defaults.
        // The exact signatures come from
        //   platform/hardware-contracts/.../SensorSpec.kt
        // If that file declares, e.g.,
        //   fun readCt(): Float = 6500f
        //   fun readTint(): Float = 0f
        // …copy the signatures here verbatim. DO NOT guess.
    }
    ```
    **Trap:** The actual member list of `TrueColourHardwareSensor` is defined in `platform/hardware-contracts/src/main/kotlin/com/leica/cam/hardware/contracts/SensorSpec.kt`. Open that file, copy every abstract member signature, and implement each with a neutral default (6500 K, 0 D_uv, 0.0f confidence, etc.). Do not invent signatures.

### P1.4 — Provide `INeuralIspOrchestrator`

- [ ] Create `engines/neural-isp/impl/src/main/kotlin/com/leica/cam/neural_isp/pipeline/NeuralIspOrchestratorImpl.kt`:
    ```kotlin
    package com.leica.cam.neural_isp.pipeline

    import com.leica.cam.common.result.LeicaResult
    import com.leica.cam.neural_isp.api.INeuralIspOrchestrator
    import com.leica.cam.neural_isp.api.ThermalBudget
    import com.leica.cam.neural_isp.api.TonedBuffer
    import com.leica.cam.photon_matrix.FusedPhotonBuffer
    import javax.inject.Inject
    import javax.inject.Named
    import javax.inject.Singleton

    /**
     * Default [INeuralIspOrchestrator] implementation. Routes between the
     * neural and traditional image pipelines based on [ThermalBudget].
     *
     * Current status (Phase 0 wiring): this simply delegates to the routing
     * processor provided by [NeuralIspModule]. Per-SoC delegate selection
     * for real neural inference is delivered by Plan.md Dimension 1 (see
     * processing-problems.md §MicroISP).
     */
    @Singleton
    class NeuralIspOrchestratorImpl @Inject constructor(
        @Named("neural_processor") private val neural: ImagePipelineProcessor,
        @Named("traditional_processor") private val traditional: ImagePipelineProcessor,
    ) : INeuralIspOrchestrator {

        override suspend fun enhance(
            toned: TonedBuffer,
            thermalBudget: ThermalBudget,
        ): LeicaResult<FusedPhotonBuffer> {
            val processor = if (thermalBudget.tier.allowsNeuralIsp()) neural else traditional
            return processor.process(toned)
        }
    }
    ```
    **Trap:** `ThermalBudget` / `ThermalTier` members differ by codebase version. Read `engines/neural-isp/api/src/main/kotlin/com/leica/cam/neural_isp/api/NeuralIspContracts.kt` for the exact signature of `enhance()` and the `allowsNeuralIsp()` predicate. If `allowsNeuralIsp()` does not exist, branch on `thermalBudget.tier == ThermalTier.FULL` instead.
    **Trap:** `ImagePipelineProcessor.process(toned)` must return `LeicaResult<FusedPhotonBuffer>`. If it returns `LeicaResult<TonedBuffer>` in your branch, unwrap and rewrap via `FusedPhotonBuffer(underlying = …)`. Read the actual `ImagePipelineProcessor` interface before inventing behaviour.

- [ ] Modify `engines/neural-isp/impl/src/main/kotlin/com/leica/cam/neural_isp/di/NeuralIspModule.kt`. Append to the `object NeuralIspModule { … }` body:
    ```kotlin
        @Provides
        @Singleton
        fun provideNeuralIspOrchestrator(
            impl: NeuralIspOrchestratorImpl,
        ): com.leica.cam.neural_isp.api.INeuralIspOrchestrator = impl
    ```

### P1.5 — Bind `IHyperToneWB2Engine` to `HyperToneWB2Engine`

> The class `HyperToneWB2Engine` currently has a **no-arg primary constructor** but `HypertoneWbModule.provideHyperToneWB2Engine` calls it with **7 arguments**. P2 fixes the constructor. P1 just adds the interface binding so the DI graph is complete.

- [ ] Create the file `engines/hypertone-wb/impl/src/main/kotlin/com/leica/cam/hypertone_wb/pipeline/HyperToneWB2EngineAdapter.kt`:
    ```kotlin
    package com.leica.cam.hypertone_wb.pipeline

    import com.leica.cam.color_science.api.ColourMappedBuffer
    import com.leica.cam.common.result.LeicaResult
    import com.leica.cam.hypertone_wb.api.IHyperToneWB2Engine
    import com.leica.cam.hypertone_wb.api.IlluminantMap
    import com.leica.cam.hypertone_wb.api.SkinZoneMap
    import com.leica.cam.hypertone_wb.api.WbCorrectedBuffer
    import javax.inject.Inject
    import javax.inject.Singleton

    /**
     * Adapter from the concrete [HyperToneWB2Engine] to the public
     * [IHyperToneWB2Engine] contract consumed by the capture orchestrator.
     *
     * This is intentionally thin — it converts the pipeline's `process(frame, ...)`
     * API (RGB float frame in / RGB float frame out) to the LUMO contract's
     * `correct(colour, skin, illum)` (photon buffers in / corrected photon
     * buffers out). The concrete engine does the real work.
     */
    @Singleton
    class HyperToneWB2EngineAdapter @Inject constructor(
        private val engine: HyperToneWB2Engine,
    ) : IHyperToneWB2Engine {

        override suspend fun correct(
            colour: ColourMappedBuffer,
            skinZones: SkinZoneMap,
            illuminant: IlluminantMap,
        ): LeicaResult<WbCorrectedBuffer> {
            // The concrete HyperToneWB2Engine operates on RgbFrame; we pass the
            // ColourMappedBuffer's underlying photon buffer through, applying
            // the illuminant dominance as a single-zone correction until the
            // full zone-map-driven path is wired (see processing-problems.md §HyperTone-AWB).
            val underlying = colour.underlying
            return LeicaResult.Success(
                WbCorrectedBuffer.Corrected(
                    underlying = underlying,
                    dominantKelvin = illuminant.dominantKelvin,
                ),
            )
        }
    }
    ```
    **Trap:** `WbCorrectedBuffer.Corrected` has an `internal` constructor in `HyperToneWBContracts.kt`. `internal` in Kotlin restricts to module — since this adapter lives inside `:hypertone-wb:impl` but `WbCorrectedBuffer` is declared in `:hypertone-wb:api`, the `internal` visibility applies to that other module. If `WbCorrectedBuffer.Corrected` constructor is inaccessible here, the fix is to open up that constructor by removing the `internal` modifier in `HyperToneWBContracts.kt` **or** to add a public factory `fun corrected(...)` on the sealed class's companion object. Pick the minimal change (remove `internal`) and document it as a breaking API surface in `docs/known-issues/di.md`.

- [ ] Modify `engines/hypertone-wb/impl/src/main/kotlin/com/leica/cam/hypertone_wb/di/HypertoneWbModule.kt`. Inside the `object HypertoneWbModule { … }` body, **after** the `provideHyperToneWB2Engine(...)` function, add:
    ```kotlin
        @Provides
        @Singleton
        fun provideIHyperToneWB2Engine(
            adapter: HyperToneWB2EngineAdapter,
        ): com.leica.cam.hypertone_wb.api.IHyperToneWB2Engine = adapter
    ```

## P1 Verification

- [ ] Run: `./gradlew :app:kaptDebugKotlin --stacktrace 2>&1 | grep -i "MissingBinding\|could not find\|cannot be provided"`. **Expect:** no output (i.e. no missing bindings).
- [ ] Run: `./gradlew :app:kaptDebugKotlin --stacktrace`. **Expect:** either success, or a Kotlin **compile** error (P2). If kapt fails with a Dagger error, re-read P1 and check for typos.
- [ ] Commit with message `di(p1): complete Hilt graph — LeicaLogger, GpuBackend, TrueColourHardwareSensor, INeuralIspOrchestrator, IHyperToneWB2Engine bindings`.

---

# Sub-plan P2 — Compilation / type-mismatch / signature drift

**Gate:** `./gradlew :app:compileDebugKotlin` must finish successfully. No red squigglies.

## The failure modes to fix

### P2.1 — `HyperToneWB2Engine` constructor mismatch (build-breaking)

The current class has no declared constructor (implicit no-arg), but the DI module calls it as:

```kotlin
HyperToneWB2Engine(sensor, fusion, spatial, temporal, guard, gpu, logger)
```

This is an `Unresolved reference` / `Too many arguments` compile error the moment kapt generates the `HypertoneWbModule_ProvideHyperToneWB2EngineFactory`.

**Decision (baked in by the Advisor):** the engine keeps the current no-arg body (the physics code inside uses local state only — `prevCctKelvin`, `prevGreenGain`) **but** declares its dependencies so the DI call is valid. Those dependencies are wired through but not yet used in the body; the **real** zone-driven path is delivered by P4.3 and `processing-problems.md §HyperTone-AWB`.

- [ ] Locate in `engines/hypertone-wb/impl/src/main/kotlin/com/leica/cam/hypertone_wb/pipeline/HyperToneWB2Engine.kt`:
    ```kotlin
    class HyperToneWB2Engine {
    ```
- [ ] Replace it with:
    ```kotlin
    import com.leica.cam.common.logging.LeicaLogger
    import com.leica.cam.gpu_compute.GpuBackend
    import javax.inject.Inject
    import javax.inject.Singleton

    @Singleton
    class HyperToneWB2Engine @Inject constructor(
        @Suppress("unused") private val ctSensor: PartitionedCTSensor,
        @Suppress("unused") private val fusion: MultiModalIlluminantFusion,
        @Suppress("unused") private val spatial: MixedLightSpatialWbEngine,
        @Suppress("unused") private val temporal: WbTemporalMemory,
        @Suppress("unused") private val guard: SkinZoneWbGuard,
        @Suppress("unused") private val gpu: GpuBackend,
        @Suppress("unused") private val logger: LeicaLogger,
    ) {
    ```
    `@Suppress("unused")` is the **explicit sign** that these are wired-but-not-yet-consumed — detekt will otherwise fail. P4.3 removes the suppressions and actually calls each dependency.

- [ ] In the same file, delete the existing `provideHyperToneWB2Engine` block in `HypertoneWbModule.kt` if it exists (it's replaced by constructor injection). Then replace the module wiring:

    Locate in `engines/hypertone-wb/impl/src/main/kotlin/com/leica/cam/hypertone_wb/di/HypertoneWbModule.kt`:
    ```kotlin
    @Provides
    @Singleton
    fun provideHyperToneWB2Engine(
        sensor: PartitionedCTSensor,
        fusion: MultiModalIlluminantFusion,
        spatial: MixedLightSpatialWbEngine,
        temporal: WbTemporalMemory,
        guard: SkinZoneWbGuard,
        gpu: GpuBackend,
        logger: LeicaLogger,
    ) = HyperToneWB2Engine(sensor, fusion, spatial, temporal, guard, gpu, logger)
    ```
    Delete that whole function. Because `HyperToneWB2Engine` is now `@Inject`-able via its constructor and `@Singleton`, Hilt will provide it automatically.

### P2.2 — `AiEngineOrchestrator.downsample()` returns zeros

Observed in `engines/ai-engine/impl/src/main/kotlin/com/leica/cam/ai_engine/impl/pipeline/AiEngineOrchestrator.kt`:

```kotlin
private fun downsample(
    fused: FusedPhotonBuffer,
    targetWidth: Int,
    targetHeight: Int,
): FloatArray = FloatArray(targetWidth * targetHeight * 3)
```

This returns an all-zero tile. The downstream `SceneClassifier` and `SemanticSegmenter` then classify a black image, which silently poisons every AI output on the capture path. Compiles fine — but semantically broken.

- [ ] Replace the body with a minimal nearest-neighbour RGB downsampler over the fused buffer. The exact shape of `FusedPhotonBuffer` is in `core/photon-matrix/api/.../PhotonMatrixContracts.kt`; open it and read the accessors. Assuming it exposes per-channel float accessors `r[i]`, `g[i]`, `b[i]` with `width`/`height`:
    ```kotlin
    private fun downsample(
        fused: FusedPhotonBuffer,
        targetWidth: Int,
        targetHeight: Int,
    ): FloatArray {
        val out = FloatArray(targetWidth * targetHeight * 3)
        val srcW = fused.width
        val srcH = fused.height
        if (srcW == 0 || srcH == 0) return out
        val xScale = srcW.toFloat() / targetWidth
        val yScale = srcH.toFloat() / targetHeight
        var outIdx = 0
        for (y in 0 until targetHeight) {
            val sy = (y * yScale).toInt().coerceIn(0, srcH - 1)
            for (x in 0 until targetWidth) {
                val sx = (x * xScale).toInt().coerceIn(0, srcW - 1)
                val srcIdx = sy * srcW + sx
                out[outIdx++] = fused.r(srcIdx)
                out[outIdx++] = fused.g(srcIdx)
                out[outIdx++] = fused.b(srcIdx)
            }
        }
        return out
    }
    ```
    **Trap:** If `FusedPhotonBuffer` does not expose `r/g/b` accessors at that granularity, use whatever accessor it does expose (`getRgbTile(...)`, `asFloatArray()`, etc.). The goal is "non-zero output". Do not fabricate an API.

### P2.3 — `internal` constructors blocking the adapter

If P1.5 hit the `WbCorrectedBuffer.Corrected internal constructor` problem:

- [ ] Locate in `engines/hypertone-wb/api/src/main/kotlin/com/leica/cam/hypertone_wb/api/HyperToneWBContracts.kt`:
    ```kotlin
    data class Corrected internal constructor(
        override val underlying: FusedPhotonBuffer,
        val dominantKelvin: Float,
    ) : WbCorrectedBuffer()
    ```
- [ ] Replace with:
    ```kotlin
    data class Corrected(
        override val underlying: FusedPhotonBuffer,
        val dominantKelvin: Float,
    ) : WbCorrectedBuffer()
    ```
    This is a deliberate API-surface widening. Log it in `docs/known-issues/di.md` under "API changes made during P2".

### P2.4 — `Plan.md` was a *different* plan

The previous `Plan.md` at `HEAD~1` was specifically about wiring the color-science engine (CS-1…CS-6). That plan is **not** superseded here — it addressed the color-science subsystem in isolation. This plan (P0…P5) addresses **build/DI/permission/wiring** bugs that block **all** subsystems from running. After P4 lands, the earlier color-science plan's verification targets will become testable for the first time.

**Action:** none at this step. Just be aware when committing that the previous `Plan.md` content has moved to `docs/color-science/Plan-CS.md` as a subordinate plan (the Executor does this in P5.4).

## P2 Verification

- [ ] Run: `./gradlew :app:compileDebugKotlin --stacktrace`. **Expect:** exits 0.
- [ ] Run: `./gradlew :app:kaptDebugKotlin --stacktrace`. **Expect:** exits 0. The DI graph is now both complete and type-correct.
- [ ] Run: `./gradlew ktlintCheck detekt`. **Expect:** clean; any new violations are on your new files. Fix them.
- [ ] Commit with message `fix(p2): HyperToneWB2Engine constructor signature, AiEngineOrchestrator downsample, open WbCorrectedBuffer`.

---

# Sub-plan P3 — Android SDK / permissions / manifest / NDK

**Gate:** `./gradlew :app:assembleDebug` completes. `adb install -r app/build/outputs/apk/dev/debug/app-dev-debug.apk` succeeds on a running emulator/device.

## The failure modes to fix

### P3.1 — `compileSdk = 35` with AGP `8.4.2`

AGP 8.4.2 officially supports `compileSdk ≤ 34`. Building against API 35 with AGP 8.4 can succeed but emits the warning "compileSdk=35 may produce unexpected behaviour" and in some toolchain combinations **fails** at resource-merge time with `ResourceCompiler` errors.

**Decision (Advisor):** Pin `compileSdk = 34` for AGP 8.4.2. Do **not** upgrade AGP here — that is a separate, wider refactor with its own risk surface.

- [ ] Modify `build-logic/convention/src/main/kotlin/LeicaConventionSupport.kt`. Locate:
    ```kotlin
    internal const val LeicaCompileSdk = 35
    internal const val LeicaTargetSdk = 35
    ```
- [ ] Replace with:
    ```kotlin
    internal const val LeicaCompileSdk = 34
    internal const val LeicaTargetSdk = 34
    ```
- [ ] **Do not** change `LeicaMinSdk = 29`.

### P3.2 — Native code not wired into AGP

`platform-android/native-imaging-core/impl/src/main/cpp/CMakeLists.txt` + `native_imaging_core.cpp` exist, but the module's `build.gradle.kts` does not declare `externalNativeBuild { cmake { … } }` or an NDK version. The `.so` will never build; `NativeImagingBridge`'s `System.loadLibrary("native_imaging_core")` (or however it's named) will throw `UnsatisfiedLinkError` at runtime.

- [ ] Modify `platform-android/native-imaging-core/impl/build.gradle.kts`. After the `android { namespace = … }` opening brace, add:
    ```kotlin
    android {
        namespace = "com.leica.cam.native_imaging_core.impl"

        defaultConfig {
            consumerProguardFiles("consumer-rules.pro")
            externalNativeBuild {
                cmake {
                    arguments += listOf("-DANDROID_STL=c++_shared")
                    cppFlags += listOf("-std=c++20", "-fno-rtti", "-fno-exceptions")
                }
                ndk {
                    abiFilters += listOf("arm64-v8a")
                }
            }
        }

        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }

        ndkVersion = "27.0.12077973"   // r27 per README.md §Prerequisites
    }
    ```
    **Trap:** `ndkVersion` is exact; if the CI agent does not have r27 installed, the build will fail with "NDK not configured". Keep the version string in sync with `README.md` and note it in `docs/known-issues/build.md`.
    **Trap:** Check the `.cpp` for any use of RTTI / exceptions before adding the `-fno-rtti -fno-exceptions` flags. If it uses `dynamic_cast` or `throw`, remove those two flags.

### P3.3 — Missing runtime-permission plumbing for Android 13+ notifications

`CameraScreen.kt` fires `android.widget.Toast` on capture completion. On Android 13+ a toast does not require `POST_NOTIFICATIONS`, but the LUMO pipeline is planned to surface a **foreground-service** notification during long exposures / HDR merges (see `processing-problems.md §ProXDR`). Declare the permission now to avoid a second migration.

- [ ] Modify `app/src/main/AndroidManifest.xml`. After the last `<uses-permission … />` line, add:
    ```xml
    <!-- Notifications for long-running capture (HDR+, Night, Astro) — API 33+. -->
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    ```
- [ ] Also verify that `RequiredPermissions.kt` already lists `android.permission.CAMERA`, `android.permission.READ_MEDIA_IMAGES` (API 33+), `android.permission.READ_EXTERNAL_STORAGE` (API ≤ 32), and `android.permission.WRITE_EXTERNAL_STORAGE` (API ≤ 28) — this file **does** already do this correctly. No change needed.

### P3.4 — `android:screenOrientation="portrait"` blocks landscape

The app supports only portrait. For a camera UI that is typical, but the `screenOrientation` combined with `configChanges="orientation|screenSize|keyboardHidden|uiMode"` means an on-device rotation *never fires* `onConfigurationChanged` — so the EIS / composition horizon-guide logic never updates. The README promises `uses-feature android:name="android.hardware.camera.autofocus" android:required="false"` (optional hardware), but not rotation.

**Decision:** leave portrait lock in place. ARIA-X's judgement: camera UIs ship portrait-locked by default; landscape is a follow-up. Track in `docs/known-issues/sdk.md`.

### P3.5 — Manifest meta: `android:requestLegacyExternalStorage`

Currently absent — good for API 29+. Confirm the app targets `targetSdk = 34` (P3.1) which forces scoped storage, so no change is required.

## P3 Verification

- [ ] Run: `./gradlew :app:assembleDevDebug`. **Expect:** exits 0 and produces `app/build/outputs/apk/dev/debug/app-dev-debug.apk`.
- [ ] Run: `./gradlew :platform-android:native-imaging-core:impl:externalNativeBuildDevDebug`. **Expect:** exits 0; `.so` for `arm64-v8a` is produced under `build/intermediates/cxx/`.
- [ ] `adb install -r app/build/outputs/apk/dev/debug/app-dev-debug.apk`. **Expect:** `Success`.
- [ ] Launch the app on device. **Expect:** splash → permission prompt for CAMERA (and READ_MEDIA_IMAGES on API 33+). Accept. The preview surface renders (camera opens). Logcat shows `LeicaCamApp: Model warm-up complete: N models ready` within 2 seconds.
- [ ] Commit with message `chore(p3): pin compileSdk=34 for AGP 8.4.2, wire CMake, declare POST_NOTIFICATIONS`.

---

# Sub-plan P4 — Runtime wiring (shutter → processing)

**Gate:** on a real device, pressing the shutter in the preview screen produces a JPEG in `DCIM/Camera` whose **logcat trace** shows `FusionLM2Engine.fuse`, `ProXdrOrchestrator` (when HDR mode ≠ OFF), `HyperToneWhiteBalanceEngine` (the AWB-aware WB), and `ToneLM2Engine.apply` all executing in order. Not just log lines — real method calls on real engines.

## The failure modes to fix

### P4.1 — `CaptureProcessingOrchestrator.fusionFrames()` is a stub

In `core/capture-orchestrator/src/main/kotlin/com/leica/cam/capture/orchestrator/CaptureProcessingOrchestrator.kt`:

```kotlin
private fun fusionFrames(aligned: AlignedBuffer, request: CaptureRequest): FusedPhotonBuffer {
    val firstFrame = aligned.frames.first()
    val fusionQuality = if (aligned.frames.size >= FUSION_MIN_FRAMES) 1.0f else 0.7f
    return FusedPhotonBuffer(underlying = firstFrame, fusionQuality = fusionQuality, frameCount = aligned.frames.size, motionMagnitude = 0f)
}
```

Takes the first frame. No Wiener, no Debevec, no Mertens. The log line says "FusionLM 2.0".

- [ ] Inject `FusionLM2Engine` into the orchestrator. Modify the primary constructor of `CaptureProcessingOrchestrator`:
    ```kotlin
    class CaptureProcessingOrchestrator @Inject constructor(
        // … existing deps …
        private val fusionEngine: com.leica.cam.imaging_pipeline.pipeline.FusionLM2Engine,
        private val toneEngine: com.leica.cam.imaging_pipeline.pipeline.ToneLM2Engine,
        private val proXdrOrchestrator: com.leica.cam.imaging_pipeline.hdr.ProXdrOrchestrator,
        private val hyperToneWbEngine: com.leica.cam.hypertone_wb.pipeline.HyperToneWhiteBalanceEngine,
    ```
    **Trap:** These classes live in `:imaging-pipeline:impl` and `:hypertone-wb:impl`. `:core:capture-orchestrator` currently depends on their `:api` counterparts only. Add `implementation(project(":imaging-pipeline:impl"))` and `implementation(project(":hypertone-wb:impl"))` to `core/capture-orchestrator/build.gradle.kts`.
    **Trap:** Adding `:impl` → `:impl` module deps violates the README's "engines talk only through `:api`" rule. **This is the explicit crack in that rule**: the capture orchestrator is in `:core:capture-orchestrator`, which already sits above the engine layer (it's a top-level composer). Document the exception in `docs/known-issues/wiring.md`. The clean long-term fix is to publish a `:imaging-pipeline:facade` that re-exports these three classes — but that refactor is out of scope here.

- [ ] Replace the body of `fusionFrames()`:
    ```kotlin
    private suspend fun fusionFrames(
        aligned: com.leica.cam.motion_engine.api.AlignedBuffer,
        request: CaptureRequest,
    ): FusedPhotonBuffer {
        val fusionConfig = com.leica.cam.smart_imaging.FusionConfig()
        return when (val r = fusionEngine.fuse(aligned, fusionConfig)) {
            is LeicaResult.Success -> r.value
            is LeicaResult.Failure -> {
                logger.warn(TAG, "FusionLM2 failed: ${r.message}; falling back to first-frame")
                FusedPhotonBuffer(
                    underlying = aligned.frames.first(),
                    fusionQuality = 0.5f,
                    frameCount = aligned.frames.size,
                    motionMagnitude = 0f,
                )
            }
        }
    }
    ```
    **Trap:** `FusionLM2Engine.fuse` signature — read `engines/imaging-pipeline/impl/src/main/kotlin/com/leica/cam/imaging_pipeline/pipeline/FusionLM2Engine.kt` first. If it takes a different shape, adapt.

### P4.2 — ProXDR never runs under PRO_XDR mode

Stage 4c picks an `HdrStrategy` but nothing in the orchestrator ever **calls** `ProXdrOrchestrator.orchestrate(...)` on the fused frame. Under `HdrCaptureMode.PRO_XDR` the pipeline still runs Stage 10 (Durand bilateral) on an HDR-merged frame that was built by a placeholder.

- [ ] After `fused = fusionFrames(...)` in `processCapture()`, add a branch:
    ```kotlin
    val postHdr: FusedPhotonBuffer = if (hdrMode == HdrMode.PRO_XDR) {
        when (val r = proXdrOrchestrator.orchestrate(fused, hdrStrategy)) {
            is LeicaResult.Success -> r.value
            is LeicaResult.Failure -> {
                logger.warn(TAG, "ProXDR failed: ${r.message}; continuing with fused frame")
                fused
            }
        }
    } else fused
    ```
    Then use `postHdr` (not `fused`) in the remaining stages.
    **Trap:** `ProXdrOrchestrator.orchestrate` may take a different parameter shape — check the class. Its input is likely a list of frames + a strategy, not a single `FusedPhotonBuffer`. In that case, pass `aligned.frames` and the strategy, and expect a `FusedPhotonBuffer` back.

### P4.3 — HyperTone neural-AWB path is dead-on-arrival

`HyperToneWhiteBalanceEngine` (with the `AwbPredictor?` constructor param) is the *only* class that actually calls `AwbModelRunner`. It is injected **nowhere** except in its own test. The orchestrator's `runWhiteBalanceCorrection()` goes through `IHyperToneWB2Engine.correct(colour, skinZones, illuminant)` which has no access to the neural prior.

- [ ] Replace the body of `runWhiteBalanceCorrection()` to *first* run `HyperToneWhiteBalanceEngine.process(...)` (which invokes the neural AWB model), *then* hand the result off to `IHyperToneWB2Engine.correct(...)` for the per-zone bilateral correction. Skeleton:
    ```kotlin
    private suspend fun runWhiteBalanceCorrection(
        results: ParallelAnalysisResults,
        ispDecision: CaptureTimeIspRouter.IspRoutingDecision,
        request: CaptureRequest,
        fused: FusedPhotonBuffer,
    ): LeicaResult<TonedBuffer> {
        // 1) Neural-AWB-aware global WB pass (runs AwbModelRunner if available).
        val frame = fused.toRgbFrame()    // helper: builds a com.leica.cam.hypertone_wb.pipeline.RgbFrame
        val neuralWbFrame = when (val r = hyperToneWbEngine.process(
            frame = frame,
            sensorToXyz3x3 = request.sensorToXyz3x3(),
            sceneContext = null,
            skinMask = null,
            wbBias = null,
        )) {
            is LeicaResult.Success -> r.value
            is LeicaResult.Failure -> {
                logger.warn(TAG, "Neural AWB pass failed: ${r.message}")
                frame
            }
        }

        // 2) Per-zone bilateral WB correction via IHyperToneWB2Engine.
        val skinZones = SkinZoneMap(results.face.skinZones.width, results.face.skinZones.height, results.face.skinZones.mask)
        val illuminantMap = IlluminantMap(
            tiles = emptyList(),
            dominantKelvin = results.scene.illuminantHint.estimatedKelvin,
        )
        val wbCorrected = wbEngine.correct(results.colour.withFrame(neuralWbFrame), skinZones, illuminantMap)
            .getOrElse { return it }

        val underlying = (wbCorrected as WbCorrectedBuffer.Corrected).underlying
        return LeicaResult.Success(TonedBuffer.TonedImage(underlying, request.toneConfig.profile))
    }
    ```
    **Trap:** `FusedPhotonBuffer.toRgbFrame()`, `CaptureRequest.sensorToXyz3x3()`, and `ColourMappedBuffer.withFrame(...)` do not exist yet. Add them as extension functions in `CaptureProcessingOrchestratorExtensions.kt` (new file in the same package). The matrix itself should come from `Camera2CameraInfo` — if not available at this step, return the identity matrix `floatArrayOf(1f,0f,0f, 0f,1f,0f, 0f,0f,1f)` with a `// TODO: wire real CameraCharacteristics.SENSOR_FORWARD_MATRIX1` comment.

### P4.4 — Tone-mapping stage does not call `ToneLM2Engine`

Stage 10 calls `perceptualToneMapper.map(buffer = wbResult, scene = …, toneConfig = …)`. That is the `capture-orchestrator`'s **own** `PerceptualToneMapper` — a local class. The engine module `:imaging-pipeline:impl` ships `ToneLM2Engine` with the Durand bilateral + cinematic S-curve + luminosity-only sharpening that the README promises. It never runs.

- [ ] After the `perceptualToneMapper.map(...)` call in Stage 10, replace the `toneMapped` assignment:
    ```kotlin
    val toneMapped = toneEngine.apply(
        buffer = wbResult,
        scene = parallelResults.scene,
        toneConfig = captureRequest.toneConfig,
    ).getOrElse {
        logger.warn(TAG, "ToneLM2 failed: ${it.message}; falling back to PerceptualToneMapper")
        perceptualToneMapper.map(
            buffer = wbResult,
            scene = parallelResults.scene,
            toneConfig = captureRequest.toneConfig,
        )
    }
    ```
    **Trap:** `ToneLM2Engine.apply` signature — read `engines/imaging-pipeline/impl/.../pipeline/ToneLM2Engine.kt`. It likely returns a `LeicaResult<TonedBuffer>`. If the shape differs, adapt.

### P4.5 — MicroISP runner only fires on ultra-wide / front

`MicroIspRunner.isEligible(sensorId)` returns true for `ov08d10`, `ov16a1q`, `gc16b3`. That matches the README — MicroISP is intentionally disabled on the main S5KHM6 to avoid doubling the Imagiq ISP output. But the orchestrator never **asks** whether the current sensor is eligible. On a build that runs the main sensor, MicroISP silently never runs; on a build that runs the ultra-wide, it still never runs because nothing calls `refine(bayerTile)`.

The fix surface is large and belongs in `processing-problems.md §MicroISP`. This sub-plan only:

- [ ] Adds a TODO log line at Stage 11 (Neural ISP) noting the sensor-id-eligibility check is not yet wired:
    ```kotlin
    logger.info(TAG, "ISP enhancement: ${if (ispDecision.useNeuralIsp) "Neural" else "Traditional"} (MicroISP sensor-id gate: TODO — see processing-problems.md §MicroISP)")
    ```

## P4 Verification

- [ ] Run: `./gradlew :app:assembleDebug` — still green after P4 edits.
- [ ] On device, capture one photo in AUTO mode. In logcat, filter for `CaptureProcessingOrchestrator`. **Expect** these lines **in this order**:
    - `ZSL: Retrieved N burst frames`
    - `Fusion complete: quality=… frameCount=N` — and the logcat line from **inside** `FusionLM2Engine.fuse` (grep for `FusionLM2`)
    - `HyperTone WB applied`  — **and** a logcat line from inside `AwbModelRunner.predict` if the AWB model asset was loaded
    - `Perceptual tone mapping applied (Stages A-E)` — **and** a logcat line from inside `ToneLM2Engine.apply`
    - `━━━ CAPTURE COMPLETE ━━━`
- [ ] Flip HDR mode to PRO_XDR and capture again. **Expect** an additional logcat line from `ProXdrOrchestrator`.
- [ ] Commit with message `feat(p4): wire FusionLM2/ProXDR/ToneLM2/HyperTone neural-AWB into CaptureProcessingOrchestrator`.

---

# Sub-plan P5 — Known-issues registry + verification gates

**Gate:** `docs/known-issues/` exists and every issue flagged in P0–P4 has a file entry. `.github/workflows/ci.yml` runs `./gradlew :app:assembleDebug` as a hard gate on every PR.

## Steps

### P5.1 — Seed `docs/known-issues/README.md`

- [ ] Create the file with exactly this content:

    ```markdown
    # Known Issues Registry

    Every entry is a future work item that was observed during the P0–P4
    build-and-wire hardening pass (see `/Plan.md`). Entries are grouped by
    category; the registry is append-only.

    | File | Category |
    |---|---|
    | `build.md` | Gradle / AGP / NDK |
    | `di.md` | Hilt / DI graph |
    | `processing.md` | Algorithmic wiring — cross-linked to `/processing-problems.md` |
    | `sdk.md` | Android SDK / permissions / target API |
    | `wiring.md` | Module-graph / architectural debt |

    ## Ledger format

    Each row in a file is:

    ```
    ### I<yyyy-mm-dd>-<n>: <short title>
    - Observed in: <file:line or commit sha>
    - Severity: <BLOCKER | MAJOR | MINOR | STYLE>
    - Owner: <team / skill>
    - Linked plan: <Plan.md#subplan or external>
    - Resolution: <open | deferred | in-progress | resolved in commit X>
    ```
    ```

### P5.2 — Seed each category file

Populate `build.md`, `di.md`, `processing.md`, `sdk.md`, `wiring.md` with one row per issue fixed in P0–P4 (resolution: "resolved in P<n>"), plus **open** rows for all the items the sub-plans explicitly deferred (MicroISP sensor-id gate, AGP 8.7 upgrade, real `CameraCharacteristics.SENSOR_FORWARD_MATRIX1` ingestion, etc.).

Use the exact section layout from `README.md`. Keep each entry to 8-10 lines.

### P5.3 — CI gate

- [ ] Create/modify `.github/workflows/ci.yml`. Minimal content:
    ```yaml
    name: ci
    on: [push, pull_request]
    jobs:
      build:
        runs-on: ubuntu-latest
        steps:
          - uses: actions/checkout@v4
          - uses: actions/setup-java@v4
            with: { distribution: temurin, java-version: 17 }
          - name: Install NDK r27
            run: |
              sdkmanager --install "ndk;27.0.12077973"
          - name: Assemble
            run: ./gradlew :app:assembleDevDebug --stacktrace
          - name: Unit tests
            run: ./gradlew test --stacktrace
          - name: Lint
            run: ./gradlew ktlintCheck detekt --stacktrace
    ```

### P5.4 — Relocate the previous color-science plan

- [ ] Move the color-science plan content that used to live at `Plan.md` (as of commit `33e43f0`) to `docs/color-science/Plan-CS.md` so the top-level `Plan.md` is now the engineering-remediation plan (this file). Reference it in `docs/known-issues/processing.md` under the color-science ledger row.

## P5 Verification

- [ ] `ls docs/known-issues/` shows the five files.
- [ ] Open a dummy PR touching any file. CI turns green.
- [ ] Commit with message `docs(p5): seed docs/known-issues/, wire CI gates, relocate color-science plan`.

---

## Known Edge Cases & Gotchas

- **Trap:** `kapt` incrementally compiles poorly in this project because of the 33-module graph. After P1 lands, the first full build on a clean cache takes ~6 minutes. Do not interpret a slow build as a hang.
  **Do this instead:** run with `--max-workers=4` and watch for the `Hilt:transformClasses*` log line; if it appears, kapt is making progress.

- **Trap:** Dagger sometimes reports `[Dagger/MissingBinding] SomeType cannot be provided without an @Inject constructor` for a type that *does* have an `@Inject` constructor. This is almost always because **the module declaring the type has no `kapt` plugin applied** (the P0 failure mode).
  **Do this instead:** when you see that error, first run `./gradlew :that-module:dependencies` and verify the `kapt` configuration exists.

- **Trap:** `@Singleton` on an adapter class (P1.5's `HyperToneWB2EngineAdapter`) combined with a non-`@Singleton` delegate (`HyperToneWB2Engine`) causes two instances to exist — the singleton adapter holds a reference to the non-singleton delegate, and a different caller may instantiate another `HyperToneWB2Engine`. Since `HyperToneWB2Engine` holds per-capture temporal state (`prevCctKelvin`, `prevGreenGain`), this causes temporal-smoothing jitter.
  **Do this instead:** mark **both** the adapter and the delegate `@Singleton` (P2.1 already does this).

- **Trap:** `kaptDebugKotlin` in one module can cascade-fail `:app:kaptDebugKotlin` with confusing wrapped messages.
  **Do this instead:** always run the failing *leaf* module first (`./gradlew :engines:hypertone-wb:impl:kaptDebugKotlin`) to get the real error.

- **Trap:** The Android-only `android.util.Log` reference must never appear in `:common` (pure JVM). A kapt build can nonetheless *appear* to compile it because kapt relaxes classpath checks — the failure surfaces at runtime with `NoClassDefFoundError: android/util/Log`.
  **Do this instead:** keep `AndroidLeicaLogger` strictly in `:app`. The JVM default in `:common` is a `NoOpLeicaLogger` (P1.1).

- **Trap:** The `externalNativeBuild { cmake { version = "3.22.1" } }` block requires that exact CMake to be installed on the SDK machine. CI images sometimes ship 3.18 only.
  **Do this instead:** add `sdkmanager --install "cmake;3.22.1"` to the CI workflow (P5.3 already includes `ndk;27.0.12077973`; extend with cmake).

- **Trap:** `MediaPipeTasks.FaceLandmarker.createFromOptions` loads the `.task` file from **assets** — the `preBuild` task already copies `/Model/**/*.task` to `app/src/main/assets/models/`. If that task does not run (e.g. because the `:app` module's `preBuild` was skipped), FaceLandmarker returns null silently.
  **Do this instead:** before every release build, run `./gradlew :app:copyOnDeviceModels` explicitly and verify `app/src/main/assets/models/Face Landmarker/face_landmarker.task` exists.

- **Trap:** When P4.3's `HyperToneWhiteBalanceEngine` is first wired, its `AwbPredictor?` is **nullable** — if Dagger cannot find a provider for `AwbPredictor`, the engine is instantiated with `null` and silently skips the neural prior. The fallback behaviour is "grey-world CCT", which is nearly indistinguishable in normal daylight but wrong under mixed indoor light.
  **Do this instead:** after P4, add a unit test that asserts `DaggerAppComponent ... HyperToneWhiteBalanceEngine.awbPredictor` is non-null on a real DI graph. (See `processing-problems.md §HyperTone-AWB` for the assertion.)

---

## Out of Scope

- Upgrading AGP beyond 8.4.2 or Kotlin beyond 1.9.24. (`docs/known-issues/build.md` tracks this as a separate item.)
- Replacing kapt with KSP for Hilt. (Blocked on Hilt 2.52+ which requires Kotlin 2.0+.)
- Implementing the MicroISP sensor-id runtime gate. (Handled in `processing-problems.md §MicroISP`.)
- The color-science plan (CS-1…CS-6 in the old `Plan.md`). Relocated to `docs/color-science/Plan-CS.md` by P5.4 — the Executor may pick it up after P4 is green.
- Any UI/UX redesign. (Track under `critique` / `impeccable` skills.)
- Real Camera2 forward-matrix ingestion. (Listed as a TODO in P4.3 and tracked under `docs/known-issues/processing.md` — drawn from the old Plan.md CS-2.)
- Release signing, Play Store upload, crash reporting. (A release plan is out of scope; track under `android-app-builder`'s `release-checklist.md`.)

---

## Appendix A — Complete inventory of observed issues (for the Executor's reference)

This is the raw list collected during codebase triage. Each item is already covered by the sub-plans above; the list is here so the Executor can **search** for a specific error message when debugging.

### Build / Gradle (covered by P0, P3)

1. `core/capture-orchestrator/build.gradle.kts` — missing `alias(libs.plugins.hilt)` and `alias(libs.plugins.kotlin.kapt)`. Module contains `@Module object CaptureOrchestratorModule` with 20+ `@Provides` functions and an `@Inject constructor(...)` class (`CaptureProcessingOrchestrator`). Without kapt, none of these factories are generated.
2. `core/photon-matrix/impl/build.gradle.kts` — same gap; `PhotonMatrixIngestor` and `PhotonMatrixAssembler` are the only implementations of `IPhotonMatrixIngestor` / `IPhotonMatrixAssembler` but without kapt they cannot be injected. Also, there is no `@Module` here providing them — P1.4's `@Binds`-pattern needs to be mirrored for these two interfaces (add to the Appendix of `docs/known-issues/di.md`; it's handled implicitly because `PhotonMatrixIngestor : IPhotonMatrixIngestor @Inject constructor()` works out of the box once hilt+kapt apply).
3. `engines/smart-imaging/impl/build.gradle.kts` — same gap; `SmartImagingOrchestrator` is `@Inject constructor(...)` with 16 dependencies. Also missing `implementation(project(":imaging-pipeline:impl"))` so `FusionLM2Engine` / `ToneLM2Engine` are compile-only shadows.
4. `platform-android/native-imaging-core/impl/build.gradle.kts` — `externalNativeBuild { cmake { … } }` block entirely absent. CMake file exists at `src/main/cpp/CMakeLists.txt`; `.so` never produced.
5. AGP `8.4.2` ↔ `compileSdk = 35` mismatch (see P3.1). AGP 8.7+ is required for compileSdk=35. Pinning `compileSdk = 34` is the zero-risk fix.
6. Gradle wrapper is `8.8` — OK for AGP 8.4.2.
7. `gradle.properties` is missing the `kotlin.incremental=true` and `kotlin.incremental.useClasspathSnapshot=true` flags — minor performance. Not a blocker. `docs/known-issues/build.md`.

### DI / Hilt (covered by P1, P2)

8. `IHyperToneWB2Engine` — no implementation anywhere. Two orchestrators inject it. **Dagger kapt will fail** with `[Dagger/MissingBinding] IHyperToneWB2Engine cannot be provided without a @Provides-annotated method`.
9. `INeuralIspOrchestrator` — no `@Provides`; module provides stages only. Same `[Dagger/MissingBinding]` failure.
10. `LeicaLogger` — interface exists in `:common`; no implementation, no `@Binds`, no `@Provides`.
11. `GpuBackend` — `sealed interface` with three variants; no `@Provides` in `GpuComputeModule`.
12. `TrueColourHardwareSensor` — interface in `:hardware-contracts`; only test code implements it (anonymous `object :`).
13. `HyperToneWB2Engine` — the class has a no-arg constructor but `HypertoneWbModule.provideHyperToneWB2Engine` calls it as a 7-arg constructor. **This is a Kotlin compile error**, not a Dagger error. Kotlin will stop the build before kapt even runs — but only in modules where the call-site is compiled, which is `:hypertone-wb:impl` itself.
14. `AwbPredictor?` in `HyperToneWhiteBalanceEngine` — the `?` makes it nullable, which Hilt resolves as "provide if available else null". Fine. But nothing actually wires `HyperToneWhiteBalanceEngine` into the capture path (P4.3).
15. `WbCorrectedBuffer.Corrected internal constructor` — cross-module `internal` visibility blocks the adapter in P1.5. Fix by widening to public (P2.3) and logging the API-surface change.

### Compilation / semantics (covered by P2)

16. `AiEngineOrchestrator.downsample()` returns a zero-filled array — compiles, semantically broken. Every AI model on the capture path classifies black pixels.
17. `AwbModelRunner.validateOutputLayout` uses reflection to peek inside `LiteRtSession`'s private `interpreterHandle` field (via `javaClass.getDeclaredField`). Works today because R8 is disabled for the `AiEngineOrchestrator` class in debug, but ProGuard/R8 in release will **rename that field** and break the check. Add a keep rule in P5 to `app/proguard-rules.pro`:
    ```
    -keepclassmembers class com.leica.cam.ai_engine.impl.runtime.LiteRtSession {
        private <fields>;
    }
    ```
    This is a MINOR item listed in `docs/known-issues/di.md`; the short-term fix is to expose a typed accessor on `LiteRtSession` (Phase-0 wiring).
18. `SceneClassifierRunner.detectNormStyle` uses the same reflection trick. Same fix.
19. `AiContracts.SceneLabel` and `ColorScienceContracts.IlluminantHint` — both packages declare their own `IlluminantHint`. `CaptureProcessingOrchestrator` imports the color-science variant, then passes `results.scene.illuminantHint` (the AI variant) into it — implicit type coercion currently works because the field names match, but they are **distinct types**. Add a mapper `AiIlluminantHint.toColorScienceIlluminantHint()` and use it explicitly.

### SDK / permissions / manifest / NDK (covered by P3)

20. `compileSdk = 35` vs AGP 8.4.2 (P3.1).
21. Missing `POST_NOTIFICATIONS` for API 33+ (P3.3).
22. `externalNativeBuild` unconfigured (P3.2).
23. Manifest declares `android:screenOrientation="portrait"` plus `configChanges="orientation|..."`. Portrait-lock is fine. Combined with `uses-feature android.hardware.camera.any` required, the app silently refuses to install on devices without a back camera — which is fine for a flagship target but note it. Track as MINOR in `docs/known-issues/sdk.md`.
24. The `app/src/main/res/` directory contains launcher icons but **no `values/strings.xml`**. `@string/app_name` is referenced nowhere (the manifest uses `android:label="LeicaCam"` directly), so no build failure. Still: add `strings.xml` for i18n hygiene. MINOR.
25. `LeicaCamApp` uses `@JvmSuppressWildcards Function1<String, ByteBuffer>` as an `@Inject` target. This is advanced Hilt and works — but if the `@Named("assetBytes")` provider is wrong (P1 verifies it exists in `AiEngineModule`), the failure message is opaque. **Do this instead:** if you see `kotlin.jvm.functions.Function1 cannot be provided`, check the `@Named("assetBytes")` name spelling.
26. `android:allowBackup="false"` — correct for a camera app (captures may contain PII). No change.

### Runtime wiring (covered by P4)

27. `fusionFrames()` returns `frames.first()` (P4.1).
28. `ProXdrOrchestrator.orchestrate(...)` is never called from the capture orchestrator (P4.2).
29. `HyperToneWhiteBalanceEngine.process(...)` is never called — so `AwbModelRunner.predict(...)` is also never called from production code (P4.3).
30. `ToneLM2Engine.apply(...)` is never called — local `PerceptualToneMapper` shadows it (P4.4).
31. `ImagingPipeline.run(...)` is never called from the capture path at all. `ImagingPipelineModule` provides it; `CaptureProcessingOrchestrator` does not import it. This is covered implicitly by P4.4 — once ToneLM2 / FusionLM2 / ProXDR are wired, `ImagingPipeline` (which composes all three) can be used in its stead. Track as MAJOR in `docs/known-issues/wiring.md` with a follow-up cleanup.
32. `MicroIspRunner.isEligible(sensorId)` is never called (P4.5, deferred).
33. The `ZeroShutterLagRingBuffer<Any>` is provided with capacity 15 but **nothing ever calls `zslBuffer.add(...)`** — so the orchestrator always hits the `"ZSL buffer empty"` warn branch and uses `createFallbackFrame()` which is literally `Any()`. This breaks the entire fusion pipeline silently. Track as BLOCKER in `docs/known-issues/wiring.md`; the real fix is to add a frame-in callback from the Camera2 preview path. That fix is **out of scope** for this plan (it sits across `:sensor-hal`, `:feature:camera`, and `:core:capture-orchestrator`) — but note it loudly.
34. `NativeImagingRuntimeFacade.submitPreviewFrame(...)` in `:feature:camera` is not called from `CameraPreview.kt`. The native runtime is never fed frames. Track as MAJOR — it gates all GPU preview-path processing.

### Quality / style / detekt (covered by ktlint runs at each gate)

35. Several files use `!!` without a justification comment (engineering-principles violation). Running `detekt` after P2 will surface them; fix as encountered.
36. `AiEngineOrchestrator` uses `catch (e: Throwable)` on a cancellable coroutine path inside `runCatching` — rethrows `CancellationException` manually (correct). Verify the same pattern is used everywhere `runCatching` wraps a suspend call.
37. Some constants in `ImagingPipeline.kt` (e.g. `GHOST_VARIANCE_THRESHOLD = 0.04f`) have physical justifications in KDoc — keep this standard for any new constants the Executor adds.

---

## Appendix B — Skill invocation map

The skills and agents referenced in the sub-plans live under `.agents/skills/`. Below is where to invoke each for the Executor:

| Skill | Invoked in | What it does for you |
|---|---|---|
| `android-app-builder` (ARIA-X) | P0 setup, P3 NDK wiring, P4 shutter-path debugging | Full Android build-and-crash expertise; references `build-errors.md`, `crash-patterns.md`, `camera-advanced.md`, `release-checklist.md`. |
| `Backend Enhancer` | P0 module build files, P1 DI module authoring | Hilt/Kotlin/DI graph author. |
| `kotlin-specialist` | P2 constructor / type / signature fixes | Deep Kotlin-language fluency (sealed classes, internal visibility, variance). |
| `critique` + `impeccable` | After P4 green; run on the capture screen UI | UX scoring, AI-slop detection. |
| `Lumo Imaging Engineer` | Cross-reference during P4 when wiring FusionLM2 / ProXDR / ToneLM2 | Imaging-pipeline domain knowledge. |
| `color-science-engineer` | After P4, when picking up the relocated `docs/color-science/Plan-CS.md` | Color-science specialist (CCM / OKLAB / ΔE2000). |
| `the-advisor` (this plan's author) | At any point if the sub-plan itself needs updating | Planning pass; do not use for implementation. |
| `systematic-debugging` | If any gate fails twice | Debug-workflow skill; applies when the Executor is stuck. |
| `analyzing-projects` | P5 when seeding `docs/known-issues/` | Project-triage skill; helps write category entries in consistent format. |
| `writing-plans` + `writing-skills` | P5.1 when authoring the ledger | Doc-authoring standards. |
| `finishing-a-development-branch` | After P5 green; preparing the PR | Squash + conventional-commit authoring. |
| `managing-git` | Final commit + PR step | Git hygiene. |

Skill activation for Claude Code: `activate_ai_developer_skill(skill_name="<name>")`. For other executor models, read `.agents/skills/<name>/SKILL.md` directly.

---

*End of Plan.md.*
