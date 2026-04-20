# Project Structure — LeicaCam

_Last updated: 2026-04-20 (UTC) — reflects current `settings.gradle.kts` and on-disk layout._

This document is the **ground-truth map** of the LeicaCam repository. It is kept in sync with:

- `settings.gradle.kts` — module inclusion source of truth
- The `:app`, `:feature:*`, `:camera-core:*`, engine, and platform module directories
- The DI graph expressed through Hilt `@Module`s
- The runtime capture flow through `ImagingPipeline` and `ProXdrHdrOrchestrator`

If any of these diverge from this file, this file is **stale**. Update it.

---

## 1. Executive Snapshot

LeicaCam is a multi-module Android/Kotlin computational photography stack that implements the **LUMO** imaging platform: FusionLM multi-frame RAW fusion, ColorLM colour correction, HyperTone WB, ToneLM perceptual tone mapping, ProXDR HDR, Bokeh Engine, and Lightning Snap zero-shutter-lag capture. It targets a specific MediaTek-Dimensity device with 8 distinct image sensors (Samsung S5KHM6, OmniVision OV64B40/OV50D40/OV08D10/OV16A1Q, GalaxyCore GC16B3, SmartSens SC202CS/SC202PCS).

- **~ 160 Kotlin source files** (excluding generated code, tests, skills, tooling).
- **2 C++ files** under `native-imaging-core/impl/src/main/cpp/`.
- **5 on-device AI models** under `/Model/` (AWB, Face Landmarker, Image Classifier, MicroISP, DeepLabv3 Scene Understanding).
- **Inviolable LUMO laws**: RAW-domain-first, 16-bit end-to-end, physics-grounded noise, shadow-denoise-before-lift, skin-anchor-is-sacred, per-zone WB, zero cloud inference.

---

## 2. Top-Level Directory Map (as of 2026-04-20)

```
/
├── app/                        Android app shell — Application + MainActivity + NavHost + Compose theme
├── feature/                    User-facing flows (UI + orchestrator per flow)
│   ├── camera/                 Camera capture UX, state machine, native runtime facade
│   ├── gallery/                Gallery browser + metadata surface
│   └── settings/               Settings UX
├── camera-core/                Camera core domain contracts and ISP layer
│   ├── api/                    Interfaces / DTOs (platform-light)
│   └── impl/                   Implementation + Camera2SessionConfigurator + IspOptimizer
├── native-imaging-core/        Kotlin ↔ C++ bridge; native ownership of pixel handles
│   ├── api/
│   └── impl/                   Kotlin orchestrator + JNI bridge + `src/main/cpp/native_imaging_core.cpp`
├── imaging-pipeline/           Capture-to-output orchestration
│   ├── api/
│   └── impl/                   ImagingPipeline, ProXdrHdrEngine, FusionLM2Engine, ToneLM2Engine,
│                               MultiScaleFrameAligner, AcesToneMapper, VideoPipeline,
│                               ComputationalModes, OutputMetadataPipeline
├── color-science/              Colour transforms, LUTs, perceptual profile engines
│   ├── api/
│   └── impl/                   ColorScienceEngines.kt (CCM, gamut mapping, Robertson CCT)
├── hypertone-wb/               Illuminant estimation + white-balance stabilisation
│   ├── api/
│   └── impl/                   HyperToneWhiteBalanceEngine, SkinZoneWbGuard, WbTemporalMemory
├── ai-engine/                  Scene / face / depth / quality / segmentation inference orchestration
│   ├── api/                    IAiEngine contracts, SceneLabel, AiModels DTO
│   └── impl/                   AiEngineOrchestrator + ModelRegistry + EnginesImpl + di/
├── depth-engine/               Monocular depth & depth fusion
│   ├── api/
│   └── impl/
├── face-engine/                Face analysis (detection + landmarks + skin mask)
│   ├── api/
│   └── impl/
├── neural-isp/                 Learned ISP routing and staged neural processing
│   ├── api/
│   └── impl/
├── photon-matrix/              Multi-frame fusion contracts (spectral reconstruction)
│   ├── api/
│   └── impl/                   PhotonMatrixFusionEngine, correction/
├── smart-imaging/              Smart orchestration (scene-adaptive mode selection)
│   ├── api/
│   └── impl/                   SmartImagingOrchestrator
├── bokeh-engine/               CoC-based synthetic-aperture rendering
│   ├── api/
│   └── impl/                   BokehEngineOrchestrator
├── motion-engine/              Motion alignment + RAW frame alignment
│   ├── api/
│   └── impl/                   RawFrameAligner
├── sensor-hal/                 Camera2/session/autofocus/metering/ZSL/SoC/sensor profiles
├── lens-model/                 Geometric + optical correction primitives
├── gpu-compute/                Vulkan/GLES/CPU backends + compute pipelines + shaders
├── hardware-contracts/         Sensor/GPU/NPU/photon data contracts (authoritative PhotonBuffer)
├── common/                     LeicaResult, DomainError, PipelineStage, LeicaLogger, NonEmptyList
├── common-test/                Shared test fixtures and builders
├── ui-components/              Reusable Compose UI components and theme (LeicaBlack, LeicaRed, LeicaTheme)
├── Model/                      On-device AI models (dev source; copied to assets at build — see README)
│   ├── AWB/                    awb_final.onnx, awb_final_full_integer_quant.tflite
│   ├── Face Landmarker/        face_landmarker.task (MediaPipe format)
│   ├── Image Classifier/       1.tflite (placeholder name)
│   ├── MicroISP/               MicroISP_V4_fp16.tflite
│   └── Scene Understanding/    deeplabv3.tflite
├── Knowledge/                  Research-level references (HDR, imaging system)
├── Reference/                  Legacy/reference assets
├── config/                     Static-analysis config (detekt.yml)
├── docs/                       ADRs + architecture docs
│   └── adr/                    ADR-001 … ADR-006
├── .agents/                    Agent skills (Advisor, Lumo, Leica-Cam, etc.)
├── build.gradle.kts            Root Gradle config
├── settings.gradle.kts         Module inclusion source of truth
├── gradle.properties
├── gradlew / gradlew.bat       Gradle wrapper
├── Implementation.md           Historical phase-by-phase implementation notes (2201 lines)
├── Plan.md                     Four-dimension upgrade Plan (current)
├── changelog.md
├── project-structure.md        ← THIS FILE
└── README.md
```

