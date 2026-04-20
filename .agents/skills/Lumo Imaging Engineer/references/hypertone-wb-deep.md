# HyperTone WB — Deep Implementation Reference

> **Module:** `:hypertone-wb` → `HyperToneWB2Engine.kt`
> **Scope:** Zone-partitioned correlated colour temperature (CCT) estimation, multi-modal illuminant fusion, mixed-illumination spatial WB via SLIC superpixels, skin-tone anchoring, and Kalman temporal smoothing.
> **Canonical invariant:** *Skin-tone anchor is sacred.* All scene colours are balanced **relative to skin** under a D65-mapped reference, not mathematically averaged.

---

## 0. Why HyperTone is Different

Most phone WB engines treat the frame as a single patch and produce one pair of `(gainR, gainB)`. This fails catastrophically in:

- Mixed lighting (tungsten lamp + daylight window) — single gain splits the difference and everything looks sickly.
- Strong single-colour scenes (red rose, green grass meadow) — gray-world collapses because the scene-average chromaticity is not neutral.
- Portraits under warm indoor light — skin goes either orange (under-corrected) or green (over-corrected by gray-world).

HyperTone solves these by:

1. **Partitioned 4×4 CT sensing** — treat each tile as its own illuminant problem.
2. **Four independent illuminant estimators** fused by learned confidence weights.
3. **Spatial WB via SLIC superpixels** in mixed-light regions — different gains in different parts of the frame.
4. **Skin-tone anchor** — if a face is detected, the WB gain is biased so skin chromaticity matches the ITU-R BT.2100 reference skin-under-D65.
5. **Kalman temporal smoothing** — eliminates frame-to-frame WB flicker in video and preview.

---

## 1. Chromaticity Space — Preferred Coordinate System

All CCT math is done in the **CIE 1960 UCS `(u, v)` space** (not xy). The Planckian locus is smoother there and distance calculations are perceptually closer to uniform.

Conversion from xy:

```
u = 4x / (-2x + 12y + 3)
v = 6y / (-2x + 12y + 3)
```

For the modern `(u', v')` CIE 1976 variant used by IES/CIE TM-30:

```
u' = u
v' = 1.5 · v          // relation: v' = 1.5 v  (NOTE: this is 1960 → 1976 mapping)
```

> HyperTone uses **1960 `(u, v)`** for CCT because Robertson's LUT and Duv are defined there. For output storage and logging we convert to 1976 `(u', v')`.

---

## 2. CCT Estimation — Three Methods

### 2.1 McCamy's cubic approximation (fast, approximate)

Valid for CCT ∈ [2000 K, 12500 K]. Given CIE xy chromaticity:

```
n = (x − 0.3320) / (0.1858 − y)
CCT_McCamy = 437·n³ + 3601·n² + 6861·n + 5517
```

**Accuracy:** within ~50 K of the correct CCT on or near the Planckian locus. Useful for real-time preview only. **Do not use for final capture WB.**

### 2.2 Robertson 1968 (accurate, LUT-based)

Uses a **31-row isotemperature line lookup table** in `(u, v)` space covering 1/T from 0 to 600 μK⁻¹ (corresponding to ∞ K down to ~1667 K).

The LUT (excerpt, μK⁻¹, u, v, slope m):

```
μK⁻¹    u          v          m (slope of isothermal line)
  0     0.18006    0.26352    −0.24341
 10     0.18066    0.26589    −0.25479
 20     0.18133    0.26846    −0.26876
 30     0.18208    0.27119    −0.28539
 ...
600     0.38000    0.41870    −1.77430
```

Algorithm:

```kotlin
fun cctRobertson(u: Double, v: Double): Pair<Double, Double> /* (CCT, Duv) */ {
    val table = ROBERTSON_31_ROW_LUT
    var di = 0.0   // previous distance to isotherm
    for (i in 1 until 31) {
        val row = table[i]
        // signed perpendicular distance from (u,v) to the i-th isotherm
        val dj = ((v - row.v) - row.m * (u - row.u)) /
                 sqrt(1.0 + row.m * row.m)
        if (i > 0 && di * dj <= 0) {
            // crossed an isotherm — interpolate between i-1 and i
            val fraction = di / (di - dj)
            val reciprocalT = (table[i-1].mired + fraction *
                              (row.mired - table[i-1].mired))
            val cct = 1e6 / reciprocalT
            // Duv = signed perpendicular distance from locus
            val duv = dj * (1.0 - fraction)
            return cct to duv
        }
        di = dj
    }
    return Double.NaN to Double.NaN   // off-locus
}
```

**Accuracy:** < 2 K error for 2500–20000 K with Duv < 0.02.

### 2.3 Robertson 2022 (Ohno / DOE refinement)

The 2022 revision (Ohno 2014 + Smet 2023) uses triangular interpolation and a denser LUT. Achieves < 0.1 K error over the full 1500–20000 K range. Use Robertson 2022 when the input chromaticity is off-locus (`|Duv| > 0.01`) — the 1968 method degrades there.

Store both LUTs (31-row for 1968, 101-row for 2022) in `assets/colour/cct_lut_*.bin`, little-endian `float64` triples `(1/T, u, v, slope)`.

### 2.4 Duv (tint) — distance from Planckian locus

```
Duv = ±sqrt( (u - u_locus(CCT))² + (v - v_locus(CCT))² )
```

Sign convention: **+ above the locus (green tint), − below (magenta tint)**. Used as the "tint" slider counterpart to CCT.

Adobe's "Tint" value is `Duv × 3000`. FusionLM/HyperTone store raw Duv in the range `[-0.02, +0.02]`.

---

## 3. Partitioned CT Sensing — 4×4 Tile Grid

HyperTone divides the frame into a **4×4 grid (16 tiles)** and estimates an illuminant per tile.

### 3.1 Per-tile estimator (Gray World variant with edge exclusion)

```kotlin
data class TileIlluminant(
    val tileX: Int, val tileY: Int,
    val cct:  Double,
    val duv:  Double,
    val rgbGains: FloatArray,   // [rGain, gGain = 1, bGain]
    val confidence: Float,       // 0..1
)

fun estimatePerTile(buf: PhotonBuffer, tx: Int, ty: Int): TileIlluminant {
    val rows = buf.h / 4
    val cols = buf.w / 4
    val y0 = ty * rows; val y1 = y0 + rows
    val x0 = tx * cols; val x1 = x0 + cols

    // Collect gray-candidate pixels: exclude edges, skin, saturated, dark
    var sumR = 0.0; var sumG = 0.0; var sumB = 0.0; var n = 0
    for (y in y0 until y1) for (x in x0 until x1) {
        val (r, g, b) = buf.rgbAt(x, y)
        val lum = 0.2126*r + 0.7152*g + 0.0722*b
        if (lum < 0.05 || lum > 0.85) continue                 // too dark / clipped
        if (gradientMag(buf, x, y) > 30) continue              // skip edges
        if (buf.skinMask[y, x]) continue                       // skip skin
        sumR += r; sumG += g; sumB += b; n++
    }
    if (n < 50) return degenerateTile(tx, ty)

    val meanR = sumR / n; val meanG = sumG / n; val meanB = sumB / n
    val rGain = meanG / meanR; val bGain = meanG / meanB
    // Convert gains → estimated illuminant chromaticity (device-space)
    val illumXYZ = applySensorCcm(rGain, 1.0, bGain)           // D65 matrix
    val (x_cie, y_cie) = xyzToXy(illumXYZ)
    val (u, v) = xyToUv(x_cie, y_cie)
    val (cct, duv) = cctRobertson(u, v)

    return TileIlluminant(
        tileX = tx, tileY = ty,
        cct = cct, duv = duv,
        rgbGains = floatArrayOf(rGain.toFloat(), 1f, bGain.toFloat()),
        confidence = (n / ((rows * cols).toFloat())).coerceIn(0f, 1f),
    )
}
```

### 3.2 Mixed-light detection (trigger for spatial WB)

```
mixedLight = (max_CCT_tile − min_CCT_tile) > 1500 K
            || (max_Duv_tile − min_Duv_tile) > 0.010
```

