# Colour Science — Deep Implementation Reference

> **Module:** `:color-science` → `ColorLM2Engine.kt`, `PerZoneCcmBuilder.kt`, `GamutMapper.kt`
> **Scope:** Colour correction matrix (CCM) derivation by least squares, spectral reconstruction from RGB to 31-band reflectance, dual-illuminant DNG ForwardMatrix interpolation, chromatic adaptation, gamut mapping via CIECAM02 CUSP, and tetrahedral 3D LUT interpolation.
> **Design principle:** Physics-first. Every colour transformation must be invertible, traceable, and benchmarked against a Macbeth ColorChecker under known illuminants. No empirical "looks right" tuning without a ΔE*₀₀ number behind it.

---

## 0. Colour Pipeline Overview

```
RAW sensor RGB (device-native primaries)
    │
    │  1. White balance (HyperTone, gain + Bradford adapt to D65)
    ▼
RAW-white-balanced RGB (still in sensor primaries, but D65-adapted)
    │
    │  2. Sensor → CIE XYZ (D65)      via DNG ColorMatrix / ForwardMatrix
    ▼
CIE XYZ (D65)
    │
    │  3. Bradford chromatic adaptation D65 → D50 (PCS)
    ▼
CIE XYZ (D50) — Profile Connection Space
    │
    │  4. ACEScg working gamut + per-zone creative CCM (ColorLM 2.0)
    ▼
ACEScg wide gamut, linear
    │
    │  5. 3D LUT (Leica Classic / Hasselblad Natural / etc.) — tetrahedral interp
    ▼
Post-LUT ACEScg
    │
    │  6. CIECAM02 CUSP gamut mapping → Display P3 (or sRGB)
    ▼
Display-P3 linear
    │
    │  7. OETF encoding (sRGB / PQ / HLG)
    ▼
Final display-referred 10-bit HEIC output
```

Each step is auditable and characterised by matrices or LUTs stored as versioned assets.

---

## 1. CCM Derivation by Least Squares

### 1.1 Problem statement

Given:
- A set of `N` colour patches (Macbeth ColorChecker: N=24) with known CIE XYZ values under illuminant L: `Y_ref ∈ ℝ^(N×3)`.
- The same patches captured by the sensor under the same illuminant L, with white balance applied: `X_sensor ∈ ℝ^(N×3)`.

Solve for the `3×3` CCM `M` such that:

```
X_sensor · M ≈ Y_ref
```

### 1.2 Closed-form least squares

```
M = (X_sensor^T · X_sensor)^(-1) · X_sensor^T · Y_ref
```

Equivalently: `M = pinv(X_sensor) · Y_ref`.

**Numerically stable Kotlin implementation using QR decomposition:**

```kotlin
import org.apache.commons.math3.linear.*

fun solveCcm(
    sensorRgb: Array<DoubleArray>,   // N x 3, after WB
    referenceXyz: Array<DoubleArray> // N x 3, under same illuminant
): Array<DoubleArray> {
    require(sensorRgb.size == referenceXyz.size)
    val X = MatrixUtils.createRealMatrix(sensorRgb)
    val Y = MatrixUtils.createRealMatrix(referenceXyz)
    val decomp = QRDecomposition(X)
    val solver = decomp.solver
    val M = solver.solve(Y)
    return M.data
}
```

### 1.3 Constrained CCM (row-sum = 1)

Unconstrained CCM can cause brightness drift (rows sum ≠ 1). For a "grey-preserving" CCM, constrain each row to sum to 1:

```
minimise   ||X · M − Y||²
subject to M · [1,1,1]^T = [1,1,1]^T
```

Use Lagrangian reduction: substitute `M[i,2] = 1 − M[i,0] − M[i,1]` and solve a `3×2`-per-row unconstrained problem.

### 1.4 Perceptual CCM (ΔE*₀₀-optimal)

Plain least squares minimises XYZ error, which does **not** correspond to perceived error. For Leica/Hasselblad-grade colour, minimise CIEDE2000:

```
minimise   Σ_i  [ ΔE*₀₀( Lab(X_i · M), Lab(Y_i) ) ]²
```

