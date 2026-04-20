# Device Sensor Profiles — Quick Reference
# For: leica-cam-upgrade skill
# Device: MediaTek Dimensity phone with multi-camera system

## Sensor Roster (from HAL sensor identifier strings)

| Role | Manufacturer | Model | MP | Format | Pixel | Binning | Lens Variants |
|---|---|---|---|---|---|---|---|
| Main (primary) | Samsung ISOCELL | S5KHM6 (HM6) | 108 | 1/1.67" | 0.64µm | 9-in-1 Nonacell+ → 12MP | AAC, SEMCO + _cn |
| Main (secondary) | OmniVision | OV64B40 | 64 | 1/2.0" | 0.70µm | 4-cell → 16MP | OFILM + _cn |
| Main (tertiary) | OmniVision | OV50D40 | 50 | 1/2.88" | 0.612µm | 4-cell → 12.5MP | SUNNY |
| Front (primary) | OmniVision | OV16A1Q | 16 | 1/3.06" | ~0.7µm | Fixed focus | AAC, SUNNY + _cn |
| Front (secondary) | GalaxyCore | GC16B3 | 16 | 1/3.10" | 0.7µm | Fixed focus | AAC, SUNNY + _cn |
| Ultra-wide | OmniVision | OV08D10 | 8 | 1/4.0" | 1.12µm | None typical | AAC, SUNNY |
| Depth assist | SmartSens | SC202CS | 2 | compact | 1.75µm* | N/A | AAC, SUNNY, SUNNY2 + _cn |
| Macro | SmartSens | SC202PCS | 2 | compact | 1.75µm* | N/A | AAC, SUNNY |

*Estimated from sibling SC201CS product announcement

## Lens Manufacturer Codes
- `aac` = AAC Technologies (Hong Kong, major optical module maker)
- `sunny` = Sunny Optical Technology (China, major optical module maker)
- `sunny2` = Second Sunny Optical module variant (different FOV calibration)
- `ofilm` = OFILM Group (China, optical/haptic components)
- `semco` = Samsung Electro-Mechanics (Korea, lens modules)
- `_cn` = China market variant (same hardware, different HAL tuning)

## Per-Sensor Noise Model Priors (when SENSOR_NOISE_PROFILE unavailable)

| Sensor | Shot noise A | Read noise B | Notes |
|---|---|---|---|
| S5KHM6 | 3e-6 to 1.5e-5 | 8e-9 to 6e-8 | Smart-ISO Pro modifies noise model when active |
| OV64B40 | 5e-6 to 2e-5 | 3e-8 to 2e-7 | Gr/Gb split > 2DN → apply correction |
| OV50D40 | 6e-6 to 2.5e-5 | 4e-8 to 2.5e-7 | Smallest pixels — deepest stacking needed |
| OV16A1Q | 5e-6 to 2e-5 | 3e-8 to 2e-7 | OmniVision PureCel family |
| GC16B3 | 8e-6 to 3e-5 | 5e-8 to 3e-7 | GalaxyCore — higher read noise, FPN at ISO>400 |
| OV08D10 | 4e-6 to 1.5e-5 | 2e-8 to 1e-7 | Large 1.12µm pixels, better per-pixel SNR |
| SC202CS | N/A | N/A | Depth assist only — never feed to imaging pipeline |
| SC202PCS | N/A | N/A | Macro only — minimal stacking, max N=1 if motion |

## FusionLM Burst Depth Recommendations by Sensor

| Sensor | ISO ≤ 400 | ISO 401–1600 | ISO > 1600 | Notes |
|---|---|---|---|---|
| S5KHM6 (12MP Nonacell) | N = 3–5 | N = 5–7 | N = 9 | Integer-pixel alignment only in binned mode |
| OV64B40 (16MP binned) | N = 5 | N = 7 | N = 9 | Higher read noise vs ISOCELL |
| OV50D40 (12.5MP binned) | N = 5–7 | N = 9 | N = 9 + spatial Wiener | Smallest pixels → deepest stacking |
| OV08D10 (8MP) | N = 3 | N = 5 | N = 7 | UW fixed focus — motion less critical |
| SC202PCS (2MP macro) | N = 1 if motion | N = 1 if motion | N = 1 | Macro: any motion = abort stacking |

## Key Per-Sensor Corrections

### Samsung S5KHM6
- No Gr/Gb correction needed (ISOCELL 2.0 < 1DN split)
- Warm red bias at CCT < 3000K: apply R×0.97 in lamp zones
- Detect Smart-ISO Pro: if active, skip standard noise model
- Detect Staggered HDR: if row-level black level varies > 5DN, de-interleave first
- ISOCELL 2.0: reduce cross-talk correction kernel radius by 30% vs older Samsung

### OmniVision OV64B40 / OV50D40
- Gr/Gb correction: if |mean(Gr) - mean(Gb)| > 2DN → Gb_corrected = Gb + (mean_Gr - mean_Gb)
- Blue underresponse at CCT > 6000K: boost B in sky zone CCM by 1.03–1.05
- FPN: row-based correction when ISO > 800 (OV64B40) or ISO > 600 (OV50D40)
- OV50D40 ALS: consume always-on ALS signal in AdvancedMeteringEngine

### GalaxyCore GC16B3
- Higher FPN than OmniVision: row correction at ISO > 400
- No PDAF: use contrast AF (Layer 2) only
- Green tint bias 3000–4500K: in MultiModalIlluminantFusion, use weights {A:0.15, B:0.10, C:0.40, D:0.35}
- Skin detection weight: 0.40 (front camera) vs 0.25 (rear cameras)

### OmniVision OV08D10 (Ultra-wide)
- Brown-Conrady distortion model minimum 6 coefficients (k1, k2, p1, p2, k3, k4)
- 4th-order vignette compensation (not flat shading map)
- No focus distance metadata: depth from MiDaS only
- Column AND row FPN correction when ISO > 400
- CA correction kernel radius +20% vs main camera defaults
- Verify straight lines post-distortion-correction

### SmartSens SC202CS (Depth)
- Never feed to imaging pipeline (ColorLM, ToneLM, FusionLM)
- Use as coarse depth prior to initialise MiDaS
- Upsample to main camera resolution via guided filter (luminance guide)
- Per-variant lens calibration: sc202cs_aac, sc202cs_sunny, sc202cs_sunny2
- Greyscale/NIR output: no WB, CCM, or tone curve

### SmartSens SC202PCS (Macro)
- Fixed focus at 3–5cm: disable AF, disable depth estimation, disable bokeh
- Conservative sharpening: sharpenAmount = 0.3f
- High saturation micro-boost: textureSatBoost = 1.12f in ColorLM
- If motionMagnitude > 0.5f: N = 1 (single frame only)
- Diffraction-limited: avoid over-sharpening (amplifies diffraction rings)
