# Project Structure — LeicaCam

_Last updated: 2026-04-24 (UTC) — updated after icon integration, P5 UI-wiring pass, and P0 verification._

This document is the authoritative repository map for the current LeicaCam tree. It reflects the current state where:

- `app/src/main/res/mipmap-*/ic_launcher*.png` now carries the real Leica Cam brand icons (mdpi → xxxhdpi, square + round variants).
- `android:icon` / `android:roundIcon` in `AndroidManifest.xml` point to `@mipmap/ic_launcher` / `@mipmap/ic_launcher_round`.
- `app/src/main/kotlin/com/leica/cam/di/AssetsModule.kt` is **gone**; the canonical `@Named("assetBytes")` provider lives in `:ai-engine:impl`.
- `:ai-engine:api` owns all shared AI contracts (`AiNeuralPredictions.kt`, `AiContracts.kt`, `AiInterfaces.kt`, `AiModels.kt`).
- `ImagingPipeline` routes HDR through `hdr/ProXdrOrchestrator`. `pipeline/ProXdrHdrEngine.kt` is **deleted**.
- LiteRT and MediaPipe are direct dependencies of `:ai-engine:impl`. No impl→impl imports remain.
- `CameraScreen.kt` Flash and HDR buttons now visually reflect the current mode with animated icon + tint transitions.
- `ModelRegistry` reads from Android `AssetManager`; the `fromAssets(...)` companion factory is the canonical entry point.

If code and this file diverge, update this file.

---

## 1. Executive Snapshot

LeicaCam is a multi-module Android computational-photography stack built around strict `api` / `impl` boundaries and the **LUMO** imaging platform.

Core characteristics:

- **Android app shell:** `:app`
- **Feature modules:** `:feature:camera`, `:feature:gallery`, `:feature:settings`
- **Core engines:** AI, HDR / imaging pipeline, HyperTone WB, depth, face, motion, bokeh, neural ISP, smart imaging
- **Platform/shared layers:** `:common`, `:hardware-contracts`, `:sensor-hal`, `:gpu-compute`, `:native-imaging-core`, `:ui-components`
- **Native path:** JNI-backed runtime under `platform-android/native-imaging-core/impl` with C++17 source in `impl/src/main/cpp`
- **On-device ML:** LiteRT 1.0.1 + MediaPipe tasks-vision 0.10.14 in `engines/ai-engine/impl`
- **Brand assets:** Leica Cam icons (mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi) in `app/src/main/res/mipmap-*/`

Repository rules that matter:

- `:xyz:impl` depends on its own `:api` plus other modules' `:api`, never another engine's `:impl`.
- Hilt graph contributors must be on the `:app` classpath.
- `CancellationException` / `InterruptedException` must not be swallowed.
- Shared contracts used across engines live in API modules, not implementation packages.
- Every fallible function returns `LeicaResult<T>`, never throws.

---

## 2. Top-Level Directory Map

```text
/
├── app/                         Android app shell (Application + Activity + nav + brand icons)
├── features/                    User-facing camera / gallery / settings flows
├── core/                        Camera core, color science, lens model, photon matrix
├── engines/                     AI, HDR pipeline, HyperTone WB, depth, face, motion, bokeh, neural-ISP, smart-imaging
├── platform/                    Pure Kotlin shared contracts and result/error types
├── platform-android/            Android-specific runtime / GPU / Camera2 / JNI / UI modules
├── Model/                       Source model assets; copied into app/src/main/assets/models/ at build time
├── build-logic/                 Convention plugins (Gradle Kotlin DSL)
├── Plan.md                      Staged repair / upgrade plan (P0–P6 + P-DOCS)
├── Problems list.md             Defect inventory used for the repair passes
├── Implementation.md            Historical implementation notes
├── changelog.md                 Change log
└── project-structure.md         This file
```

### `app/`

