# Project Structure — LeicaCam

_Last updated: 2026-04-23 (UTC) — re-audited against the on-disk tree,
`settings.gradle.kts`, the actual `build.gradle.kts` per module, and the
real package layout of every `impl` module._

This document is the **ground-truth map** of the LeicaCam repository. It
is kept in sync with:

- `settings.gradle.kts` — module inclusion source of truth
- `build-logic/convention/` — composite build plugins
  (`leica.android.application`, `leica.android.library`,
  `leica.jvm.library`, `leica.engine.module`)
- The per-module `build.gradle.kts` files (engine dep graph)
- The DI graph expressed through Hilt `@Module`s
- The runtime capture flow through `ImagingPipeline` and the new
  `ProXdrOrchestrator` (HDR sub-package)

If any of the above diverge from this file, this file is **stale**.
Update it.

> ⚠️ For the current known-defect picture, see `Problems list.md`.
> For the active upgrade plan, see `Plan.md`. Both live at the repo root.

---

## 1. Executive Snapshot

LeicaCam is a multi-module Android/Kotlin computational-photography
stack that implements the **LUMO** imaging platform: FusionLM multi-
frame RAW fusion, ColorLM colour correction, HyperTone WB, ToneLM
perceptual tone mapping, ProXDR HDR (rebuilt D2 path), Bokeh Engine,
and Lightning Snap zero-shutter-lag capture. It targets a specific
MediaTek-Dimensity device with 8 distinct image sensors (Samsung
S5KHM6, OmniVision OV64B40/OV50D40/OV08D10/OV16A1Q, GalaxyCore
GC16B3, SmartSens SC202CS/SC202PCS).

Measured current state:

- **215 Kotlin source files** under `engines/ features/ core/
  platform/ platform-android/ app/` (excluding skills, tooling, tests
  in auxiliary roots). Of these, **181 production** + **34 test**.
- **2 C++ files** under
  `platform-android/native-imaging-core/impl/src/main/cpp/`
  (`native_imaging_core.cpp` 1012 lines, `photon_buffer.h` 340 lines).
- **5 on-device AI models** under `/Model/` (AWB, Face Landmarker,
  Image Classifier, MicroISP, Scene Understanding / DeepLabv3).
- **37 Gradle modules** in `settings.gradle.kts` + 1 `build-logic`
  composite build.
- **18 Hilt `@Module` files** (all named `<Module>Module.kt`, not
  `DependencyModule.kt` as previous revisions of this file claimed).
- **Inviolable LUMO laws** (enforced by code review): RAW-domain-first,
  16-bit end-to-end, physics-grounded noise, shadow-denoise-before-
  lift, skin-anchor-is-sacred, per-zone WB, zero cloud inference.

**Module-directory convention (since D3):**
- `core/` — domain contracts that are *not* engines per se (camera
  session plumbing, photon-matrix fusion, lens model, colour science).
- `engines/` — specialised imaging engines (AI, HDR/imaging pipeline,
  HyperTone WB, depth, face, bokeh, motion, neural ISP, smart imaging).
- `platform/` — pure-JVM shared code (`:common`, `:common-test`,
  `:hardware-contracts`). No Android imports.
- `platform-android/` — Android-specific platform modules (sensor-hal,
  gpu-compute, native-imaging-core JNI, ui-components).
- `features/` — user-facing flows (camera, gallery, settings).
- `app/` — Application shell + `MainActivity` + nav graph.

---

## 2. Top-Level Directory Map (as of 2026-04-23)

