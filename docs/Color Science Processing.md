# Color Science Processing — LeicaCam (Leica + Hasselblad HNCS)

_Last updated: 2026-04-25 (UTC). Owner: `color-science-engineer` skill agent._

This document is the **single source of truth** for how LeicaCam renders color from sensor RAW to display-encoded output. It is grounded in the four research papers under `Knowledge/`:

- `Color science research paper.pdf`
- `color_science_engine_leica_hasselblad_research.pdf`
- `color_science_engine_research.pdf`
- `Architecting a Hybrid Color Pipeline_ Merging Perceptual Colorimetry and Generative AI for Android.pdf`

…and in the four reference files under `.agents/skills/color-science-engineer/references/`:

- `pipeline-math.md` — matrices, OKLAB, ΔE2000, filmic curve, CCT estimation.
- `android-implementation.md` — Camera2 keys, GPU shader pipeline, NDK demosaic.
- `calibration-methodology.md` — ColorChecker workflow, illuminant interpolation.
- `leica-hasselblad-rendering.md` — HNCS philosophy, tonal targets, skin-tone sovereignty.

If code and this document diverge, **fix the divergence**: the document is the contract.

---

## 1. Design Philosophy

LeicaCam is a RAW-first, 16-bit-end-to-end pipeline. Color science is **not** a LUT applied at the end of an SDR JPEG: it is a chain of typed transforms in well-defined color spaces, with a clean separation between **calibration** (correct) and **look** (preferred).

Three principles are non-negotiable:

1. **Linear-light operations only** until the final OETF. Every CCM, white-balance gain, gamut map, and tone-mapping operator runs in linear floating-point. Gamma/log encoding happens once, at the end.
2. **Perceptual operations in perceptual spaces.** Saturation, vibrance, hue rotation, skin protection, and selective color all run in **OKLAB** (preferred) or CIELab. Never in HSV/HSL.
3. **Calibration is decoupled from the look.** The dual-illuminant CCM brings the sensor's native RGB into colorimetric agreement with CIE XYZ. The 3D LUT applies the Leica/Hasselblad **aesthetic deviation** on top. Recalibrating the sensor never changes the look; tuning the look never re-derives the CCM.

These principles map directly to the **HNCS** (Hasselblad Natural Color Solution) framework — one universal mathematically adaptive profile per illuminant — and to **Leica's** cinematic micro-contrast / skin-tone sovereignty / restrained foliage boost philosophy.

---

## 2. End-to-End Pipeline

```text
Sensor RAW (Bayer 10/12/16-bit, native gamut)
   │
   ▼
[1] Black-level subtract + lens-shading correction      (NDK / Vulkan)
   │
   ▼
[2] AHD / RCD demosaic → linear camera RGB              (NDK / Vulkan)
   │
   ▼
[3] White balance — HyperTone WB                        (:hypertone-wb:impl)
       • Per-tile 4×4 CCT estimate (Robertson 1968)
       • Multi-modal illuminant fusion (neural prior + statistics + spectral)
       • Skin-zone-anchored CCT correction
       • Mixed-light spatial blending
   │
   ▼
[4] PHOTON MATRIX FUSION — multi-frame burst merge      (:photon-matrix:impl)
       • Bayer-domain alignment + Wiener noise weighting
   │
   ▼
[5] HDR (when active)                                   (:imaging-pipeline:impl)
       • ProXdrOrchestrator → Debevec radiance / Mertens fallback
   │
   ▼
[6] COLOR SCIENCE ENGINE — ColorLM 2.0                  (:color-science:impl)
       a. Per-hue HSL adjustments (8-band Gaussian, user-controlled)
       b. Vibrance / CIECAM-style perceptual saturation (skin-protected)
       c. Skin-tone protection (Fitzpatrick I–VI anchors, ΔE*₀₀ cap)
       d. Per-zone CCM:
              CCM_pixel(x,y) = Σ_z p_z(x,y) · (baseline + δ_z)
              baseline = Bradford(D50→D65) · ForwardMatrix(α)
              α = (1/CCT − 1/6500) / (1/2856 − 1/6500), clamp [0,1]
       e. 3D LUT (65³, tetrahedral interpolation, ACEScg linear in/out)
       f. CIECAM02 CUSP gamut mapping → Display-P3 / sRGB hull
       g. Film grain synthesis (deterministic blue-noise, profile-tuned)
   │
   ▼
[7] Tone mapping (luminance-only)                       (:imaging-pipeline:impl)
       • Durand bilateral local TM
       • Cinematic S-curve (Hable/Hasselblad-tuned)
       • Filmic shoulder ≈ 75% input, toe ≈ 50–55/255
   │
   ▼
[8] Luminance sharpening (post-tone-curve, last)        (:imaging-pipeline:impl)
       • Leica micro-contrast: USM radius 1.5–2 px, amount 20–30 %
   │
   ▼
[9] OETF + encode (sRGB / HLG / PQ)                     (NDK / Vulkan)
   │
   ▼
[10] Container: HEIC (Display-P3, 10-bit) or DNG raw    (:imaging-pipeline:impl)
```