```text
app/
├── build.gradle.kts             Engine impl deps for Hilt aggregation + model copy task
├── src/main/
│   ├── AndroidManifest.xml      Uses @mipmap/ic_launcher, @mipmap/ic_launcher_round
│   ├── kotlin/com/leica/cam/
│   │   ├── LeicaCamApp.kt       @HiltAndroidApp; warms AI models via ModelRegistry.warmUpAll
│   │   └── MainActivity.kt      Compose navigation host
│   └── res/
│       ├── drawable/
│       │   └── ic_launcher_playstore.png   512×512 Play Store artwork
│       ├── mipmap-mdpi/         ic_launcher.png (48×48), ic_launcher_round.png
│       ├── mipmap-hdpi/         ic_launcher.png (72×72), ic_launcher_round.png
│       ├── mipmap-xhdpi/        ic_launcher.png (96×96), ic_launcher_round.png
│       ├── mipmap-xxhdpi/       ic_launcher.png (144×144), ic_launcher_round.png
│       └── mipmap-xxxhdpi/      ic_launcher.png (192×192), ic_launcher_round.png
```

No `di/AssetsModule.kt` — deleted; the `@Named("assetBytes")` provider is in `:ai-engine:impl`.

### `features/`

- `camera/` — preview, permissions, capture controls, session command bus, camera UI, UI orchestrator
  - Key UI file: `CameraScreen.kt` — Flash icon cycles OFF→ON→AUTO with animated tint; HDR badge cycles OFF→ON→SMART→PRO with mode label
- `gallery/` — gallery UI, metadata presentation
- `settings/` — preferences repository, SharedPreferences store, settings screens

### `core/`

- `camera-core/` — Camera2 session configurator, ISP optimizer, chipset detector
- `color-science/` — color rendering pipeline engines and models
- `lens-model/` — lens calibration data, distortion correction suite
- `photon-matrix/` — fused photon buffer contracts, assembler/ingestor, Gr/Gb correction

### `engines/`

- `ai-engine/` — shared AI contracts in `api`; live LiteRT/MediaPipe runners in `impl`
- `imaging-pipeline/` — HDR, tone-map, merge, metadata, computational pipeline
- `hypertone-wb/` — white-balance engines (HyperToneWB2, mixed-light, Robertson CCT, skin-zone guard)
- `depth-engine/` — MiDaS depth estimation contracts and fusion
- `face-engine/` — face engine contracts and pipeline implementation
- `motion-engine/` — raw frame alignment / motion contracts
- `bokeh-engine/` — synthetic aperture / CoC bokeh orchestrator (P6 implementation pending)
- `neural-isp/` — neural ISP pipeline stages (MicroISP tile refinement wiring through `:ai-engine:api`)
- `smart-imaging/` — smart imaging orchestrator

### `platform/`

- `common/` — `LeicaResult`, `PipelineStage`, `ThermalState`, `LeicaLogger`, coroutine scope helpers, shared primitives
- `hardware-contracts/` — canonical photon / sensor / GPU / NPU / lens spec contracts
- `common-test/` — shared test fixtures

### `platform-android/`

- `sensor-hal/` — Camera2 / CameraX integration, ZSL ring buffer, session state machine, AF, metering, sensor profiles
- `gpu-compute/` — Vulkan / OpenGL ES / CPU compute backends, shader registry, Vulkan pipeline
- `native-imaging-core/` — Kotlin orchestrator (`ImagingRuntimeOrchestrator`) + C++ runtime bridge (`NativeImagingBridge`)
- `ui-components/` — Leica design system (tokens, theme, typography, spacing, motion, haptics, camera components, dial sheet)

---

## 3. Gradle Module Graph

Source of truth: `settings.gradle.kts`

### App + features

| Module | Dir |
|--------|-----|
| `:app` | `app/` |
| `:feature:camera` | `features/camera` |
| `:feature:gallery` | `features/gallery` |
| `:feature:settings` | `features/settings` |

### Core modules