```
/
├── app/                        Android app shell (LeicaCamApp + MainActivity + nav)
│   └── src/main/
│       ├── kotlin/com/leica/cam/
│       │   ├── LeicaCamApp.kt               @HiltAndroidApp Application + AI warm-up
│       │   ├── MainActivity.kt              @AndroidEntryPoint + NavHost + bottom nav
│       │   └── di/AssetsModule.kt           ⚠ DUPLICATE of AiEngineModule.provideAssetBytesLoader
│       └── proguard-rules.pro
│
├── features/                   User-facing flows (UI + orchestrator per flow)
│   ├── camera/                 Camera capture UX, state machine, native runtime facade
│   │   └── src/main/kotlin/com/leica/cam/feature/camera/
│   │       ├── NativeImagingRuntimeFacade.kt   Presentation-layer facade
│   │       ├── controls/
│   │       │   └── CaptureControlsViewModel.kt
│   │       ├── di/FeatureCameraModule.kt
│   │       ├── permissions/
│   │       │   ├── PermissionGate.kt / PermissionState.kt / RequiredPermissions.kt
│   │       ├── preview/
│   │       │   └── CameraPreview.kt             Compose wrapper around PreviewView
│   │       └── ui/
│   │           ├── CameraScreen.kt              Viewfinder composable + HUD
│   │           └── CameraUiOrchestrator.kt      Mode switch + PRO controls + edit tools
│   ├── gallery/
│   │   └── src/main/kotlin/com/leica/cam/feature/gallery/
│   │       ├── di/FeatureGalleryModule.kt
│   │       └── ui/{GalleryScreen.kt, GalleryMetadataEngine.kt}
│   └── settings/
│       └── src/main/kotlin/com/leica/cam/feature/settings/
│           ├── di/FeatureSettingsModule.kt
│           ├── preferences/{CameraPreferences, CameraPreferencesRepository, SharedPreferencesCameraStore}.kt
│           └── ui/{SettingsScreen, SettingsCatalog, SettingsSection, SettingsSectionModels, SettingsViewModel}.kt
│
├── core/                       Domain contracts + core transforms (platform-light)
│   ├── camera-core/
│   │   ├── api/ src/main/kotlin/com/leica/cam/camera_core/api/
│   │   │     CameraCoreContracts.kt
│   │   └── impl/ src/main/kotlin/com/leica/cam/camera_core/
│   │         ├── di/CameraCoreModule.kt
│   │         └── impl/isp/{Camera2SessionConfigurator, ChipsetDetector, IspOptimizer}.kt
│   ├── color-science/
│   │   ├── api/ src/main/kotlin/com/leica/cam/color_science/api/ColorScienceContracts.kt
│   │   └── impl/ src/main/kotlin/com/leica/cam/color_science/
│   │         ├── di/ColorScienceModule.kt
│   │         └── pipeline/{ColorScienceEngines, ColorScienceModels}.kt
│   ├── lens-model/            (single module, no api/impl split — uses classes directly)
│   │   └── src/main/kotlin/com/leica/cam/lens_model/
│   │       ├── calibration/LensCalibrationData.kt
│   │       ├── correction/LensCorrectionSuite.kt
│   │       └── di/LensModelModule.kt
│   └── photon-matrix/
│       ├── api/ src/main/kotlin/com/leica/cam/photon_matrix/
│       │     {FrameMetadata, FusedPhotonBuffer, IPhotonMatrixAssembler, IPhotonMatrixIngestor,
│       │      PhotonColorSpace, PhotonPlane, ProXdrOutputMode}.kt
│       └── impl/ src/main/kotlin/com/leica/cam/photon_matrix/
│             ├── {PhotonMatrixAssembler, PhotonMatrixIngestor}.kt
│             └── correction/GrGbCorrectionEngine.kt
│
├── engines/                    Specialised imaging engines (each has api + impl split)
│   ├── ai-engine/
│   │   ├── api/ src/main/kotlin/com/leica/cam/ai_engine/api/
│   │   │     {AiContracts, AiInterfaces, AiModels}.kt
│   │   └── impl/ src/main/kotlin/com/leica/cam/ai_engine/impl/
│   │         ├── di/AiEngineModule.kt
│   │         ├── models/                      LiteRT / MediaPipe runners
│   │         │   ├── AwbModelRunner.kt           awb_final_*.tflite
│   │         │   ├── FaceLandmarkerRunner.kt     face_landmarker.task (MediaPipe)
│   │         │   ├── MicroIspRunner.kt           MicroISP_V4_fp16.tflite
│   │         │   ├── SceneClassifierRunner.kt    1.tflite (Image Classifier)
│   │         │   └── SemanticSegmenterRunner.kt  deeplabv3.tflite
│   │         ├── pipeline/
│   │         │   ├── AiEngineOrchestrator.kt   ⚠ Currently returns STUB values (see Problems list.md P1-1)
│   │         │   └── EnginesImpl.kt
│   │         ├── registry/ModelRegistry.kt     Magic-byte fingerprint + role assignment
│   │         └── runtime/
│   │             ├── DelegatePicker.kt         MTK/QNN/ENN/GPU/XNNPACK priority
│   │             ├── LiteRtSession.kt          Interpreter wrapper (reflective; see Plan.md P3)
│   │             └── VendorDelegateLoader.kt   Reflective vendor SDK loader
│   ├── bokeh-engine/
│   │   ├── api/ …/BokehContracts.kt
│   │   └── impl/ …/ {BokehEngineOrchestrator.kt, di/BokehEngineModule.kt}
│   │         ⚠ Current impl is a thin stub; real CoC math is Plan.md P6.
│   ├── depth-engine/
│   │   ├── api/ …/DepthContracts.kt
│   │   └── impl/ …/
│   │         ├── DepthSensingFusion.kt
│   │         ├── di/DepthEngineModule.kt
│   │         └── pipeline/DepthImpl.kt          ⚠ Returns hardcoded 0.5 depth values
│   ├── face-engine/
│   │   ├── api/ …/FaceContracts.kt
│   │   └── impl/ …/
│   │         ├── FaceEngine.kt
│   │         ├── di/FaceEngineModule.kt
│   │         └── pipeline/FaceImpl.kt           ⚠ Stub — returns 468 × (0.5, 0.5, 0.5)
│   ├── hypertone-wb/
│   │   ├── api/ …/ {HyperToneWBContracts, UserAwbMode}.kt
│   │   └── impl/ src/main/kotlin/com/leica/cam/hypertone_wb/
│   │         ├── di/HypertoneWbModule.kt
│   │         └── pipeline/
│   │             ├── ColorTemperatureMath.kt
│   │             ├── HyperToneModels.kt
│   │             ├── HyperToneWB2Engine.kt
│   │             ├── HyperToneWhiteBalanceEngine.kt   ⚠ Imports ai_engine.impl directly (see P0-3)
│   │             ├── IlluminantEstimators.kt
│   │             ├── KelvinToCcmConverter.kt
│   │             ├── MixedLightSpatialWbEngine.kt
│   │             ├── MultiModalIlluminantFusion.kt
│   │             ├── PartitionedCTSensor.kt
│   │             ├── RobertsonCctEstimator.kt
│   │             ├── SkinZoneWbGuard.kt
│   │             └── WbTemporalMemory.kt
│   ├── imaging-pipeline/       (THE hot path)
│   │   ├── api/ src/main/kotlin/com/leica/cam/imaging_pipeline/
│   │   │     ├── api/{ImagingPipelineContracts, UserHdrMode}.kt
│   │   │     └── pipeline/SemanticMask.kt
│   │   └── impl/ src/main/kotlin/com/leica/cam/imaging_pipeline/
│   │         ├── di/ImagingPipelineModule.kt
│   │         ├── hdr/                            ← NEW (D2 split — canonical)
│   │         │   ├── BracketSelector.kt
│   │         │   ├── DeformableFeatureAligner.kt    Pyramidal Lucas-Kanade
│   │         │   ├── GhostMaskEngine.kt             Pre-alignment MTB
│   │         │   ├── HdrTypes.kt                    SceneDescriptor, PerChannelNoise, HdrModePicker
│   │         │   ├── HighlightReconstructor.kt
│   │         │   ├── MertensFallback.kt             Burt–Adelson Laplacian pyramid
│   │         │   ├── ProXdrOrchestrator.kt          ← NEW HDR entry point (not yet called!)
│   │         │   ├── RadianceMerger.kt              Per-channel Wiener + Debevec
│   │         │   └── ShadowRestorer.kt
│   │         └── pipeline/                       ← OLD path, still LIVE
│   │             ├── AcesToneMapper.kt
│   │             ├── ComputationalModes.kt          (407 LOC: portrait/night/astro/...)
│   │             ├── FusionLM2Engine.kt             (424 LOC)
│   │             ├── ImagingPipeline.kt             (1402 LOC — still the active path)
│   │             ├── MultiScaleFrameAligner.kt      (425 LOC)
│   │             ├── OutputMetadataPipeline.kt      (381 LOC)
│   │             ├── ProXdrHdrEngine.kt             (546 LOC — DEAD, slated for deletion per Plan.md P2)
│   │             ├── ToneLM2Engine.kt               (309 LOC)
│   │             └── VideoPipeline.kt               (630 LOC)
│   ├── motion-engine/
│   │   ├── api/ …/MotionContracts.kt
│   │   └── impl/ …/ {RawFrameAligner.kt, di/MotionEngineModule.kt}
│   ├── neural-isp/
│   │   ├── api/ …/NeuralIspContracts.kt
│   │   └── impl/ src/main/kotlin/com/leica/cam/neural_isp/
│   │         ├── di/NeuralIspModule.kt
│   │         └── pipeline/{NeuralIspModels, NeuralIspProcessors, NeuralIspStages}.kt
│   └── smart-imaging/
│       ├── api/ …/ISmartImagingOrchestrator.kt
│       └── impl/ src/main/kotlin/com/leica/cam/smart_imaging/SmartImagingOrchestrator.kt
│
├── platform/                   Pure-JVM shared code (zero Android imports)
│   ├── common/
│   │   └── src/main/kotlin/com/leica/cam/common/
│   │       ├── CameraSessionScope.kt
│   │       ├── Constants.kt             ⚠ Contains 1 detekt threshold; candidate for deletion
│   │       ├── Logger.kt                ⚠ Duplicates logging/LeicaLogger.kt
│   │       ├── PipelineErrorHandling.kt
│   │       ├── Result.kt                ⚠ Top-level, overlaps result/LeicaResult.kt
│   │       ├── logging/LeicaLogger.kt
│   │       ├── result/{DomainError, LeicaResult, PipelineStage}.kt
│   │       └── types/NonEmptyList.kt
│   ├── common-test/
│   │   └── src/main/kotlin/com/leica/cam/test/builders/PhotonBufferBuilder.kt
│   └── hardware-contracts/
│       └── src/main/kotlin/com/leica/cam/hardware/contracts/
│           ├── {GpuCapabilities, LensSpec, NpuCapabilities, SensorSpec}.kt
│           └── photon/PhotonBuffer.kt    The authoritative PhotonBuffer sealed type
│
├── platform-android/           Android-specific platform modules
│   ├── gpu-compute/
│   │   └── src/main/kotlin/com/leica/cam/gpu_compute/
│   │       ├── {CpuFallbackBackend, GpuBackend, GpuComputeInitializer,
│   │       │   OpenGlEsBackend, VulkanBackend}.kt
│   │       ├── di/GpuComputeModule.kt
│   │       └── vulkan/{ShaderRegistry, VulkanComputePipeline}.kt
│   ├── native-imaging-core/
│   │   ├── api/ src/main/kotlin/com/leica/cam/nativeimagingcore/
│   │   │     {INativeImagingOrchestrator, NativeImagingContracts, NativeImagingRuntimeFacade}.kt
│   │   └── impl/
│   │       ├── src/main/cpp/
│   │       │   ├── CMakeLists.txt
│   │       │   ├── native_imaging_core.cpp (1012 LOC)
│   │       │   └── photon_buffer.h         (340 LOC)
│   │       └── src/main/kotlin/com/leica/cam/native_imaging_core/impl/nativeimagingcore/
│   │           ├── ImagingRuntimeOrchestrator.kt  ⚠ Worker loop has races (Problems list P1-8)
│   │           ├── NativeImagingBridge.kt
│   │           └── RuntimeGovernor.kt
│   ├── sensor-hal/
│   │   └── src/main/kotlin/com/leica/cam/sensor_hal/
│   │       ├── autofocus/HybridAutoFocusEngine.kt
│   │       ├── capability/{Camera2MetadataSource, CameraCapabilityProfile}.kt
│   │       ├── di/SensorHalModule.kt
│   │       ├── isp/IspIntegrationOrchestrator.kt
│   │       ├── metering/AdvancedMeteringEngine.kt
│   │       ├── sensor/
│   │       │   ├── SmartIsoProDetector.kt
│   │       │   └── profiles/{SensorProfile, SensorProfileRegistry}.kt
│   │       ├── session/
│   │       │   ├── Camera2CameraController.kt    (CameraX + Camera2 interop — ACTIVE controller)
│   │       │   ├── CameraRequestControlState.kt
│   │       │   ├── CameraSessionManager.kt
│   │       │   ├── CameraSessionStateMachine.kt  (sealed-class FSM)
│   │       │   └── DefaultCameraSelector.kt
│   │       ├── soc/SoCProfile.kt
│   │       └── zsl/ZeroShutterLagRingBuffer.kt   ⚠ Not thread-safe (Problems list P1-12)
│   └── ui-components/
│       └── src/main/kotlin/com/leica/cam/ui_components/
│           ├── camera/
│           │   ├── GridOverlay.kt
│           │   ├── LeicaComponents.kt
│           │   ├── LeicaDialSheet.kt
│           │   └── Phase9UiModels.kt
│           ├── di/UiComponentsModule.kt
│           ├── motion/{Haptics, LeicaTransitions}.kt
│           ├── settings/{LeicaSegmentedControl, LeicaToggleRow}.kt
│           └── theme/{LeicaColors, LeicaElevation, LeicaMotion, LeicaShapes,
│                      LeicaSpacing, LeicaTheme, LeicaTypography}.kt
│
├── Model/                      On-device AI model assets (copied to assets/models/ at build)
│   ├── AWB/                    awb_final.onnx (dev), awb_final_full_integer_quant.tflite (shipping)
│   ├── Face Landmarker/        face_landmarker.task (MediaPipe format)
│   ├── Image Classifier/       1.tflite  (placeholder name)
│   ├── MicroISP/               MicroISP_V4_fp16.tflite
│   ├── Scene Understanding/    deeplabv3.tflite
│   └── Temp                    (1-byte sentinel file)
│
├── Knowledge/                  Research-level references
│   ├── Advance HDR algorithm research.md
│   ├── advance imaging system research.md
│   ├── 2504.05623v2.pdf        (reference paper)
│   └── Temp
│
├── Reference/                  Legacy/reference assets
├── config/detekt/detekt.yml    Static-analysis config
├── docs/
│   ├── adr/                    ADR-001 … ADR-006
│   ├── ndk-core-rearchitecture.md
│   └── phase0-architecture.md
│
├── build-logic/                Composite build — convention plugins
│   ├── settings.gradle.kts
│   └── convention/
│       ├── build.gradle.kts
│       └── src/main/kotlin/
│           ├── LeicaAndroidApplicationPlugin.kt
│           ├── LeicaAndroidLibraryPlugin.kt
│           ├── LeicaConventionSupport.kt
│           ├── LeicaEngineModulePlugin.kt
│           └── LeicaJvmLibraryPlugin.kt
│
├── build.gradle.kts            Root Gradle config (applies no-op plugins)
├── settings.gradle.kts         Module inclusion source of truth (37 modules)
├── gradle/
│   ├── libs.versions.toml      Version catalog
│   └── wrapper/                Gradle wrapper
├── gradle.properties
├── gradlew / gradlew.bat
├── Implementation.md           Historical phase-by-phase notes (phases 0–10, 2201 lines)
├── Plan.md                     Active upgrade plan (P0 → P6 sub-plans)
├── Problems list.md            Current defect audit (P0/P1/P2/P3 severity)
├── changelog.md                Release notes
├── project-structure.md        ← THIS FILE
├── README.md
├── skills-lock.json            Skill version pin (tooling)
├── package.json + package-lock.json + node_modules/  ⚠ JS tooling — unrelated to Android app
├── lib/download-providers.js    ⚠ JS tool (non-app)
├── scripts/                     ⚠ Mostly Node-based helpers (bump-version, generate-changelog, etc.)
├── src/                         ⚠ JS detect-antipatterns utilities (non-app)
├── tests/                       ⚠ JS tests matching src/
├── source/skills/               ⚠ Parallel "skills" location — stale; see Problems list P3-19
├── split_modules.sh, split_modules2.sh  ⚠ D3 refactor scripts — stale
├── .agents/ .Jules/ .bolt/      Tool-specific directories (agent skills, Jules, bolt.new)
└── .github/                     CI / issue templates / workflows
```

