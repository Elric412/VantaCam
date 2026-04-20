# ProXDR HDR Engine — Deep Implementation Reference

> **Module:** `:photon-matrix:hdr` → `ProXdrOrchestrator.kt`, `HighlightReconstructionEngine.kt`, `ShadowDetailRestorer.kt` plus `:smart-imaging:tonelm:ToneLM2Engine.kt`
> **Scope:** Capture-time exposure bracket selection, ghost-free multi-frame merge, highlight reconstruction, bilateral base/detail tone mapping, and semantic priority-map-driven local tone rendering.
> **Design principle:** Scene-aware, not bracket-symmetric. Faces and foreground subjects are **prioritised** for tone allocation over sky and background.

---

## 0. Three HDR Paradigms and Why ProXDR Mixes Them

| Paradigm | Input domain | Output | Strength | Weakness |
|---|---|---|---|---|
| **Debevec radiance recovery** | Multi-exposure (known EVs) | Physically linear HDR radiance map | True luminance preserved | Requires tone mapping; slow |
| **Mertens exposure fusion** | Multi-exposure (no EVs needed) | LDR already-tone-mapped | Fast, one-shot | Not real HDR; can't be re-graded |
| **HDR+ burst with synthetic HDR** | Same-exposure burst + raw headroom | Linear RAW-domain HDR | Works at zero parallax; quiet | Limited true DR |

**ProXDR combines all three:**
1. A **same-exposure burst** goes through FusionLM (`:smart-imaging:fusionlm`) to produce a noise-reduced reference with headroom.
2. Optionally, additional **EV-offset frames** (−2, +2 EV) are merged in on top using Debevec-style radiance reconstruction, for extreme DR scenes.
3. A **Mertens-style weight map** is used as a fallback tone-rendering path when radiance reconstruction fails (e.g., clipped frames everywhere).
4. The radiance is then tone-mapped using **Durand-style bilateral base/detail decomposition** with a **semantic priority map** that modulates local exposure boost per scene-class.

---

## 1. Capture Strategy — Exposure Bracket Selection

### 1.1 NPU-predicted bracket

Before the shutter press, the preview stream feeds a lightweight regression model that outputs the optimal bracket. Model: MobileNetV3-small backbone → 3 heads (bracket_ev_low, bracket_ev_high, base_ev_offset), ≤ 200 KB INT8.

Inputs:

- 224×224 luminance histogram (pre-flattened into 256 bins)
- Scene-class one-hot (from `AiEngine.SceneClassifier`)
- Face-presence flag + largest face's mean luminance
- Highlight percentile (99.9th) and shadow percentile (0.1st) of the preview

Outputs (example): `[bracket_ev_low = -2.5, base_ev = 0.0, bracket_ev_high = +1.5]`.

### 1.2 Rule-based fallback (when NPU unavailable)

```kotlin
fun pickBracket(
    hist: LongArray,              // 256-bin histogram
    facePresent: Boolean,
    faceMeanLuma: Float,
    thermalBudget: ThermalState,
): List<Float> /* EV offsets relative to base */ {

    val p01 = percentile(hist, 0.01f)    // deep shadows
    val p99 = percentile(hist, 0.99f)    // highlights
    val dynRange = 20f * log10(p99 / p01.coerceAtLeast(1f))   // dB

    return when {
        thermalBudget == ThermalState.SEVERE -> listOf(0f)
        dynRange < 55f                       -> listOf(0f)                // single frame OK
        dynRange < 70f                       -> listOf(0f, -1.5f)         // 2-frame
        facePresent                          -> listOf(-1f, 0f, +1.5f)    // face-biased toward midtones
        dynRange < 90f                       -> listOf(-2f, 0f, +1.5f)    // 3-frame standard
        else                                 -> listOf(-4f, -2f, 0f, +2f, +4f) // extreme 5-frame
    }
}
```

### 1.3 Constraints

- Never exceed **5 EV** spread below base (sensor noise floor runs out).
- Never exceed **+4 EV** above base (base frame becomes unusable as reference due to motion blur from long exposure).
- Longest exposure in the bracket must complete within a fixed **capture budget** (default 600 ms) to keep user-perceived shutter lag acceptable.