| Module | Dir |
|--------|-----|
| `:camera-core:api` | `core/camera-core/api` |
| `:camera-core:impl` | `core/camera-core/impl` |
| `:color-science:api` | `core/color-science/api` |
| `:color-science:impl` | `core/color-science/impl` |
| `:photon-matrix:api` | `core/photon-matrix/api` |
| `:photon-matrix:impl` | `core/photon-matrix/impl` |
| `:lens-model` | `core/lens-model` |

### Imaging / AI engine modules

| Module | Dir |
|--------|-----|
| `:imaging-pipeline:api` | `engines/imaging-pipeline/api` |
| `:imaging-pipeline:impl` | `engines/imaging-pipeline/impl` |
| `:hypertone-wb:api` | `engines/hypertone-wb/api` |
| `:hypertone-wb:impl` | `engines/hypertone-wb/impl` |
| `:ai-engine:api` | `engines/ai-engine/api` |
| `:ai-engine:impl` | `engines/ai-engine/impl` |
| `:depth-engine:api` | `engines/depth-engine/api` |
| `:depth-engine:impl` | `engines/depth-engine/impl` |
| `:face-engine:api` | `engines/face-engine/api` |
| `:face-engine:impl` | `engines/face-engine/impl` |
| `:neural-isp:api` | `engines/neural-isp/api` |
| `:neural-isp:impl` | `engines/neural-isp/impl` |
| `:smart-imaging:api` | `engines/smart-imaging/api` |
| `:smart-imaging:impl` | `engines/smart-imaging/impl` |
| `:bokeh-engine:api` | `engines/bokeh-engine/api` |
| `:bokeh-engine:impl` | `engines/bokeh-engine/impl` |
| `:motion-engine:api` | `engines/motion-engine/api` |
| `:motion-engine:impl` | `engines/motion-engine/impl` |

### Platform / shared modules

| Module | Dir |
|--------|-----|
| `:native-imaging-core:api` | `platform-android/native-imaging-core/api` |
| `:native-imaging-core:impl` | `platform-android/native-imaging-core/impl` |
| `:sensor-hal` | `platform-android/sensor-hal` |
| `:gpu-compute` | `platform-android/gpu-compute` |
| `:ui-components` | `platform-android/ui-components` |
| `:common` | `platform/common` |
| `:common-test` | `platform/common-test` |
| `:hardware-contracts` | `platform/hardware-contracts` |

---

## 4. Module Dependency Diagram

```text
:app
├── :feature:camera / :feature:gallery / :feature:settings
├── engine impl modules (Hilt aggregation — all listed in app/build.gradle.kts)
│   :ai-engine:impl, :imaging-pipeline:impl, :hypertone-wb:impl,
│   :color-science:impl, :depth-engine:impl, :face-engine:impl,
│   :neural-isp:impl, :photon-matrix:impl, :smart-imaging:impl,
│   :bokeh-engine:impl, :motion-engine:impl, :native-imaging-core:impl,
│   :camera-core:impl
└── ui shell (:ui-components, navigation, Hilt)

:feature:camera
├── :ai-engine:api
├── :imaging-pipeline:api
├── :hypertone-wb:api
├── :sensor-hal
├── :native-imaging-core:api
├── :ui-components
└── other engine APIs

:ai-engine:impl
├── :ai-engine:api
├── :common
├── :photon-matrix:api
├── LiteRT 1.0.1 (litert, litert-gpu, litert-support)
├── MediaPipe tasks-vision 0.10.14
└── Hilt bindings for all 5 AI runners + ModelRegistry

:imaging-pipeline:impl
├── :imaging-pipeline:api
├── :ai-engine:api          ← receives NeuralIspRefiner + SemanticSegmenter contracts
├── :common
└── live HDR path via hdr/ProXdrOrchestrator

:hypertone-wb:impl
├── :hypertone-wb:api
├── :ai-engine:api          ← receives AwbPredictor contract only
└── :common
```

Architecturally important wiring:

