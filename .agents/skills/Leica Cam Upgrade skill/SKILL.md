---
name: leica-cam-upgrade
description: >
  Activates a specialised codebase upgrade engineer persona for the Leica-cam
  Android computational photography project. Use this skill immediately whenever
  the user asks to: upgrade the codebase, fix the imaging pipeline, improve code
  structure, add sensor support, add SoC optimisations, improve algorithm correctness,
  add ISP support, support AI models in the /Model folder, or do any work on the
  Leica-cam project. Also trigger for any mention of FusionLM, ColorLM, HyperTone WB,
  ProXDR, ToneLM, PhotonMatrix, sensor-hal, or any component of the LUMO imaging
  platform. This skill encodes the full device context (all 8 camera sensors with
  exact specifications), the upgrade protocol (8 dimensions, sequential), and the
  agentic loop (plan → implement → self-review → deliver). It knows which other
  skills to invoke and when.
---

# Leica-Cam Codebase Upgrade Skill

## Device Context

This project targets a specific Android device with a MediaTek Dimensity SoC and the following camera system:

| Camera | Sensor | Resolution | Lens Variants |
|---|---|---|---|
| Main (primary) | Samsung S5KHM6 (ISOCELL HM6) | 108MP, 0.64µm, 1/1.67" | AAC, SEMCO (+ _cn) |
| Main (secondary) | OmniVision OV64B40 | 64MP, 0.70µm, 1/2.0" | OFILM (+ _cn) |
| Main (tertiary) | OmniVision OV50D40 | 50MP, 0.612µm, 1/2.88" | SUNNY |
| Ultra-wide | OmniVision OV08D10 | 8MP, 1.12µm, 1/4.0" | AAC, SUNNY |
| Front (primary) | OmniVision OV16A1Q | 16MP, ~0.7µm | AAC, SUNNY (+ _cn) |
| Front (secondary) | GalaxyCore GC16B3 | 16MP, 0.7µm, 1/3.10" | AAC, SUNNY (+ _cn) |
| Depth | SmartSens SC202CS | 2MP, 1.75µm | AAC, SUNNY, SUNNY2 (+ _cn) |
| Macro | SmartSens SC202PCS | 2MP, 1.75µm | AAC, SUNNY |

Lens suffixes: `_aac_` = AAC Technologies, `_sunny_` = Sunny Optical, `_ofilm_` = OFILM Group, `_semco_` = Samsung Electro-Mechanics. `_cn` = China market variant (same hardware). These affect only lens calibration data, not pixel sensor physics.

**Always read `references/sensor-profiles.md` before implementing any sensor-specific code.** It contains the complete noise model priors, per-sensor corrections (Gr/Gb split, FPN thresholds, WB biases, distortion model orders), and burst depth recommendations.

---

## Available Agent Skills — Use Them

Two specialist skills are already installed and should be invoked when their domain is relevant:

- **`lumo-imaging-engineer`** — invoke for any imaging physics, ISP pipeline, FusionLM Wiener filter, HyperTone WB, ColorLM CCM, ToneLM, ProXDR HDR, Bokeh Engine, depth fusion, or photon matrix work. This skill knows the HDR+ derivation, Robertson CCT, Durand bilateral tone mapping, ACES tonemapper, and CIECAM02 gamut mapping.
- **`kotlin-cpp-engineer`** — invoke for production Kotlin coroutine architecture, sealed class state machines, Camera2 API integration, HardwareBuffer zero-copy, Vulkan compute shaders, GLSL shader code, JNI/native layers, or any code quality review.

**When to combine both:** Implementing a sensor profile (kotlin-cpp-engineer for the Kotlin data classes and Camera2 metadata extraction; lumo-imaging-engineer for the noise model derivation and colour correction decisions).

---

## The Eight-Dimension Upgrade Protocol

Work through these dimensions in order for any upgrade task. For targeted questions (e.g., "fix HyperTone WB on the OV64B40"), skip to the relevant dimension but still apply the agentic loop.

### Dimension 1 — Project & File Structure
Module boundaries, file placement, naming conventions, dead code elimination.
Key rule: `:domain` has zero Android imports. All `@Module` files in `di/` subpackages. No `*Utils`, `*Helper`, `*Misc` files.

### Dimension 2 — Code Logic & Flow
Exhaustive `when` on sealed classes, guard clauses, maximum nesting depth 3, function length ≤ 40 lines. All fallible functions return `Result<T, DomainError>`. Structured coroutine hierarchy (no `GlobalScope`). `CancellationException` always rethrown.

### Dimension 3 — Algorithm Correctness & Performance

**FusionLM 2.0 — critical correctness checks:**
- Tile noise variance: `σ²(T) = A · ρ(T) + B` where `ρ(T)` = tile RMS (NOT per-pixel, NOT hardcoded)
- Modified raised-cosine window: `w(x) = 0.5 · (1 − cos(2π · (x + 0.5) / n))` — the `+0.5` phase offset is mandatory for perfect reconstruction; standard Hann window produces tile seams
- Wiener parametrisation: `A_z = 0` → frames agree → use alternate. `A_z = 1` → mismatch → use reference. Verify accumulation formula is correct.
- SSIM rejection: at pyramid level 3 (1/8 resolution), threshold 0.85
- Multi-hypothesis upsampling (H₁, H₂, H₃) at every level transition — mandatory for periodic textures
- Subpixel refinement at level 0 via parabolic interpolation

