# LeicaCam

> A production-grade, modular Android computational photography stack written in Kotlin + C++ + Vulkan Compute. It implements the **LUMO** imaging platform: physics-first, RAW-domain, on-device end-to-end — with colour rendering inspired by Leica (authentic, cinematic micro-contrast) and Hasselblad (HNCS unified-profile skin fidelity).

<p align="center">
  <em>Status: active development · Language: Kotlin 2.0 · Platform: Android 10+ (SDK 29) · License: see repository</em>
</p>

---

## Table of contents

1. [What this is](#what-this-is)
2. [Architectural pillars](#architectural-pillars)
3. [Multi-module layout](#multi-module-layout)
4. [Capture pipeline at a glance](#capture-pipeline-at-a-glance)
5. [Target device & sensors](#target-device--sensors)
6. [On-device AI models](#on-device-ai-models)
7. [Build & run](#build--run)
8. [Test](#test)
9. [Repository map](#repository-map)
10. [Engineering principles (non-negotiable)](#engineering-principles-non-negotiable)
11. [Current roadmap](#current-roadmap)
12. [Contributing](#contributing)
13. [Further reading](#further-reading)

---

## What this is

LeicaCam is **not** a thin wrapper over Camera2. It is a full computational photography stack comprising:

- A scene-adaptive **HDR engine** (ProXDR) combining HDR+ burst merge, Debevec radiance recovery, and Mertens exposure fusion fallback — with ghost-aware MTB masking, highlight reconstruction from cross-channel ratios, and shadow restoration that runs **before** any tone lift.
- A **multi-frame RAW fusion** engine (FusionLM 2.0) with physics-grounded Wiener weights derived from `SENSOR_NOISE_PROFILE` — never hard-coded constants.
- A **perceptual tone pipeline** (ToneLM 2.0): shadow denoise → local EV modulation by semantic priority → Durand bilateral base/detail decomposition → cinematic S-curve with face override → luminosity-only sharpening.
- A **white-balance engine** (HyperTone WB) anchored on skin tone (±300 K clamp around the skin CCT), using Robertson's method in CIE 1960 (u, v) space — **not** McCamy's approximation.
- A **Bokeh Engine** that computes a physically correct circle-of-confusion (`CoC = aperture · |depth − focus_depth| / depth`) — no uniform Gaussians, no fake blur.
- An **AI engine** that orchestrates on-device TFLite / MediaPipe models for AWB, face landmarks, scene classification, semantic segmentation, and a MicroISP refiner — routed through the MediaTek APU / Qualcomm QNN / Samsung ENN vendor delegates with a GPU → XNNPACK CPU fall-through.
- A **colour-science layer** (ColorLM 2.0) with two-illuminant CCM interpolation, CUSP gamut mapping, and extended-gamut Display P3 output.
- **Zero cloud inference** — every model runs on the device, every time.

Everything above operates **on 16-bit linear RAW data** until the very last output-encode step. No 8-bit intermediates, no gamma in the middle of the pipeline.

---

## Architectural pillars

1. **RAW-domain first.** Multi-frame fusion happens on the Bayer mosaic *before* demosaicing. Always.
2. **16-bit end-to-end.** Float16 or Int16 throughout the hot path. An 8-bit intermediate is a bug, not a choice.
3. **Physics-grounded noise.** Every Wiener weight derives from `CameraCharacteristics.SENSOR_NOISE_PROFILE` — `σ²(x) = A · x + B` — per CFA channel. Magic constants are forbidden.
4. **Shadow-denoise-before-lift.** Denoising amplified by a 2-stop lift becomes noise amplified 4×. We denoise shadows *before* the tone curve touches them.
5. **Skin-anchor is sacred.** Skin tone is the pivot around which every other zone is balanced.
6. **No global white balance.** Per-zone, per-semantic-class WB. A backlit portrait at sunset is not one CCT.
7. **On-device always.** Zero cloud calls during capture or processing.

These are also the rules the code review enforces. Breaking them is grounds for a blocking review.

---

## Multi-module layout

LeicaCam is a multi-module Gradle build with **33 Gradle modules** organised around a consistent `api`/`impl` split for every engine and platform integration. See [`project-structure.md`](./project-structure.md) for the full map, dependency diagram, runtime flow, and DI wiring.

High-level groups:

| Group | Examples | Purpose |
|---|---|---|
| **App + features** | `:app`, `:feature:camera`, `:feature:gallery`, `:feature:settings` | UI + user flows |
| **Camera core + runtime** | `:camera-core:*`, `:native-imaging-core:*`, `:imaging-pipeline:*` | Capture-to-output orchestration |
| **Imaging engines** | `:ai-engine:*`, `:hypertone-wb:*`, `:photon-matrix:*`, `:bokeh-engine:*`, `:neural-isp:*`, `:motion-engine:*`, `:color-science:*`, `:smart-imaging:*`, `:depth-engine:*`, `:face-engine:*` | Specialised algorithmic layers |
| **Platform** | `:sensor-hal`, `:gpu-compute`, `:lens-model`, `:ui-components`, `:hardware-contracts` | Android / Vulkan / hardware integrations |
| **Shared** | `:common`, `:common-test` | `LeicaResult`, `DomainError`, typed errors, test fixtures |

Dependency rules (enforced by convention):

- `:common` and `:hardware-contracts` depend on nothing else in the repo.
- `:xyz:api` modules are pure-Kotlin JVM libraries, **zero** Android imports allowed.
- `:xyz:impl` modules depend only on their own `:api` and other engines' `:api` — never another engine's `:impl`.
- `:feature:*` modules communicate with engines only through `:api` via Hilt.

---

## Capture pipeline at a glance

The full flow is described in detail in [`project-structure.md`](./project-structure.md#5-runtime-capture-flow-end-to-end). In summary:

```
Shutter press
  └─► ZSL ring buffer + optional EV-bracket captures           (:sensor-hal)
       └─► Multi-scale frame alignment                          (:imaging-pipeline)
            └─► Ghost-free HDR merge                            (:imaging-pipeline, ProXDR)
                 │  · same-exposure burst → Wiener inverse-variance
                 │  · EV-bracket          → Debevec trapezoidal-weighted radiance
                 │  · all-clipped fallback → Mertens exposure fusion (Laplacian pyramid)
                 └─► Highlight reconstruction + shadow restoration
                      └─► Shadow denoise  (BEFORE any tone lift — sacred rule)
                           └─► Semantic segmentation            (:ai-engine, DeepLabv3)
                                └─► Colour science + HyperTone WB (:color-science, :hypertone-wb)
                                     └─► Durand bilateral tone + cinematic S-curve (:imaging-pipeline, ToneLM 2.0)
                                          └─► Luminosity-only sharpening
                                               └─► DNG / HEIC / XMP encode       (:imaging-pipeline)
```

Preview path bypasses the heavy stages and uses a Reinhard-extended global tone map for < 10 ms end-to-end on a mid-range Dimensity SoC.

---

## Target device & sensors

LeicaCam is tuned for a specific Android device with a MediaTek Dimensity SoC, eight distinct camera sensors, and four lens-manufacturer variants.

| Role | Sensor | Key facts |
|---|---|---|
| Main primary | Samsung **S5KHM6** (ISOCELL HM6) | 108 MP · 0.64 µm · 1/1.67" · no Gr/Gb correction · Smart-ISO Pro detection required |
| Main secondary | OmniVision **OV64B40** | 64 MP · 0.70 µm · 1/2.0" · Gr/Gb correction if \|diff\| > 2 DN · B boost 1.03–1.05 at high CCT |
| Main tertiary | OmniVision **OV50D40** | 50 MP · 0.612 µm · 1/2.88" · higher burst depth than ISOCELL |
| Ultra-wide | OmniVision **OV08D10** | 8 MP · 1.12 µm · 1/4.0" · 6+ coefficient Brown-Conrady distortion model |
| Front primary | OmniVision **OV16A1Q** | 16 MP · 0.7 µm · front-camera skin weight 0.40 |
| Front secondary | GalaxyCore **GC16B3** | 16 MP · 0.7 µm · 1/3.10" · contrast AF only (no PDAF) |
| Depth | SmartSens **SC202CS** | 2 MP · 1.75 µm · **never** feeds the imaging pipeline — used only as MiDaS prior |
| Macro | SmartSens **SC202PCS** | 2 MP · 1.75 µm · AF/depth/bokeh disabled · HDR disabled |

Lens suffixes `_aac_` / `_sunny_` / `_ofilm_` / `_semco_` and `_cn` (China market) affect lens calibration data only, not sensor-level pixel physics. Full tuning matrix: [`.agents/skills/Leica Cam Upgrade skill/references/sensor-profiles.md`](./.agents/skills/Leica%20Cam%20Upgrade%20skill/references/sensor-profiles.md).

---

## On-device AI models

All models live under `/Model/` in the repository and are copied into the APK's `assets/models/` at build time. Inference runs via **LiteRT** (the successor to TFLite on Android 15+, which deprecated NNAPI) or MediaPipe Tasks, with a SoC-aware delegate priority:

| Vendor | Primary delegate | Secondary | Fallback |
|---|---|---|---|
| MediaTek Dimensity | `mtk-apu` | GPU | XNNPACK CPU |
| Qualcomm Snapdragon | QNN (Hexagon DSP) | GPU | XNNPACK CPU |
| Samsung Exynos | ENN | GPU | XNNPACK CPU |

Shipped models:

| Directory | File | Role |
|---|---|---|
| `Model/AWB/` | `awb_final_full_integer_quant.tflite` | Neural AWB prior; blended with skin-anchor CCT |
| `Model/Face Landmarker/` | `face_landmarker.task` (MediaPipe) | Face landmarks for skin masking + face-tone pass |
| `Model/Image Classifier/` | `1.tflite` | Scene classification → `SceneLabel` → mode routing |
| `Model/Scene Understanding/` | `deeplabv3.tflite` | Semantic segmentation → `SemanticMask` for priority tone mapping |
| `Model/MicroISP/` | `MicroISP_V4_fp16.tflite` | Learned Bayer-domain refinement — **ultra-wide / front only** (disabled on S5KHM6 to avoid double-processing Imagiq ISP output) |

> The wiring that actually **runs** these models at capture time is delivered by Dimension 1 of [`Plan.md`](./Plan.md). Model files are present; the runtime path is currently being integrated.

---

## Build & run

### Prerequisites

- **JDK 17**
- **Android SDK** with `compileSdk = 35`, `minSdk = 29`
- **Android NDK r27** (for `:native-imaging-core:impl`)
- **Vulkan-capable Android device** for GPU compute paths (or a recent Android emulator with Vulkan enabled)

### Quick commands

```bash
# Full build (all modules, all variants)
./gradlew assemble

# Debug APK only
./gradlew :app:assembleDebug

# Install on a connected device
./gradlew :app:installDebug

# Lint + static analysis
./gradlew ktlintCheck detekt

# Public-API binary compatibility check
./gradlew apiCheck
```

### Module-targeted builds

```bash
./gradlew :imaging-pipeline:impl:assemble
./gradlew :ai-engine:impl:assemble
./gradlew :sensor-hal:assemble
```

### Bundling the on-device models into the APK

When Plan.md Dimension 1 is merged, `./gradlew :app:preBuild` will automatically run the `copyOnDeviceModels` task that mirrors `/Model/**/*.tflite` and `/Model/**/*.task` into `app/src/main/assets/models/`. No manual step required.

---

## Test

```bash
# All unit tests
./gradlew test

# Module-scoped
./gradlew :imaging-pipeline:impl:test
./gradlew :hypertone-wb:impl:test
./gradlew :ai-engine:impl:test

# Android instrumentation tests (requires connected device or emulator)
./gradlew connectedDebugAndroidTest
```

Engine modules ship with physics-grounded unit tests: every noise-model, Wiener-merge, bilateral-decomposition, and S-curve path has a round-trip correctness test in its `src/test/`. Representative examples:

- `imaging-pipeline/impl/src/test/java/com/leica/cam/imaging_pipeline/pipeline/ImagingPipelineTest.kt`
- `sensor-hal/src/test/java/com/leica/cam/sensor_hal/session/CameraSessionStateMachineTest.kt`
- `hypertone-wb/impl/src/test/java/com/leica/cam/hypertone_wb/pipeline/*Test.kt`

> **On-device smoke test is mandatory before any release.** Unit tests cannot catch the kind of regressions that matter here — pink highlights, motion ghosts, skin-tone casts, noisy shadow lifts. Capture a backlit portrait under mixed lighting and verify visually.

---

## Repository map

See [`project-structure.md`](./project-structure.md) for the authoritative directory map, module graph, dependency diagram, and runtime-flow documentation.

Other key documents:

- [`Plan.md`](./Plan.md) — the active four-dimension upgrade plan (model integration, HDR rebuild, structure refactor, known-issues registry).
- [`Implementation.md`](./Implementation.md) — historical phase-by-phase implementation notes (phases 0 – 10).
- [`changelog.md`](./changelog.md) — release notes.
- [`docs/adr/`](./docs/adr/) — Architecture Decision Records:
  - ADR-001 — 16-bit photon buffer
  - ADR-002 — API / Impl split
  - ADR-003 — LUMO parallel pipeline
  - ADR-004 — Vulkan Compute
  - ADR-005 — Partitioned CT sensing
  - ADR-006 — Separate engines
- [`docs/known-issues/`](./docs/known-issues/) — technical-debt registry (seeded by Plan.md Dimension 4).
- [`Knowledge/`](./Knowledge/) — research foundations (HDR algorithm research, imaging system research, reference papers).

---

## Engineering principles (non-negotiable)

These are enforced by code review, ktlint, detekt, and the `kotlinx-binary-compatibility-validator`:

- **Kotlin style**: no `!!` without a justification comment; no `GlobalScope`; `CancellationException` is always re-thrown; `catch (e: Exception)` is forbidden for cancellable coroutine paths.
- **Error handling**: all fallible functions return `LeicaResult<T>` (`Success` / `Failure.Pipeline` / `Failure.Hardware` / `Failure.Recoverable`). No checked exceptions cross module boundaries.
- **Function length**: ≤ 40 lines; exhaustive `when` on sealed classes; max nesting depth 3.
- **Naming**: no `*Utils`, `*Helper`, `*Misc`. Every file exports something specific.
- **Modules**: `:xyz:api` has zero Android imports. All `@Module` files live in `di/` sub-packages.
- **C++** (`:native-imaging-core:impl`): RAII for every resource; smart pointers only; `[[nodiscard]]` return values must be handled.
- **GPU shaders**: `f16` precision, physically motivated kernels, explicit memory-access patterns. No built-in blur; write the kernel yourself.
- **ML**: on-device only; INT8 or FP16 quantisation; background-thread inference; warm-up at app start; delegate fall-through must terminate at XNNPACK CPU.

---

## Current roadmap

The active upgrade plan is [`Plan.md`](./Plan.md). It describes four sequential dimensions:

1. **Dimension 1 — Model integration & per-sensor fine-tuning.** Wire the five `/Model/` TFLite / MediaPipe assets into the capture path via LiteRT + SoC-aware delegate selection. Apply per-sensor pre-processing rules so one AWB or segmentation model behaves correctly across all eight sensors.
2. **Dimension 2 — HDR engine rebuild.** Split the 546-line `ProXdrHdrEngine.kt` into eight focused files under `imaging-pipeline/impl/.../hdr/`. Replace the translation-only aligner with pyramidal Lucas-Kanade flow-guided alignment, fix per-channel Wiener weights from `SENSOR_NOISE_PROFILE`, implement a real Burt-Adelson Laplacian pyramid for the Mertens fallback, and move MTB ghost masking to the pre-alignment stage where it belongs.
3. **Dimension 3 — Enterprise structure refactor.** Add a `build-logic` composite build with `leica.android.library` / `leica.android.application` / `leica.jvm.library` / `leica.engine.module` convention plugins. Consolidate the 15 duplicated `DependencyModule.kt` files. Re-group modules under `core/ engines/ platform/ platform-android/ features/`. Re-enable ktlint everywhere. Standardise source paths to `src/main/kotlin/`.
4. **Dimension 4 — Known-issues registry.** Seed a `docs/known-issues/` directory with HDR, AI, structure, and performance issue indexes — the ledger future plans will draw from.

Each dimension is a **separate PR with its own verification gate**. Do not merge them as one.

---

## Contributing

- Read [`project-structure.md`](./project-structure.md) and the relevant ADR(s) for the subsystem you're touching.
- Follow the engineering principles above. Code review will block on violations.
- Every new algorithm cites its source paper in the KDoc — if you can't name the paper, you're using a heuristic and should reconsider.
- Tests: every new `:impl` class gets a round-trip correctness test. Physics-grounded engines (HDR, WB, tone) additionally get a golden-image perceptual test with a known reference output (`common-test` fixtures).
- New sensors or lens variants: update `sensor-hal/.../sensor/profiles/SensorProfileRegistry.kt` **and** [`.agents/skills/Leica Cam Upgrade skill/references/sensor-profiles.md`](./.agents/skills/Leica%20Cam%20Upgrade%20skill/references/sensor-profiles.md) in the same commit.

---

## Further reading

**Core algorithms**

- Hasinoff et al., *Burst photography for high dynamic range and low-light imaging on mobile cameras* (SIGGRAPH Asia 2016) — the HDR+ paper, source of the trapezoidal exposure weighting in `HdrMergeEngine`.
- Liba et al., *Handheld mobile photography in very low light* (SIGGRAPH Asia 2019) — Google Night Sight; source of motion metering and learned WB approach.
- Debevec & Malik, *Recovering high dynamic range radiance maps from photographs* (SIGGRAPH 1997) — the radiance-recovery branch of ProXDR.
- Mertens, Kautz, Van Reeth, *Exposure fusion* (2009) — the Mertens-fusion fallback path.
- Durand & Dorsey, *Fast bilateral filtering for the display of high-dynamic-range images* (SIGGRAPH 2002) — the base/detail tone-mapping decomposition in `DurandBilateralToneMappingEngine`.
- Burt & Adelson, *The Laplacian pyramid as a compact image code* (IEEE TCOM 1983) — the correct blending scheme for Mertens.
- Robertson, *Computation of correlated color temperature and distribution temperature* (1968) — the CCT method used in HyperTone WB.
- Reinhard et al., *Photographic tone reproduction for digital images* (SIGGRAPH 2002) — the Reinhard Extended variant used on the preview path.
- Lucas & Kanade, *An iterative image registration technique* (1981) — the basis of `DeformableFeatureAligner` in Plan.md D2.

**Recent research influencing the roadmap**

- NTIRE 2025 Efficient Burst HDR Challenge (CVPRW 2025) — flow-guided deformable alignment + pyramid cross-attention, porting concept in Plan.md D2.
- HL-HDR: *High-Low-Frequency-Aware HDR* (CVPR 2025) — rationale for splitting HDR reconstruction into low-frequency alignment + high-frequency detail branches.

**Platform references**

- [Android Camera2 API](https://developer.android.com/reference/android/hardware/camera2/package-summary)
- [LiteRT (TFLite successor on Android 15+)](https://ai.google.dev/edge/litert)
- [MediaPipe Tasks — Vision](https://ai.google.dev/edge/mediapipe/solutions/vision/face_landmarker/android)
- [Vulkan Compute on Android](https://source.android.com/docs/core/graphics/arch-vulkan)

---

<p align="center"><em>Physics first. RAW first. On-device always.</em></p>