- `ImagingPipeline` receives `NeuralIspRefiner` and `SemanticSegmenter` from `:ai-engine:api`.
- `HyperToneWhiteBalanceEngine` receives `AwbPredictor` from `:ai-engine:api`.
- `AiEngineOrchestrator` depends on `SceneClassifier`, `SemanticSegmenter`, and `FaceLandmarker` contracts.
- **Zero** `:impl` → `:impl` cross-module imports remain.

---

## 5. Runtime Capture Flow (current live path)

```text
Shutter / capture request
  → sensor-hal (Camera2CameraController / CameraSessionManager / ZSL ring buffer)
  → native-imaging-core (ImagingRuntimeOrchestrator → NativeImagingBridge JNI)
  → photon-matrix fusion (FusedPhotonBuffer from multi-sensor frames)
  → ai-engine (AiEngineOrchestrator — concurrent SceneClassifier + SemanticSegmenter + FaceLandmarker)
  → imaging-pipeline
       1. ProXdrOrchestrator
          a. BracketSelector (ZSL / EV bracket selection)
          b. GhostMaskEngine (pre-alignment ghost detection)
          c. DeformableFeatureAligner (dense optical flow alignment)
          d. RadianceMerger (Wiener burst / Debevec EV merge)
          e. MertensFallback (single-frame SDR path)
       2. ShadowDenoiseEngine (FFDNet-style shadow denoising)
       3. optional MicroISP tile refinement (NeuralIspRefiner — ultra-wide / front only)
       4. optional semantic auto-segmentation (SemanticSegmenter → SemanticMask)
       5. DurandBilateralToneMappingEngine
       6. CinematicSCurveEngine
       7. LuminositySharpener
       8. AcesToneMapper (P2 — when PRO_XDR mode active)
  → HyperToneWhiteBalanceEngine (AwbPredictor neural priors + CCM + mixed-light fusion)
  → color-science / metadata composers (DNG, HEIC, XMP, EXIF)
  → output
```

### Live HDR entry point

`engines/imaging-pipeline/impl/.../hdr/ProXdrOrchestrator.kt`

The old `pipeline/ProXdrHdrEngine.kt` is **deleted** — it no longer exists in the tree.

### Thermal state

`platform/common/src/main/kotlin/com/leica/cam/common/ThermalState.kt` is canonical.
HDR and runtime thermal gating use this shared enum.

---

## 6. DI / Wiring Map

### App-level Hilt aggregation

`app/build.gradle.kts` includes all engine `:impl` modules so Hilt can discover every `@Module` contributor.

### Canonical DI modules

| Module | File |
|--------|------|
| `AiEngineModule` | `engines/ai-engine/impl/.../di/AiEngineModule.kt` |
| `ImagingPipelineModule` | `engines/imaging-pipeline/impl/.../di/ImagingPipelineModule.kt` |
| `HypertoneWbModule` | `engines/hypertone-wb/impl/.../di/HypertoneWbModule.kt` |
| `CameraCoreModule` | `core/camera-core/impl/.../di/CameraCoreModule.kt` |
| `ColorScienceModule` | `core/color-science/impl/.../di/ColorScienceModule.kt` |
| `DepthEngineModule` | `engines/depth-engine/impl/.../di/DepthEngineModule.kt` |
| `FaceEngineModule` | `engines/face-engine/impl/.../di/FaceEngineModule.kt` |
| `NeuralIspModule` | `engines/neural-isp/impl/.../di/NeuralIspModule.kt` |
| `BokehEngineModule` | `engines/bokeh-engine/impl/.../di/BokehEngineModule.kt` |
| `MotionEngineModule` | `engines/motion-engine/impl/.../di/MotionEngineModule.kt` |
| `GpuComputeModule` | `platform-android/gpu-compute/.../di/GpuComputeModule.kt` |
| `SensorHalModule` | `platform-android/sensor-hal/.../di/SensorHalModule.kt` |
| `FeatureCameraModule` | `features/camera/.../di/FeatureCameraModule.kt` |
| `FeatureGalleryModule` | `features/gallery/.../di/FeatureGalleryModule.kt` |
| `FeatureSettingsModule` | `features/settings/.../di/FeatureSettingsModule.kt` |
| `UiComponentsModule` | `platform-android/ui-components/.../di/UiComponentsModule.kt` |