**⚠ markers** point to items detailed in `Problems list.md`. They are
on-disk reality, not plan aspirations.

---

## 3. Gradle Module Graph (source of truth)

From `settings.gradle.kts` — **37 modules** total.

**App + features (4)**

- `:app`
- `:feature:camera` → `features/camera`
- `:feature:gallery` → `features/gallery`
- `:feature:settings` → `features/settings`

**Core (7: 3 api/impl pairs + 1 flat)**

- `:camera-core:api`, `:camera-core:impl` → `core/camera-core/…`
- `:color-science:api`, `:color-science:impl` → `core/color-science/…`
- `:photon-matrix:api`, `:photon-matrix:impl` → `core/photon-matrix/…`
- `:lens-model` (flat — no api/impl split) → `core/lens-model`

**Engines (18: 9 api/impl pairs)**

- `:ai-engine:api`, `:ai-engine:impl`
- `:bokeh-engine:api`, `:bokeh-engine:impl`
- `:depth-engine:api`, `:depth-engine:impl`
- `:face-engine:api`, `:face-engine:impl`
- `:hypertone-wb:api`, `:hypertone-wb:impl`
- `:imaging-pipeline:api`, `:imaging-pipeline:impl`
- `:motion-engine:api`, `:motion-engine:impl`
- `:neural-isp:api`, `:neural-isp:impl`
- `:smart-imaging:api`, `:smart-imaging:impl`

**Platform (3) — pure JVM**

- `:common`, `:common-test`, `:hardware-contracts`

**Platform-Android (5)**

- `:gpu-compute`
- `:native-imaging-core:api`, `:native-imaging-core:impl`
- `:sensor-hal`
- `:ui-components`

### Convention plugins (composite build `build-logic/`)

The root `settings.gradle.kts` pulls in `build-logic` as a composite
build. Every production `build.gradle.kts` applies one of:

- `leica.android.application` — for `:app`
- `leica.android.library` — for any Android lib module
- `leica.jvm.library` — for pure-JVM `:api` modules and `:common*`
- `leica.engine.module` — reserved for engine conventions (future use)

Plugin class ↔ id mapping lives in
`build-logic/convention/build.gradle.kts` and the
`LeicaConventionSupport.kt` shared helpers.

### Dependency rules (enforced by ADR-002 and the convention plugin)

- `:common`, `:common-test`, `:hardware-contracts` depend on **nothing
  else in the repo**.
- `:xyz:api` modules depend only on `:common` + `:hardware-contracts`
  + external stable types. **Zero Android imports.**
- `:xyz:impl` modules depend on their own `:api` + the `:api`s of
  engines they orchestrate. **Never** another engine's `:impl`.
  - ⚠ **Currently violated** in three places (see Problems list P0-2,
    P0-3, P1-1). These are P0 in `Plan.md`.
- `:feature:*` modules depend on engine `:api`s via Hilt injection,
  never directly on `:impl`.
- `:app` depends on features + (per Plan.md P0) the `:impl` of every
  engine whose Hilt `@Module` contributes bindings the app needs.

---

## 4. Module Dependency Diagram

