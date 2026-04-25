# Pipeline Math Reference

## The Full Color Chain (Every Transition)

```
RAW (camera native, linear)
  → [Linearize: subtract black, divide by white]
  → Linear camera RGB (scene-referred)
  → [White balance: diagonal gain matrix]
  → WB-corrected camera RGB
  → [CCM (3×3, per-illuminant, interpolated by CCT)]
  → Linear XYZ D65 (scene-referred)
  → [XYZ → working space matrix]
  → Linear sRGB or Linear Display-P3 (scene-referred)
  → [OKLAB conversion — for all aesthetic operations]
  → OKLAB (L, a, b)
  → [Selective saturation, skin protection, hue ops]
  → [Filmic tone curve on L, chroma follows]
  → [Local tone mapping on L]
  → OKLAB (rendered, display-referred)
  → [OKLAB → linear sRGB]
  → Linear sRGB (display-referred)
  → [sRGB gamma encoding]
  → sRGB (display-referred, gamma-encoded) — FINAL OUTPUT
```

---

## Color Space Matrices

### Linear sRGB → CIE XYZ D65
```
M_sRGB_to_XYZ =
[ 0.4124564  0.3575761  0.1804375 ]
[ 0.2126729  0.7151522  0.0721750 ]
[ 0.0193339  0.1191920  0.9503041 ]
```

### CIE XYZ D65 → Linear sRGB
```
M_XYZ_to_sRGB =
[  3.2404542  -1.5371385  -0.4985314 ]
[ -0.9692660   1.8760108   0.0415560 ]
[  0.0556434  -0.2040259   1.0572252 ]
```

### CIE XYZ D65 → Linear Display-P3
```
M_XYZ_to_P3 =
[  2.4934970  -0.9313836  -0.4027108 ]
[ -0.8294890   1.7626641   0.0236247 ]
[  0.0358458  -0.0761724   0.9568845 ]
```

### Bradford Chromatic Adaptation D65 → D50
```
M_Bradford_D65_to_D50 =
[ 1.0478112   0.0228866  -0.0501270 ]
[ 0.0295424   0.9904844  -0.0170491 ]
[-0.0092345   0.0150436   0.7521316 ]
```

---

## OKLAB Conversion (Most Perceptually Uniform Modern Space)

### Linear sRGB → OKLAB

Step 1: Linear sRGB → LMS
```
M1 =
[ 0.4122214708  0.5363325363  0.0514459929 ]
[ 0.2119034982  0.6806995451  0.1073969566 ]
[ 0.0883024619  0.2817188376  0.6299787005 ]
```
Step 2: Apply cube root: `l' = cbrt(l)`, `m' = cbrt(m)`, `s' = cbrt(s)`

Step 3: LMS' → Lab
```
M2 =
[ 0.2104542553   0.7936177850  -0.0040720468 ]
[ 1.9779984951  -2.4285922050   0.4505937099 ]
[ 0.0259040371   0.7827717662  -0.8086757660 ]
```

### OKLAB → Linear sRGB

Step 1: Lab → LMS'
```
M2_inv =
[ 1.0000000000   0.3963377774   0.2158037573 ]
[ 1.0000000000  -0.1055613458  -0.0638541728 ]
[ 1.0000000000  -0.0894841775  -1.2914855480 ]
```
Step 2: Cube: `l = l'^3`

Step 3: LMS → Linear sRGB
```
M1_inv =
[  4.0767416621  -3.3077115913   0.2309699292 ]
[ -1.2684380046   2.6097574011  -0.3413193965 ]
[ -0.0041960863  -0.7034186147   1.7076147010 ]
```

---

## Filmic Tone Curve — Hable/Hasselblad-tuned

The "Hasselblad Film Curve" equivalent. Apply to L channel in OKLAB (not to RGB).

```
f(x) = ((x*(A*x + C*B) + D*E) / (x*(A*x + B) + D*F)) - E/F

Parameters (Leica/Hasselblad-class look):
  A = 0.22  // Shoulder strength — controls highlight compression onset
  B = 0.30  // Linear section slope — controls midtone contrast
  C = 0.10  // Toe strength — controls shadow lift (lifted blacks)
  D = 0.20
  E = 0.01  // Toe denominator — fine-tunes shadow transition
  F = 0.30  // Linear white point

White scale: W = 1.0 / f(1.0)   → apply as: output = f(input) * W

Key output values:
  f(0.00) ≈ 0.033   // Lifted blacks (film-like shadow floor)
  f(0.18) ≈ 0.407   // 18% gray maps to ~41% — open midtones
  f(0.50) ≈ 0.719   // Highlights still hold detail at 50% input
  f(1.00) = 1.000   // White point (after W normalization)

Chroma preservation when applying to L:
  chrScale = pow(newL / max(oldL, 0.001), 0.65)  // Sub-linear chroma scaling
  a_out = a_in * chrScale
  b_out = b_in * chrScale
```