### AI DI details

`AiEngineModule` provides:

- `ModelRegistry` via `ModelRegistry.fromAssets(context.assets, logger)`
- `@Named("assetBytes")` — `(path: String) -> ByteBuffer` from `context.assets.open(path)`
- Interface bindings:
  - `AwbPredictor` ← `AwbModelRunner`
  - `NeuralIspRefiner` ← `MicroIspRunner`
  - `SemanticSegmenter` ← `SemanticSegmenterRunner`
  - `SceneClassifier` ← `SceneClassifierRunner`
  - `FaceLandmarker` ← `FaceLandmarkerRunner`
  - `IAiEngine` ← `AiEngineOrchestrator`

### Imaging DI details

`ImagingPipelineModule` provides (among others):

- `ProXdrOrchestrator`
- `DurandBilateralToneMappingEngine`
- `CinematicSCurveEngine`
- `ShadowDenoiseEngine`
- `LuminositySharpener`
- `FfdNetNoiseReductionEngine` (compatibility wrapper over `ShadowDenoiseEngine`)
- `ImagingPipeline` (wired with `NeuralIspRefiner` + `SemanticSegmenter` from `:ai-engine:api`)
- `ImagingPipelineOrchestrator`
- `ComputationalModesOrchestrator` (portrait / astro / macro / night / seamless-zoom / super-res)
- Various metadata composers and privacy log

### Settings DI details

- `CameraPreferencesRepository @Inject constructor(store: SharedPreferencesCameraStore)` — singleton, `@Inject`-annotated.
- `SharedPreferencesCameraStore @Inject constructor(@ApplicationContext context: Context)` — singleton.
- Persists: `flashMode`, `hdr.mode`, `awb.mode`, `currentZoom`, `cameraFacing`, `grid.*`.

---

## 7. Key Files by Subsystem

### App shell

```
app/src/main/kotlin/com/leica/cam/LeicaCamApp.kt
app/src/main/kotlin/com/leica/cam/MainActivity.kt
app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png
app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher_round.png
app/src/main/res/drawable/ic_launcher_playstore.png
```

### AI engine

```
engines/ai-engine/api/.../AiContracts.kt         — SceneLabel enum, SceneAnalysis, QualityScore, IlluminantHint, TrackedObject
engines/ai-engine/api/.../AiInterfaces.kt        — IAiEngine, CaptureMode
engines/ai-engine/api/.../AiModels.kt            — model metadata types
engines/ai-engine/api/.../AiNeuralPredictions.kt — AwbNeuralPrior, AwbPredictor, NeuralIspRefiner,
                                                     SemanticSegmenter, SemanticSegmentationOutput,
                                                     SemanticZoneCode, SceneClassifier, SceneClassificationOutput,
                                                     FaceLandmarker, FaceLandmarkerOutput, FaceMesh, AssetBytesLoader
engines/ai-engine/impl/.../pipeline/AiEngineOrchestrator.kt
engines/ai-engine/impl/.../registry/ModelRegistry.kt   — fromAssets() companion factory, PipelineRole enum
engines/ai-engine/impl/.../runtime/LiteRtSession.kt
engines/ai-engine/impl/.../runtime/VendorDelegateLoader.kt  — only remaining reflective ML path
engines/ai-engine/impl/.../runtime/DelegatePicker.kt
engines/ai-engine/impl/.../models/AwbModelRunner.kt        — implements AwbPredictor
engines/ai-engine/impl/.../models/SceneClassifierRunner.kt — implements SceneClassifier
engines/ai-engine/impl/.../models/SemanticSegmenterRunner.kt — implements SemanticSegmenter
engines/ai-engine/impl/.../models/MicroIspRunner.kt        — implements NeuralIspRefiner
engines/ai-engine/impl/.../models/FaceLandmarkerRunner.kt  — implements FaceLandmarker (MediaPipe)
```