### 1.4 ZSL integration

For the base EV frame, pull the most recent ZSL frame from the ring buffer (see `android-camera2-patterns.md` §2). For EV-offset frames, trigger explicit captures via `setRepeatingBurst` with a sequence of `CaptureRequest.Builder` objects, each with its own `SENSOR_EXPOSURE_TIME` and `SENSOR_SENSITIVITY` overrides.

---

## 2. Debevec Radiance Reconstruction (HDR Merge)

### 2.1 Response curve recovery

The camera's response function `f: E·Δt → Z` (where `Z` is the pixel value, `E` is irradiance, `Δt` is exposure time) is generally nonlinear. Debevec & Malik (1997) recover `g = ln(f^(-1))` via a sparse least-squares system:

```
minimise   Σ_{i,j}   [ w(Z_ij) · (g(Z_ij) − ln(E_i) − ln(Δt_j)) ]²
        +  λ · Σ_z   [ w(z) · g''(z) ]²
```

where:
- `i` indexes spatial pixels (typically 50–200 sampled positions)
- `j` indexes the EV bracket frames
- `w(z)` is a hat weighting: 0 at z=0 and z=255, peak at z=127
- `λ = 20` regularises for smoothness of `g`

**Computed once per sensor at calibration**, not per capture. Store as 256-entry float LUT.

### 2.2 Linear raw shortcut

For RAW Bayer input (which is what the LUMO pipeline uses), the response is already linear (by design of the sensor electronics and black-level subtraction). Skip the `g` curve recovery — use the noise-normalised raw pixel value directly as log-irradiance after dividing by `Δt` (the sensor gain/ISO is already baked into the per-frame noise model via the `A, B` parameters).

```
E_hat(x,y) = Σ_j  w(Z_ij) · (Z_ij / Δt_j)   /   Σ_j  w(Z_ij)
```

### 2.3 Modified weighting for raw burst HDR

HDR+ style weighting is per-frequency (Wiener). For EV-spread HDR, use **per-pixel trapezoidal weighting**:

```
w(z) = 1                  for 0.1 ≤ z ≤ 0.9   (safe range)
w(z) = 10 · (z − 0.0)     for 0.0 ≤ z < 0.1   (shadow ramp)
w(z) = 10 · (1.0 − z)     for 0.9 < z ≤ 1.0   (highlight ramp)
```

where z is the normalised raw pixel ∈ [0, 1] after black-level subtraction.

This weights each EV frame's contribution heavily in its "safe" tonal range and rolls off for under/over-exposed areas.

---

## 3. Ghost-Free Merging — Motion Mask

When the scene moves between EV frames, the above merge produces ghosts (moving subject duplicated at different exposures). ProXDR detects and masks.

### 3.1 Bitmap Movement Detection (Pece & Kautz, 2010)

For each alternate frame:

1. Compute a **median-threshold bitmap** (MTB): binarise at the median luminance per-frame.
2. XOR alternate's MTB with reference's MTB → per-pixel motion mask.
3. Dilate mask by 8 pixels to cover soft edges of moving objects.
4. Downsample by 2× and ignore clusters < 50 connected pixels (noise rejection).

Runtime: ~3 ms per MPix on GPU.

### 3.2 Wiener-based motion detection (preferred — already part of FusionLM)

For same-exposure frames, the Wiener shrinkage operator `A_z(ω)` from `fusion-lm-deep.md` §3.1 naturally down-weights motion regions in the frequency domain. Aggregate per-tile motion scores:

```
motionScore(tile) = Σ_ω  A_z(ω)          (high A_z = rejected = likely motion)
ghostMask = motionScore > τ_motion
```

Threshold `τ_motion = 0.35` (empirically tuned — this rejects tiles where > 35% of frequencies were driven to the reference).

### 3.3 Per-EV ghost policy

For EV-bracketed frames, motion detection is confounded by exposure difference. Apply the MTB method, not Wiener, for EV brackets. And adopt this policy:

- Reference (base EV) frame: always trusted.
- Darker frame (−EV): used only in pixels **not** in motion mask AND `Z_base ≥ 0.85` (highlight recovery).
- Brighter frame (+EV): used only in pixels **not** in motion mask AND `Z_base ≤ 0.15` (shadow recovery).

This "conservative merge" policy never introduces a ghost from a long-exposure frame into the base frame.

---

## 4. Highlight Reconstruction (HighlightReconstructionEngine)

Pixels that clip in the base frame but are well-exposed in the darker (−EV) frame should have their chromaticity reconstructed from the darker frame.

### 4.1 Clipping detection

```
clipped(x,y) = max(R, G, B) ≥ 0.98 · whiteLevel
```

Per-channel clipping flag is also tracked separately: only one channel (often the red channel in warm-lit scenes) may be clipped — in that case the other two channels still carry valid info.

### 4.2 Cross-channel ratio reconstruction

When only one channel clips, infer the clipped channel from the unclipped ones using learned ratios from the darker exposure:

```
For a pixel where R is clipped and G, B are safe:
    ratio_RG_from_dark = R_dark / G_dark
    R_reconstructed    = G_base · ratio_RG_from_dark · whitePointScale
```

This avoids the pink/magenta "highlight hallucination" from naive saturation.

### 4.3 Fully-clipped regions

If all three channels clip in the base frame:

- Replace with the darker frame's value scaled by the EV ratio: `value_reconstructed = value_dark · 2^(EV_dark − EV_base)`.
- If even the darker frame clips (extreme specular), roll the highlights off smoothly to the maximum-in-frame value using a soft-shoulder.

### 4.4 Shadow Detail Restorer (dual of §4.3)

Symmetric path: where the base frame is noise-floor-limited and the brighter frame is unclipped, use brighter frame scaled down by `2^(EV_base − EV_bright)`. Apply edge-aware denoising (`:motion-engine:MotionDeblurEngine` + bilateral filter) to the reconstructed shadow region only — don't touch the already-OK midtones.

---

## 5. Mertens Exposure Fusion — Fallback Path

For scenes where radiance reconstruction fails (e.g., all frames saturated in sky, or calibration curve invalid), fall back to Mertens fusion for a display-referred output.

### 5.1 Weight maps

Three quality measures per frame `k`:

**Contrast** (Laplacian magnitude):
```
C_k(x,y) = |∇² L_k(x,y)|                   L = luminance
```

**Saturation** (std dev across R, G, B):
```
S_k(x,y) = sqrt( ((R-μ)² + (G-μ)² + (B-μ)²) / 3 )    μ = (R+G+B)/3
```

**Well-exposedness** (Gaussian of luminance around 0.5):
```
E_k(x,y) = exp(-0.5 · (L_k(x,y) - 0.5)² / σ²)         σ = 0.2
```

Per-frame weight:
```
W_k(x,y) = (C_k)^ω_C · (S_k)^ω_S · (E_k)^ω_E
```

Default exponents `ω_C = ω_S = ω_E = 1`. Normalise across frames: `Ŵ_k = W_k / Σ W_k`.

### 5.2 Laplacian pyramid blending

Directly multiplying by `Ŵ_k` and summing causes halos. Instead:

1. Build Laplacian pyramid `L{I_k}` of each input frame.
2. Build Gaussian pyramid `G{Ŵ_k}` of each weight map.
3. At each pyramid level `l`, combine: `L{F}_l = Σ_k G{Ŵ_k}_l · L{I_k}_l`.
4. Collapse the fused Laplacian pyramid → final image.

Total pyramid depth: `log2(min(W, H)) − 2`, usually 9 for a 12 MP image.

### 5.3 When to use Mertens vs Debevec

```kotlin
fun pickHdrMode(metadata: FrameSetMetadata): HdrMode = when {
    metadata.allFramesClipped ||
    metadata.rawPathUnavailable       -> HdrMode.MERTENS_LDR
    metadata.evSpread < 0.5f          -> HdrMode.FUSION_LM_ONLY  // same-exposure
    else                              -> HdrMode.DEBEVEC_LINEAR
}
```

---

## 6. Durand Bilateral Tone Mapping