If `mixedLight == true`, proceed to Section 5 (SLIC spatial WB). Otherwise use a single global CCT (weighted average of tile CCTs by confidence × tile luminance).

---

## 4. Multi-Modal Illuminant Fusion

Four parallel estimators produce illuminant candidates; their outputs are fused by confidence-weighted voting.

### 4.1 Method A — Gray World with edge & skin exclusion

Already described in §3.1 above. Fast, fails on single-colour scenes.

### 4.2 Method B — Max-RGB (white patch retinex)

```
R_illum = percentile(R, 99.5)
G_illum = percentile(G, 99.5)
B_illum = percentile(B, 99.5)
```

Robust to scenes without a neutral surface but requires specular highlights. Fails on overcast / high-key scenes.

### 4.3 Method C — Gamut mapping (Finlayson Chromagenic / Gamut-Constrained)

Project every non-saturated pixel onto the Planckian locus. Find the CCT T* minimising:

```
T* = argmin_T  Σ_p  d_perpendicular( p_uv , locus(T) )
     where the sum is over non-edge, non-skin pixels p.
```

Solve with **Brent's method** over `T ∈ [2000 K, 12500 K]`. Runtime: ~8 evaluations, ~2 ms on CPU for 16×16 decimated frame.

### 4.4 Method D — CNN ensemble (FC4-style)

Three lightweight TFLite models (each ≤ 300 KB):

- **Global model** — input 224×224, output `(logCCT, logTint, confidence)`.
- **Local model** — input 8 tiles of 112×112, output per-tile `(CCT, tint, conf)` then mean.
- **Semantic model** — input 224×224 + pre-computed semantic mask (sky/foliage/skin/artificial-light labels), output illuminant conditional on scene classes.

Each outputs its own confidence. Aggregate:

```
(CCT_D, Duv_D, conf_D) = confidence_weighted_mean(global, local, semantic)
```

Models are on GPU delegate by default, NNAPI fallback for API ≥ 29.

### 4.5 Confidence-weighted fusion

```kotlin
val baseWeights = mapOf(
    "grayWorld" to 0.15f,
    "maxRgb"    to 0.10f,
    "gamut"     to 0.25f,
    "cnn"       to 0.50f,
)

fun fuseIlluminants(
    a: GrayWorldResult, b: MaxRgbResult, c: GamutResult, d: CnnResult,
    mixedLight: Boolean,
): FusedIlluminant {
    val w = baseWeights.toMutableMap()

    // Adaptive re-weighting
    if (allWithinTolerance(a, b, c, d, toleranceK = 300.0)) {
        w["cnn"] = 0.65f           // all methods agree → trust CNN for fine-tune
    }
    if (d.confidence < 0.6f) {
        w["gamut"] = 0.40f         // CNN uncertain → lean on gamut
        w["cnn"]   = 0.25f
    }
    if (mixedLight) {
        w["cnn"]       = 0.30f    // CNN struggles with mixed light
        w["grayWorld"] = 0.05f    // GW meaningless in mixed light
        w["gamut"]     = 0.45f
        w["maxRgb"]    = 0.20f
    }

    val sum = w.values.sum()
    val weights = w.mapValues { it.value / sum }

    // Weighted arithmetic mean in mired space (1e6 / CCT) — more perceptually linear
    val miredFused =
        weights["grayWorld"]!! * (1e6 / a.cct) +
        weights["maxRgb"]!!    * (1e6 / b.cct) +
        weights["gamut"]!!     * (1e6 / c.cct) +
        weights["cnn"]!!       * (1e6 / d.cct)
    val cctFused = 1e6 / miredFused

    val duvFused = weights["grayWorld"]!! * a.duv +
                   weights["maxRgb"]!!    * b.duv +
                   weights["gamut"]!!     * c.duv +
                   weights["cnn"]!!       * d.duv

    return FusedIlluminant(cctFused, duvFused)
}
```