```
                               ┌─────────────────┐
                               │      :app       │
                               │ (LeicaCamApp +  │
                               │  MainActivity)  │
                               └────────┬────────┘
                                        │
              ┌─────────────────────────┼─────────────────────────┐
              │                         │                         │
      ┌───────▼───────┐         ┌───────▼───────┐         ┌───────▼───────┐
      │ :feature:     │         │ :feature:     │         │ :feature:     │
      │   camera      │         │   gallery     │         │   settings    │
      └───────┬───────┘         └───────────────┘         └───────────────┘
              │
  ┌───────────┴──────────────────────────────────────────────────────────┐
  │ (all engines referenced via :api; impl side loads via Hilt in :app)   │
  ▼                                                                       ▼
┌────────────────┐   ┌─────────────────────┐   ┌───────────────┐   ┌───────────────┐
│ :camera-core   │   │ :imaging-pipeline   │   │ :ai-engine    │   │ :hypertone-wb │
│   :api :impl   │   │     :api :impl      │   │  :api :impl   │   │  :api :impl   │
│  (Camera2       │   │ ┌─────┐    ┌──────┐│   │   LiteRT +    │   │  Skin anchor  │
│   session       │   │ │ hdr/│    │pipe/ ││   │   MediaPipe   │   │  + Robertson  │
│   config,       │   │ │(new)│    │(old) ││   │   runners     │   │  CCT          │
│   ChipsetDetect)│   │ └──┬──┘    └──┬───┘│   │               │   │               │
└───────┬────────┘   └────┼──────────┼────┘   └───────┬───────┘   └───────┬───────┘
        │                 │          │                │                    │
        │                 ▼          ▼                │                    │
        │        ┌─────────────┐ ┌──────────────┐    │                    │
        │        │ProXdr       │ │Frame         │    │                    │
        │        │Orchestrator │ │AlignmentEng. │    │                    │
        │        │(new, unused │ │(old, active) │    │                    │
        │        │ in process) │ │+HdrMergeEng. │    │                    │
        │        └─────────────┘ └──────────────┘    │                    │
        │                                            │                    │
        └──────────────── :sensor-hal ◄──────────────┤                    │
                             │                       │                    │
                             ▼                       ▼                    ▼
                    ┌─────────────────┐    ┌────────────────┐   ┌────────────────┐
                    │ :native-imaging │    │ :depth-engine  │   │ :face-engine   │
                    │   :api :impl    │    │  :api :impl    │   │  :api :impl    │
                    │   (JNI to C++)  │    │ (MiDaS stub)   │   │ (stub mesh)    │
                    └────────┬────────┘    └───────┬────────┘   └───────┬────────┘
                             │                     │                    │
                             ▼                     ▼                    ▼
                    ┌───────────────┐     ┌────────────────┐   ┌────────────────┐
                    │ :gpu-compute  │     │ :bokeh-engine  │   │ :motion-engine │
                    │  Vulkan /     │     │  :api :impl    │   │  :api :impl    │
                    │  OpenGLES /   │     │ (stub CoC)     │   │ (RAW aligner)  │
                    │  CPU fallback │     └────────────────┘   └────────────────┘
                    └───────────────┘
                             │
                             ▼
               ┌─────────────────────────────┐
               │ :neural-isp      :smart-imaging│
               │   :api :impl       :api :impl  │
               └──────────────────────┬──────────┘
                                      │
             ┌────────────────────────┼────────────────────────┐
             ▼                        ▼                        ▼
    ┌─────────────────┐      ┌─────────────────┐      ┌──────────────────┐
    │ :common         │      │ :hardware-       │      │ :ui-components   │
    │ LeicaResult,    │      │   contracts      │      │ Compose theme +  │
    │ DomainError,    │      │ PhotonBuffer,    │      │ Phase9 overlay   │
    │ LeicaLogger,    │      │ SensorSpec, ...  │      │                  │
    │ PipelineStage,  │      └──────────────────┘      └──────────────────┘
    │ (Constants,     │              ▲
    │  Logger,        │              │
    │  Result — drift)│              │
    └────────┬────────┘              │
             │                       │
             └── every module depends on :common and :hardware-contracts ──
```

The "`pipe/ (old)`" node in `:imaging-pipeline:impl` is the active
runtime path. The "`hdr/ (new)`" node is the D2 replacement
(`ProXdrOrchestrator` + sub-engines) — code-complete, tested, **not
wired into `ImagingPipeline.process`** yet. Plan.md P2 wires it.

---

## 5. Runtime Capture Flow (end-to-end, as-currently-running)

Entry point:
`features/camera/.../ui/CameraScreen.kt`
→ `deps.sessionManager.capture()`
→ `platform-android/sensor-hal/.../session/Camera2CameraController.capture()`
→ `ImageCapture.takePicture(...)` via CameraX.

The **imaging-pipeline** (`ImagingPipeline.process`) is exposed through
`:feature:camera`'s `NativeImagingRuntimeFacade`, but as of this file's
date, the `CameraScreen` shutter button does **not** funnel the
captured image through `ImagingPipeline.process` — it goes directly
through CameraX's JPEG save path and then (if the native runtime is
configured) through `ImagingRuntimeOrchestrator` → native C++ core.
The full Kotlin pipeline below runs in unit tests and is reachable via
the facade, but the end-to-end UI → pipeline hook is pending Plan.md
P5.

```
╔════════════════════════════════════════════════════════════════════════════╗
║                      USER TAPS SHUTTER (CameraScreen.kt)                   ║
╚════════════════════════════════════════════════════════════════════════════╝
                                     │
                                     ▼
┌────────────────────────────────────────────────────────────────────────────┐
│  Stage 0:  Lightning Snap / ZSL ring buffer                                │
│  File: platform-android/sensor-hal/.../zsl/ZeroShutterLagRingBuffer.kt     │
│  ⚠ Not thread-safe (Problems list P1-12). Plain ArrayDeque.                │
│  Pulls most recent RAW_SENSOR frame; trigger EV-offset captures.           │
└──────────────────────────────────┬─────────────────────────────────────────┘
                                   │  N RAW frames + noise metadata
                                   ▼
┌────────────────────────────────────────────────────────────────────────────┐
│  Stage 1 (LEGACY):  Multi-scale translational SAD alignment                │
│  File: engines/imaging-pipeline/impl/.../pipeline/ImagingPipeline.kt       │
│         class FrameAlignmentEngine                                         │
│  (D2 replacement `DeformableFeatureAligner` exists but not yet called)     │
└──────────────────────────────────┬─────────────────────────────────────────┘
                                   │  aligned frames + per-frame transforms
                                   ▼
┌────────────────────────────────────────────────────────────────────────────┐
│  Stage 2 (LEGACY):  Ghost-free HDR merge                                   │
│  File: engines/imaging-pipeline/impl/.../pipeline/ImagingPipeline.kt       │
│         class HdrMergeEngine                                               │
│    - same-exposure burst → Wiener merge (inverse-variance on green)        │
│    - EV-bracketed → Debevec trapezoidal-weighted radiance                  │
│    - all-clipped → Mertens exposure fusion (simple weighted blend — buggy) │
│                                                                            │
│  (D2 replacements `ProXdrOrchestrator`, `RadianceMerger`, `MertensFallback`│
│   exist in engines/imaging-pipeline/impl/.../hdr/ but are NOT invoked.)    │
└──────────────────────────────────┬─────────────────────────────────────────┘
                                   │  merged 16-bit linear RGB + ghost mask
                                   ▼
┌────────────────────────────────────────────────────────────────────────────┐
│  Stage 3:  Shadow denoise (BEFORE any tone lift — LUMO law 4)              │
│  File: engines/imaging-pipeline/impl/.../pipeline/ImagingPipeline.kt       │
│         class ShadowDenoiseEngine                                          │
└──────────────────────────────────┬─────────────────────────────────────────┘
                                   │
                                   ▼
┌────────────────────────────────────────────────────────────────────────────┐
│  Stage 3.5: Neural ISP (D1.8) — gated to ultra-wide/front sensors          │
│  File: engines/ai-engine/impl/.../models/MicroIspRunner.kt                 │
│  ⚠ Currently a pass-through — see Problems list P1-2                       │
└──────────────────────────────────┬─────────────────────────────────────────┘
                                   │
                                   ▼
┌────────────────────────────────────────────────────────────────────────────┐
│  Stage 3.6: Semantic segmentation (DeepLabv3)                              │
│  File: engines/ai-engine/impl/.../models/SemanticSegmenterRunner.kt        │
│  ⚠ Output parsing broken — reads int labels as float (P1-5)                │
│  ⚠ confidenceThreshold computed and ignored (P1-6)                         │
│  Builds SemanticMask (BACKGROUND / MIDGROUND / PERSON / UNKNOWN)           │
└──────────────────────────────────┬─────────────────────────────────────────┘
                                   │  SemanticMask
                                   ▼
┌────────────────────────────────────────────────────────────────────────────┐
│  Stage 4:  Colour science + WB                                             │
│  core/color-science/impl/.../pipeline/ColorScienceEngines.kt               │
│  engines/hypertone-wb/impl/.../pipeline/HyperToneWhiteBalanceEngine.kt     │
│  ⚠ Imports ai_engine.impl directly (P0-3); will not compile as-is.         │
│  AWB blended with skin anchor ± 300 K via HyperToneWB2Engine.              │
└──────────────────────────────────┬─────────────────────────────────────────┘
                                   │
                                   ▼
┌────────────────────────────────────────────────────────────────────────────┐
│  Stage 5:  Durand bilateral local tone mapping + Cinematic S-curve         │
│  File: engines/imaging-pipeline/impl/.../pipeline/ImagingPipeline.kt       │
│         DurandBilateralToneMappingEngine → CinematicSCurveEngine           │
│  Face override (faceMask) lifts shadowFloor and pulls shoulder earlier.    │
└──────────────────────────────────┬─────────────────────────────────────────┘
                                   │
                                   ▼
┌────────────────────────────────────────────────────────────────────────────┐
│  Stage 6:  Luminosity-only sharpening (Lab L channel)                      │
│  File: engines/imaging-pipeline/impl/.../pipeline/ImagingPipeline.kt       │
│         class LuminositySharpener (USM on luminance only)                  │
└──────────────────────────────────┬─────────────────────────────────────────┘
                                   │
                                   ▼
┌────────────────────────────────────────────────────────────────────────────┐
│  Stage 7:  Output encode + metadata                                        │
│  File: engines/imaging-pipeline/impl/.../pipeline/OutputMetadataPipeline.kt│
│    DNG opcode builder + HEIC profile (P3 + HDR10) + XMP `pc:` namespace    │
│    + privacy-first audit log.                                              │
└────────────────────────────────────────────────────────────────────────────┘
```