The core of ToneLM 2.0's local tone mapping. Decomposes the log-luminance into a **base** (large-scale structure) and **detail** (fine texture), compresses the base, preserves the detail.

### 6.1 Log-luminance

Work in log space to linearise multiplicative contrast:

```
L(x,y) = log2(0.01 + Y(x,y))          Y = 0.2126·R + 0.7152·G + 0.0722·B
```

The `0.01` offset prevents `log(0)`.

### 6.2 Bilateral filter

```
B_{σ_s, σ_r}(x) = (1/k_x) · Σ_{x'} f_s(||x - x'||) · f_r(|I(x) - I(x')|) · I(x')
k_x             =         Σ_{x'} f_s(||x - x'||) · f_r(|I(x) - I(x')|)
```

where `f_s` is a spatial Gaussian (σ_s = 2% of image dimension, so ~40 pixels for a 1920-wide image) and `f_r` is a range Gaussian (σ_r = 0.4 in log₂-space).

**Implementation choice:** use He's O(1) **guided filter** as a drop-in replacement. Same visual outcome, far faster on GPU.

### 6.3 Base / detail decomposition

```
base   = bilateral_filter( L )
detail = L - base
```

### 6.4 Contrast compression on base

```
base_compressed = base · (target_contrast / input_contrast) + offset
```

where:
- `input_contrast = p95(base) − p5(base)` (5th to 95th percentile range)
- `target_contrast = log2(100)` ≈ 6.64 (targets a 100:1 output display contrast)
- `offset` adjusts so `p95(base_compressed) = log2(target_display_white)`

### 6.5 Recombination

```
L_out = base_compressed + detailBoost · detail
```

`detailBoost` controls local contrast enhancement. Safe range [0.8, 1.2]. Above 1.2 creates halos; below 0.8 produces flat images.

### 6.6 Saturation correction

Converting back from log luminance to RGB:

```
Y_out   = 2^L_out
scale   = Y_out / Y_in
R_out   = (R_in / Y_in)^s · Y_out         s ∈ [0.4, 0.6]
G_out   = (G_in / Y_in)^s · Y_out
B_out   = (B_in / Y_in)^s · Y_out
```

`s < 1` desaturates slightly during tone compression (avoids the "oil painting" look of saturated mids).

---

## 7. Reinhard Extended — Global Operator (For Quick Paths)

For preview and thumbnails, the full bilateral pipeline is too expensive. Use Reinhard's **extended** global operator:

```
L_out = L_in · (1 + L_in / L_white²) / (1 + L_in)
```

where `L_white = max(L)` (or the 99.5th percentile to avoid outliers).

- If `L_in << L_white`: `L_out ≈ L_in / (1 + L_in)` (classic Reinhard, compresses smoothly).
- If `L_in ≈ L_white`: `L_out → L_in / (1 + L_white/L_white²) ≈ 1` (clean mapping to display max).
- If `L_in > L_white`: graceful overshoot (allows superwhites).

Apply in log-luminance or directly on linear Y; the latter is faster.

---

## 8. Semantic Priority Map

The single most distinctive ToneLM feature. Different scene regions receive different tone-allocation budgets.

### 8.1 Zone priority order (highest first)

```
1. Face (skin + facial features)      weight 1.00
2. Human body / foreground subject    weight 0.85
3. Product / object of interest       weight 0.70
4. Mid-ground (buildings, objects)    weight 0.50
5. Background                         weight 0.30
6. Sky                                weight 0.15
```

### 8.2 Priority-weighted luminance target

Naive tone mapping distributes contrast uniformly across the histogram. ProXDR biases toward priority regions:

```
target_luminance_budget(zone) = 0.3 + 0.5 · priority_weight(zone)
```

So a face zone targets mean luminance 0.8 (nicely lit), while sky targets 0.375 (kept darker so highlights roll off gracefully).

### 8.3 Local exposure modulation

Before tone mapping:

```
EV_local(x,y) = base_EV + (target_luminance(zone(x,y)) - observed_luminance(zone(x,y))) · clamp_scale
```

Apply as a multiplicative boost in linear space **before** bilateral decomposition. Clamp to ±1 stop to avoid creating visible lighting changes across zone boundaries.

