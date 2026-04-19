# Project Structure — LeicaCam (Updated)

_Last updated: 2026-04-18 (UTC)_

This document reflects the **current Gradle module graph** defined in `settings.gradle.kts`.

## 1) Top-level architecture

LeicaCam is a multi-module Android/Kotlin project organized around:

1. **App + Feature modules** (UI and user workflows)
2. **Core camera/imaging orchestration modules**
3. **Specialized imaging engines** (AI, depth, bokeh, WB, motion, etc.)
4. **Shared infrastructure modules** (contracts, GPU, common utilities, test utilities)

The repository uses a consistent **API/Impl split** for many subsystems:

- `:module:api` → public contracts/interfaces/types
- `:module:impl` → concrete implementation

## 2) Gradle module map (source of truth)

From `settings.gradle.kts`, the included modules are:

### App entrypoint
- `:app`

### Feature modules
- `:feature:camera`
- `:feature:gallery`
- `:feature:settings`

### Camera core and runtime
- `:camera-core:api`
- `:camera-core:impl`
- `:native-imaging-core:api`
- `:native-imaging-core:impl`
- `:imaging-pipeline:api`
- `:imaging-pipeline:impl`

### Imaging science & engines
- `:color-science:api`
- `:color-science:impl`
- `:hypertone-wb:api`
- `:hypertone-wb:impl`
- `:ai-engine:api`
- `:ai-engine:impl`
- `:depth-engine:api`
- `:depth-engine:impl`
- `:face-engine:api`
- `:face-engine:impl`
- `:neural-isp:api`
- `:neural-isp:impl`
- `:photon-matrix:api`
- `:photon-matrix:impl`
- `:smart-imaging:api`
- `:smart-imaging:impl`
- `:bokeh-engine:api`
- `:bokeh-engine:impl`
- `:motion-engine:api`
- `:motion-engine:impl`

### Hardware / platform / shared modules
- `:sensor-hal`
- `:lens-model`
- `:gpu-compute`
- `:ui-components`
- `:common`
- `:common-test`
- `:hardware-contracts`

## 3) Repository layout (high-level)

```text
Pro-Cam/
├── app/                       # Android app shell (Application, MainActivity)
├── feature/
│   ├── camera/                # Camera UX/orchestration
│   ├── gallery/               # Gallery UX + metadata presentation
│   └── settings/              # Settings UX
├── camera-core/
│   ├── api/                   # Camera core contracts
│   └── impl/                  # Camera core implementation + DI wiring
├── native-imaging-core/
│   ├── api/                   # Runtime/core contracts
│   └── impl/                  # Kotlin + C++ bridge/runtime orchestration
├── imaging-pipeline/
│   ├── api/
│   └── impl/                  # Still/video pipeline orchestration
├── color-science/             # Color processing (api + impl)
├── hypertone-wb/              # White-balance pipeline (api + impl)
├── ai-engine/                 # AI contracts + implementations
├── depth-engine/              # Depth contracts + implementations
├── face-engine/               # Face analysis contracts + implementations
├── neural-isp/                # Neural ISP contracts + implementations
├── photon-matrix/             # Multi-frame fusion contracts + implementations
├── smart-imaging/             # Smart orchestration contracts + implementations
├── bokeh-engine/              # Bokeh contracts + implementations
├── motion-engine/             # Motion alignment contracts + implementations
├── sensor-hal/                # Camera2/session/autofocus/metering/ZSL layer
├── lens-model/                # Lens correction/calibration layer
├── gpu-compute/               # Vulkan/GLES/CPU compute backends + shaders
├── hardware-contracts/        # Sensor/GPU/NPU/photon data contracts
├── common/                    # Shared utilities/result types/logging
├── common-test/               # Shared test fixtures/builders
├── ui-components/             # Reusable Compose UI components
├── docs/                      # ADRs + architecture docs
├── Knowledge/                 # Knowledge/reference docs
├── Reference/                 # Legacy/reference assets and resources
├── config/                    # Static analysis config (e.g., detekt)
├── build.gradle.kts           # Root build config
├── settings.gradle.kts        # Module inclusion source of truth
└── project-structure.md       # This file
```

## 4) Representative key files by subsystem

### App & features
- `app/src/main/java/com/leica/cam/LeicaCamApp.kt`
- `app/src/main/java/com/leica/cam/MainActivity.kt`
- `feature/camera/src/main/java/com/leica/cam/feature/camera/ui/CameraScreen.kt`
- `feature/gallery/src/main/java/com/leica/cam/feature/gallery/ui/GalleryScreen.kt`
- `feature/settings/src/main/java/com/leica/cam/feature/settings/ui/SettingsScreen.kt`

### Pipeline + runtime
- `imaging-pipeline/impl/src/main/java/com/leica/cam/imaging_pipeline/pipeline/ImagingPipeline.kt`
- `imaging-pipeline/impl/src/main/java/com/leica/cam/imaging_pipeline/pipeline/VideoPipeline.kt`
- `native-imaging-core/impl/src/main/java/com/leica/cam/native_imaging_core/impl/nativeimagingcore/ImagingRuntimeOrchestrator.kt`
- `native-imaging-core/impl/src/main/cpp/native_imaging_core.cpp`

### Compute + hardware abstraction
- `gpu-compute/src/main/java/com/leica/cam/gpu_compute/GpuBackend.kt`
- `gpu-compute/src/main/java/com/leica/cam/gpu_compute/VulkanBackend.kt`
- `sensor-hal/src/main/java/com/leica/cam/sensor_hal/session/CameraSessionManager.kt`
- `hardware-contracts/src/main/kotlin/com/leica/cam/hardware/contracts/photon/PhotonBuffer.kt`

### Specialized imaging
- `hypertone-wb/impl/src/main/java/com/leica/cam/hypertone_wb/pipeline/HyperToneWhiteBalanceEngine.kt`
- `motion-engine/impl/src/main/java/com/leica/cam/motion_engine/impl/RawFrameAligner.kt`
- `bokeh-engine/impl/src/main/java/com/leica/cam/bokeh_engine/impl/BokehEngineOrchestrator.kt`
- `lens-model/src/main/java/com/leica/cam/lens_model/correction/LensCorrectionSuite.kt`

## 5) Notes for keeping this file current

When modules are added/removed/renamed:

1. Update `settings.gradle.kts` first.
2. Mirror the change in section **2) Gradle module map**.
3. Update section **3) Repository layout** only if folder structure changed.
4. Refresh representative files in section **4)** if package paths move.