- Nonlinear — solve with Levenberg-Marquardt initialised from the closed-form LS solution.
- Typically reduces mean ΔE*₀₀ by 30–50% vs plain LS on Macbeth, at the cost of ~200 ms calibration compute (done once per calibration run, not per capture).

```kotlin
fun solveCcmPerceptual(
    sensorRgb: Array<DoubleArray>,
    referenceXyz: Array<DoubleArray>,
    illuminantXyz: DoubleArray = D50_WHITE,
): Array<DoubleArray> {
    val initial = solveCcm(sensorRgb, referenceXyz).flatMap { it.toList() }.toDoubleArray()
    val optimiser = LevenbergMarquardtOptimizer()
    val problem = LeastSquaresBuilder()
        .start(initial)
        .model { m ->  // returns residuals of ΔE*₀₀ per patch
            val M = m.to3x3()
            Array(sensorRgb.size) { i ->
                val xyz = matVec(M, sensorRgb[i])
                val lab1 = xyzToLab(xyz, illuminantXyz)
                val lab2 = xyzToLab(referenceXyz[i], illuminantXyz)
                deltaE2000(lab1, lab2)
            }
        }
        .target(DoubleArray(sensorRgb.size))   // target = 0 error
        .maxEvaluations(500)
        .build()
    return optimiser.optimize(problem).point.to3x3()
}
```

### 1.5 Macbeth ColorChecker reference values (D50, sRGB)

The 24 patches in sRGB (ColorChecker 24, pre-2014 edition, BabelColor Avg):

```
#01 Dark skin     (115, 82,  68)
#02 Light skin    (194,150, 130)
#03 Blue sky      ( 98,122, 157)
#04 Foliage       ( 87,108,  67)
#05 Blue flower   (133,128, 177)
#06 Bluish green  (103,189, 170)
#07 Orange        (214,126,  44)
#08 Purplish blue ( 80, 91, 166)
#09 Moderate red  (193, 90,  99)
#10 Purple        ( 94, 60, 108)
#11 Yellow green  (157,188,  64)
#12 Orange yellow (224,163,  46)
#13 Blue          ( 56, 61, 150)
#14 Green         ( 70,148,  73)
#15 Red           (175, 54,  60)
#16 Yellow        (231,199,  31)
#17 Magenta       (187, 86, 149)
#18 Cyan          (  8,133, 161)
#19 White 9.5     (243,243, 242)
#20 Neutral 8     (200,200, 200)
#21 Neutral 6.5   (160,160, 160)
#22 Neutral 5     (122,121, 120)
#23 Neutral 3.5   ( 85, 85,  85)
#24 Black 2       ( 52, 52,  52)
```

These are **D50**, sRGB-gamma-encoded. For linear XYZ D50, decode the sRGB OETF and apply the sRGB→XYZ-D65 matrix + Bradford D65→D50.