---

## 3. Gradle Module Graph (source of truth)

From `settings.gradle.kts` — 33 modules total:

**App + features (4)**
- `:app`
- `:feature:camera`, `:feature:gallery`, `:feature:settings`

**Camera + runtime cores (6)**
- `:camera-core:api`, `:camera-core:impl`
- `:native-imaging-core:api`, `:native-imaging-core:impl`
- `:imaging-pipeline:api`, `:imaging-pipeline:impl`

**Imaging science & engines (16)**
- `:color-science:api`, `:color-science:impl`
- `:hypertone-wb:api`, `:hypertone-wb:impl`
- `:ai-engine:api`, `:ai-engine:impl`
- `:depth-engine:api`, `:depth-engine:impl`
- `:face-engine:api`, `:face-engine:impl`
- `:neural-isp:api`, `:neural-isp:impl`
- `:photon-matrix:api`, `:photon-matrix:impl`
- `:smart-imaging:api`, `:smart-imaging:impl`
- `:bokeh-engine:api`, `:bokeh-engine:impl`
- `:motion-engine:api`, `:motion-engine:impl`

**Hardware / platform / shared (7)**
- `:sensor-hal`
- `:lens-model`
- `:gpu-compute`
- `:ui-components`
- `:common`
- `:common-test`
- `:hardware-contracts`

> **API/Impl split.** Every engine splits public contracts (`:xyz:api`) from the implementation (`:xyz:impl`). `:api` modules are pure-Kotlin JVM libraries with zero Android imports; `:impl` modules are Android libraries that depend on their own `:api` and may pull Android/Hilt/Camera2.

---

## 4. Module Dependency Diagram