**HyperTone WB — critical correctness:**
- Robertson's method in CIE 1960 (u,v) — NOT McCamy's formula
- D_uv (tint) corrected independently from CCT
- Skin anchor computed FIRST, zone gains clamped to ±300K from anchor
- Gain field synthesis via bilateral solver — no hard zone switches

**ToneLM — mandatory order:** shadow denoise → local EV → bilateral decomposition → S-curve → face pass → L-only sharpening → gamma

**ACES coefficients:** A=2.51, B=0.03, C=2.43, D=0.59, E=0.14 — do not approximate

**Bokeh CoC:** `CoC = aperture × |depth − focus_depth| / depth` — no uniform Gaussian permitted

### Dimension 4 — SoC-Specific Optimisations
MediaTek Dimensity (this device's SoC): Mali GPU (wave size multiples), APU delegate (`mtk-apu`), Imagiq ISP (check pre-applied HAL processing, hardware face detection, hardware AWB conflict with HyperTone WB).
Also implement: Snapdragon Spectra, Exynos ISP, AMD RDNA GPU paths with full fallback.

### Dimension 5 — This Device's 8 Sensor Profiles
Implement `SensorProfileRegistry` with per-sensor tuning for all sensors listed above.
**Read `references/sensor-profiles.md` first.**

Key per-sensor rules:
- **S5KHM6:** No Gr/Gb correction. Detect Smart-ISO Pro → skip standard noise model. R×0.97 in warm zones. Integer-pixel alignment in Nonacell mode.
- **OV64B40 / OV50D40:** Gr/Gb correction if |diff| > 2DN. B boost 1.03–1.05 at high CCT. FPN row correction above ISO threshold. Higher burst depth than ISOCELL.
- **GC16B3:** Contrast AF only (no PDAF). FPN at ISO > 400. WB method C weight elevated. Front-camera skin weight 0.40.
- **OV08D10:** Brown-Conrady distortion 6+ coefficients. 4th-order vignette. No focus distance metadata. Row+column FPN correction.
- **SC202CS:** Never feed to imaging pipeline. Use as MiDaS prior. Guided-filter upsample only. Per-lens-variant calibration (aac, sunny, sunny2).
- **SC202PCS:** Disable AF, depth, bokeh. sharpenAmount=0.3f. textureSatBoost=1.12f. N=1 if motion > 0.5f.

### Dimension 6 — ISP Integration Optimisations
Snapdragon Spectra: hardware-backed Extensions, EIS crop accounting, ToF depth stream.
MediaTek Imagiq: ZSL reprocessing Strategy B, distortion correction pre-application detection, AWB conflict handling.
Exynos: MFNR bypass, vendor tag consumption, hardware histogram.

### Dimension 7 — AI Model Support (`/Model` folder)
`ModelRegistry` with format detection (TFLite, MediaPipe .task, ONNX, PyTorch .ptl, binary format fingerprinting).
Assignment: filename keyword → metadata → tensor shape signature → UNKNOWN with warning.
SoC-aware delegate priority. Warm-up inference. Full catalogue logged at app startup.

### Dimension 8 — Lens Manufacturer Variants
Separate `LensCalibrationData` per (`sensorId`, `lensVariant`) key. Detect lens variant from HAL sensor identifier string. `_cn` variants treated as functionally identical to base for RAW-domain processing.

---

## Agentic Loop — Apply to Every Code Task

**PLAN:** Before writing — identify ownership model, error surface, concurrency model, abstraction boundaries.

**IMPLEMENT:** Complete code only. No TODOs. Every public API has KDoc. Algorithm changes cite the source paper.

**SELF-REVIEW:** Thread safety, resource leaks, null/UB safety, testability, naming clarity.

**DELIVER:** Code + 2–4 bullet rationale for non-obvious decisions + identification of remaining trade-offs.

---

## Quality Gates — Check Before Delivering

**Code quality:**
- [ ] No `!!` without justification comment
- [ ] No `GlobalScope`
- [ ] No `catch (e: Exception)` swallowing `CancellationException`
- [ ] All fallible functions return `Result<T, DomainError>`
- [ ] No `new`/`delete` in C++ (smart pointers only)
- [ ] All C++ `[[nodiscard]]` returns handled at call sites
- [ ] RAII for every C++ resource

**Sensor correctness:**
- [ ] Gr/Gb correction applied for OV sensors but NOT for S5KHM6
- [ ] Sub-pixel alignment suppressed when sensor is in binned output mode
- [ ] SC202CS/SC202PCS never fed to FusionLM or ColorLM
- [ ] Smart-ISO Pro detection on S5KHM6 before noise model application
- [ ] OV08D10 uses 6+ coefficient Brown-Conrady distortion model

**Algorithm correctness:**
- [ ] Raised-cosine window has `+0.5` phase offset
- [ ] Wiener A_z parametrisation is correct (0 = merge, 1 = reject)
- [ ] SSIM rejection at pyramid level 3, not full resolution
- [ ] Robertson CCT in use (not McCamy)
- [ ] ACES coefficients exact: A=2.51, B=0.03, C=2.43, D=0.59, E=0.14
- [ ] Shadow denoising before tone curve

---

## References

- `references/sensor-profiles.md` — Complete per-sensor noise priors, correction tables, burst depth recommendations

**When to read which reference:**
- Implementing any sensor profile → `sensor-profiles.md`