The color-science block (step 6) is the subject of this document.

---

## 3. Color Spaces & Transitions

| # | Space | Where | Why |
|---|---|---|---|
| 1 | Native sensor RGB (linear) | Steps 1–5 | Photon-counting; no display assumption. |
| 2 | CIE XYZ D50 | Step 6d input | DNG profile-connection space; per-illuminant CCMs land here. |
| 3 | Linear sRGB / Linear Rec.2020 | Step 6d→6e | Bradford-adapted from XYZ D50→D65 then matrix to working space. |
| 4 | ACEScg linear | LUT input/output | Wide-gamut working space; no banding. |
| 5 | OKLAB | Inside 6a–6c, 6f | Perceptually uniform. All "taste" operations live here. |
| 6 | Display-P3 / sRGB encoded (gamma 2.4 / piecewise) | Step 9 output | Final display-referred encoding. |

**Tagging rule:** every intermediate buffer is tagged with its space. An untagged buffer is a bug.

### 3.1 Canonical matrices (D65)

Linear sRGB → CIE XYZ D65:

```
[ 0.4124564  0.3575761  0.1804375 ]
[ 0.2126729  0.7151522  0.0721750 ]
[ 0.0193339  0.1191920  0.9503041 ]
```

CIE XYZ D65 → Linear sRGB:

```
[  3.2404542  −1.5371385  −0.4985314 ]
[ −0.9692660   1.8760108   0.0415560 ]
[  0.0556434  −0.2040259   1.0572252 ]
```

CIE XYZ D65 → Display-P3 (linear):

```
[  2.4934970  −0.9313836  −0.4027108 ]
[ −0.8294890   1.7626641   0.0236247 ]
[  0.0358458  −0.0761724   0.9568845 ]
```

These are encoded verbatim in `core/color-science/impl/.../pipeline/ColorScienceEngines.kt` and verified against IEC 61966-2-1 and SMPTE RP 431-2. Do not transcribe them by hand into shaders — pull them from the typed constants in code.

### 3.2 OKLAB — preferred perceptual space

Linear sRGB → LMS (Björn Ottosson 2020):

```
[ 0.4122214708  0.5363325363  0.0514459929 ]
[ 0.2119034982  0.6806995451  0.1073969566 ]
[ 0.0883024619  0.2817188376  0.6299787005 ]
```

Then `LMS' = ∛(LMS)` (cube root each component), then LMS' → OKLAB:

```
[ 0.2104542553  0.7936177850 −0.0040720468 ]
[ 1.9779984951 −2.4285922050  0.4505937099 ]
[ 0.0259040371  0.7827717662 −0.8086757660 ]
```

Inverse: OKLAB → LMS' via the inverse matrix, cube each LMS' component, LMS → linear sRGB via inverse. All saturation, vibrance, hue rotation, skin-tone protection, and CUSP chroma compression operate in OKLAB.

---

## 4. Calibration — The Floor

### 4.1 Camera2 metadata ingestion

`Camera2CalibrationReader` (under `:color-science:impl`) reads, on capture-session creation:

| Camera2 key | Used as |
|---|---|
| `SENSOR_REFERENCE_ILLUMINANT1` | Typically `STANDARD_A` (2856 K) — anchor for `forwardMatrixA`. |
| `SENSOR_REFERENCE_ILLUMINANT2` | Typically `D65` (6500 K) — anchor for `forwardMatrixD65`. |
| `SENSOR_FORWARD_MATRIX1` | 3×3 row-major; sensor RGB → XYZ D50 under illuminant 1. |
| `SENSOR_FORWARD_MATRIX2` | 3×3 row-major; sensor RGB → XYZ D50 under illuminant 2. |
| `SENSOR_BLACK_LEVEL_PATTERN` | Per-channel black levels for linearization. |
| `SENSOR_INFO_WHITE_LEVEL` | White-clipping reference. |
| `COLOR_CORRECTION_GAINS` (per-frame) | WB gains pre-applied by HyperTone. |
| `COLOR_CORRECTION_TRANSFORM` (per-frame) | Reported sensor→sRGB matrix (used for cross-check, not authoritative). |

These matrices are **never hard-coded**. The Sony-IMX defaults in `DngDualIlluminantInterpolator.Companion` exist solely as a fallback for the (vanishingly rare) device that exposes neither matrix.

### 4.2 Dual-illuminant interpolation

Given scene CCT estimated by HyperTone WB:

```
miredScene = 1e6 / CCT
miredA     = 1e6 / 2856 ≈ 350.14
miredD65   = 1e6 / 6500 ≈ 153.85
α          = clamp((miredScene − miredD65) / (miredA − miredD65), 0, 1)
ForwardMatrix(α) = α · ForwardMatrixA + (1 − α) · ForwardMatrixD65
```

Mired interpolation is approximately perceptually uniform across CCT — the formula matches DNG §2.3 and is preserved verbatim from `DngDualIlluminantInterpolator.forwardMatrixForCct`. Above 6500 K we extrapolate but clamp `α` at 0; below 2856 K we clamp `α` at 1.

### 4.3 ColorChecker validation targets (D65)

| Metric | Target |
|---|---|
| Average ΔE2000 across 24 patches | **≤ 3.0** |
| Max ΔE2000 | **≤ 5.5** |
| Neutral patches (gray, white, black) ΔE2000 | **≤ 1.5** |
| Skin patches (Light Skin, Dark Skin) ΔE2000 | **≤ 4.0** |
| Gray-card ΔE2000 (after WB) | **≤ 1.0** |
| Shadow floor (black patch sRGB) | 48–58 / 255 |
| 18 % gray output (sRGB) | 103–112 / 255 |

Under StdA (2856 K): average ΔE2000 ≤ 4.0, skin hue 24°–34° in OKLAB.

These are the gate criteria for `ColorAccuracyBenchmark`. CS-6 (in `Plan.md`) reports them on every CI run.

### 4.4 Skin-tone hue band (OKLAB polar)

| Illuminant | Hue angle (rad) | Hue angle (°) | Chroma | Lightness |
|---|---|---|---|---|
| D65 | 0.46 – 0.52 | 26 – 30 | 0.10 – 0.22 | 0.32 – 0.88 |
| StdA (tungsten) | 0.24 – 0.34 | 14 – 19 | 0.10 – 0.22 | 0.32 – 0.88 |
| Overcast (~7500 K) | 0.46 – 0.55 | 26 – 31 | 0.10 – 0.22 | 0.32 – 0.88 |

Hue > 0.55 rad under D65 → red bias. Hue < 0.40 rad under D65 → yellow bias. Hue > 0.60 rad under StdA → undercorrected tungsten. These thresholds are emitted by `SkinToneProtectionPipeline` validation diagnostics.

---

## 5. Look — The Ceiling

The "look" is the deliberate, opinionated deviation from colorimetric accuracy that defines the Leica or Hasselblad rendering. It is encoded by:

1. **`ZoneCcmDelta`** per `ColourZone` (skin, sky, foliage, water, artificial-light, neutral). A 3×3 multiplicative delta on top of the calibrated CCM, plus a per-zone `saturationBoost` and `deltaECap`.
2. **The 3D LUT** (65³, tetrahedral). Bakes the profile's `ProfileLook` parameters — `shadowLift`, `shoulderStrength`, `globalSaturationScale`, `greenDesaturation`, `redContrastBoost`, `warmShiftKelvinEquivalent` — into a procedurally generated linear-in / linear-out table. Trilinear is **never used** because of its hue-shift artifacts on the gray axis. Tetrahedral subdivides the unit cube into six tetrahedra (4 vertices each) and is hue-preserving on primary diagonals.
3. **The filmic shoulder** baked into the LUT's tone application:

   ```
   f(x) = x / (x + S · (1 − x))     // soft-shoulder roll-off
   ```

   with `S = ProfileLook.shoulderStrength`. The full Hable/Hasselblad-tuned form (from `pipeline-math.md`) is reserved for the imaging-pipeline tone-mapping block in step 7 — the LUT bake uses only the soft shoulder so the global S-curve can still operate.

4. **CIECAM02 CUSP gamut mapping** (simplified Lab approximation on mobile GPUs). Compresses out-of-gamut chroma along hue-specific cusp lines instead of hard-clipping channels. Banding-free at 8-bit output.

### 5.1 The five built-in profiles

| Profile | Look | Skin ΔE cap | Default grain | Use case |
|---|---|---|---|---|
| `LEICA_M_CLASSIC` | Cinematic; +8 % red contrast; skin warmed +2 %; foliage +10 %; sky deeper | 2.0 | 0.010 amount, 1.1 px | Street, portrait, low light |
| `HASSELBLAD_NATURAL` | HNCS universal; tightest skin fidelity (ΔE ≤ 1.5); P3 sky extension | **1.5** | 0.005 amount, 0.8 px | Default; landscape, product, professional |
| `PORTRA_FILM` | Warm shift +180 K equiv.; muted greens; lifted shadows; gentle shoulder | n/a | 0.012 amount, 1.2 px | Editorial portrait |
| `VELVIA_FILM` | High global saturation 1.22; green boost; cooler warm shift | n/a | 0.006 amount, 0.8 px | Landscape, travel |
| `HP5_BW` | Monochrome; +10 % red contrast on luminance; visible grain | n/a | 0.025 amount, 1.5 px | B&W |

All profile data lives in `ColorProfileLibrary` in `core/color-science/impl/.../pipeline/ColorScienceEngines.kt`. To add a profile, append a new `ColorProfileSpec` there — never elsewhere.

### 5.2 Skin-tone sovereignty

Skin tones are **never** sacrificed by global operations. The `SkinToneProtectionPipeline` enforces this in three layers:

1. **Detection** — tri-space test (YCbCr range, HSL hue/saturation, Lab hue/lightness) with morphological opening to suppress wood/sand false positives.
2. **Anchor correction** — six Fitzpatrick anchors (I → VI, Monk Skin Tone scale). The mean Lab of detected skin pixels is pulled 30 % toward the nearest anchor, but only if mean ΔE2000 to the anchor exceeds 4.0.
3. **Chrominance smoothing** — Gaussian-weighted 3×3 chroma-only blur on skin pixels (luma untouched). Reduces pore-level color noise without losing texture.

The skin hue band in OKLAB is **27–30°** under D65 (0.46–0.52 rad) and **24–34°** under StdA. Any operation that pushes skin hue outside this band is a regression.

The six Fitzpatrick anchors (Lab, D65) — Monk Skin Tone scale-aligned:

| Type | L* | a* | b* | Skin description |
|---|---|---|---|---|
| I | 72 | 12 | 16 | Very light |
| II | 65 | 15 | 20 | Light |
| III | 58 | 19 | 24 | Medium light |
| IV | 50 | 22 | 26 | Medium |
| V | 43 | 22 | 22 | Medium dark |
| VI | 35 | 18 | 18 | Dark |

Inclusivity across these six anchors is a hard requirement of any Leica/Hasselblad-grade pipeline.

### 5.3 Zone-aware rendering

The semantic mask from `:ai-engine:api SemanticSegmenter` (DeepLabv3 on-device, `Model/Scene Understanding/deeplabv3.tflite`) provides per-pixel zone probabilities. `PerZoneCcmEngine` blends per-zone CCM deltas:

```
CCM_pixel = identity + Σ_z  p_z(x,y) · δ_z
out_lin   = CCM_pixel · in_lin
```

Where the skin probability exceeds 0.3, the resulting pixel's ΔE2000 to the input pixel is capped at the profile's `deltaECap` — overshooting skin pixels are linearly interpolated back along the gradient. This is the Hasselblad HNCS guarantee, expressed in code.

---

## 6. Performance — Single Pipeline, Two Speeds

There is **one** pipeline. Preview and capture run the same math; only resolution and LUT grid size change.

| Path | Resolution | LUT | Backend | Target latency |
|---|---|---|---|---|
| Preview (30–60 fps) | 1920×1080 (downsampled) | 33³ | Vulkan compute (`shaders/lut_3d_tetra.comp`) | **< 10 ms / frame** |
| Capture (full quality) | Native sensor (e.g., 12,032×9,024) | 65³ | NDK + Vulkan | **< 500 ms / frame** |

Both paths produce identical math at the resolutions that overlap. The LUT for both paths is built once per profile via `TetrahedralLutEngine.buildProceduralLut` — no two parallel implementations.

### 6.1 GPU shader

`platform-android/gpu-compute/src/main/assets/shaders/lut_3d_tetra.comp` (GLSL → SPIR-V) implements the same six-tetrahedron decomposition as `TetrahedralLutEngine.sampleTetrahedral` in Kotlin. The Kotlin path is the **reference truth** for unit-test comparison against the shader.

### 6.2 AI augmentation (on-device only)

The semantic mask consumed by `PerZoneCcmEngine` is supplied by `SemanticSegmenter` (LiteRT, `Model/Scene Understanding/deeplabv3.tflite`). The illuminant prior consumed by HyperTone WB is supplied by `AwbPredictor` (`Model/AWB/awb_final_full_integer_quant.tflite`). Face-mask augmentation for skin protection is supplied by MediaPipe `FaceLandmarker` (`Model/Face Landmarker/face_landmarker.task`). Profile auto-selection consumes `Model/Image Classifier/1.tflite`. **No cloud inference at any step.**

Delegate strategy:

| SoC family | Preferred delegate | Fallback chain |
|---|---|---|
| MediaTek Dimensity | `mt-apu` | GPU → XNNPACK |
| Qualcomm Snapdragon | `QNN` | GPU → XNNPACK |
| Samsung Exynos | `ENN` | GPU → XNNPACK |
| Other | GPU | XNNPACK |

---

## 7. Validation

`ColorAccuracyBenchmark.run(profile, patches)` evaluates the full pipeline against the 24-patch Macbeth ColorChecker. Reports:

- Mean ΔE2000
- Max ΔE2000
- 90th-percentile ΔE2000
- Pass / fail vs. §4.3 thresholds

`ColorAccuracyBenchmarkTest` in CI runs it under D65 and StdA at every commit. A regression in either average ΔE2000 (D65 > 3.0 or StdA > 4.0) blocks merge.

Manual validation also includes:

- **Skin-tone chart** under tungsten / daylight / overcast; hue stability check (§4.4).
- **Backlit window scene** for highlight shoulder behavior (no hard clipping; smooth roll-off).
- **Foliage + sky scene** for green-boost (≤ +8–12 %) / sky-saturation balance (no neon, no purple cast).
- **Pure neutrals (gray, white)** ΔE2000 ≤ 1.0 on D65.
- **Smooth gradient** (sky-to-horizon) check for banding — fails indicate 8-bit intermediate processing.
- **Side-by-side reference** capture against a Leica M / Hasselblad X1D RAW processed through Phocus or Capture One — used as the ground-truth aesthetic reference, not as a numerical target.

### 7.1 ML-component validation

For any ML stage that touches color (segmenter, AWB prior, face landmarker), report:

- PSNR > 40 dB on reference scenes
- SSIM > 0.92
- LPIPS — track regression
- CLIP-IQA — track regression
- MOS (mean opinion score) > 4.0 / 5.0 on a held-out test set