```
                               ┌─────────────────┐
                               │      :app       │
                               └────────┬────────┘
                                        │
                ┌───────────────────────┼───────────────────────┐
                │                       │                       │
        ┌───────▼──────┐        ┌───────▼──────┐        ┌───────▼──────┐
        │ :feature:    │        │ :feature:    │        │ :feature:    │
        │   camera     │        │   gallery    │        │   settings   │
        └───────┬──────┘        └──────────────┘        └──────────────┘
                │
      ┌─────────┴──────────────────────────────────────────┐
      │                                                    │
      ▼                                                    ▼
┌────────────────┐                              ┌─────────────────────┐
│ :camera-core   │                              │ :imaging-pipeline   │
│   :api :impl   │                              │     :api :impl      │
└───────┬────────┘                              └──────────┬──────────┘
        │                                                  │
        └──── :sensor-hal ◄────────┐       ┌───────────────┴──────────┐
                  ▲                │       │                          │
                  │                │       ▼                          ▼
         ┌────────┴────────┐       │  :photon-matrix           :motion-engine
         │ :native-imaging │       │    :api :impl               :api :impl
         │  :api :impl     │       │       │                          │
         └────────┬────────┘       │       ▼                          ▼
                  │                │  :color-science ◄────── :hypertone-wb
                  ▼                │    :api :impl             :api :impl
            C++ JNI layer          │       │                          │
                                   │       ▼                          ▼
           ┌──────────────┐        │    :ai-engine ─────── :face-engine, :depth-engine
           │ :gpu-compute │◄───────┤    :api :impl           :api :impl
           │   Vulkan     │        │       │                          │
           └──────────────┘        │       ▼                          ▼
                                   │   :neural-isp ────── :bokeh-engine, :smart-imaging
                                   │    :api :impl          :api :impl
                                   │
                                   ▼
                         ┌──────────────────────┐
                         │ :lens-model          │
                         └──────────┬───────────┘
                                    │
             ┌──────────────────────┼──────────────────────┐
             ▼                      ▼                      ▼
    ┌─────────────────┐   ┌──────────────────┐   ┌──────────────────┐
    │ :common         │   │ :hardware-       │   │ :ui-components   │
    │ LeicaResult,    │   │   contracts      │   │ (Compose theme)  │
    │ DomainError,    │   │ PhotonBuffer,    │   │                  │
    │ LeicaLogger     │   │ SensorSpec, ...  │   └──────────────────┘
    └─────────────────┘   └──────────────────┘
             ▲                      ▲
             └────── every module depends on :common and :hardware-contracts ─────┘
```

**Dependency rules (enforced by convention plugin + ADR-006):**
- `:common`, `:hardware-contracts` depend on NOTHING else in the repo.
- `:xyz:api` modules depend only on `:common` + `:hardware-contracts` (+ their own transitives).
- `:xyz:impl` modules depend on their own `:api` + the `:api`s of engines they orchestrate — never another engine's `:impl`.
- `:feature:*` modules depend on engine `:api`s through Hilt, never directly on `:impl`.
- `:app` depends on features and the `:impl`s for DI wiring only.

---

## 5. Runtime Capture Flow (end-to-end)

The flow below is **sacred** — re-ordering any stage breaks LUMO laws. Source-code entry point: `feature/camera/.../ui/CameraUiOrchestrator.kt` → `feature/camera/.../NativeImagingRuntimeFacade.kt` → `imaging-pipeline/impl/.../pipeline/ImagingPipeline.process()`.