**Why mired space?** Perceptually, 2000K→2500K (200 mired shift) looks like the same step as 5000K→7000K (also ~200 mired). Linear averaging of Kelvin values biases toward cool illuminants.

---

## 5. SLIC Superpixel Spatial WB (Mixed-Light Path)

When `mixedLight == true`, the single global gain is insufficient. HyperTone computes a **per-superpixel gain**.

### 5.1 SLIC parameters

```kotlin
val slicK          = 400        // target number of superpixels
val slicCompactness = 20f       // balance of colour vs spatial proximity
val slicIterations = 8          // k-means iterations
```

Run the SLIC algorithm (Achanta et al., 2010) in Lab space at 1/4 preview resolution.

### 5.2 Per-superpixel illuminant

For each superpixel:

1. Collect non-edge, non-skin pixels.
2. If `|pixels| > 30` → run gray-world + gamut mapping on the superpixel → yields `(CCT_sp, Duv_sp)`.
3. If `|pixels| ≤ 30` → inherit from nearest neighbour superpixel with confidence > 0.5 (breadth-first fill).

### 5.3 Dense gain field (guided upsampling)

Superpixel CCTs are sparse. To get a per-pixel gain field without harsh boundaries:

1. Build a low-res `(CCT_map, Duv_map)` — one value per superpixel centre.
2. **Guided filter** (He et al., 2010) with the luminance of the full-res frame as guide image, radius = 40, ε = 1e-4.
3. Output: `(CCT(x,y), Duv(x,y))` dense map.

### 5.4 Skin-zone guard (per-pixel override)

Wherever `skinMask[x,y] == 1`:

```
CCT(x, y)  := blend( CCT(x, y), CCT_skinAnchor, α = 0.7 )
Duv(x, y)  := blend( Duv(x, y), Duv_skinAnchor, α = 0.7 )
```

`CCT_skinAnchor` = target CCT that maps the detected skin chromaticity to the D65-skin-reference (see §6). This prevents skin from turning green in mixed lighting where the background has heavy green cast.

---

## 6. Skin-Tone Anchor — The Sacred Invariant

### 6.1 D65 reference skin values

In **linear sRGB (D65 white point, ITU-R BT.709 primaries):**

```
Reference skin tone (medium Fitzpatrick III-IV, D65, diffuse reflection):
  R = 0.658, G = 0.475, B = 0.395        (normalised 0..1 linear)
```

In **CIE Lab:**

```
L* ≈ 72.5, a* ≈ +14.8, b* ≈ +22.4
```

These are from Leica's internal portrait-reference dataset, which aligns with BT.2100 reference skin and Pointer's Gamut skin locus.

### 6.2 Skin detection (fast path)

For preview (30 fps) use a simple YCbCr elliptical classifier:

```
inSkinGamut(Y, Cb, Cr):
    Cx = Cb - 109.38
    Cy = Cr - 152.02
    e  = (Cx*cos(2.53) + Cy*sin(2.53))² / 25.39²
       + (Cy*cos(2.53) - Cx*sin(2.53))² / 14.03²
    return e ≤ 1.0 AND Y ∈ [30, 240]
```

For capture, replace with the MediaPipe FaceMesh output's `skinZoneMask` from `:face-engine`.

### 6.3 Computing the anchor gain

Given N skin pixels `{(r_i, g_i, b_i)}` in the current frame (linear sensor space):

```
skinMeanRGB = mean({(r_i, g_i, b_i)})
targetRGB   = refSkinRGB                        // the D65 reference above

# Solve for diagonal gain (r_gain, g_gain, b_gain) that maps skinMean → target
# Constrain g_gain = 1 (unity-green convention)
r_gain = targetRGB.r * skinMeanRGB.g / (skinMeanRGB.r * targetRGB.g)
b_gain = targetRGB.b * skinMeanRGB.g / (skinMeanRGB.b * targetRGB.g)
```

The resulting gain corresponds to an illuminant CCT that we reverse-compute for logging:

```
gains → apply inverse sensor CCM → illuminant XYZ → (u,v) → Robertson → CCT_skinAnchor
```

### 6.4 Blending the anchor with global fusion