### Imaging pipeline / HDR

```
engines/imaging-pipeline/api/.../ImagingPipelineContracts.kt
engines/imaging-pipeline/api/.../UserHdrMode.kt            — OFF / ON / SMART / PRO_XDR
engines/imaging-pipeline/api/.../pipeline/SemanticMask.kt
engines/imaging-pipeline/impl/.../pipeline/ImagingPipeline.kt
engines/imaging-pipeline/impl/.../hdr/ProXdrOrchestrator.kt    ← LIVE HDR ENTRY POINT
engines/imaging-pipeline/impl/.../hdr/BracketSelector.kt
engines/imaging-pipeline/impl/.../hdr/DeformableFeatureAligner.kt
engines/imaging-pipeline/impl/.../hdr/GhostMaskEngine.kt
engines/imaging-pipeline/impl/.../hdr/MertensFallback.kt
engines/imaging-pipeline/impl/.../hdr/RadianceMerger.kt
engines/imaging-pipeline/impl/.../hdr/HighlightReconstructor.kt
engines/imaging-pipeline/impl/.../hdr/ShadowRestorer.kt
engines/imaging-pipeline/impl/.../hdr/HdrTypes.kt
engines/imaging-pipeline/impl/.../pipeline/AcesToneMapper.kt
engines/imaging-pipeline/impl/.../pipeline/ComputationalModes.kt  — ComputationalModesOrchestrator
engines/imaging-pipeline/impl/.../pipeline/FusionLM2Engine.kt
engines/imaging-pipeline/impl/.../pipeline/MultiScaleFrameAligner.kt
engines/imaging-pipeline/impl/.../pipeline/OutputMetadataPipeline.kt
engines/imaging-pipeline/impl/.../pipeline/ToneLM2Engine.kt
engines/imaging-pipeline/impl/.../pipeline/VideoPipeline.kt
```

### HyperTone WB

```
engines/hypertone-wb/impl/.../pipeline/HyperToneWhiteBalanceEngine.kt
engines/hypertone-wb/impl/.../pipeline/HyperToneWB2Engine.kt
engines/hypertone-wb/impl/.../pipeline/MultiModalIlluminantFusion.kt
engines/hypertone-wb/impl/.../pipeline/MixedLightSpatialWbEngine.kt
engines/hypertone-wb/impl/.../pipeline/RobertsonCctEstimator.kt
engines/hypertone-wb/impl/.../pipeline/SkinZoneWbGuard.kt
engines/hypertone-wb/impl/.../pipeline/WbTemporalMemory.kt
engines/hypertone-wb/impl/.../pipeline/ColorTemperatureMath.kt
engines/hypertone-wb/impl/.../pipeline/KelvinToCcmConverter.kt
engines/hypertone-wb/impl/.../pipeline/IlluminantEstimators.kt
engines/hypertone-wb/impl/.../pipeline/PartitionedCTSensor.kt
```

### Camera UI

```
features/camera/.../ui/CameraScreen.kt           — Flash (OFF/ON/AUTO icon+tint) + HDR (badge label OFF/ON/SMART/PRO)
features/camera/.../ui/CameraUiOrchestrator.kt   — CameraModeSwitcher, PostCaptureEditToolCatalog
features/camera/.../controls/CaptureControlsViewModel.kt
features/camera/.../preview/CameraPreview.kt
features/camera/.../preview/SessionCommandBus.kt
features/camera/.../permissions/PermissionGate.kt
features/camera/.../NativeImagingRuntimeFacade.kt
```

### Settings / preferences

```
features/settings/.../preferences/CameraPreferences.kt      — FlashMode, CameraFacing, HdrPreferences, AwbPreferences, GridPreferences
features/settings/.../preferences/CameraPreferencesRepository.kt  — @Singleton @Inject
features/settings/.../preferences/SharedPreferencesCameraStore.kt — persists all prefs
```

