# Project Structure — LeicaCam

_Last updated: 2026-04-23 (UTC) after the P0–P3 repair pass._

This document is the repository map for the current LeicaCam tree. It reflects the repaired state where:

- `app/src/main/kotlin/com/leica/cam/di/AssetsModule.kt` is gone.
- `:ai-engine:api` owns the shared AI contracts used by other modules.
- `ImagingPipeline` now routes HDR through `hdr/ProXdrOrchestrator`.
- `pipeline/ProXdrHdrEngine.kt` is deleted.
- LiteRT and MediaPipe are direct dependencies of `:ai-engine:impl`.
- Reflection remains only in `VendorDelegateLoader` for vendor-only delegates.

If code and this file diverge, update this file.

---

## 1. Executive Snapshot

LeicaCam is a multi-module Android computational-photography stack built around strict `api` / `impl` boundaries.

Core characteristics:

- **Android app shell:** `:app`
- **Feature modules:** `:feature:camera`, `:feature:gallery`, `:feature:settings`
- **Core engines:** AI, HDR / imaging pipeline, HyperTone WB, depth, face, motion, bokeh, neural ISP, smart imaging
- **Platform/shared layers:** `:common`, `:hardware-contracts`, `:sensor-hal`, `:gpu-compute`, `:native-imaging-core`, `:ui-components`
- **Native path:** JNI-backed runtime under `platform-android/native-imaging-core/impl`
- **On-device ML:** LiteRT + MediaPipe in `engines/ai-engine/impl`

Repository rules that matter for this repaired state:

- `:xyz:impl` depends on its own `:api` plus other modules' `:api`, never another engine's `:impl`.
- Hilt graph contributors must be on the `:app` classpath.
- `CancellationException` / `InterruptedException` must not be swallowed.
- Shared contracts used across engines live in API modules, not implementation packages.

---

## 2. Top-Level Directory Map

```text
/
├── app/                         Android app shell (Application + Activity + nav)
├── features/                    User-facing camera / gallery / settings flows
├── core/                        Camera core, color science, lens model, photon matrix
├── engines/                     AI, HDR pipeline, HyperTone WB, depth, face, motion, etc.
├── platform/                    Pure Kotlin shared contracts and result/error types
├── platform-android/            Android-specific runtime / GPU / Camera2 / JNI modules
├── Model/                       Source model assets copied into app assets at build time
├── build-logic/                 Convention plugins
├── Plan.md                      Repair / upgrade execution plan
├── Problems list.md             Defect source of truth used for the repair pass
└── project-structure.md         This file
```

### `app/`

- `LeicaCamApp.kt` — `@HiltAndroidApp`; warms all discovered AI models using `ModelRegistry.warmUpAll(...)`
- `MainActivity.kt` — navigation host / Compose entry point
- No local `AssetsModule.kt` remains; the canonical `assetBytes` provider lives in `:ai-engine:impl`

### `features/`

- `camera/` — preview, controls, capture UI, runtime facade
- `gallery/` — gallery UI and metadata presentation
- `settings/` — settings UI plus preferences repository/store

### `core/`

- `camera-core/` — camera-core contracts and implementation glue
- `color-science/` — color rendering pipeline pieces
- `lens-model/` — lens calibration / correction data
- `photon-matrix/` — fused photon buffer contracts and implementation

### `engines/`

- `ai-engine/` — shared AI contracts in `api`, live LiteRT/MediaPipe runners in `impl`
- `imaging-pipeline/` — HDR, tone-map, merge, metadata, computational pipeline
- `hypertone-wb/` — white-balance engines and supporting models
- `depth-engine/`, `face-engine/`, `motion-engine/`, `bokeh-engine/`, `neural-isp/`, `smart-imaging/`

### `platform/`

- `common/` — `LeicaResult`, `PipelineStage`, `ThermalState`, logging helpers, shared primitives
- `hardware-contracts/` — canonical photon / sensor / GPU / NPU contracts
- `common-test/` — shared test fixtures

### `platform-android/`

- `sensor-hal/` — Camera2 / CameraX integration, ZSL buffer, session manager
- `gpu-compute/` — Vulkan / OpenGL / CPU compute backends
- `native-imaging-core/` — Kotlin orchestrator + C++ runtime bridge
- `ui-components/` — shared Android UI components

---

## 3. Gradle Module Graph

Source of truth: `settings.gradle.kts`

### App + features

- `:app`
- `:feature:camera`
- `:feature:gallery`
- `:feature:settings`

### Core modules

- `:camera-core:api`, `:camera-core:impl`
- `:color-science:api`, `:color-science:impl`
- `:photon-matrix:api`, `:photon-matrix:impl`
- `:lens-model`

### Imaging / AI engine modules

- `:imaging-pipeline:api`, `:imaging-pipeline:impl`
- `:hypertone-wb:api`, `:hypertone-wb:impl`
- `:ai-engine:api`, `:ai-engine:impl`
- `:depth-engine:api`, `:depth-engine:impl`
- `:face-engine:api`, `:face-engine:impl`
- `:neural-isp:api`, `:neural-isp:impl`
- `:smart-imaging:api`, `:smart-imaging:impl`
- `:bokeh-engine:api`, `:bokeh-engine:impl`
- `:motion-engine:api`, `:motion-engine:impl`