```kotlin
val faceArea = detectedFaces.sumOf { it.boundingBox.area() }.toFloat()
val frameArea = (frame.w * frame.h).toFloat()
val faceFraction = faceArea / frameArea

val anchorWeight = when {
    faceFraction < 0.02f -> 0.0f     // no meaningful face → ignore anchor
    faceFraction < 0.15f -> 0.4f     // small face in frame
    faceFraction < 0.40f -> 0.7f     // portrait-class face
    else                 -> 0.9f     // filling frame (selfie)
}

val finalCCT = lerp(fusedCCT, CCT_skinAnchor, anchorWeight)
val finalDuv = lerp(fusedDuv, Duv_skinAnchor, anchorWeight)
```

**This is the most important line in HyperTone.** If you are ever tempted to average skin-anchor with global WB by "objective" means, remember that the whole purpose of HyperTone is to produce Leica/Hasselblad-grade skin under mixed light. The anchor takes precedence, not the average.

---

## 7. Kalman Temporal Smoothing

Raw WB estimates jitter frame-to-frame due to gray-world noise and scene changes. HyperTone smooths `(CCT, Duv)` over time using a 1-D Kalman filter on each channel.

### 7.1 State model

```
state_k = [CCT_k, CCT_velocity_k]^T    (linear CV model, 2 states per channel)
```

### 7.2 Prediction step (between frames)

```
x_pred_k = F · x_{k-1}         where F = [[1, Δt], [0, 1]]
P_pred_k = F · P_{k-1} · F^T + Q
```

`Δt` = time between frames (1/30 s for preview). Process noise `Q`:

```
Q_cct = diag(25², 5²)           // 25 K/frame stddev, 5 K/s velocity drift
Q_duv = diag(0.001², 0.0002²)
```

### 7.3 Update step (with measurement)

```
y_k = measurement_k − H · x_pred_k       H = [1, 0]
S_k = H · P_pred_k · H^T + R_k           measurement noise
K_k = P_pred_k · H^T · S_k^(-1)
x_k = x_pred_k + K_k · y_k
P_k = (I − K_k · H) · P_pred_k
```

`R_k` — measurement noise — is **adaptive**:

```
R_cct(k) = sigma_cct² / fusionConfidence_k
```

So a low-confidence measurement (e.g. a nearly monochromatic scene) gets huge R, resulting in small K, resulting in "hold previous estimate."

### 7.4 Exponential moving average fallback

For code paths where a full Kalman filter is overkill (single-frame capture):

```
smoothedCCT = α · newCCT + (1 − α) · prevCCT    with α = 0.15
```

---

## 8. Kelvin → CCM Conversion

Given final `(CCT_final, Duv_final)`, convert to a `3×3` colour correction matrix `M_wb` that is applied after WB gains but before colour-science CCM (ColorLM).

### 8.1 Build illuminant XYZ from (CCT, Duv)

```kotlin
fun illuminantXyz(cct: Double, duv: Double): DoubleArray {
    val (u_loc, v_loc) = planckianUv(cct)       // point on locus
    // Perpendicular offset by Duv in (u, v) space
    val (du, dv) = perpendicularUnitVector(cct) // derivative of locus
    val u = u_loc + duv * du
    val v = v_loc + duv * dv
    val (x, y) = uvToXy(u, v)
    return doubleArrayOf(x/y, 1.0, (1-x-y)/y)    // XYZ with Y=1
}
```

### 8.2 Bradford chromatic adaptation to D65

```
M_A(Bradford) = [[ 0.8951,  0.2664, -0.1614],
                  [-0.7502,  1.7135,  0.0367],
                  [ 0.0389, -0.0685,  1.0296]]

Source_LMS      = M_A · XYZ_source
Destination_LMS = M_A · XYZ_D65
D_matrix        = diag(Dest_LMS / Source_LMS)

M_adapt = M_A^(-1) · D_matrix · M_A
```

`M_wb` = `M_adapt`. Applied as: `RGB_adapted = M_adapt · RGB_linear_sensor` (after applying per-channel gains first).

### 8.3 Dual-illuminant CCM interpolation (DNG-style)