Video flow diverges at Stage 2 into `VideoPipeline.kt` (630 LOC).
Bokeh is a post-Stage-6 compositing pass triggered by mode selection
in `ComputationalModes.kt`. The real CoC math is not yet implemented —
`BokehEngineOrchestrator` is a stub (Plan.md P6).

### Native runtime path (parallel)

`features/camera/.../NativeImagingRuntimeFacade.kt`
→ `platform-android/native-imaging-core/impl/.../ImagingRuntimeOrchestrator.kt`
→ JNI → `platform-android/native-imaging-core/impl/src/main/cpp/native_imaging_core.cpp`

⚠ The Kotlin worker loop in `ImagingRuntimeOrchestrator` has a
1:1 ingest ↔ process pairing bug that causes deadlocks and a
use-after-free on `HardwareBuffer`. See Problems list P1-8.

---

## 6. DI / "File Networking" — How Classes Talk to Each Other

LeicaCam is 100% **Hilt-based** with `@InstallIn(SingletonComponent::class)`
modules per engine. There are **18** active `@Module` files in the
production tree (zero `DependencyModule.kt` files — all have been
renamed to `<Module>Module.kt`).

### Active Hilt modules (full list — 18 files)

| File | Provides |
|---|---|
| `app/src/main/kotlin/com/leica/cam/di/AssetsModule.kt` | ⚠ Duplicate `@Named("assetBytes")` loader — see P0-4 |
| `engines/ai-engine/impl/.../di/AiEngineModule.kt` | `ModelRegistry`, `@Named("assetBytes")` loader, 5 LiteRT / MediaPipe runners, `AiEngineOrchestrator` |
| `engines/bokeh-engine/impl/.../di/BokehEngineModule.kt` | `BokehEngineOrchestrator` |
| `engines/depth-engine/impl/.../di/DepthEngineModule.kt` | `DepthEngine` impls |
| `engines/face-engine/impl/.../di/FaceEngineModule.kt` | `FaceEngine` impls |
| `engines/hypertone-wb/impl/.../di/HypertoneWbModule.kt` | `HyperToneWhiteBalanceEngine`, `HyperToneWB2Engine`, WB helpers |
| `engines/imaging-pipeline/impl/.../di/ImagingPipelineModule.kt` | `ImagingPipeline` + orchestrator + 15+ engine providers |
| `engines/motion-engine/impl/.../di/MotionEngineModule.kt` | `RawFrameAligner` |
| `engines/neural-isp/impl/.../di/NeuralIspModule.kt` | Neural ISP stages |
| `core/camera-core/impl/.../di/CameraCoreModule.kt` | `Camera2SessionConfigurator`, `IspOptimizer`, `ChipsetDetector` |
| `core/color-science/impl/.../di/ColorScienceModule.kt` | Colour engines |
| `core/lens-model/.../di/LensModelModule.kt` | `LensCorrectionSuite` + calibration data |
| `platform-android/gpu-compute/.../di/GpuComputeModule.kt` | `GpuBackend`, Vulkan pipeline, shader registry |
| `platform-android/sensor-hal/.../di/SensorHalModule.kt` | `CameraSessionManager`, `Camera2CameraController`, `SensorProfileRegistry`, `ZeroShutterLagRingBuffer`, AF + metering engines |
| `platform-android/ui-components/.../di/UiComponentsModule.kt` | Compose theme providers, `Phase9UiStateCalculator` |
| `features/camera/.../di/FeatureCameraModule.kt` | `CameraUiOrchestrator`, `CameraModeSwitcher`, `CameraScreenDeps`, `ProModeController`, `PostCaptureEditor` |
| `features/gallery/.../di/FeatureGalleryModule.kt` | `GalleryMetadataEngine` |
| `features/settings/.../di/FeatureSettingsModule.kt` | `CameraPreferencesRepository` + store |

Notes:

- **No `:photon-matrix:impl` Hilt module** — its classes
  (`PhotonMatrixAssembler`, `PhotonMatrixIngestor`, `GrGbCorrectionEngine`)
  are constructed directly via `@Inject constructor` and resolved
  transitively. If Hilt wiring needs explicit providers, add
  `core/photon-matrix/impl/src/main/kotlin/.../di/PhotonMatrixModule.kt`.
- **No `:smart-imaging:impl` Hilt module** — same pattern.
- **No `:common:` / `:hardware-contracts:` Hilt modules** — by design;
  they're pure-JVM types with no construction dependencies.

### Cross-module wiring (simplified, reflecting current state)

```
MainActivity (@AndroidEntryPoint)
  ├── inject CameraScreenDeps                ← features/camera/di/FeatureCameraModule
  │     ├── CameraUiOrchestrator
  │     ├── Phase9UiStateCalculator          ← platform-android/ui-components
  │     ├── CameraModeSwitcher
  │     ├── CameraPreferencesRepository      ← features/settings/di
  │     ├── Camera2CameraController          ← platform-android/sensor-hal/di
  │     └── CameraSessionManager             ← platform-android/sensor-hal/di
  │
  ├── inject GalleryMetadataEngine           ← features/gallery/di
  │
  └── (via LeicaCamApp @HiltAndroidApp):
      ├── inject ModelRegistry               ← engines/ai-engine/impl/di
      │     (⚠ currently scans File("models") — won't find APK assets; P0-6)
      └── inject @Named("assetBytes") Function1<String,ByteBuffer>
            (⚠ duplicate provider — P0-4)

CameraScreen shutter → Camera2CameraController.capture()
  (currently does NOT call through ImagingPipeline — CameraX writes JPEG directly.)

Pipeline-level graph (as-wired, if the facade is invoked):
  ImagingPipelineOrchestrator ← ImagingPipelineModule
    ├── ImagingPipeline
    │     ├── FrameAlignmentEngine          (legacy — still active)
    │     ├── HdrMergeEngine                (legacy — still active)
    │     ├── DurandBilateralToneMappingEngine
    │     ├── CinematicSCurveEngine
    │     ├── ShadowDenoiseEngine
    │     ├── LuminositySharpener
    │     ├── MicroIspRunner? (optional)    (⚠ pass-through; P1-2)
    │     └── SemanticSegmenterRunner? (opt)(⚠ label-read broken; P1-5)
    └── ToneLM2Engine / FusionLM2Engine / AcesToneMapper / ComputationalModes
  NOT YET WIRED:
    ProXdrOrchestrator (hdr/ sub-package)   ← the D2 replacement; Plan.md P2
```

### Thread & scope model

- **UI + Compose:** Main dispatcher (`Dispatchers.Main`).
- **Capture orchestration** (`CameraUiOrchestrator`,
  `NativeImagingRuntimeFacade`): `Dispatchers.Default` with a
  supervisor job scoped to the session.
- **Heavy imaging stages:** dedicated `CameraSessionScope` (see
  `platform/common/.../CameraSessionScope.kt`) — a `CoroutineScope`
  tied to the camera open/close lifecycle.
- **AI inference:** `Dispatchers.Default`, one `LiteRtSession` per
  runner (reflective construction; see `LiteRtSession.kt`). Plan.md
  P3 replaces reflection with typed SDK calls.