```
╔════════════════════════════════════════════════════════════════════════════╗
║                      USER TAPS SHUTTER (CameraScreen.kt)                   ║
╚════════════════════════════════════════════════════════════════════════════╝
                                     │
                                     ▼
┌────────────────────────────────────────────────────────────────────────────┐
│  Stage 0:  Lightning Snap / ZSL ring buffer                                │
│  File: sensor-hal/.../zsl/ZeroShutterLagRingBuffer.kt                      │
│  Pulls most recent RAW_SENSOR frame; trigger additional EV-offset captures │
└──────────────────────────────────┬─────────────────────────────────────────┘
                                   │  N RAW frames + noise metadata
                                   ▼
┌────────────────────────────────────────────────────────────────────────────┐
│  Stage 1:  Frame alignment (Multi-scale translational SAD, Gaussian pyramid)│
│  File: imaging-pipeline/.../pipeline/ImagingPipeline.kt  FrameAlignmentEngine │
│  [D2 replaces this with DeformableFeatureAligner — pyramidal Lucas-Kanade]   │
└──────────────────────────────────┬─────────────────────────────────────────┘
                                   │  aligned frames + per-frame transforms
                                   ▼
┌────────────────────────────────────────────────────────────────────────────┐
│  Stage 2:  Ghost-free HDR merge                                            │
│  File: imaging-pipeline/.../pipeline/ProXdrHdrEngine.kt                    │
│    - same-exposure burst → Wiener merge (inverse-variance)                 │
│    - EV-bracketed       → Debevec trapezoidal-weighted radiance            │
│    - all-clipped         → Mertens exposure fusion (Laplacian pyramid)     │
│    + HighlightReconstructionEngine  (cross-channel ratio)                  │
│    + ShadowDetailRestorer            (bilateral-feathered blend)           │
└──────────────────────────────────┬─────────────────────────────────────────┘
                                   │  merged 16-bit linear RGB + ghost mask
                                   ▼
┌────────────────────────────────────────────────────────────────────────────┐
│  Stage 3:  Shadow denoise (BEFORE any tone lift — LUMO law 4)              │
│  File: imaging-pipeline/.../pipeline/ImagingPipeline.kt  ShadowDenoiseEngine │
│  Edge-preserving bilateral; radius 2; gated by luma < 0.18                 │
└──────────────────────────────────┬─────────────────────────────────────────┘
                                   │
                                   ▼
┌────────────────────────────────────────────────────────────────────────────┐
│  Stage 3.5: Semantic segmentation (AI — DeepLabv3)                         │
│  File: ai-engine/.../impl/pipeline/AiEngineOrchestrator.kt                 │
│  Builds SemanticMask (FACE/PERSON/SUBJECT/SKY/MIDGROUND/BACKGROUND)        │
└──────────────────────────────────┬─────────────────────────────────────────┘
                                   │  SemanticMask
                                   ▼
┌────────────────────────────────────────────────────────────────────────────┐
│  Stage 4:  Colour science + WB                                             │
│  :color-science  ColorScienceEngines  + Robertson CCT                      │
│  :hypertone-wb   HyperToneWhiteBalanceEngine (skin anchor ± 300 K)         │
│  [D1 adds AwbModelRunner — blend neural prior with skin anchor]             │
└──────────────────────────────────┬─────────────────────────────────────────┘
                                   │
                                   ▼
┌────────────────────────────────────────────────────────────────────────────┐
│  Stage 5:  Durand bilateral local tone mapping + Cinematic S-curve         │
│  File: imaging-pipeline/.../pipeline/ImagingPipeline.kt                    │
│    DurandBilateralToneMappingEngine → log₂ base/detail → compress → recombine│
│    CinematicSCurveEngine → shadow toe + midtone linear + tanh shoulder     │
│    Face override (faceMask) lifts shadowFloor and pulls shoulder earlier   │
└──────────────────────────────────┬─────────────────────────────────────────┘
                                   │
                                   ▼
┌────────────────────────────────────────────────────────────────────────────┐
│  Stage 6:  Luminosity-only sharpening (Lab L channel)                      │
│  File: imaging-pipeline/.../pipeline/ImagingPipeline.kt  LuminositySharpener │
│  USM on luminance only — no colour fringing                                │
└──────────────────────────────────┬─────────────────────────────────────────┘
                                   │
                                   ▼
┌────────────────────────────────────────────────────────────────────────────┐
│  Stage 7:  Output encode + metadata                                        │
│  File: imaging-pipeline/.../pipeline/OutputMetadataPipeline.kt             │
│    DNG composition with required opcodes                                   │
│    HEIC profile selection (Display P3 + HDR10)                             │
│    XMP `pc:` namespace extended metadata                                   │
│    Privacy-first policy (location opt-in, bounded audit log)               │
└────────────────────────────────────────────────────────────────────────────┘
```

Video flow diverges at Stage 2 into `VideoPipeline.kt`. Bokeh is a post-Stage-6 compositing pass triggered by mode selection in `ComputationalModes.kt`.

---

## 6. DI / "File Networking" — How Classes Talk to Each Other

LeicaCam is 100% **Hilt-based** with `@InstallIn(SingletonComponent::class)` modules per engine. The current state has **15 duplicated `DependencyModule.kt`** files (one per engine + module) — this is technical debt addressed in Plan.md Dimension 3.

### Active DI modules (current layout)