See `colour-science-deep.md` §4 for the full DNG ForwardMatrix interpolation. The HyperTone-derived CCT is the input to that routine.

---

## 9. GLSL Shader — Per-Tile WB Application

`shaders/wb_tile.comp`:

```glsl
#version 450
layout(local_size_x = 16, local_size_y = 16) in;

layout(set = 0, binding = 0, rgba16f) readonly  uniform image2D inImage;
layout(set = 0, binding = 1, rgba16f) writeonly uniform image2D outImage;
// dense (CCT, Duv) map at 1/4 res, bilinearly sampled
layout(set = 0, binding = 2) uniform sampler2D cctMap;
layout(set = 0, binding = 3) uniform sampler2D duvMap;
layout(set = 0, binding = 4) uniform sampler2D skinMask;

layout(push_constant) uniform P {
    vec4 skinAnchorGain;     // (r, g, b, faceArea)
    float anchorWeight;
    int enableDuv;
} pc;

// Inline Bradford adaptation for a given CCT,Duv → 3x3 matrix
// (prebuild a small LUT of (CCT, Duv) -> 9-float matrix on CPU side if preferred)
mat3 wbMatrixFromCCT(float cct, float duv) {
    // ... reads from a 2D LUT texture or does Planckian math inline
    // omitted for brevity; see KelvinToCcmConverter.kt on CPU side
    return mat3(1.0); // placeholder
}

void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    vec4 src = imageLoad(inImage, p);

    vec2 uvTex = (vec2(p) + 0.5) / vec2(imageSize(inImage));
    float cct = texture(cctMap, uvTex).r;
    float duv = texture(duvMap, uvTex).r * float(pc.enableDuv);
    float skin = texture(skinMask, uvTex).r;

    mat3 Mwb = wbMatrixFromCCT(cct, duv);

    vec3 corrected = Mwb * src.rgb;

    if (skin > 0.1 && pc.anchorWeight > 0.0) {
        // blend toward skin-anchor gain in pixel space (diagonal gain)
        vec3 anchored = src.rgb * pc.skinAnchorGain.rgb;
        corrected = mix(corrected, anchored,
                        pc.anchorWeight * smoothstep(0.1, 0.7, skin));
    }

    imageStore(outImage, p, vec4(corrected, src.a));
}
```

---

## 10. Kotlin Orchestration — `HyperToneWB2Engine`

```kotlin
class HyperToneWB2Engine(
    private val ctSensor: PartitionedCTSensor,
    private val grayWorld: IlluminantEstimators.GrayWorld,
    private val maxRgb: IlluminantEstimators.MaxRgb,
    private val gamut: IlluminantEstimators.Gamut,
    private val cnn: IlluminantEstimators.Cnn,
    private val skinGuard: SkinZoneWbGuard,
    private val spatial: MixedLightSpatialWbEngine,
    private val temporal: WbTemporalMemory,
    private val ccmBuilder: KelvinToCcmConverter,
) {

    suspend fun correct(
        buf: PhotonBuffer,
        faceMesh: FaceMeshResult?,
    ): WbCorrectedBuffer = coroutineScope {

        // 1. Partitioned CT sensing (4x4 grid)
        val tiles = ctSensor.estimate(buf)

        // 2. Parallel 4-way illuminant estimation on the global frame
        val aDef = async { grayWorld.estimate(buf) }
        val bDef = async { maxRgb.estimate(buf) }
        val cDef = async { gamut.estimateBrent(buf) }
        val dDef = async { cnn.estimateEnsemble(buf) }
        val (a, b, c, d) = awaitAll(aDef, bDef, cDef, dDef)

        // 3. Detect mixed light
        val mixed = tiles.maxOf { it.cct } - tiles.minOf { it.cct } > 1500.0

        // 4. Fuse
        var fused = fuseIlluminants(a, b, c, d, mixed)

        // 5. Kalman temporal smoothing
        fused = temporal.step(fused, buf.metadata.timestampNs)

        // 6. Spatial path for mixed light, else global
        val (cctMap, duvMap) = if (mixed) {
            spatial.buildDenseMap(buf, tiles)
        } else {
            constantMap(fused.cct, fused.duv, buf.w, buf.h)
        }

        // 7. Skin anchor override
        val anchorGain = faceMesh?.let { skinGuard.computeAnchor(buf, it) }

        // 8. Build WB matrix and apply on GPU
        val wbApplied = gpu.dispatchWbShader(
            input = buf,
            cctMap = cctMap,
            duvMap = duvMap,
            skinMask = faceMesh?.skinMask,
            anchorGain = anchorGain,
            anchorWeight = anchorGain?.let { deriveWeight(faceMesh) } ?: 0f,
        )

        WbCorrectedBuffer(
            buffer = wbApplied,
            cctUsed = fused.cct,
            duvUsed = fused.duv,
            method = if (mixed) "spatial" else "global",
        )
    }
}
```

