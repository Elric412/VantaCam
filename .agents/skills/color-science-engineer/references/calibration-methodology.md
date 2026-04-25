# Calibration Methodology

## The Mindset

Calibration is engineering, not artistry. Every number comes from measurement.
The look comes after calibration — never during.

---

## Phase 1: Read Factory Calibration

Camera2 provides per-sensor calibrated matrices. These are your starting point.
They are derived from the manufacturer's lab measurements of that specific sensor
with its specific color filter array and microlenses. **Use them.**

```kotlin
// Convert Camera2 Rational[9] to float[9] (row-major)
fun Rational9ToFloatArray(r: Array<Rational>): FloatArray =
    FloatArray(9) { i -> r[i].numerator.toFloat() / r[i].denominator.toFloat() }

// The CCM maps camera-native RGB to CIE XYZ. Invert to get XYZ → camera RGB.
// To use: cameraRGB_wb → [CCM] → XYZ → [M_XYZ_to_sRGB] → linear sRGB
```

---

## Phase 2: ColorChecker Measurement

**Equipment:** X-Rite ColorChecker Classic 24-patch target, controlled D65 illumination
(or a calibrated LED light source with CRI > 95, CCT = 6500K).

**Procedure:**
1. Shoot ColorChecker at correct exposure (middle gray patch ≈ 18% reflectance)
2. Capture in RAW16
3. Run only linearization + LSC + demosaic (no WB, no CCM yet)
4. Sample each patch (take 5×5 pixel average from center of patch)
5. Record as `measured[24][3]` float arrays in linear camera RGB

**Solve for optimal CCM:**

```python
import numpy as np

# measured[24][3] — linear camera RGB samples from your pipeline
# target_xyz[24][3] — reference CIE XYZ values for D65 (from X-Rite documentation)

# Solve: measured @ CCM.T = target_xyz  (least squares, minimize ΔE)
# Simple linear: CCM = lstsq(measured, target_xyz)
CCM, _, _, _ = np.linalg.lstsq(measured, target_xyz, rcond=None)

# Better: minimize ΔE2000 in Lab space (nonlinear optimization)
from scipy.optimize import minimize
def loss(ccm_flat):
    ccm = ccm_flat.reshape(3, 3)
    predicted_xyz = measured @ ccm.T
    predicted_lab = xyz_to_lab(predicted_xyz)
    return sum(delta_e_2000(predicted_lab[i], reference_lab[i]) for i in range(24))

result = minimize(loss, CCM.flatten(), method='Nelder-Mead')
optimal_CCM = result.x.reshape(3, 3)
```

**Do this for both D65 and StdA illuminants.** You now have two calibrated matrices
for CCT interpolation.

---

## Phase 3: Separate Calibration from Look

After calibration, you have a pipeline that is colorimetrically accurate.
Now apply the look matrix as a separate, clearly labeled step:

```kotlin
// Step A: Colorimetric (from calibration — do not modify for aesthetics)
val linearSRGB = applyCalibration(linearCameraRGB, wbGains, ccm_interpolated)

// Step B: Look (aesthetic deviations — tunable, clearly separated)
val styledSRGB = applyLook(linearSRGB, lookMatrix, filmicParams, hueSatConfig)
```

**Never mix calibration and look tuning in the same matrix.**
If you do, recalibrating a new device will destroy your look, and changing the look
will require recalibration. Keep them separate.

---

## Phase 4: Skin Tone Validation

**Test target:** Shoot a human subject (or a Calibrite SkinTone chart) under three illuminants:
- D65 (6500K daylight)
- StdA (2856K tungsten)
- Overcast (7500–8000K, blue sky)

**For each illuminant, measure in the output image:**
- Skin hue angle in OKLAB: should be 0.46–0.52 radians
- Skin chroma in OKLAB: should be 0.10–0.22 (not higher)
- L gradient across face: should be smooth — no color shift at any lightness

**Failure modes:**
- Hue > 0.55 under daylight → red/orange bias in CCM. Fix: reduce R gain.
- Hue < 0.40 under daylight → yellow bias. Fix: increase R gain slightly.
- Hue > 0.60 under tungsten → CCT interpolation not reaching StdA correctly.
- Chroma > 0.25 → saturation boost is leaking into skin zone. Check skin mask.

---

## Phase 5: Quality Scorecard

Run this before shipping any pipeline update:

| Test                              | Measurement              | Pass Threshold      |
|-----------------------------------|--------------------------|---------------------|
| ColorChecker avg ΔE2000 (D65)     | 24-patch average         | < 3.0               |
| ColorChecker max ΔE2000 (D65)     | Worst single patch       | < 5.5               |
| Neutral patches ΔE2000 (D65)      | Patches 19–24            | < 1.5               |
| ColorChecker avg ΔE2000 (StdA)    | 24-patch average         | < 4.0               |
| Skin hue angle (D65)              | OKLAB degrees            | 26°–30°             |
| Skin hue angle (StdA)             | OKLAB degrees            | 24°–34°             |
| Gray card ΔE2000 (D65)            | Should be neutral        | < 1.0               |
| Gray card ΔE2000 (StdA)           | Should be neutral        | < 1.5               |
| Shadow floor (black patch output) | sRGB 0–255               | 48–58               |
| 18% gray output                   | sRGB 0–255               | 103–112             |
| Highlight shoulder (white patch)  | No hard clip visible     | Gradual rolloff     |

---

## Continuous Calibration (Per-Device, Production)

For a shipping camera app that runs on many Android devices:

1. On first launch (or periodically), capture a gray card sequence across 3 exposures
2. Measure the mean of the neutral region in camera RGB
3. Compare to your target neutral (derived from factory calibration)
4. Apply a small correction matrix that brings the device to your target

This is how Hasselblad handles device-to-device variation: each unit is individually
measured and a calibration profile is stored in the camera's firmware. For a mobile
app, you can do a simplified version using Camera2's factory calibration as the baseline
and a small per-session trim correction from ambient environment estimation.