| Module | Hilt file(s) | Provides |
|---|---|---|
| `:ai-engine:impl` | `impl/AiEngineModule.kt` **(dup — delete)**, `impl/di/AiEngineModule.kt` | `AiEngineOrchestrator`, `ModelRegistry`, runners (post-D1) |
| `:imaging-pipeline:impl` | `impl/DependencyModule.kt`, `impl/di/ImagingPipelineModule.kt` | `ImagingPipeline`, `ImagingPipelineOrchestrator`, HDR engines |
| `:camera-core:impl` | `impl/DependencyModule.kt` | `Camera2SessionConfigurator`, `IspOptimizer` |
| `:color-science:impl` | `impl/DependencyModule.kt` | Colour engines |
| `:hypertone-wb:impl` | `impl/DependencyModule.kt` | `HyperToneWhiteBalanceEngine` |
| `:bokeh-engine:impl` | `impl/DependencyModule.kt` | `BokehEngineOrchestrator` |
| `:motion-engine:impl` | `impl/DependencyModule.kt` | `RawFrameAligner` |
| `:neural-isp:impl` | `impl/DependencyModule.kt` | Neural ISP stages |
| `:lens-model` | `lens_model/DependencyModule.kt` | `LensCorrectionSuite` |
| `:gpu-compute` | `gpu_compute/DependencyModule.kt` | `GpuBackend`, `VulkanBackend`, `VulkanComputePipeline` |
| `:sensor-hal` | `sensor_hal/DependencyModule.kt`, `sensor_hal/di/SensorHalModule.kt` | `CameraSessionManager`, `SensorProfileRegistry`, `HybridAutoFocusEngine`, ZSL |
| `:ui-components` | `ui_components/DependencyModule.kt` | Compose theme, `Phase9UiStateCalculator` |
| `:common` | `common/DependencyModule.kt` | `LeicaLogger` |
| `:feature:camera` | `feature/camera/DependencyModule.kt` | `CameraUiOrchestrator`, `CameraModeSwitcher`, `NativeImagingRuntimeFacade` |
| `:feature:gallery` | `feature/gallery/DependencyModule.kt` | `GalleryMetadataEngine` |
| `:feature:settings` | `feature/settings/DependencyModule.kt` | Settings providers |

### Cross-module wiring (simplified)

```
MainActivity (@AndroidEntryPoint)
  ├── inject CameraUiOrchestrator          ← feature/camera/DependencyModule
  │     ├── inject NativeImagingRuntimeFacade
  │     │     ├── inject ImagingRuntimeOrchestrator  ← native-imaging-core/impl
  │     │     │     └── JNI → native_imaging_core.cpp
  │     │     └── inject ImagingPipelineOrchestrator ← imaging-pipeline/impl/di
  │     │           ├── ImagingPipeline
  │     │           │     ├── FrameAlignmentEngine
  │     │           │     ├── HdrMergeEngine            ← ProXdrHdrEngine.kt
  │     │           │     ├── DurandBilateralToneMappingEngine
  │     │           │     ├── CinematicSCurveEngine
  │     │           │     ├── ShadowDenoiseEngine
  │     │           │     └── LuminositySharpener
  │     │           └── ToneLM2Engine / FusionLM2Engine / AcesToneMapper
  │     ├── inject AiEngineOrchestrator      ← ai-engine/impl/di
  │     │     ├── SceneClassifier
  │     │     ├── ShotQualityEngine
  │     │     ├── ObjectTrackingEngine
  │     │     ├── AiModelManager
  │     │     └── ModelRegistry
  │     ├── inject HyperToneWhiteBalanceEngine ← hypertone-wb/impl
  │     ├── inject CameraSessionManager       ← sensor-hal/di/SensorHalModule
  │     └── inject SensorProfileRegistry      ← sensor-hal/sensor/profiles
  ├── inject CameraModeSwitcher              ← feature/camera
  ├── inject Phase9UiStateCalculator         ← ui-components
  └── inject GalleryMetadataEngine           ← feature/gallery
```

### Thread & scope model

- UI + Compose: Main dispatcher (`Dispatchers.Main`).
- Capture orchestration (`CameraUiOrchestrator`, `NativeImagingRuntimeFacade`): `Dispatchers.Default` with a supervisor job scoped to the session.
- Heavy imaging stages: dedicated `CameraSessionScope` (see `common/CameraSessionScope.kt`) — a `CoroutineScope` tied to the camera open/close lifecycle.
- AI inference: `Dispatchers.Default`, one `LiteRtSession` per runner (post-D1).
- GPU compute: Vulkan queue submission on a dedicated thread owned by `:gpu-compute`.

**LUMO rules enforced at scope boundaries:**
- `CancellationException` is always re-thrown (no `catch (e: Exception)` swallowing).
- All fallible functions return `LeicaResult<T>` — no unchecked exceptions cross module boundaries.
- No `GlobalScope` anywhere.

---

## 7. Key Files by Subsystem (what each does)

### 7.1 App entry (4 files)
- `app/src/main/java/com/leica/cam/LeicaCamApp.kt` — `@HiltAndroidApp` Application.
- `app/src/main/java/com/leica/cam/MainActivity.kt` — `@AndroidEntryPoint`, NavHost, bottom-nav between `camera / gallery / settings`, Compose theme.
- `app/build.gradle.kts` — APK config, `copyOnDeviceModels` task (post-D1).
- `app/src/main/assets/models/` — (post-D1) copied from `/Model/` at build.