### 8.4 Soft boundary handling

Zone boundaries from the segmentation mask are sharp; applying per-zone EV directly creates visible edges. Feather with a 16-pixel Gaussian blur **of the local-EV field** (not the image) before multiplying.

### 8.5 Example: backlit portrait

- Face zone: underexposed in base frame (luminance 0.18). Priority weight 1.0 → target 0.80 → EV_local = +2.15 (clamped to +1.0).
- Sky zone: overexposed (luminance 0.95). Priority 0.15 → target 0.375 → EV_local = −1.34 (clamped to −1.0).
- Result: face lifted ~1 stop, sky pulled ~1 stop, background preserved — exactly the Leica/Hasselblad portrait-in-harsh-backlight look.

---

## 9. Full ToneLM 2.0 Render Order

```kotlin
fun render(
    input: WbCorrectedBuffer,
    depth: DepthMap,
    sceneClass: SceneCategory,
    faces: List<Face>,
    segMask: SegmentationMask,
): TonedBuffer {

    // 1. Shadow denoising — BEFORE tone mapping (amplifying shadows amplifies noise)
    val denoised = shadowDenoise(input, sigmaFromNoiseProfile())

    // 2. Apply semantic per-zone EV (soft-feathered local exposure)
    val localExposed = applyLocalEv(denoised, segMask, priorityMap())

    // 3. Cinematic S-curve global compression
    val sCurved = applySCurve(localExposed, profile = SCurveProfile.CINEMATIC_DEFAULT)

    // 4. Durand bilateral local contrast
    val locallyContrasted = durandBilateral(sCurved, detailBoost = 1.1f)

    // 5. Face-priority highlight rolloff + shadow lift (second pass, targeted)
    val facePass = faces.fold(locallyContrasted) { buf, f -> faceTonePass(buf, f) }

    // 6. Luminosity-only sharpening (Lab L-channel only)
    val sharpened = sharpenLOnly(facePass, amount = 0.5f, radius = 1.0f)

    // 7. Final gamma encode (sRGB or PQ depending on output mode)
    return gammaEncode(sharpened, targetTransfer = TransferCurve.sRGB)
}
```

### 9.1 S-Curve specification (cinematic default)

```
Segment        Input range      Output mapping                    Notes
────────────  ───────────────  ────────────────────────────────── ───────────────────
Shadows       [0.00, 0.18]     L_out = 0.02 + L_in · (0.18-0.02)/0.18   toe lift floor 0.02
Midtones      [0.18, 0.72]     L_out = 0.18 + (L_in-0.18) · 1.00        linear preservation
Highlights    [0.72, 1.00]     L_out = 0.72 + tanh((L_in-0.72)/0.28·π/2) · 0.25   soft shoulder
```

### 9.2 Face tone pass overrides

```
shadow_floor_face = 0.05    (vs 0.02 global)    — +3% lift to prevent under-eye crush
highlight_shoulder_start_face = 0.65  (vs 0.72 global)  — earlier rolloff on skin
midtone_contrast_face_scale   = 0.92              — 8% reduction to avoid harsh skin contrast
```

These are applied only within the feathered face bounding box.

---

## 10. GLSL Kernel — Durand Tone Map

`shaders/tone_durand.comp`:

```glsl
#version 450
layout(local_size_x = 16, local_size_y = 16) in;

layout(set=0, binding=0, rgba16f) readonly  uniform image2D inRgb;
layout(set=0, binding=1, r16f)    readonly  uniform image2D baseLogLum;   // pre-computed bilateral/guided
layout(set=0, binding=2, rgba16f) writeonly uniform image2D outRgb;

layout(push_constant) uniform P {
    float targetContrast;     // e.g., log2(100) = 6.64
    float basePercentileSpan; // e.g., 8.0 (log2 range of base percentiles)
    float detailBoost;        // 1.1
    float saturationExp;      // 0.5
    float offset;             // log2(display_white_norm)
} pc;

void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    vec3 rgb = imageLoad(inRgb, p).rgb;

    float Y = 0.2126 * rgb.r + 0.7152 * rgb.g + 0.0722 * rgb.b;
    float L = log2(0.01 + Y);

    float base   = imageLoad(baseLogLum, p).r;
    float detail = L - base;

    float scale = pc.targetContrast / pc.basePercentileSpan;
    float baseC = base * scale + pc.offset;

    float Lout = baseC + pc.detailBoost * detail;
    float Yout = exp2(Lout);

    // Desaturating saturation correction
    vec3 chromaticity = rgb / max(Y, 1e-4);
    vec3 rgbOut = pow(chromaticity, vec3(pc.saturationExp)) * Yout;

    imageStore(outRgb, p, vec4(rgbOut, 1.0));
}
```

