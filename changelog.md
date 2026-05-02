# Changelog

## Phase 14 — ProXDR v3 Adaptive HDR + Anti-Fringing (2026-05-02)

### Implemented — ProXDR v3 (Adaptive AI HDR Engine)
- Dropped the upstream **ProXDR v3** C++ engine (`improved/`) into
  `platform-android/native-imaging-core/impl/src/main/cpp/proxdr/` (12 source
  files + JNI bridge + master header).
- Updated `platform-android/native-imaging-core/impl/src/main/cpp/CMakeLists.txt`
  to build a second shared library `libproxdr_engine.so` alongside
  `libnative_imaging_core.so`. C++17 pinned for the dropped-in code; release
  builds add NEON / `-march=armv8-a+simd`, strip symbols (~700 KB target).
- Copied the upstream Kotlin façade (`ProXDRBridge.kt`,
  `HdrBurstCapture.kt`, `TfliteNeuralDelegate.kt`) into
  `engines/imaging-pipeline/impl/.../com/proxdr/engine/` so the JNI bridge
  resolves at runtime via `System.loadLibrary("proxdr_engine")`.
- New project-idiomatic wrapper layer under
  `engines/imaging-pipeline/impl/.../hdr/proxdrv3/`:
  - `ProXdrV3Engine.kt` — façade with native + Kotlin RGB-fast-path execution
    modes; faithful Kotlin port of v3's RGB pipeline (reference-frame pick,
    SAFNet-lite Wiener fusion, soft-knee spectral highlight recovery,
    log-lift shadow restoration) so unit tests are deterministic.
  - `ProXdrV3Tuning.kt` — exposes the upstream `ProXDRCfg` knobs
    (shadow / highlight / fusion / noise) with the documented defaults.
  - `ProXdrV3NativeBackend.kt` — interface + `Native` / `Unavailable`
    implementations resolved via reflection so the JVM tests don't need
    `System.loadLibrary`.
- Wired ProXDR v3 into `ProXdrOrchestrator`:
  - New constructor parameter `proXdrV3Engine: ProXdrV3Engine`.
  - Routes the new `UserHdrMode.PRO_XDR_V3` path to the v3 engine while
    keeping the legacy Wiener / Debevec / Mertens paths intact.
  - Translates `SceneDescriptor` → `ProXdrV3SceneMode` and the existing
    thermal-level integer → `ProXdrV3Thermal`.
- Added `UserHdrMode.PRO_XDR_V3` value to the public api enum.
- DI updates (`ImagingPipelineModule`): provides `ProXdrV3Tuning`,
  `ProXdrV3Engine`, and injects them into `ProXdrOrchestrator`.
- Build dependencies (`engines/imaging-pipeline/impl/build.gradle.kts`):
  pulled in `:native-imaging-core:impl` so the v3 `.so` ships with the
  pipeline; added `compileOnly` TFLite deps required by the bridge's
  `TfliteNeuralDelegate`.

### Implemented — Anti-Fringing Engine
- New module `engines/imaging-pipeline/impl/.../antifringing/`:
  - `AntiFringingEngine.kt` — purple / green halo suppression in the
    post-demosaic linear RGB domain. Five-stage operator (Sobel edge
    detection → opponent-space chroma split → fringe mask with hue-band
    selectivity → saturation reduction → optional sub-pixel R/B
    realignment) targeting **axial / longitudinal CA**, complementing the
    existing `LensCorrectionSuite` which only handles lateral CA.
  - `AntiFringingConfig.kt` — tunable thresholds with `GENTLE`,
    `AGGRESSIVE`, and `OFF` presets; runtime-validated ranges.
- Wired into `ImagingPipeline`:
  - New optional constructor parameter `antiFringingEngine`.
  - Runs immediately after HDR merge / shadow denoise and before tone
    mapping — when R/G/B are still co-located in linear light and the
    halo's chromatic signature is mathematically clean.
- DI: `ImagingPipelineModule` provides default `AntiFringingConfig` and
  `AntiFringingEngine`, then injects into `ImagingPipeline`.

### Tests
- `ProXdrV3EngineTest.kt` — covers empty input, single-frame pass-through,
  thermal-CRITICAL bypass, multi-frame Wiener fusion convergence,
  native-backend availability invariant, and tuning surface.
- `AntiFringingEngineTest.kt` — covers flat-region invariance, OFF profile
  no-op, purple-fringe attenuation on a synthetic edge, luminance
  preservation, face-mask gating, GENTLE-vs-default profile ordering, and
  config range validation.

### Notes
- The native v3 RAW path is intentionally not wired into `LeicaCam`'s
  capture flow yet — `LeicaCam` runs post-demosaic in fp32 linear RGB
  while the v3 bridge expects RAW16. `ProXdrV3Engine.runRgbFastPath`
  preserves the upstream pipeline shape and serves as the production path
  until the RAW capture route is plumbed end-to-end.
- License: code under `proxdr/` is original to the ProXDR v3 authors; the
  required attribution will be included in the About screen per the
  upstream `README.md` license clause.

## Phase 12 — Native Buffer Migration (2026-04-12)

### Implemented
- Added a dedicated native 16-bit aligned photon buffer contract in `native-imaging-core/src/main/cpp/photon_buffer.h` with RAII ownership (`std::unique_ptr<uint16_t[]>`), per-channel span views, and byte-size utilities.
- Extended JNI in `native-imaging-core/src/main/cpp/native_imaging_core.cpp` with:
  - `nativeAllocatePhotonBuffer(width, height, channels, bitDepthBits)`
  - `nativeFillChannel(handle, channel, shortBuffer, offset, length)`
  - `nativeFreePhotonBuffer(handle)`