### 7.2 Feature layer
- `feature/camera/src/main/java/com/leica/cam/feature/camera/ui/CameraScreen.kt` — viewfinder Compose UI.
- `feature/camera/.../ui/CameraUiOrchestrator.kt` — collects UI state from all engines and broadcasts capture intents.
- `feature/camera/.../NativeImagingRuntimeFacade.kt` — presentation-layer-facing facade over `ImagingRuntimeOrchestrator`.
- `feature/gallery/.../ui/GalleryScreen.kt` — gallery grid + metadata surface.
- `feature/gallery/.../ui/GalleryMetadataEngine.kt` — loads XMP/DNG metadata and projects it to UI DTOs.
- `feature/settings/.../ui/SettingsScreen.kt` — settings UX.

### 7.3 Camera core + sensor HAL
- `camera-core/api/src/main/kotlin/com/leica/cam/camera_core/api/` — contracts.
- `camera-core/impl/.../isp/Camera2SessionConfigurator.kt` — Camera2 surface graph builder (preview + RAW + HDR streams).
- `camera-core/impl/.../isp/IspOptimizer.kt` — vendor ISP capability detection & routing.
- `sensor-hal/.../session/CameraSessionManager.kt` — owns `CameraDevice` + `CameraCaptureSession` lifecycle.
- `sensor-hal/.../session/CameraSessionStateMachine.kt` — sealed-class FSM: `Closed` → `Opening` → `Configured` → `Streaming` → `Capturing`.
- `sensor-hal/.../autofocus/HybridAutoFocusEngine.kt` — PDAF + contrast AF fusion.
- `sensor-hal/.../metering/AdvancedMeteringEngine.kt` — spot/matrix/centre-weighted metering.
- `sensor-hal/.../capability/CameraCapabilityProfile.kt` — static capability extraction from `CameraCharacteristics`.
- `sensor-hal/.../capability/Camera2MetadataSource.kt` — `SENSOR_NOISE_PROFILE`, `SENSOR_INFO_COLOR_FILTER_ARRANGEMENT`, `LENS_DISTORTION` extraction.
- `sensor-hal/.../sensor/profiles/SensorProfile.kt` + `SensorProfileRegistry.kt` — per-sensor tuning (S5KHM6, OV64B40, …, SC202PCS) per `.agents/skills/Leica Cam Upgrade skill/references/sensor-profiles.md`.
- `sensor-hal/.../sensor/SmartIsoProDetector.kt` — detects Samsung Smart-ISO Pro; disables redundant noise modelling.
- `sensor-hal/.../soc/SoCProfile.kt` — MediaTek vs Snapdragon vs Exynos capability detection.
- `sensor-hal/.../isp/IspIntegrationOrchestrator.kt` — vendor ISP capability routing.
- `sensor-hal/.../zsl/ZeroShutterLagRingBuffer.kt` — the ZSL ring buffer powering Lightning Snap.

### 7.4 Imaging pipeline (the hot path)
- `imaging-pipeline/impl/.../pipeline/ImagingPipeline.kt` (**1344 lines** — the big one; Plan.md D2 splits it)
  - `PipelineFrame` DTO (linear-light RGB + ISO + exposureNs + evOffset).
  - `AlignmentTransform`, `AlignmentResult`.
  - `NoiseModel` (Poisson-Gaussian σ² = A·x + B from SENSOR_NOISE_PROFILE).
  - `HdrMergeResult`, `HdrMergeMode`, `SemanticZone`, `SemanticMask`.
  - `FrameAlignmentEngine`  — multi-scale SAD, Gaussian pyramid (D2 target: replaced).
  - `HdrMergeEngine` — Wiener burst + Debevec linear + MTB ghost.
  - `DurandBilateralToneMappingEngine` — base/detail log-luminance decomposition.
  - `CinematicSCurveEngine` — shadow toe + linear mid + tanh shoulder + face override.
  - `ShadowDenoiseEngine` — pre-tone-lift bilateral.
  - `LuminositySharpener` — Lab L-only USM.
  - `ImagingPipeline` and `ImagingPipelineOrchestrator`.
