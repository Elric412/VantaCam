# Leica & Hasselblad Rendering Philosophy

## What Actually Makes These Cameras Look Different

### The HNCS Framework (Hasselblad Natural Color Solution)

HNCS is not a LUT. It is a philosophy encoded in a pipeline. Key principles:

**1. Universal Profile (No Subject-Type Switching)**
Traditional cameras use different profiles for portraits vs. landscapes vs. products.
HNCS rejects this. One universal profile adapts mathematically to any illuminant and
any subject. This requires per-illuminant calibration and CCT-adaptive CCM interpolation
at every shot.

**2. The "Rubber Sheet" Model of Color Space**
Hasselblad's internal documentation describes their color manipulation as dragging
points in a color space "rubber sheet." Moving one color moves neighboring colors
too. Their solution: operate in a space where the rubber sheet is perceptually uniform
(OKLAB equivalent) and use smooth, windowed adjustments — not sharp HSL band cuts.

**3. The Hasselblad Film Curve**
Three distinct zones, all smooth:
- **Toe:** Shadows are lifted slightly. No true black except for the deepest shadows.
  This is the "Scandinavian" light quality — an airy, open feel in the shadows.
- **Latitude:** The linear section covers approximately 3–4 stops. Midtones are
  rendered with real contrast — not flat. The slope here determines how "alive"
  the image feels.
- **Shoulder:** Highlights compress asymptotically. There is always detail in the
  last stop before white. Hard clipping = failure.

**4. Skin Tone Sovereignty**
HNCS documents explicitly state that skin tones are protected across all illuminants.
The pipeline is tuned so that the transition from highlight to shadow across a face
is smooth, with no color shift, hue rotation, or saturation spike at any luminance
level. This is what produces the "creamy" quality people associate with Hasselblad.

**5. Post-Processing Optimization**
HNCS is designed so that curves adjustments in Phocus affect neutrals and do not
distort skin tones. This means the a,b channels in OKLAB/Lab are not independent
from L — they scale with L in a controlled, face-friendly way. Replicate this by
using sub-linear chroma scaling when applying the tone curve.

---

### The Leica Rendering Philosophy

**Leica L-Log and the Tonal Philosophy**
Leica's L-Log curve uses BT.2020 colorspace — they are designed from the ground
up for wide gamut and HDR mastering. Even in JPEG/standard mode, Leica's tonal
response is borrowed from this approach: wide-gamut thinking applied to SDR output.

**Microcontrast — The Real "Leica Look"**
The classic "Leica look" from film was about optical quality, not color. In digital,
Leica preserves this by:
1. No aggressive noise reduction that kills micro-detail
2. Local contrast enhancement (not edge sharpening) — "clarity" but subtle
3. Very fine gradients in the midtones — no quantization artifacts

Implement by: unsharp mask on luminance channel only, radius 1.5–2px, amount 20–30%.
Do this as the absolute last step, after all color operations.

**Leica's Color Restraint**
Unlike Japanese camera makers that boost saturation aggressively, Leica renders
colors closer to their actual appearance under the given illuminant. The colors look
"real" rather than "vivid." This is achieved by:
- Not boosting foliage/sky zones beyond +8–12%
- Keeping skin tones at or slightly below the measured chroma from a gray card calibration
- Letting the filmic curve's midtone contrast carry the perceived "punch"

**Leica Neutrals**
Leica's rendition of white and gray is extremely neutral. A white paper shot under
any illuminant after AWB should have no visible color cast. This requires:
- CCM that neutralizes sensor metamerism (not just minimizes ΔE)
- White balance that reaches the correct target white point, not just "correct enough"
- No chromatic aberration in the AWB algorithm that shifts neutrals under edge lighting

---

## Rendering Targets: What the Output Should Actually Look Like

### ColorChecker Patch Targets (D65, approximate sRGB output)

| # | Patch          | R    | G    | B    | Notes |
|---|----------------|------|------|------|-------|
| 1 | Dark Skin      | 115  | 82   | 68   | Warm, not orange-red |
| 2 | Light Skin     | 194  | 150  | 130  | Natural peach |
| 3 | Blue Sky       | 98   | 122  | 157  | Cool, not purple |
| 4 | Foliage        | 87   | 108  | 67   | Natural green, not vivid |
| 5 | Blue Flower    | 133  | 128  | 177  | Lavender-blue |
| 6 | Cyan. Green    | 103  | 189  | 170  | Clean cyan |
|19 | White          | 243  | 243  | 242  | Near-neutral (very slight warmth) |
|20 | Light Gray     | 200  | 201  | 200  | |
|23 | Dark Gray      | 89   | 90   | 90   | |
|24 | Black          | 52   | 52   | 52   | Lifted (filmic toe) |

### Key Perceptual Targets

- **Shadow floor:** ~52/255 in sRGB (film-like lifted black, not pure black)
- **18% gray:** ~107/255 in sRGB (≈42% gamma-encoded)
- **Highlight rolloff onset:** ~75% input → smooth shoulder
- **Average saturation delta:** +5–8% above colorimetric accuracy (not more)
- **Skin saturation:** At or below measured ChartA Skin patch chroma (never above)

---

## How to Tune for the Hasselblad/Leica Look — Step by Step

1. **Calibrate first, look later.** Get your ColorChecker ΔE2000 below 4.0 before
   touching any aesthetic parameter. You cannot build a "look" on top of wrong color.

2. **Set your filmic curve toe.** The black patch (patch 24) should output ~50–55/255
   in sRGB (not 0). Adjust the `E` and `C` parameters of the Hable curve until this
   is hit. This is the "lifted blacks" that gives the Hasselblad feel.

3. **Set your midtone slope.** The 18% gray card should map to 40–44% output (luminance).
   This is open and airy — not high-contrast. Adjust `B` parameter.

4. **Set your shoulder.** Shoot a scene with a bright window. The window frame should
   be visible (not clipped to white) at the boundary, with a smooth, film-like rolloff.
   Adjust `A` parameter.

5. **Tune skin tones.** Shoot a face under D65 daylight. Measure the skin patch hue
   angle in OKLAB. It should be 0.47–0.51 radians. If it drifts orange (>0.52), the CCM
   has a red bias — reduce the R gain slightly or adjust the M(0,0) element.

6. **Validate under StdA (tungsten).** The same face under tungsten should still look
   natural. Skin hue angle under tungsten will drift warmer — your CCT-interpolated
   CCM should compensate. ΔE2000 for skin under StdA should be < 5.0.

7. **Check foliage and sky.** Foliage should look natural (not neon). Sky should be
   cool blue, not purple. If foliage is oversaturated: reduce the green zone boost to
   +8% max. If sky is purple: the blue-red ratio in the CCM is off.

8. **Final microcontrast pass.** Apply luminance-only local contrast at radius 2px,
   strength 0.25. Step back. If you can *notice* the sharpening, it's too strong.
   The Leica look is the absence of any obvious sharpening while still feeling crisp.