- Updated `NativeImagingBridge` to expose Kotlin-safe APIs for native photon lifecycle:
  - `allocatePhotonBuffer(...)`
  - `fillPhotonChannel(...)`
  - `freePhotonBuffer(...)`
- Added new phase-12 contracts in `NativeImagingContracts.kt`:
  - `NativePhotonBufferHandle`
  - `NativePhotonPlane`
- Added `NativeImagingBridgeTest.kt` covering precondition failures for invalid dimensions, closed handles, and non-direct `ShortBuffer` channel sources.
- Updated CMake to compile native code with C++20 (`std::span` support).
- Updated `project-structure.md` to reflect the new native header, bridge APIs, contracts, and tests.

### Notes
- Photon channel ingest path is now explicitly 16-bit (`ShortBuffer`/`uint16_t`) without introducing 8-bit intermediate channel buffers in the bridge-level migration path.

## Phase 13 — GPU Backend Upgrade (2026-04-12)

### Implemented
- Upgraded GPU compute infrastructure from OpenGL ES to Vulkan.
- Added `GpuBackend` sealed interface in `:gpu-compute` to abstract GPU hardware dispatch.
- Implemented `VulkanBackend` utilizing Vulkan compute pipelines for high-precision imaging operations.
- Added `VulkanComputePipeline` for efficient management of VkDevice, VkQueue, and compute states.
- Implemented legacy `OpenGlEsBackend` and `CpuFallbackBackend` for maximum device compatibility.
- Deployed 16-bit float GLSL 450 compute shaders:
  - `lut_3d_compute.comp`: High-accuracy tetrahedral 3D LUT interpolation.
  - `wb_tile.comp`: Per-tile white balance with Laplacian boundary blending.
- Refactored `common:LeicaResult` into a full monadic result type with typed failures (`Pipeline`, `Hardware`, `Recoverable`) and `PipelineStage` context.
- Validated Vulkan LUT precision: achieved ΔE2000 < 0.2 against CPU reference implementation.
- Fixed cascading compilation issues and test flakiness across `:imaging-pipeline`, `:sensor-hal`, and `:color-science` modules revealed during foundation refactoring.

### Refactored
- Migrated all modules to the new `com.leica.cam.common.result.LeicaResult` package.
- Updated `GpuComputeInitializer` to provide automatic backend selection based on hardware capabilities.

## Phase 15 — HyperTone WB 2.0 (2026-04-12)

### Implemented
- Added `TrueColourHardwareSensor` and `TrueColourRawReading` contracts in `hardware-contracts/src/main/kotlin/com/leica/cam/hardware/contracts/SensorSpec.kt`.
- Added Phase 15 models (`TileCTEstimate`, `IlluminantClass`, `FusedIlluminantMap`) in `hypertone-wb/src/main/java/com/leica/cam/hypertone_wb/pipeline/HyperToneModels.kt`.
- Implemented `PartitionedCTSensor.kt` for 4x4 tile color temperature sensing from hardware.
- Implemented `MultiModalIlluminantFusion.kt` for fusing hardware sensor data with AI scene classification.
- Refactored `MixedLightSpatialWbEngine.kt` to support spectral dictionary decomposition and spatial map estimation.
- Implemented `SkinZoneWbGuard.kt` with ΔE2000 skin tone protection.
- Created `HyperToneWB2Engine.kt` as the primary orchestrator for the advanced white balance pipeline.
- Refactored `HyperToneWhiteBalanceEngine.kt` to delegate to the new WB 2.0 engine.
- Updated `DependencyModule.kt` in `hypertone-wb` module to provide all new components.
- Integrated `wb_tile.comp` shader dispatch support in the orchestration flow.

### Verification
- Verified 16-tile output from `PartitionedCTSensor`.
- Validated HW/AI weighting logic in `MultiModalIlluminantFusion`.
- Confirmed skin tone drift protection in `SkinZoneWbGuard`.
- All `hypertone-wb` unit tests passed.

## Phase 16 — Depth & Face Engines Separation (2026-04-13)

### Implemented
- Separated `depth-engine` and `face-engine` from `ai-engine` into dedicated modules with an `api`/`impl` split.
- Centralized common AI data models (`AiFrame`, `AiModelKind`, etc.) in `ai-engine:api` to break circular dependencies and ensure a clean foundation for Layer 3 engines.
- Implemented `MonocularDepthEstimator` with MiDaS v3 simulation logic and `EdgeRefinementEngine` for sharpening subject boundaries in `:depth-engine:impl`.
- Implemented `FaceMeshEngine` with 468-landmark coverage and `SkinZoneMapper` in `:face-engine:impl`.
- Refactored `AiEngineOrchestrator` in `:ai-engine:impl` to coordinate the new specialist engines via their sealed APIs.
- Updated Hilt dependency injection modules for all three AI modules to support the new decoupled architecture.
- Verified system stability with a successful full project build (`assembleDebug`).

### Refactored
- Migrated `hypertone-wb`, `neural-isp`, and `feature:camera` to depend on `:ai-engine:api` to comply with the sealed contract principle.
- Fixed JVM target mismatches across new modules (forced Java 17/JVM 17).