- `imaging-pipeline/impl/.../pipeline/ProXdrHdrEngine.kt` (**546 lines**) — HDR orchestrator + bracket selector + highlight reconstructor + shadow restorer + Mertens fallback. (Plan.md D2 splits into 8 files under `.../hdr/`.)
- `imaging-pipeline/impl/.../pipeline/FusionLM2Engine.kt` (**424 lines**) — FusionLM 2.0 Wiener-weighted tile merge.
- `imaging-pipeline/impl/.../pipeline/MultiScaleFrameAligner.kt` (**425 lines**) — pyramidal alignment helper.
- `imaging-pipeline/impl/.../pipeline/ToneLM2Engine.kt` (**309 lines**) — orchestrates shadow→local-EV→bilateral→S-curve→face→sharpen.
- `imaging-pipeline/impl/.../pipeline/AcesToneMapper.kt` (**125 lines**) — ACES filmic tonemap with fixed coefficients (A=2.51, B=0.03, C=2.43, D=0.59, E=0.14).
- `imaging-pipeline/impl/.../pipeline/ComputationalModes.kt` (**407 lines**) — scene-mode routing (portrait, night, landscape, pano, ...).
- `imaging-pipeline/impl/.../pipeline/VideoPipeline.kt` (**630 lines**) — video encode path with temporal denoise and EIS.
- `imaging-pipeline/impl/.../pipeline/OutputMetadataPipeline.kt` (**381 lines**) — DNG opcode builder + HEIC profile + XMP `pc:` namespace + privacy policy.
- `imaging-pipeline/impl/.../di/ImagingPipelineModule.kt` — canonical DI entry.

### 7.5 Colour, WB, AI, depth, face
- `color-science/impl/.../pipeline/ColorScienceEngines.kt` — CCM interpolation (two-illuminant), gamut mapping (CUSP), Robertson CCT.
- `hypertone-wb/impl/.../pipeline/HyperToneWhiteBalanceEngine.kt` — skin-anchored per-zone WB, Robertson CCT, D_uv tint, bilateral gain-field synthesis.
- `hypertone-wb/impl/.../SkinZoneWbGuard.kt` — clamps all zone CCTs to skin ± 300 K.
- `hypertone-wb/impl/.../WbTemporalMemory.kt` — `α = 0.15` EMA smoothing for flicker-free preview.
- `ai-engine/api/.../AiContracts.kt`, `AiInterfaces.kt`, `AiModels.kt` — DTOs and interfaces.
- `ai-engine/impl/.../pipeline/AiEngineOrchestrator.kt` — scene + quality + tracking orchestrator.
- `ai-engine/impl/.../pipeline/EnginesImpl.kt` — concrete engine implementations.
- `ai-engine/impl/.../registry/ModelRegistry.kt` — magic-byte format detection + keyword role assignment for `/Model/`.
- `depth-engine/api/`, `depth-engine/impl/` — depth contracts + implementation.
- `face-engine/api/`, `face-engine/impl/` — face analysis (bounding box + landmarks + skin mask).

### 7.6 Photon matrix, bokeh, motion, neural ISP, smart imaging
- `photon-matrix/impl/.../PhotonMatrixFusionEngine.kt` — spectral reconstruction (partitioned CT sensing per ADR-005).
- `photon-matrix/impl/.../correction/` — per-sensor photon-domain corrections.
- `bokeh-engine/impl/.../BokehEngineOrchestrator.kt` — CoC-based synthetic aperture; depth-aware kernel.
- `motion-engine/impl/.../RawFrameAligner.kt` — additional RAW-domain aligner used outside the HDR path (e.g., for super-res).
- `neural-isp/impl/.../pipeline/` — learned ISP stage routing.
- `smart-imaging/impl/.../SmartImagingOrchestrator.kt` — scene-adaptive routing between modes.

### 7.7 GPU, lens, native, UI, shared
- `gpu-compute/src/main/java/com/leica/cam/gpu_compute/GpuBackend.kt` — abstraction interface.
- `gpu-compute/.../VulkanBackend.kt`, `vulkan/VulkanComputePipeline.kt` — Vulkan Compute implementation.
- `lens-model/src/main/java/com/leica/cam/lens_model/calibration/` — per-lens-variant (AAC, Sunny, OFILM, SEMCO, `_cn`) calibration data.
- `lens-model/.../correction/LensCorrectionSuite.kt` — Brown-Conrady distortion + vignette correction.
- `native-imaging-core/impl/src/main/cpp/native_imaging_core.cpp` — JNI native runtime core (zero-copy HardwareBuffer handoff).
- `native-imaging-core/impl/.../nativeimagingcore/ImagingRuntimeOrchestrator.kt` — Kotlin side of the JNI bridge.
- `native-imaging-core/impl/.../NativeImagingBridge.kt`, `NativeImagingContracts.kt` — bridge types.
- `ui-components/src/main/java/com/leica/cam/ui_components/theme/LeicaTheme.kt` — `LeicaBlack`, `LeicaRed`, typography.
- `ui-components/.../camera/Phase9UiStateCalculator.kt` — UI-state derivation for camera screen.
- `common/src/main/java/com/leica/cam/common/result/LeicaResult.kt` — sealed Success/Failure (Pipeline, Hardware, Recoverable).
- `common/.../result/DomainError.kt` — typed domain errors.
- `common/.../result/PipelineStage.kt` — stage identifiers.
- `common/.../logging/LeicaLogger.kt` — structured log facade.
- `common/.../types/NonEmptyList.kt` — `NonEmptyList<T>` utility.
- `hardware-contracts/src/main/kotlin/com/leica/cam/hardware/contracts/photon/PhotonBuffer.kt` — authoritative `PhotonBuffer` contract (dup in `:photon-matrix:api` slated for deletion in D3).