- **GPU compute:** Vulkan queue submission on a dedicated thread owned
  by `:gpu-compute`.

### LUMO rules enforced at scope boundaries

- `CancellationException` is always re-thrown (no
  `catch (e: Exception)` swallowing).
  - ⚠ Currently violated in `FaceLandmarkerRunner.kt:135` (see P2-6).
- All fallible functions return `LeicaResult<T>` — no unchecked
  exceptions cross module boundaries.
  - ⚠ Violated in `Camera2CameraController.capture()` and
    `CameraSessionManager.capture()` (see P1-11, P2-14).
- No `GlobalScope` anywhere. ✓

---

## 7. Key Files by Subsystem (what each does)

### 7.1 App entry (4 items)

- `app/src/main/kotlin/com/leica/cam/LeicaCamApp.kt`
  — `@HiltAndroidApp` Application + AI warm-up on `Dispatchers.IO`
  (⚠ warm-up currently broken — 4-byte dummy buffers; P0-7).
- `app/src/main/kotlin/com/leica/cam/MainActivity.kt`
  — `@AndroidEntryPoint`, NavHost, bottom-nav between
  `camera / gallery / settings`, Compose theme, `FLAG_KEEP_SCREEN_ON`.
- `app/src/main/kotlin/com/leica/cam/di/AssetsModule.kt`
  — ⚠ Duplicate `@Named("assetBytes")` provider; delete per P0-4.
- `app/build.gradle.kts`
  — APK config, Compose + Hilt, `copyOnDeviceModels` task that mirrors
  `/Model/**/*.tflite` + `*.task` into `app/src/main/assets/models/`.
  ⚠ Missing project deps on engine `:impl` modules — P0-1.

### 7.2 Feature layer

#### `:feature:camera` (`features/camera/`)

- `ui/CameraScreen.kt` — viewfinder Compose UI.
  ⚠ Most HUD buttons are no-op stubs (P1-14); overlay state is
  hardcoded (P1-15).
- `ui/CameraUiOrchestrator.kt` — mode switcher, PRO mode controls,
  40+ post-capture edit tool catalogue, gesture orchestrator.
- `NativeImagingRuntimeFacade.kt` — presentation-layer-facing facade
  over `ImagingRuntimeOrchestrator`.
- `controls/CaptureControlsViewModel.kt` — bound to PRO mode form.
- `preview/CameraPreview.kt` — Compose `AndroidView` wrapping
  `PreviewView`; observes lifecycle to open/close the session.
  ⚠ Fire-and-forget coroutines with swallowed errors (P1-13).
- `permissions/{PermissionGate, PermissionState, RequiredPermissions}.kt`
  — Accompanist-based permissions flow.

#### `:feature:gallery` (`features/gallery/`)

- `ui/GalleryScreen.kt` — gallery grid + metadata surface.
- `ui/GalleryMetadataEngine.kt` — loads XMP/DNG metadata and projects
  it to UI DTOs.

#### `:feature:settings` (`features/settings/`)

- `ui/SettingsScreen.kt` — settings UX.
- `ui/SettingsCatalog.kt`, `ui/SettingsSection*.kt`, `ui/SettingsViewModel.kt`
  — section model + rendering + state.
- `preferences/CameraPreferences.kt` — the typed preferences DTO
  (grid style, user HDR mode, user AWB mode, flash, etc.).
- `preferences/CameraPreferencesRepository.kt` — `StateFlow`-backed
  single source of truth. ⚠ Missing `@Inject` constructor (P2-16).
- `preferences/SharedPreferencesCameraStore.kt` — the SharedPreferences
  persistence backend.

### 7.3 Camera core + sensor HAL

#### `:camera-core` (`core/camera-core/`)

- `api/CameraCoreContracts.kt` — contracts.
- `impl/isp/Camera2SessionConfigurator.kt` — Camera2 surface graph
  builder (preview + RAW + HDR streams).
- `impl/isp/ChipsetDetector.kt` — detects SoC family
  (Qualcomm / MediaTek / Exynos / Generic).
- `impl/isp/IspOptimizer.kt` — vendor ISP capability detection &
  routing.
- `di/CameraCoreModule.kt` — Hilt providers.

#### `:sensor-hal` (`platform-android/sensor-hal/`)

- `session/Camera2CameraController.kt` — **THE active camera
  controller** (CameraX + Camera2 interop). Handles manual
  ISO/shutter/WB. ⚠ Leaks `captureExecutor` on close (P1-10); main-
  thread Camera2 IPC can ANR (P1-9).
- `session/CameraSessionManager.kt` — lifecycle orchestrator with
  retry + idempotent guards. ⚠ Throws on failure across module
  boundary (P2-14).
- `session/CameraSessionStateMachine.kt` — sealed-class FSM:
  `CLOSED → OPENING → CONFIGURED → IDLE → CAPTURING → ...`.
- `session/CameraRequestControlState.kt` — immutable ISO/shutter/WB
  request-state value object.
- `session/DefaultCameraSelector.kt` — default camera picker.
- `autofocus/HybridAutoFocusEngine.kt` — PDAF + contrast AF fusion.
- `metering/AdvancedMeteringEngine.kt` — spot/matrix/centre-weighted
  metering.
- `capability/CameraCapabilityProfile.kt` + `Camera2MetadataSource.kt`
  — extracts `SENSOR_NOISE_PROFILE`,
  `SENSOR_INFO_COLOR_FILTER_ARRANGEMENT`, `LENS_DISTORTION` from
  `CameraCharacteristics`.
- `sensor/profiles/SensorProfile.kt` + `SensorProfileRegistry.kt` —
  per-sensor tuning (S5KHM6, OV64B40, …, SC202PCS) per
  `.agents/skills/Leica Cam Upgrade skill/references/sensor-profiles.md`.
- `sensor/SmartIsoProDetector.kt` — detects Samsung Smart-ISO Pro
  mode; disables redundant noise modelling.
- `soc/SoCProfile.kt` — MediaTek vs Snapdragon vs Exynos capability
  detection.
- `isp/IspIntegrationOrchestrator.kt` — vendor ISP capability routing.
- `zsl/ZeroShutterLagRingBuffer.kt` — ⚠ Not thread-safe (P1-12).

### 7.4 Imaging pipeline (the hot path)

#### Legacy path (`engines/imaging-pipeline/impl/src/main/kotlin/.../pipeline/`)

- `ImagingPipeline.kt` (**1402 LOC** — still the active path;
  Plan.md P2 slims it)
  - `PipelineFrame` DTO (linear-light RGB + ISO + exposure +
    evOffset).
  - `AlignmentTransform`, `AlignmentResult`.
  - `NoiseModel` (σ² = A·x + B).
  - `HdrMergeResult`, `HdrMergeMode`, `SemanticZone`, `SemanticMask`.
  - `FrameAlignmentEngine` — multi-scale SAD (legacy — to be replaced).
  - `HdrMergeEngine` — Wiener burst + Debevec linear + MTB ghost
    (legacy — to be replaced).
  - `DurandBilateralToneMappingEngine` — base/detail log-luminance
    decomposition.
  - `CinematicSCurveEngine` — shadow toe + linear mid + tanh shoulder
    + face override.
  - `ShadowDenoiseEngine` — pre-tone-lift bilateral denoise.
  - `LuminositySharpener` — Lab L-only USM.
  - `ImagingPipeline` class — the live `process()` entry point.
  - `ImagingPipelineOrchestrator` — scene-adaptive routing.
- `ProXdrHdrEngine.kt` (**546 LOC**) — ⚠ **DEAD CODE**; legacy HDR
  orchestrator + its own `SceneDescriptor`, `SceneCategory`,
  `ThermalState`, `HdrBracketSelector`. Referenced only by itself and
  by KDoc in the new `hdr/` package. Delete per Plan.md P2.
- `FusionLM2Engine.kt` (**424 LOC**) — FusionLM 2.0 Wiener-weighted
  tile merge.
- `MultiScaleFrameAligner.kt` (**425 LOC**) — pyramidal alignment
  helper.
- `ToneLM2Engine.kt` (**309 LOC**) — shadow → local EV → bilateral
  → S-curve → face pass → sharpen orchestration.
- `AcesToneMapper.kt` (**125 LOC**) — ACES filmic tonemap
  (A=2.51, B=0.03, C=2.43, D=0.59, E=0.14).