### Platform / shared modules

- `:native-imaging-core:api`, `:native-imaging-core:impl`
- `:sensor-hal`
- `:gpu-compute`
- `:ui-components`
- `:common`
- `:common-test`
- `:hardware-contracts`

### Repair-pass dependency changes to note

- `:app` now includes the engine impl modules required for Hilt aggregation.
- `:imaging-pipeline:impl` depends on `:ai-engine:api`, not `:ai-engine:impl`.
- `:hypertone-wb:impl` consumes `AwbPredictor` / `AwbNeuralPrior` from `:ai-engine:api`.
- `:ai-engine:impl` has direct LiteRT and MediaPipe dependencies.

---

## 4. Module Dependency Diagram

```text
:app
├── :feature:camera / :feature:gallery / :feature:settings
├── engine impl modules for Hilt aggregation
└── ui shell

:feature:camera
├── :ai-engine:api
├── :imaging-pipeline:api
├── :hypertone-wb:api
├── :sensor-hal
├── :native-imaging-core:api
└── other engine APIs

:ai-engine:impl
├── :ai-engine:api
├── :common
├── LiteRT / MediaPipe SDKs
└── Hilt bindings for AI runners and ModelRegistry

:imaging-pipeline:impl
├── :imaging-pipeline:api
├── :ai-engine:api
├── :common
└── live HDR path through hdr/ProXdrOrchestrator

:hypertone-wb:impl
├── :hypertone-wb:api
├── :ai-engine:api
├── :common
└── uses AwbPredictor contract only
```

Architecturally important repaired links:

- `ImagingPipeline` now receives `NeuralIspRefiner` and `SemanticSegmenter` from `:ai-engine:api`.
- `HyperToneWhiteBalanceEngine` now receives `AwbPredictor` from `:ai-engine:api`.
- `AiEngineOrchestrator` depends on `SceneClassifier`, `SemanticSegmenter`, and `FaceLandmarker` contracts.

---

## 5. Runtime Capture Flow (current live path)

```text
Shutter / capture request
  → sensor-hal (Camera2 / CameraX / ZSL)
  → native-imaging-core (runtime bridge when native path is used)
  → photon-matrix fusion / fused buffer contracts
  → ai-engine (scene / segmentation / face inference contracts and runners)
  → imaging-pipeline
       1. ProXdrOrchestrator
          - ghost mask
          - deformable alignment
          - radiance merge / Mertens fallback
       2. ShadowDenoiseEngine
       3. optional MicroISP tile refinement
       4. optional semantic auto-segmentation
       5. Durand bilateral tone mapping
       6. Cinematic S-curve
       7. Luminosity sharpening
  → HyperTone WB / color science / metadata / output paths
```

### Live HDR path

The live HDR entry point is now:

- `engines/imaging-pipeline/impl/.../hdr/ProXdrOrchestrator.kt`

The deleted legacy path is no longer part of the tree:

- `pipeline/ProXdrHdrEngine.kt` — removed in the repair pass

### Thermal state source of truth

- `platform/common/.../ThermalState.kt` is canonical.
- HDR and runtime thermal gating use this shared enum.

---

## 6. DI / Wiring Map

### App-level Hilt aggregation

`app/build.gradle.kts` now includes the engine impl modules so Hilt can see all `@Module` contributors.

### Canonical DI modules

- `engines/ai-engine/impl/.../di/AiEngineModule.kt`
- `engines/imaging-pipeline/impl/.../di/ImagingPipelineModule.kt`
- `engines/hypertone-wb/impl/.../di/HypertoneWbModule.kt`
- `core/camera-core/impl/.../di/CameraCoreModule.kt`
- `core/color-science/impl/.../di/ColorScienceModule.kt`
- `engines/depth-engine/impl/.../di/DepthEngineModule.kt`
- `engines/face-engine/impl/.../di/FaceEngineModule.kt`
- `engines/neural-isp/impl/.../di/NeuralIspModule.kt`
- `engines/bokeh-engine/impl/.../di/BokehEngineModule.kt`
- `platform-android/gpu-compute/.../di/GpuComputeModule.kt`
- `features/camera/.../di/FeatureCameraModule.kt`
- `features/gallery/.../di/FeatureGalleryModule.kt`
- `features/settings/.../di/FeatureSettingsModule.kt`

### AI DI details

`AiEngineModule` provides:

- `ModelRegistry` via `ModelRegistry.fromAssets(...)`
- `@Named("assetBytes")` asset loader
- interface bindings:
  - `AwbPredictor`
  - `NeuralIspRefiner`
  - `SemanticSegmenter`
  - `SceneClassifier`
  - `FaceLandmarker`
  - `IAiEngine`

### Imaging DI details

`ImagingPipelineModule` provides:

- `ProXdrOrchestrator`
- `DurandBilateralToneMappingEngine`
- `CinematicSCurveEngine`
- `ShadowDenoiseEngine`
- `LuminositySharpener`
- compatibility `FfdNetNoiseReductionEngine`
- `ImagingPipeline`
- `ImagingPipelineOrchestrator`