---

## CCT Estimation — Robertson's Method

```
Planckian locus control points (Robertson 1968):
u_r[i], v_r[i], t_r[i] — see full table in references

Algorithm:
  1. Compute XYZ from scene sample (use white-ish region or global avg)
  2. u = 4X / (X + 15Y + 3Z)
  3. v = 6Y / (X + 15Y + 3Z)
  4. d[i] = (v - v_r[i]) - t_r[i] * (u - u_r[i])
  5. CCT = 1.0 / lerp(1.0/T[i], 1.0/T[i+1], d[i]/(d[i]-d[i+1]))
     where i is the index where d[i] and d[i+1] have opposite sign
```

Full 31-entry Robertson table: see `references/robertson_table.json`

---

## CCM Illuminant Interpolation

```
Given:
  CCM_D65  (from CameraCharacteristics.SENSOR_COLOR_TRANSFORM1, illuminant D65)
  CCM_StdA (from CameraCharacteristics.SENSOR_COLOR_TRANSFORM2, illuminant ~2856K)
  cct_scene = estimated scene CCT

t = (cct_scene - 2856) / (6504 - 2856)  // normalize to [0, 1]
t = clamp(t, 0.0, 1.0)

CCM_runtime[i] = CCM_StdA[i] * (1 - t) + CCM_D65[i] * t   // for each of 9 elements
```

To extend range (shade, overcast at 7000–9000K), extrapolate beyond D65 using the
same linear interpolation, clamped so the matrix remains numerically stable.

---

## Delta E 2000 — The Calibration Metric

Full implementation (all intermediate steps):

```
// Input: Lab1 = [L1, a1, b1], Lab2 = [L2, a2, b2]
C1 = sqrt(a1² + b1²)
C2 = sqrt(a2² + b2²)
C_bar = (C1 + C2) / 2
G = 0.5 * (1 - sqrt(C_bar^7 / (C_bar^7 + 25^7)))
a1' = a1 * (1 + G);   a2' = a2 * (1 + G)
C1' = sqrt(a1'² + b1²); C2' = sqrt(a2'² + b2²)
h1' = atan2(b1, a1') [0°, 360°]; h2' = atan2(b2, a2') [0°, 360°]

dL' = L2 - L1
dC' = C2' - C1'
dh' = h2' - h1' (adjusted for circularity)
dH' = 2 * sqrt(C1' * C2') * sin(dh'/2)

L_bar' = (L1 + L2) / 2
C_bar' = (C1' + C2') / 2
H_bar' = average hue (circular)
T = 1 - 0.17*cos(H_bar'-30°) + 0.24*cos(2*H_bar') + 0.32*cos(3*H_bar'+6°) - 0.20*cos(4*H_bar'-63°)
SL = 1 + 0.015*(L_bar'-50)² / sqrt(20+(L_bar'-50)²)
SC = 1 + 0.045*C_bar'
SH = 1 + 0.015*C_bar'*T
RC = 2 * sqrt(C_bar'^7 / (C_bar'^7 + 25^7))
RT = -sin(2*dTheta) * RC   where dTheta = 30*exp(-((H_bar'-275)/25)²)

dE00 = sqrt((dL'/SL)² + (dC'/SC)² + (dH'/SH)² + RT*(dC'/SC)*(dH'/SH))
```

**Acceptable thresholds for a premium camera pipeline:**
- Average ΔE2000 across ColorChecker 24 patches < 3.0
- Max ΔE2000 < 5.5
- Neutral patches (19–24) ΔE2000 < 1.5
- Skin patches (1, 2, 3) ΔE2000 < 4.0

---

## Skin Tone Hue Zone (OKLAB polar)

```
Skin hue center:  H = 0.488 rad (~27.9°)  [in OKLAB polar coords]
Protection zone:  ±0.385 rad (~22°) half-width
Chroma range:     C ∈ [0.03, 0.28]
Lightness range:  L ∈ [0.32, 0.88]

Mask strength: gaussian(dist_from_center, σ = 0.19) * chroma_mask * lightness_mask
```

---

## sRGB Gamma Encoding (IEC 61966-2-1)

```
if x <= 0.0031308:
    output = 12.92 * x
else:
    output = 1.055 * x^(1/2.4) - 0.055

Exact inverse (sRGB → linear):
if x <= 0.04045:
    linear = x / 12.92
else:
    linear = ((x + 0.055) / 1.055)^2.4
```

---

## YUV → Linear RGB (Camera2 Preview — BT.601 Full Range)

```
R = Y + 1.402   * (Cr - 0.5)
G = Y - 0.34414 * (Cb - 0.5) - 0.71414 * (Cr - 0.5)
B = Y + 1.772   * (Cb - 0.5)
```

Note: Camera2 `YUV_420_888` may be BT.601 or BT.709 depending on device.
Check `CaptureResult.COLOR_CORRECTION_TRANSFORM` and validate with a gray card.