- `ComputationalModes.kt` (**407 LOC**) — portrait / night / macro /
  astro / panorama / seamless-zoom + orchestrator.
- `VideoPipeline.kt` (**630 LOC**) — video encode path with OIS+EIS
  fusion, temporal denoise, cinema colour profile, pro audio pipeline,
  time-lapse engine.
- `OutputMetadataPipeline.kt` (**381 LOC**) — DNG opcode builder +
  HEIC profile (Display P3 + HDR10) + XMP `pc:` namespace +
  privacy-first audit log + `PrivacyMetadataPolicy`.

#### New HDR path (`engines/imaging-pipeline/impl/src/main/kotlin/.../hdr/`)

Added in D2; **code-complete, tested, not yet invoked** from
`ImagingPipeline.process`. Plan.md P2 performs the switch.

- `BracketSelector.kt` — EV-bracket selection with thermal + face
  bias.
- `DeformableFeatureAligner.kt` — 4-level pyramidal Lucas-Kanade
  dense optical flow + bilinear warp.
- `GhostMaskEngine.kt` — pre-alignment MTB + Gaussian soft dilation
  (runs BEFORE alignment — fixes legacy bug where MTB ran after).
- `HdrTypes.kt` — `SceneDescriptor`, `SceneCategory`, `ThermalState`,
  `HdrFrameSetMetadata`, `HdrModePicker`, `PerChannelNoise`,
  `ChannelNoise`, `HdrProcessingResult`. ⚠ Parallel universe to
  `ProXdrHdrEngine.kt`'s duplicate types (P2-4).
- `HighlightReconstructor.kt` — cross-channel ratio-based highlight
  recovery from darkest frame.
- `MertensFallback.kt` — real Burt–Adelson Laplacian pyramid blend.
- `ProXdrOrchestrator.kt` — **new HDR orchestrator**; the entry point
  Plan.md P2 wires into `ImagingPipeline.process`. ⚠ Line 123–125 has
  a type error (P0-5).
- `RadianceMerger.kt` — per-CFA-channel Wiener merge + Debevec
  trapezoidal radiance recovery.
- `ShadowRestorer.kt` — bilateral-feathered shadow restoration from
  brightest frame.

### 7.5 Colour, WB, AI, depth, face

- `core/color-science/impl/.../pipeline/ColorScienceEngines.kt` —
  CCM interpolation (two-illuminant), CUSP gamut mapping, Robertson
  CCT in CIE 1960 (u, v).
- `core/color-science/impl/.../pipeline/ColorScienceModels.kt` —
  associated data types.
- `engines/hypertone-wb/impl/.../pipeline/HyperToneWhiteBalanceEngine.kt`
  — D1.7 facade; blends neural AWB prior with Robertson estimate.
  ⚠ Cross-impl import (P0-3).
- `engines/hypertone-wb/impl/.../pipeline/HyperToneWB2Engine.kt` —
  Phase-15 per-zone bilateral gain-field WB.
- `engines/hypertone-wb/impl/.../pipeline/SkinZoneWbGuard.kt` —
  clamps all zone CCTs to skin ± 300 K.
- `engines/hypertone-wb/impl/.../pipeline/WbTemporalMemory.kt` —
  `α = 0.15` EMA smoothing for flicker-free preview.
- `engines/hypertone-wb/impl/.../pipeline/{IlluminantEstimators,
  MixedLightSpatialWbEngine, MultiModalIlluminantFusion,
  PartitionedCTSensor, RobertsonCctEstimator, ColorTemperatureMath,
  KelvinToCcmConverter, HyperToneModels}.kt` — supporting
  estimators, math, and data types.
- `engines/ai-engine/api/.../AiContracts.kt, AiInterfaces.kt, AiModels.kt`
  — DTOs and interfaces (`IAiEngine`, `SceneAnalysis`, `SceneLabel`,
  `QualityScore`, etc.). ⚠ Duplicate `SceneLabel` in impl runner (P1-3).
- `engines/ai-engine/impl/.../pipeline/AiEngineOrchestrator.kt` —
  ⚠ **Returns hardcoded stub values** — see P1-1. Plan.md P1 rewrites.
- `engines/ai-engine/impl/.../pipeline/EnginesImpl.kt` — concrete
  engine implementations (helpers for the orchestrator).
- `engines/ai-engine/impl/.../registry/ModelRegistry.kt` — magic-byte
  format detection + keyword role assignment + warm-up. ⚠ Multiple
  bugs (P0-6 wrong dir, P0-7 dummy buffers, P3-3 keyword overlap).
- `engines/ai-engine/impl/.../runtime/{LiteRtSession, DelegatePicker,
  VendorDelegateLoader}.kt` — reflection-based LiteRT wrapper + SoC-
  aware delegate picker. Plan.md P3 swaps to typed calls.
- `engines/ai-engine/impl/.../models/{AwbModel, FaceLandmarker,
  MicroIsp, SceneClassifier, SemanticSegmenter}Runner.kt` — per-model
  inference wrappers.
- `engines/depth-engine/api/.../DepthContracts.kt`,
  `engines/depth-engine/impl/.../{DepthSensingFusion.kt,
  pipeline/DepthImpl.kt}` — ⚠ Stub (hardcoded 0.5 depth).
- `engines/face-engine/api/.../FaceContracts.kt`,
  `engines/face-engine/impl/.../{FaceEngine.kt, pipeline/FaceImpl.kt}`
  — ⚠ Stub (468 × (0.5, 0.5, 0.5) landmarks).

### 7.6 Photon matrix, bokeh, motion, neural ISP, smart imaging

- `core/photon-matrix/impl/.../{PhotonMatrixAssembler, PhotonMatrixIngestor}.kt`
  — spectral reconstruction (partitioned CT sensing per ADR-005).
- `core/photon-matrix/impl/.../correction/GrGbCorrectionEngine.kt` —
  Gr/Gb imbalance correction.
- `engines/bokeh-engine/impl/.../BokehEngineOrchestrator.kt` — ⚠ Stub;
  real CoC math is Plan.md P6.
- `engines/motion-engine/impl/.../RawFrameAligner.kt` — additional
  RAW-domain aligner used outside the HDR path (e.g. super-res).
- `engines/neural-isp/impl/.../pipeline/{NeuralIspModels,
  NeuralIspProcessors, NeuralIspStages}.kt` — learned ISP stage
  routing.
- `engines/smart-imaging/impl/.../SmartImagingOrchestrator.kt` —
  scene-adaptive routing between computational modes.

### 7.7 GPU, lens, native, UI, shared

- `platform-android/gpu-compute/.../GpuBackend.kt` — abstraction
  interface.
- `platform-android/gpu-compute/.../VulkanBackend.kt` +
  `vulkan/VulkanComputePipeline.kt` + `vulkan/ShaderRegistry.kt` —
  Vulkan Compute implementation.
- `platform-android/gpu-compute/.../OpenGlEsBackend.kt` — GLES 3.1
  compute fallback.
- `platform-android/gpu-compute/.../CpuFallbackBackend.kt` — CPU
  fallback.
- `platform-android/gpu-compute/.../GpuComputeInitializer.kt` —
  picks the active backend at startup.
- `core/lens-model/.../calibration/LensCalibrationData.kt` —
  per-lens-variant (AAC, Sunny, OFILM, SEMCO, `_cn`) calibration data.
- `core/lens-model/.../correction/LensCorrectionSuite.kt` —
  Brown-Conrady distortion + vignette correction.
- `platform-android/native-imaging-core/impl/src/main/cpp/native_imaging_core.cpp`
  — 1012-line JNI native runtime core (zero-copy HardwareBuffer
  handoff).
- `platform-android/native-imaging-core/impl/src/main/cpp/photon_buffer.h`
  — 340-line C++ photon-buffer RAII contract.
- `platform-android/native-imaging-core/impl/.../ImagingRuntimeOrchestrator.kt`
  — Kotlin side of the JNI bridge. ⚠ Worker loop is broken (P1-8).
- `platform-android/native-imaging-core/impl/.../{NativeImagingBridge,
  RuntimeGovernor}.kt` — bridge types + governor.
- `platform-android/ui-components/.../theme/LeicaTheme.kt` + sibling
  theme tokens (`LeicaColors`, `LeicaTypography`, `LeicaSpacing`,
  `LeicaShapes`, `LeicaElevation`, `LeicaMotion`).