Per the Hybrid Color Pipeline paper, perceptual loss functions (LPIPS, CLIP-IQA) are used during training, not just at evaluation.

---

## 8. Extension Points

| Extension | Where | Out of scope here |
|---|---|---|
| Add a new color profile | `ColorProfileLibrary` in `ColorScienceEngines.kt` | Re-tuning the global tone curve. |
| Add a new color zone | `ColourZone` enum + `ZoneCcmDelta` map per profile | New segmentation classes (live in `:ai-engine`). |
| Override CCM for a specific sensor | `Camera2CalibrationReader.ingest(...)` consumes `CameraCharacteristics`; per-device tweaks belong in a per-device `CameraCalibrationProfile` (not yet introduced). | Per-device factory profiling at scale. |
| Swap the LUT grid resolution | `LUT_GRID_SIZE` constant. Test impact on banding. | Switching to 4D LUTs (out of scope). |
| Add neural ACES-1.0 tone-map | New engine in `:imaging-pipeline:impl`, **after** color science. | Integrating into the LUT bake. |
| Add generative-AI grading (text-prompt) | New stage **after** color science, gated behind a feature flag. | Mixing it with calibration. |

---

## 9. Hard Rules

1. Tone curves are applied to **luminance only**, never per-RGB-channel (would cause hue rotation in highlights).
2. Saturation is always **hue-selective, luminance-aware, skin-protected**. No global saturation slider that touches the calibration matrices.
3. Single-CCM pipelines are forbidden. The dual-illuminant interpolator is mandatory.
4. The LUT contains only the **look** — never the calibration matrix and never the OETF.
5. Skin pixels with `p(skin) > 0.3` are subject to the ΔE*₀₀ cap; this clamp is non-negotiable.
6. Preview and capture must be **perceptually identical** at overlapping resolutions.
7. No HSV / HSL for any precision operation. Use OKLAB.
8. Camera2 metadata is **read every session**. Never cache CCMs across devices.
9. Trilinear interpolation is forbidden in the LUT path. Tetrahedral only.
10. No cloud inference. Every model that touches color runs on-device via LiteRT or MediaPipe.

---

## 10. Glossary

| Term | Definition |
|---|---|
| **CCM** | Color Correction Matrix; sensor RGB → CIE XYZ. |
| **CCT** | Correlated Color Temperature (Kelvin); the scene illuminant. |
| **ΔE2000** | Perceptually-weighted color difference metric. Standard. |
| **HNCS** | Hasselblad Natural Color Solution. Universal adaptive profile. |
| **OKLAB** | Modern perceptually-uniform color space (Björn Ottosson 2020). |
| **Tetrahedral interpolation** | 3D LUT lookup using 4-vertex tetrahedra (vs. 8-vertex trilinear). Hue-preserving. |
| **Forward matrix** | DNG matrix; sensor RGB → CIE XYZ D50. |
| **Bradford CAT** | Chromatic adaptation transform; XYZ-under-illuminant-A → XYZ-under-illuminant-B. |
| **CUSP gamut mapping** | Hue-specific chroma compression onto the display gamut hull. Avoids banding at clipping. |
| **Mired** | Reciprocal megakelvin (10⁶/CCT). Approximately perceptually uniform across CCT. |
| **Scene-referred** | Linear values proportional to scene luminance. |
| **Display-referred** | Encoded values prepared for a specific display. |
| **OETF** | Opto-Electronic Transfer Function (e.g., sRGB gamma, HLG, PQ). |

---

## 11. Document Maintenance

Update this file whenever you:

- Add or remove a `ColorProfile`.
- Change the render order in `ColorSciencePipeline.process(...)`.
- Modify the dual-illuminant interpolation formula.
- Adjust the ΔE2000 thresholds in `ColorAccuracyBenchmark`.
- Add a new `ColourZone` or change the skin-tone Fitzpatrick anchor table.
- Replace a model under `Model/` that the color-science block consumes.
- Modify any constant matrix (sRGB↔XYZ, OKLAB, Bradford).

A divergence between this document and `core/color-science/impl/.../pipeline/ColorScienceEngines.kt` is treated as a P1 bug.