### Platform / runtime

```
platform/common/.../ThermalState.kt                           — canonical thermal enum
platform/common/.../result/LeicaResult.kt                     — Success / Failure sealed class
platform/common/.../result/PipelineStage.kt
platform-android/native-imaging-core/impl/.../ImagingRuntimeOrchestrator.kt
platform-android/native-imaging-core/impl/.../NativeImagingBridge.kt
platform-android/native-imaging-core/impl/.../RuntimeGovernor.kt
platform-android/sensor-hal/.../session/Camera2CameraController.kt
platform-android/sensor-hal/.../session/CameraSessionManager.kt
platform-android/sensor-hal/.../zsl/ZeroShutterLagRingBuffer.kt
platform-android/sensor-hal/.../autofocus/HybridAutoFocusEngine.kt
platform-android/sensor-hal/.../metering/AdvancedMeteringEngine.kt
platform-android/gpu-compute/.../VulkanBackend.kt
platform-android/gpu-compute/.../VulkanComputePipeline.kt
```

### UI design system

```
platform-android/ui-components/.../theme/LeicaColors.kt       — LeicaColorScheme, LeicaPalette
platform-android/ui-components/.../theme/LeicaTheme.kt        — LeicaTokens (colors, spacing, motion, elevation)
platform-android/ui-components/.../theme/LeicaSpacing.kt
platform-android/ui-components/.../theme/LeicaTypography.kt
platform-android/ui-components/.../camera/LeicaComponents.kt  — LeicaShutterButton, LeicaModeSwitcher, ViewfinderOverlay, SceneBadge, AfBracket
platform-android/ui-components/.../camera/LeicaDialSheet.kt
platform-android/ui-components/.../camera/GridOverlay.kt
platform-android/ui-components/.../camera/Phase9UiModels.kt   — Phase9UiStateCalculator
platform-android/ui-components/.../motion/Haptics.kt
platform-android/ui-components/.../motion/LeicaTransitions.kt
```

---

## 8. Tests Added / Updated

### Engine tests

```
engines/ai-engine/impl/src/test/.../AiEngineOrchestratorTest.kt
engines/imaging-pipeline/impl/src/test/.../ImagingPipelineTest.kt
engines/imaging-pipeline/impl/src/test/.../MertensFallbackTest.kt
engines/imaging-pipeline/impl/src/test/.../GhostMaskEngineTest.kt
engines/imaging-pipeline/impl/src/test/.../RadianceMergerTest.kt
engines/imaging-pipeline/impl/src/test/.../DeformableFeatureAlignerTest.kt
engines/imaging-pipeline/impl/src/test/.../ProXdrOrchestratorUserOverrideTest.kt
engines/imaging-pipeline/impl/src/test/.../HdrModePickerUserOverrideTest.kt
engines/imaging-pipeline/impl/src/test/.../ComputationalModesPhase7Test.kt
engines/imaging-pipeline/impl/src/test/.../VideoPipelinePhase8Test.kt
engines/imaging-pipeline/impl/src/test/.../OutputMetadataPhase10Test.kt
engines/hypertone-wb/impl/src/test/.../HyperToneWhiteBalanceEngineTest.kt
engines/hypertone-wb/impl/src/test/.../MultiModalIlluminantFusionUserAwbTest.kt
engines/hypertone-wb/impl/src/test/.../Phase15Test.kt
```

### Feature / platform tests