### Settings DI details

- `CameraPreferencesRepository` now uses constructor injection.
- `FeatureSettingsModule` no longer duplicates repository construction.

---

## 7. Key Files by Subsystem

### App shell

- `app/src/main/kotlin/com/leica/cam/LeicaCamApp.kt`
- `app/build.gradle.kts`

### AI engine

- `engines/ai-engine/api/src/main/kotlin/com/leica/cam/ai_engine/api/AiContracts.kt`
- `engines/ai-engine/api/src/main/kotlin/com/leica/cam/ai_engine/api/AiInterfaces.kt`
- `engines/ai-engine/api/src/main/kotlin/com/leica/cam/ai_engine/api/AiNeuralPredictions.kt`
- `engines/ai-engine/impl/.../pipeline/AiEngineOrchestrator.kt`
- `engines/ai-engine/impl/.../registry/ModelRegistry.kt`
- `engines/ai-engine/impl/.../runtime/LiteRtSession.kt`
- `engines/ai-engine/impl/.../runtime/VendorDelegateLoader.kt`
- `engines/ai-engine/impl/.../models/AwbModelRunner.kt`
- `engines/ai-engine/impl/.../models/SceneClassifierRunner.kt`
- `engines/ai-engine/impl/.../models/SemanticSegmenterRunner.kt`
- `engines/ai-engine/impl/.../models/MicroIspRunner.kt`
- `engines/ai-engine/impl/.../models/FaceLandmarkerRunner.kt`

### Imaging pipeline / HDR

- `engines/imaging-pipeline/impl/.../pipeline/ImagingPipeline.kt`
- `engines/imaging-pipeline/impl/.../hdr/ProXdrOrchestrator.kt`
- `engines/imaging-pipeline/impl/.../hdr/MertensFallback.kt`
- `engines/imaging-pipeline/impl/.../hdr/GhostMaskEngine.kt`
- `engines/imaging-pipeline/impl/.../hdr/RadianceMerger.kt`
- `engines/imaging-pipeline/impl/.../hdr/BracketSelector.kt`

### HyperTone WB

- `engines/hypertone-wb/impl/.../pipeline/HyperToneWhiteBalanceEngine.kt`
- `engines/hypertone-wb/impl/.../pipeline/HyperToneWB2Engine.kt`

### Platform/runtime

- `platform/common/src/main/kotlin/com/leica/cam/common/ThermalState.kt`
- `platform-android/native-imaging-core/impl/.../ImagingRuntimeOrchestrator.kt`
- `features/settings/.../preferences/CameraPreferencesRepository.kt`

### Tests added/updated in the repair pass

- `engines/ai-engine/impl/src/test/.../AiEngineOrchestratorTest.kt`
- `engines/imaging-pipeline/impl/src/test/.../ImagingPipelineTest.kt`
- `engines/imaging-pipeline/impl/src/test/.../MertensFallbackTest.kt`
- `engines/hypertone-wb/impl/src/test/.../HyperToneWhiteBalanceEngineTest.kt`
- `engines/hypertone-wb/impl/src/test/.../Phase15Test.kt`

---

## 8. Current Warnings / Intentional Follow-ups

These are not stale-doc bugs; they describe the current code accurately:

- `AiEngineOrchestrator.downsample(...)` still falls back to zero-filled tiles unless the caller supplies pre-downsampled buffers.
- `ImagingPipeline.applyMicroIsp(...)` now invokes the refiner, but the RGB→4-channel tile synthesis is still an approximation of Bayer-domain tiling.
- `FfdNetNoiseReductionEngine` is a compatibility wrapper over `ShadowDenoiseEngine`, kept so older night-mode code and tests still compile while the repaired hot path uses `ShadowDenoiseEngine` directly.
- `VendorDelegateLoader` is the only remaining reflective ML-loading path, because vendor delegate SDKs are not available on Maven.

---

## 9. Build / Tooling Notes

- Build system: Gradle Kotlin DSL
- Convention plugins live in `build-logic/`
- Model assets are copied from `/Model/` into `app/src/main/assets/models/` by `copyOnDeviceModels`
- `:ai-engine:impl` now has direct LiteRT and MediaPipe dependencies

Typical verification commands used by the plan:

```bash
./gradlew assembleDevDebug
./gradlew :app:kaptGenerateStubsDevDebugKotlin
./gradlew :ai-engine:impl:test
./gradlew :imaging-pipeline:impl:test
./gradlew :hypertone-wb:impl:test
```

---

## 10. Documentation / Planning Files

- `Plan.md` — staged repair / upgrade plan
- `Problems list.md` — audit and defect inventory
- `README.md` — high-level product / architecture overview
- `Implementation.md` — historical implementation notes

---

## 11. Maintenance Checklist for This File

Update this file whenever you change:

- module inclusion in `settings.gradle.kts`
- DI ownership or Hilt module locations
- live runtime flow (especially capture / HDR / AI wiring)
- deleted or newly-canonical files
- any warning marker that has become outdated