Canonical XYZ reference (D50) for regression: stored at `assets/colour/macbeth24_xyz_d50.json`. Source: [BabelColor](https://babelcolor.com/index_htm_files/RGB%20Coordinates%20of%20the%20Macbeth%20ColorChecker.pdf).

### 1.6 Capture procedure for CCM calibration

1. Print or place physical ColorChecker in a viewing booth with stable D50 (or D65) illumination.
2. Capture a RAW frame with locked exposure, focus on chart, no saturation on white patch.
3. Apply WB using a known-neutral patch (#22 Neutral 5).
4. Sample the centre 50% of each patch (avoid edges), mean R, G, B per patch → `X_sensor`.
5. Look up patch XYZ from the stored JSON → `Y_ref`.
6. Solve (§1.2 → §1.4) → yields `CCM_L` for illuminant L.
7. Repeat under a second illuminant (e.g. A = tungsten 2856 K) → `CCM_A`.
8. Store `(CCM_A, CCM_D65)` for dual-illuminant interpolation.

---

## 2. DNG Dual-Illuminant ColorMatrix / ForwardMatrix

The DNG spec (Adobe) defines two illuminants (typically A and D65) with per-illuminant calibration matrices. Interpolation between them is required to process captures under arbitrary illuminants.

### 2.1 DNG tags

```
CalibrationIlluminant1       = 17   // A (tungsten, 2856 K)
CalibrationIlluminant2       = 21   // D65 (6500 K)
ColorMatrix1    [3×3]        // XYZ_A     → camera_native
ColorMatrix2    [3×3]        // XYZ_D65   → camera_native
ForwardMatrix1  [3×3]        // camera_native (post-WB) → XYZ_D50 (PCS) under A
ForwardMatrix2  [3×3]        // camera_native (post-WB) → XYZ_D50 (PCS) under D65
CameraCalibration1 [3×3]     // diagonal, per-unit scaling under A
CameraCalibration2 [3×3]     // diagonal, per-unit scaling under D65
AnalogBalance   [3]          // analog gain per channel
```

### 2.2 Interpolation formula

Given scene CCT `T_scene` from HyperTone:

```
α = clamp( (miredScene − miredD65) / (miredA − miredD65), 0, 1 )
  where miredX = 1e6 / CCT_X
```

Interpolate both matrices (don't interpolate ColorMatrix and then invert — interpolate and apply):

```
ForwardMatrix_scene = α · ForwardMatrix1 + (1 − α) · ForwardMatrix2
ColorMatrix_scene   = α · ColorMatrix1   + (1 − α) · ColorMatrix2    // for WB estimation only
```

### 2.3 Applying ForwardMatrix in the pipeline

ForwardMatrix is applied **after** WB gains. It expects unity-gain-balanced camera-native RGB and produces XYZ in the D50 profile-connection space (PCS):

```
// Step 1: WB gain (diagonal)
rgb_wb = diag(gainR, 1, gainB) * rgb_raw

// Step 2: Camera calibration (diagonal per-unit)
rgb_cal = CameraCalibration_scene * rgb_wb

// Step 3: Forward to XYZ_D50
xyz_d50 = ForwardMatrix_scene * rgb_cal
```

### 2.4 When you only have ColorMatrix (no ForwardMatrix)

Some older DNG profiles only supply `ColorMatrix1/2` (the inverse direction: XYZ → camera-native). Construct the forward path as:

```
CM_inv      = inverse( ColorMatrix_scene )
D_neutral   = diag( CM_inv · XYZ(neutral_point_scene) )
ForwardApprox = inverse( D_neutral )  · CM_inv  · M_CAT(D65 → D50)
```

where `M_CAT` is Bradford adaptation. This gives a working approximation but the forward matrix in the actual DNG is always preferred.

---

## 3. Chromatic Adaptation Transforms (CAT)

### 3.1 Bradford (recommended default)

Cone response matrix:

```
M_A(Bradford) = [[ 0.8951,  0.2664, -0.1614],
                  [-0.7502,  1.7135,  0.0367],
                  [ 0.0389, -0.0685,  1.0296]]

M_A^(-1) = [[ 0.9869929, -0.1470543,  0.1599627],
             [ 0.4323053,  0.5183603,  0.0492912],
             [-0.0085287,  0.0400428,  0.9684867]]
```

### 3.2 CAT02 (ICC v4 / CIECAM02)

```
M_A(CAT02) = [[ 0.7328,  0.4296, -0.1624],
               [-0.7036,  1.6975,  0.0061],
               [ 0.0030,  0.0136,  0.9834]]
```

### 3.3 General adaptation formula

Adapting an XYZ tristimulus from source white `(X_WS, Y_WS, Z_WS)` to destination white `(X_WD, Y_WD, Z_WD)`:

```
[L_S, M_S, S_S]   = M_A · [X_WS, Y_WS, Z_WS]
[L_D, M_D, S_D]   = M_A · [X_WD, Y_WD, Z_WD]

D = diag( L_D/L_S, M_D/M_S, S_D/S_S )

M_adapt = M_A^(-1) · D · M_A
```

Then: `XYZ_dest = M_adapt · XYZ_source`.

### 3.4 Common reference whites (normalised Y=1)

```
D50 : X = 0.96422, Y = 1.00000, Z = 0.82521
D65 : X = 0.95047, Y = 1.00000, Z = 1.08883
A   : X = 1.09850, Y = 1.00000, Z = 0.35585   (tungsten 2856 K)
D55 : X = 0.95682, Y = 1.00000, Z = 0.92149
E   : X = 1.00000, Y = 1.00000, Z = 1.00000   (equi-energy)
```

---

## 4. ColorLM 2.0 — Per-Zone Creative CCM

Beyond physically-accurate colour, Leica/Hasselblad colour science applies **creative per-zone CCMs** that nudge hues according to semantic class (sky → deeper blue, foliage → more saturated green, skin → protected).

### 4.1 Zone segmentation

Use the `:ai-engine` DeepLabV3+ MobileNet model (21 classes) to produce a semantic mask. Merge to 6 zones for ColorLM:

| ColorLM zone | DeepLab classes merged |
|---|---|
| Skin | person (skin-labelled sub-regions from FaceEngine) |
| Sky | sky |
| Foliage | plant, tree, grass |
| Water | water |
| Artificial Light | lamp, light-emitting pixels (luminance > 0.9 + warm chromaticity) |
| Neutral / Other | everything else |

### 4.2 Per-zone CCM

Each zone has its own `3×3` delta-CCM relative to the baseline CCM. These are authored by colourists; `:assets/colour/colorlm_profiles/*.json`:

```json
{
  "profile": "Leica Classic",
  "baseline": { /* 3x3 matrix */ },
  "zones": {
    "skin":     { "deltaCcm": [[1.02,-0.01,-0.01],[0.00,1.00,0.00],[-0.01,-0.01,1.02]],
                  "saturationBoost": 0.95,
                  "deltaE_cap": 2.0 },
    "sky":      { "deltaCcm": [[0.98,0.00,0.02],[0.00,0.99,0.01],[-0.02,0.00,1.02]],
                  "saturationBoost": 1.08 },
    "foliage":  { "deltaCcm": [[0.97,0.00,0.03],[0.00,1.05,-0.05],[0.00,0.00,1.00]],
                  "saturationBoost": 1.10 },
    "water":    { /* ... */ },
    "artificial_light": { "saturationBoost": 0.92 },
    "neutral":  { "deltaCcm": "identity" }
  }
}
```

### 4.3 Blended application

Given per-pixel class-probabilities `p_z(x,y)` (sum to 1):

```
CCM_pixel(x,y) = Σ_z  p_z(x,y) · (baseline + deltaCcm_z)
```

This is a `3×3` matrix per pixel in theory; in practice, compute it in a compute shader via `6 × 9 = 54` scalar ops per pixel, well within budget.

### 4.4 Skin ΔE*₀₀ cap (non-negotiable)

For every skin pixel, the output MUST satisfy:

```
ΔE*₀₀( skinPixelOutput , skinPixelInput ) ≤ deltaE_cap   (default 2.0)
```

If the creative zone CCM would push skin beyond this, clamp back toward the input along the gradient. Implementation in `SkinTonePreserver.kt`:

```kotlin
fun preserveSkin(input: Vec3Lab, output: Vec3Lab, cap: Double = 2.0): Vec3Lab {
    val dE = deltaE2000(input, output)
    if (dE <= cap) return output
    val t = cap / dE
    return lerpLab(input, output, t.toFloat())
}
```

---

## 5. Spectral Reconstruction — RGB → 31-Band Reflectance

### 5.1 Why spectral

For advanced scenes (mixed light, metameric failure, neon lighting), a 3-channel sensor loses information that cannot be recovered by any CCM. Spectral reconstruction predicts a full 31-band reflectance spectrum (400 nm → 700 nm, 10 nm step) from the RGB triplet, enabling:

- **Re-illumination** under any target illuminant (e.g. for DCI-P3 grading).
- **Metameric correction** for problem colours (traffic-sign cyan, certain fabrics).
- **HDR highlight reconstruction** via learned ratios across bands.

### 5.2 Architecture (LUMO spec: 3-hidden-layer MLP, 256 units)

```
Input  : (R, G, B) in linear sensor space, post-WB   (3)
H1     : Dense(256) + ReLU + LayerNorm               (3 → 256)
H2     : Dense(256) + ReLU + LayerNorm               (256 → 256)
H3     : Dense(256) + ReLU + LayerNorm               (256 → 256)
Output : Dense(31)  + Sigmoid (reflectance ∈ [0,1])  (256 → 31)
```

Quantised INT8 size: ~320 KB. TFLite delegate on GPU ~5 ms per 256×256 crop, or 2 ms via CoreML-equivalent on modern NPUs.

### 5.3 Training data

- **ICVL Hyperspectral Dataset** (31-band, 1392×1300, 200 natural scenes).
- **CAVE Multispectral** (31-band, 512×512, 32 scenes).
- **ARAD 1K** (NTIRE 2020 Challenge, 31-band, 1000 scenes).

Render synthetic RGB by integrating the reflectance spectra × known illuminant × CIE 1931 colour-matching functions × sensor spectral sensitivity (published in DNG profiles or measured).

### 5.4 Losses

```
L_total = L_spectral + λ_1 · L_perceptual + λ_2 · L_metameric

L_spectral    = MeanRelativeAbsoluteError per band
L_perceptual  = CIEDE2000 distance between rendered-from-predicted-spectrum vs reference XYZ
L_metameric   = KL divergence between predicted spectrum and prior reflectance distribution
```

`λ_1 = 0.5, λ_2 = 0.05`.

### 5.5 Use in pipeline

Spectral recon is triggered **only** in ColorLM's "extreme" mode (e.g. neon / stage lighting scene-class), not default. Cost is ~8 ms per frame on modern NPU.

```kotlin
fun spectralColorCorrection(
    rgbPatch: FloatArray,   // [H*W*3]
    sceneIlluminantSpd: FloatArray, // current scene illuminant SPD, 31 bands
    targetIlluminantSpd: FloatArray = D65_SPD,
    sensorSpectralSensitivity: Array<FloatArray>,
): FloatArray {
    val reflectance = spectralMlp.infer(rgbPatch)        // [H*W*31]
    val reIllum     = multiply(reflectance, targetIlluminantSpd)   // [H*W*31]
    val xyz         = integrateXyz(reIllum, CIE_CMF_1931)          // [H*W*3]
    return xyz
}
```

---

## 6. Gamut Mapping — CIECAM02 CUSP with Soft Knee

### 6.1 Problem

The working space (ACEScg, BT.2020, or similar wide gamut) is larger than the display gamut (Display-P3 or sRGB). Colours outside the display gamut must be mapped inward. Hard-clipping causes hue shifts and banding in oranges, deep reds, and cyans.

### 6.2 CUSP-based approach

For each colour with JCh coordinates (from CIECAM02):

1. Compute the **cusp** of the destination gamut at the colour's hue `h` — i.e., the point of maximum chroma on the gamut boundary at that hue.
2. Define a **soft-knee line** from the neutral axis through the cusp.
3. If the input chroma `C_in` exceeds a "knee start" value (e.g., 90% of cusp chroma), compress along the knee line; otherwise keep unchanged.

### 6.3 Soft-knee formula

```
C_knee  = 0.9 · C_cusp(h)     // start compression
C_max   = C_cusp(h)           // never exceed cusp

if C_in ≤ C_knee:
    C_out = C_in
else:
    t      = (C_in − C_knee) / (C_max_input − C_knee)
    C_out  = C_knee + (C_max − C_knee) · tanh(t · π/2)
```

`tanh` gives smooth rolloff asymptotic to `C_max`. J (lightness) is held constant for mild out-of-gamut colours; for extreme out-of-gamut, `J` is nudged toward the cusp's `J_cusp(h)`.

### 6.4 Pre-computed cusp table

Cusp is a function of hue angle only (for a fixed destination gamut). Precompute 360 samples (1° each):

```
val cuspTable_DisplayP3  : FloatArray(720)  // (C, J) pairs at each hue
val cuspTable_sRGB       : FloatArray(720)
```

Lookup is bilinear interpolation between adjacent hue bins. Cost: ~4 ns per pixel.

### 6.5 Dual-threshold to reduce oversaturation on already-safe colours

```
if C_in < 0.75 · C_cusp:         C_out = C_in            // safe
else if C_in < 0.9 · C_cusp:     C_out = smooth transition
else:                             C_out = C_knee + tanh rolloff (as above)
```

---

## 7. Tetrahedral 3D LUT Interpolation

3D LUTs (e.g., Leica Classic, Hasselblad Natural) store an RGB→RGB transform at a coarse grid (typically 33×33×33 or 65×65×65). Interpolation between grid points is required.

### 7.1 Why tetrahedral (not trilinear)

- Trilinear: weighted average of 8 cube corners. Cheap but introduces hue shifts along diagonals.
- Tetrahedral: the unit cube is split into 6 tetrahedra; interpolation uses only 4 cube corners. No hue distortion. Same cost once implemented.

### 7.2 Cube subdivision into 6 tetrahedra

Let `(dr, dg, db)` be the fractional position within a unit cube. The 6 cases are determined by the ordering of `(dr, dg, db)`:

| Case | Condition | Vertices (0,0,0)-indexed |
|---|---|---|
| 1 | dr ≥ dg ≥ db | 000, 100, 110, 111 |
| 2 | dr ≥ db > dg | 000, 100, 101, 111 |
| 3 | db > dr ≥ dg | 000, 001, 101, 111 |
| 4 | dg > dr ≥ db | 000, 010, 110, 111 |
| 5 | dg ≥ db > dr | 000, 010, 011, 111 |
| 6 | db > dg > dr | 000, 001, 011, 111 |

### 7.3 GLSL kernel

`shaders/lut_3d_tetra.comp`:

```glsl
#version 450
layout(local_size_x = 16, local_size_y = 16) in;

layout(set=0, binding=0) uniform sampler3D lut;     // size N x N x N (e.g., 33^3)
layout(set=0, binding=1, rgba16f) readonly  uniform image2D inImg;
layout(set=0, binding=2, rgba16f) writeonly uniform image2D outImg;
layout(push_constant) uniform P { int lutSize; } pc;

vec3 lutLookup(ivec3 coord) {
    return texelFetch(lut, coord, 0).rgb;
}

vec3 tetrahedralSample(vec3 rgb) {
    float scale = float(pc.lutSize - 1);
    vec3 p     = rgb * scale;
    ivec3 i    = ivec3(floor(p));
    vec3 d     = fract(p);

    i = clamp(i, ivec3(0), ivec3(pc.lutSize - 2));

    vec3 V000 = lutLookup(i + ivec3(0,0,0));
    vec3 V100 = lutLookup(i + ivec3(1,0,0));
    vec3 V010 = lutLookup(i + ivec3(0,1,0));
    vec3 V001 = lutLookup(i + ivec3(0,0,1));
    vec3 V110 = lutLookup(i + ivec3(1,1,0));
    vec3 V101 = lutLookup(i + ivec3(1,0,1));
    vec3 V011 = lutLookup(i + ivec3(0,1,1));
    vec3 V111 = lutLookup(i + ivec3(1,1,1));

    float dr = d.r, dg = d.g, db = d.b;
    vec3 result;

    if (dr >= dg && dg >= db) {
        result = (1.0 - dr) * V000 + (dr - dg) * V100 + (dg - db) * V110 + db * V111;
    } else if (dr >= db && db > dg) {
        result = (1.0 - dr) * V000 + (dr - db) * V100 + (db - dg) * V101 + dg * V111;
    } else if (db > dr && dr >= dg) {
        result = (1.0 - db) * V000 + (db - dr) * V001 + (dr - dg) * V101 + dg * V111;
    } else if (dg > dr && dr >= db) {
        result = (1.0 - dg) * V000 + (dg - dr) * V010 + (dr - db) * V110 + db * V111;
    } else if (dg >= db && db > dr) {
        result = (1.0 - dg) * V000 + (dg - db) * V010 + (db - dr) * V011 + dr * V111;
    } else {
        result = (1.0 - db) * V000 + (db - dg) * V001 + (dg - dr) * V011 + dr * V111;
    }

    return result;
}

void main() {
    ivec2 pix = ivec2(gl_GlobalInvocationID.xy);
    vec3 rgb = imageLoad(inImg, pix).rgb;
    rgb = clamp(rgb, 0.0, 1.0);
    vec3 mapped = tetrahedralSample(rgb);
    imageStore(outImg, pix, vec4(mapped, 1.0));
}
```

### 7.4 Dynamic LUT blending

For transitions (e.g., scene change mid-burst, user slider between two profiles), blend two LUTs before sampling:

```
V_k_blended = (1 − α) · V_k_A + α · V_k_B   for each grid vertex k
```

Better: bake the blended LUT once when `α` changes, then use the standard kernel. Avoid blending inside the kernel — doubles memory bandwidth.

---

## 8. Colour Profile Assets

Production profiles stored at `assets/colour/profiles/`:

| Profile | 3D LUT | Per-zone deltaCcm | Target use |
|---|---|---|---|
| Leica Classic | 65³ `.cube` | skin +2% warm, greens +10% saturation | Default portrait-class |
| Leica Contemporary | 33³ `.cube` | cooler shadows, bluer sky | Editorial / modern |
| Hasselblad Natural | 65³ `.cube` | skin protected ΔE≤1.5, lifted mids | Hero camera app default |
| Hasselblad X | 33³ `.cube` | extra saturation in saturated primaries | Landscape / product |
| Neutral (ACES RRT) | 65³ `.cube` | none | For DNG-first workflows |

All LUTs are authored in ACEScg linear input, ACEScg linear output (i.e., *they do not bake in the display gamma*). Gamut mapping (§6) and OETF are applied as separate downstream passes.

---

## 9. Validation Protocol

Every colour-pipeline change must pass the following unit tests before merge:

1. **Macbeth ColorChecker regression** — capture under D50, D65, A, TL84. Mean ΔE*₀₀ against reference XYZ must be within tolerance:
   - D65: mean ≤ 2.5, max ≤ 4.5
   - D50/TL84: mean ≤ 3.0, max ≤ 5.5
   - A (tungsten): mean ≤ 4.0, max ≤ 7.0
2. **Skin-protection test** — 20 portrait samples across Fitzpatrick I–VI. Every skin pixel must satisfy ΔE*₀₀(output, input) ≤ 2.0 after creative CCM.
3. **Gamut integrity test** — no output colour outside Display-P3 gamut after gamut mapping.
4. **Invertibility test** — `M_adapt^(-1) · M_adapt = I` to float precision (< 1e-5).
5. **3D LUT smoothness** — second-derivative test across LUT grid must have no discontinuities > 0.01 (post-interpolation).

---

## 10. References

1. **Adobe Systems.** (2023). *Digital Negative (DNG) Specification, Version 1.7.1.0.* Covers `ColorMatrix1/2`, `ForwardMatrix1/2`, calibration illuminants, and interpolation semantics.
2. **Hong, G., Luo, M. R., & Rhodes, P. A.** (2001). *A study of digital camera colorimetric characterisation based on polynomial modelling.* Color Research & Application, 26(1), 76–84. **Foundation for LS CCM derivation.**
3. **Finlayson, G. D., & Drew, M. S.** (1997). *Constrained least-squares regression in color spaces.* Journal of Electronic Imaging, 6(4). **Row-sum constraint for grey preservation.**
4. **Sharma, G., Wu, W., & Dalal, E. N.** (2005). *The CIEDE2000 color-difference formula: Implementation notes, supplementary test data, and mathematical observations.* Color Research & Application, 30(1), 21–30. **ΔE*₀₀ definition used for perceptual CCM and skin cap.**
5. **Lindbloom, B.** (2017). *Chromatic Adaptation* and *RGB Working Spaces.* [brucelindbloom.com](http://www.brucelindbloom.com/Eqn_ChromAdapt.html). **Canonical matrix reference.**
6. **Moroney, N., Fairchild, M. D., Hunt, R. W. G., Li, C., Luo, M. R., & Newman, T.** (2002). *The CIECAM02 Color Appearance Model.* CIC10. [repository.rit.edu](https://repository.rit.edu/cgi/viewcontent.cgi?article=1146&context=other). **Gamut mapping working space.**
7. **Arad, B., Ben-Shahar, O., Timofte, R.** (2022). *NTIRE 2022 Spectral Recovery Challenge and Data Set.* CVPR. **Dataset for spectral reconstruction.**
8. **Kang, S. B., & Anderson, J.** (1997). *An Active Multispectral Color System with an Automatic Selection of Camera and Illuminants.* **Multi-illuminant colour characterisation.**
9. **Pharr, M., Jakob, W., & Humphreys, G.** (2016). *Physically Based Rendering (3rd ed.).* Chapter 5, "Color and Radiometry". **Colour science fundamentals.**
10. **BabelColor.** *RGB Coordinates of the Macbeth ColorChecker.* [babelcolor.com](https://babelcolor.com/index_htm_files/RGB%20Coordinates%20of%20the%20Macbeth%20ColorChecker.pdf). **Reference patch XYZ values.**