```
features/camera/src/test/.../CaptureControlsViewModelTest.kt
features/camera/src/test/.../PermissionStateTest.kt
features/camera/src/test/.../CameraUiOrchestratorPhase9Test.kt
features/gallery/src/test/.../GalleryMetadataEnginePhase9Test.kt
features/settings/src/test/.../CameraPreferencesRepositoryTest.kt
features/settings/src/test/.../SettingsCatalogTest.kt
platform-android/sensor-hal/src/test/.../HybridAutoFocusEngineTest.kt
platform-android/sensor-hal/src/test/.../CameraCapabilityProfileBuilderTest.kt
platform-android/sensor-hal/src/test/.../AdvancedMeteringEngineTest.kt
platform-android/sensor-hal/src/test/.../CameraRequestControlStateTest.kt
platform-android/sensor-hal/src/test/.../CameraSessionStateMachineTest.kt
platform-android/sensor-hal/src/test/.../ZeroShutterLagRingBufferTest.kt
platform-android/native-imaging-core/impl/src/test/.../NativeImagingBridgeTest.kt
platform-android/native-imaging-core/impl/src/test/.../RuntimeGovernorTest.kt
platform-android/ui-components/src/test/.../GridOverlayGeometryTest.kt
platform-android/ui-components/src/test/.../LeicaTokensTest.kt
```

---

## 9. Current Warnings / Intentional Follow-ups

These are not stale-doc bugs; they describe the current code accurately:

- `AiEngineOrchestrator.downsample(...)` still falls back to zero-filled tiles unless the caller supplies pre-downsampled buffers.
- `ImagingPipeline.applyMicroIsp(...)` invokes the refiner, but the RGB→4-channel tile synthesis is an approximation of Bayer-domain tiling (P6 will refine this).
- `FfdNetNoiseReductionEngine` is a compatibility wrapper over `ShadowDenoiseEngine`; older night-mode code still compiles; the repaired hot path uses `ShadowDenoiseEngine` directly.
- `VendorDelegateLoader` is the only remaining reflective ML-loading path — vendor delegate SDKs are not available on Maven.
- `BokehEngineOrchestrator` currently stubs CoC math; P6 will wire real depth + face-mask inputs.
- `CameraScreen.kt` Flash and HDR HUD buttons are now visually reactive; `LumaFrame`, `AfBracket`, and `faces` overlay inputs are still hard-coded placeholders (P5 full real-data wiring is a follow-up).

---

## 10. Build / Tooling Notes

- Build system: Gradle Kotlin DSL, convention plugins in `build-logic/`
- Model assets are copied from `/Model/**/*.tflite` and `**/*.task` into `app/src/main/assets/models/` by the `copyOnDeviceModels` task, which runs before `preBuild`
- `:ai-engine:impl` has direct LiteRT and MediaPipe dependencies
- NDK r27 required for `:native-imaging-core:impl` (C++17 source under `impl/src/main/cpp`)
- C++ code under `native-imaging-core/impl/src/main/cpp/` — Vulkan compute shaders live under `gpu-compute/src/main/assets/shaders/` and `gpu-compute/src/main/resources/shaders/`

Typical verification commands:

```bash
./gradlew clean
./gradlew assembleDevDebug
./gradlew :app:kaptGenerateStubsDevDebugKotlin
./gradlew :ai-engine:impl:test
./gradlew :imaging-pipeline:impl:test
./gradlew :hypertone-wb:impl:test

# P0 sanity checks
grep -rn "com.leica.cam.ai_engine.impl" engines/hypertone-wb/impl engines/imaging-pipeline/impl
# → must return zero results
find . -name AssetsModule.kt -path '*/app/*'
# → must return empty
```

---

## 11. Documentation / Planning Files

| File | Purpose |
|------|---------|
| `Plan.md` | Staged repair / upgrade plan (P0–P6 + P-DOCS); sub-plans in order |
| `Problems list.md` | Defect inventory and audit notes used for repair passes |
| `Implementation.md` | Historical implementation notes and decisions |
| `README.md` | High-level product and architecture overview |
| `changelog.md` | Change log |
| `project-structure.md` | This file — authoritative repository map |

---

## 12. Maintenance Checklist for This File

Update this file whenever you:

- Add or remove a module in `settings.gradle.kts`
- Change DI ownership or Hilt module locations
- Modify the live runtime capture flow (HDR / AI wiring)
- Delete or add canonical files
- Add new warnings or resolve existing ones
- Change the app icon / res structure