---

## 11. Testing Matrix

| Test scene | Expected behaviour | Pass criterion |
|---|---|---|
| D65 ColorChecker | Neutral patches reported within ΔE*₀₀ ≤ 1.5 | 23/24 patches pass |
| A (tungsten) ColorChecker | Same patches; CCT reported 2700–2900 K | ΔE ≤ 2.5; CCT within 150 K |
| Mixed tungsten + window | Window area and indoor area get different gains visible in output | Chromaticity split ≥ 800 K across frame |
| Portrait under sodium street lamp | Skin neutral-to-warm, *not* green-cast | skin Δa* ∈ [+8, +22], Δb* ∈ [+14, +28] at D65 reference |
| Green lawn full-frame | Gray world catastrophic; HyperTone with gamut+CNN dominant → correct | CCT within 400 K of ground truth |
| Rapid pan across lighting boundary | No flicker | per-frame CCT Δ < 200 K after Kalman |

---

## 12. References

1. **Robertson, A. R.** (1968). *Computation of correlated color temperature and distribution temperature.* JOSA 58(11), 1528–1535. **Original isotemperature LUT method.**
2. **Smet, K. A. G., & Royer, M.** (2023). *Modifications of the Robertson method for calculating correlated color temperature.* LEUKOS 20(1), 1–24. [energy.gov](https://www.energy.gov/eere/ssl/articles/modifications-robertson-method-calculating-correlated-color-temperature-improve). **Modern high-accuracy variant.**
3. **McCamy, C. S.** (1992). *Correlated color temperature as an explicit function of chromaticity coordinates.* Color Research & Application, 17(2), 142–144. **The cubic approximation.**
4. **Ohno, Y.** (2014). *Practical use and calculation of CCT and Duv.* LEUKOS 10(1), 47–55. **Duv standardisation.**
5. **Achanta, R., Shaji, A., Smith, K., Lucchi, A., Fua, P., & Süsstrunk, S.** (2012). *SLIC Superpixels Compared to State-of-the-Art Superpixel Methods.* IEEE PAMI 34(11), 2274–2282. [cs.jhu.edu](https://www.cs.jhu.edu/~ayuille1/JHUcourses/VisionAsBayesianInference2022/4/Achanta_SLIC_PAMI2012.pdf). **SLIC reference.**
6. **Hu, Y., Wang, B., & Lin, S.** (2017). *FC4: Fully Convolutional Color Constancy with Confidence-weighted Pooling.* CVPR. **Modern CNN-based illuminant estimation.**
7. **Finlayson, G. D., & Trezzi, E.** (2004). *Shades of Gray and Colour Constancy.* CIC. **Unifying view of Gray World & Max-RGB.**
8. **He, K., Sun, J., & Tang, X.** (2010). *Guided Image Filtering.* ECCV. [csail.mit.edu](https://people.csail.mit.edu/kaiming/publications/eccv10guidedfilter.pdf). **Guided-filter upsampling.**
9. **Lindbloom, B.** (2017). *Chromatic Adaptation (Bradford / CAT02 / Von Kries).* [brucelindbloom.com](http://www.brucelindbloom.com/Eqn_ChromAdapt.html). **Matrix formulation.**