### 7.8 On-device AI models (`/Model/`)
- `/Model/AWB/awb_final.onnx` — source; **not loaded on device** (ONNX kept for development/QA only).
- `/Model/AWB/awb_final_full_integer_quant.tflite` — INT8-quantised on-device runtime format.
- `/Model/Face Landmarker/face_landmarker.task` — MediaPipe Tasks bundle.
- `/Model/Image Classifier/1.tflite` — ImageNet-style classifier; mapped to `SceneLabel` via lookup.
- `/Model/MicroISP/MicroISP_V4_fp16.tflite` — learned Bayer-domain ISP refinement.
- `/Model/Scene Understanding/deeplabv3.tflite` — DeepLabv3 semantic segmenter (Coco 21-class).

---

## 8. Build & CI Anatomy

- **Root Gradle**: `build.gradle.kts` — applies `detekt`, `ktlint`, `kotlinx-binary-compatibility-validator`. Currently excludes 15 module paths from ktlint (technical debt; see `docs/known-issues/STRUCTURE_ISSUES.md`).
- **Version catalog**: `gradle/libs.versions.toml` (verify — may be missing; D3.1 ensures it).
- **Detekt config**: `config/detekt/detekt.yml`.
- **Per-module**: every module has its own `build.gradle.kts`; duplication addressed by D3.2 convention plugins.
- **ProGuard/R8**: `app/proguard-rules.pro` (verify); rules for Hilt, LiteRT, MediaPipe.
- **Wrapper**: `gradlew` / `gradlew.bat` / `gradle/wrapper/gradle-wrapper.properties`.
- **CI**: `.github/` contains workflow(s); run `./gradlew assemble test ktlintCheck detekt` on PR.

## 9. Documentation & Knowledge

- `README.md` — top-level entry.
- `project-structure.md` — THIS FILE.
- `Plan.md` — active four-dimension upgrade plan.
- `Implementation.md` — historical phase-by-phase notes (phases 0–10).
- `changelog.md` — release notes.
- `docs/adr/ADR-001…006` — Architecture Decision Records.
- `docs/known-issues/` — (post-D4) the technical-debt registry: `KNOWN_ISSUES.md` index + `HDR_ISSUES.md`, `AI_ISSUES.md`, `STRUCTURE_ISSUES.md`, `PERF_ISSUES.md`.
- `Knowledge/Advance HDR algorithm research.md` — research foundation for ProXDR.
- `Knowledge/advance imaging system research.md` — research foundation for the full LUMO platform.
- `Knowledge/2504.05623v2.pdf` — a key reference paper (vendored).
- `.agents/skills/Leica Cam Upgrade skill/references/sensor-profiles.md` — per-sensor tuning bible.
- `.agents/skills/Lumo Imaging Engineer/references/hdr-engine-deep.md` — ProXDR deep reference.

---

## 10. How to Keep This File Current

When modules are added, removed, renamed, or directory-relocated:

1. Update `settings.gradle.kts` FIRST.
2. Re-run `./gradlew :allprojects` and confirm the build still works before touching this file.
3. Mirror the change in Section 3 (Gradle module graph) and Section 4 (dependency diagram).
4. Update Section 6 (DI wiring) if a new `@Module` was added or deleted.
5. Update Section 7 (key files) if a major file was added, split, or removed.
6. Update Section 9 (docs) if new ADRs, known-issues sub-registries, or research notes were added.
7. Bump the "_Last updated_" date at the top of this file.

**When refactoring per Plan.md D3**, this file gets a substantial rewrite of Section 2 (directory map) and Section 3 (Gradle module graph) to reflect the `core/ engines/ platform/ platform-android/ features/` regrouping. Keep the module IDs stable; only `projectDir` overrides change.