- `platform-android/ui-components/.../camera/{LeicaComponents,
  LeicaDialSheet, GridOverlay, Phase9UiModels}.kt` — Compose camera
  widgets + overlay model.
- `platform-android/ui-components/.../settings/{LeicaSegmentedControl,
  LeicaToggleRow}.kt` — reusable settings row widgets.
- `platform-android/ui-components/.../motion/{Haptics, LeicaTransitions}.kt`
  — motion + haptic tokens.
- `platform/common/.../result/LeicaResult.kt` — sealed
  Success / Failure (Pipeline / Hardware / Recoverable).
- `platform/common/.../result/DomainError.kt` — typed domain errors.
- `platform/common/.../result/PipelineStage.kt` — stage identifiers.
- `platform/common/.../logging/LeicaLogger.kt` — structured log
  facade.
- `platform/common/.../types/NonEmptyList.kt` — `NonEmptyList<T>`
  utility.
- `platform/common/.../CameraSessionScope.kt` — capture-lifecycle
  `CoroutineScope`.
- `platform/common/.../{Logger.kt, Constants.kt, Result.kt,
  PipelineErrorHandling.kt}` — ⚠ Junk-drawer residents; see
  Problems list P3-11 through P3-14.
- `platform/hardware-contracts/.../photon/PhotonBuffer.kt` — the
  authoritative `PhotonBuffer` sealed type.
- `platform/hardware-contracts/.../{GpuCapabilities, LensSpec,
  NpuCapabilities, SensorSpec}.kt` — hardware capability contracts.

### 7.8 On-device AI models (`/Model/`)

Copied to `app/src/main/assets/models/` at build time by the
`copyOnDeviceModels` Gradle task (registered in `app/build.gradle.kts`).

- `/Model/AWB/awb_final.onnx` — source; **not loaded on device**
  (ONNX kept for development/QA only).
- `/Model/AWB/awb_final_full_integer_quant.tflite` — INT8-quantised
  on-device runtime format.
- `/Model/Face Landmarker/face_landmarker.task` — MediaPipe Tasks
  bundle (zip-format).
- `/Model/Image Classifier/1.tflite` — ImageNet-style classifier;
  mapped to `SceneLabel` via lookup. ⚠ Dead NIGHT branch (P1-4).
- `/Model/MicroISP/MicroISP_V4_fp16.tflite` — learned Bayer-domain
  ISP refinement.
- `/Model/Scene Understanding/deeplabv3.tflite` — DeepLabv3 semantic
  segmenter (COCO 21-class variant). ⚠ Output parsing broken (P1-5).

---

## 8. Build & CI Anatomy

- **Root Gradle** (`build.gradle.kts`): applies
  `android-application`, `android-library`, `kotlin-android`,
  `kotlin-jvm`, `kotlin-kapt`, `hilt` with `apply false` — real
  application happens in per-module `build.gradle.kts`.
- **Composite build** (`build-logic/`): hosts convention plugins
  (`leica.android.application`, `leica.android.library`,
  `leica.jvm.library`, `leica.engine.module`). Included via
  `pluginManagement { includeBuild("build-logic") }` in the root
  `settings.gradle.kts`.
- **Version catalog** (`gradle/libs.versions.toml`): pinned versions
  for Kotlin 1.9.24, AGP 8.4.2, Compose BOM 2024.06.00, Hilt 2.51.1,
  CameraX 1.3.4, LiteRT 1.0.1, MediaPipe tasks-vision 0.10.14,
  coroutines 1.8.1, detekt 1.23.6, ktlint 12.1.1,
  binary-compatibility-validator 0.18.1.
- **Per-module** `build.gradle.kts`: each one applies
  `id("leica.android.application")` or `id("leica.android.library")`
  then declares only its own dependencies.
- **Detekt config:** `config/detekt/detekt.yml` — referenced from
  the convention plugin.
- **ProGuard/R8:** `app/proguard-rules.pro` (minimal stub; extend for
  Hilt, LiteRT, MediaPipe at release time).
- **Wrapper:** `gradlew` / `gradlew.bat` /
  `gradle/wrapper/gradle-wrapper.properties`.
- **CI:** `.github/workflows/` — audit separately; likely needs a
  refresh to include the Android-specific lanes.
- **Tests:** `./gradlew test` runs per-module unit tests. 34 test
  files exist under `engines/* impl/src/test/` and similar.
- **Instrumentation tests:** `./gradlew connectedDebugAndroidTest` —
  no tests currently defined at the `:app` level.

---

## 9. Documentation & Knowledge

- `README.md` — top-level entry.
- `project-structure.md` — **this file**.
- `Plan.md` — active upgrade plan (P0 → P6 sub-plans, with agent-skill
  routing table and file-level instructions).
- `Problems list.md` — current defect audit with P0/P1/P2/P3
  severity bands and specific file:line references.
- `Implementation.md` — historical phase-by-phase notes (phases 0–10,
  2201 lines). Consider moving to `docs/history/`.
- `changelog.md` — release notes.
- `docs/adr/ADR-001 … ADR-006.md` — Architecture Decision Records.
- `docs/ndk-core-rearchitecture.md` — NDK / native core rearchitecture
  notes.
- `docs/phase0-architecture.md` — original phase-0 architecture brief.
- `Knowledge/Advance HDR algorithm research.md` — research foundation
  for ProXDR.
- `Knowledge/advance imaging system research.md` — research foundation
  for the full LUMO platform.
- `Knowledge/2504.05623v2.pdf` — key reference paper (vendored).
- `.agents/skills/Leica Cam Upgrade skill/references/sensor-profiles.md`
  — per-sensor tuning bible.
- `.agents/skills/Lumo Imaging Engineer/references/hdr-engine-deep.md`
  — ProXDR deep reference.
- `.agents/skills/` (55 entries) — agent skill definitions; each
  `<skill>/SKILL.md` is the activation entry point (see
  `activate_ai_developer_skill` tool).

---

## 10. Auxiliary / non-Android repo contents

The repo root contains several items unrelated to the Android app
itself. They are documented here for completeness and flagged for
eventual relocation (see `Problems list.md` P3-17 through P3-21).

- `node_modules/` + `package.json` + `package-lock.json` — Node-based
  tooling.
- `lib/download-providers.js` — Node helper.
- `scripts/*.mjs` + `scripts/*.sh` — mostly Node + bash one-shots
  (bump-version, generate-changelog, pr-risk-check, require-tests,
  secret-scan, docs-prompt-injection-scan, rtk-benchmark, etc.).
- `src/detect-antipatterns*.mjs` + `tests/*.test.mjs` —
  JavaScript lint utilities (not part of the Android app).
- `source/skills/` — parallel "skills" directory (likely stale —
  contains an overlap with `.agents/skills/`).
- `split_modules.sh`, `split_modules2.sh` — D3 refactor
  one-shot scripts.
- `.Jules/`, `.bolt/` — tool-specific directories (Jules,
  bolt.new); safe to ignore for Android work.
- `skills-lock.json` — skill-version pin used by `.agents/skills/`.
- `Reference/` — legacy/reference assets (excluded from this audit
  on request).
- `GSD 2.0/` — parallel project tree (excluded from this audit
  on request).
- `.editorconfig`, `.gitignore`, `.github/` — standard repo files.

---

## 11. How to Keep This File Current

When modules are added, removed, renamed, or directory-relocated:

1. Update `settings.gradle.kts` FIRST.
2. Run `./gradlew projects` and confirm the module graph resolves
   without errors before touching this file.
3. Mirror the change in Section 3 (Gradle module graph) and
   Section 4 (dependency diagram).
4. Update Section 6 (DI modules) if a new `@Module` was added,
   deleted, or renamed.
5. Update Section 7 (key files) if a major file was added, split,
   or removed. Include ⚠ markers when a file has an open
   entry in `Problems list.md`.
6. Update Section 9 (docs) if new ADRs, known-issues sub-registries,
   or research notes were added.
7. If the plan in `Plan.md` completes a sub-plan, remove the ⚠
   markers for the bugs it fixed and refresh the runtime-flow
   diagram in Section 5.
8. Bump the `_Last updated:_` date at the top of this file in the
   same commit.

**Cross-reference discipline:** every ⚠ marker in this file MUST
point to a specific entry in `Problems list.md`. If a warning is
fixed, delete both the ⚠ here and the entry there in the same PR.