---

## 11. Testing Matrix

| Test scene | Procedure | Pass criterion |
|---|---|---|
| Backlit portrait | Capture against bright window, face in shade | Face mean luminance ∈ [0.55, 0.75]; sky rolloff smooth, no banding |
| Sunset with silhouette | Extreme dynamic range, no face | Deep shadows lifted to L ≥ 0.08; sky retains orange gradient |
| Candlelight scene | Very low light + strong point source | No highlight halo > 6 px; shadow noise < -40 dB SNR |
| Fast pan (motion) | User panning between frames of 3-EV bracket | Zero visible ghosting in merged output |
| Stage lighting (neon) | RGB primaries + black background | No colour shift on lit regions; blacks remain L ≤ 0.02 |
| Snow scene | High key, wide gamut | White not clipped; subtle shadow detail retained |

Quantitative: TMQI (Tone-Mapped Image Quality Index, Yeganeh & Wang 2013) ≥ 0.85 on a 20-image internal test set.

---

## 12. References

1. **Debevec, P. E., & Malik, J.** (1997). *Recovering High Dynamic Range Radiance Maps from Photographs.* SIGGRAPH. [pauldebevec.com](https://www.pauldebevec.com/Research/HDR/debevec-siggraph97.pdf). **The foundational HDR paper.**
2. **Mertens, T., Kautz, J., & Van Reeth, F.** (2009). *Exposure Fusion: A Simple and Practical Alternative to High Dynamic Range Photography.* Computer Graphics Forum 28(1), 161–171. [web.stanford.edu](https://web.stanford.edu/class/cs231m/project-1/exposure-fusion.pdf). **Mertens fusion.**
3. **Durand, F., & Dorsey, J.** (2002). *Fast Bilateral Filtering for the Display of High-Dynamic-Range Images.* SIGGRAPH. [people.csail.mit.edu](https://people.csail.mit.edu/fredo/PUBLI/Siggraph2002/DurandBilateral.pdf). **Base/detail tone mapping.**
4. **Reinhard, E., Stark, M., Shirley, P., & Ferwerda, J.** (2002). *Photographic Tone Reproduction for Digital Images.* SIGGRAPH. **Reinhard & Reinhard Extended operators.**
5. **Pece, F., & Kautz, J.** (2010). *Bitmap Movement Detection: HDR for Dynamic Scenes.* CVMP. [jankautz.com](https://jankautz.com/publications/BMD_CVMP10.pdf). **MTB ghost detection.**
6. **Hasinoff, S. W. et al.** (2016). *Burst photography for high dynamic range and low-light imaging on mobile cameras.* SIGGRAPH Asia. **Ties HDR into same-exposure burst path.**
7. **He, K., Sun, J., & Tang, X.** (2010). *Guided Image Filtering.* ECCV. **O(1) edge-preserving filter.**
8. **Yeganeh, H., & Wang, Z.** (2013). *Objective Quality Assessment of Tone-Mapped Images.* IEEE TIP 22(2), 657–667. **TMQI metric for CI.**
9. **Grossberg, M. D., & Nayar, S. K.** (2003). *Determining the Camera Response from Images: What Is Knowable?* IEEE PAMI 25(11). **Response curve identifiability — required reading if you extend to non-RAW inputs.**
10. **Tumblin, J., & Turk, G.** (1999). *LCIS: A Boundary Hierarchy for Detail-Preserving Contrast Reduction.* SIGGRAPH. **Historical context for bilateral tone mapping.**
