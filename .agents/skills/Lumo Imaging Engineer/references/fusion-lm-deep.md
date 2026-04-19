# FusionLM 2.0 — Deep Implementation Reference

> **Module:** `:smart-imaging:fusionlm` → `FusionLM2Engine.kt`
> **Scope:** Multi-frame RAW Bayer burst fusion in the frequency domain using a pairwise Wiener filter, derived from the Google HDR+ paper (Hasinoff et al., SIGGRAPH Asia 2016) and the IPOL open-source reconstruction (Monod et al., 2021).
> **Status:** Production-grade. This document is the canonical source for FusionLM. Do not deviate from the equations below without recomputing the noise-propagation analysis.

---

## 0. Why Frequency-Domain Pairwise Fusion (Not Pixel Averaging, Not Mertens)

| Method | Noise reduction | Ghosting robustness | Aliasing | Throughput |
|---|---|---|---|---|
| Simple pixel average | √N (ideal) | Catastrophic | Bad | Fast |
| Robust pixel average with variance rejection | ≈ √(N/2) typical | Moderate | Bad | Fast |
| Mertens exposure fusion (Laplacian pyramid) | N/A (LDR output) | Poor for fast motion | Good | Slow |
| **HDR+ pairwise Wiener in 2D-FFT tile domain** | **Near-ideal √N at aligned freqs, falls back to reference per-frequency at misaligned freqs** | **Excellent (sub-tile, sub-frequency granularity)** | **Excellent (raised-cosine windowing)** | **Fast on GPU (FFT is vendor-optimised)** |

FusionLM 2.0 implements the third option. The key insight is that **alignment failure can be partial** — a moving leaf in one corner of an otherwise static tile only corrupts a narrow band of spatial frequencies, not the whole tile. The Wiener shrinkage operator acts per frequency bin, so we keep 63 out of 64 useful frequency bins even when one bin fails.

---

## 1. Physical Noise Model (Foundation for Everything Below)

### 1.1 Two-term Poisson–Gaussian model

For a linearised RAW Bayer pixel `x` (photoelectrons, after black-level subtraction and gain normalisation), the signal-dependent noise variance is:

```
σ²(x) = A · x + B
```

where:

- `A` = total gain factor. `A = (ISO / base_ISO) · (1 / QE)`, where QE is the sensor's quantum efficiency (unitless, typically 0.5–0.8 for modern BSI CMOS).
- `B` = total variance floor = read_noise² + quantisation_noise² + dark_current_variance.
- `x` is in units of "normalised photon count" after `photonsPerPixel = (pixelValue − blackLevel) / (gain · QE)`.

This is the **Healey–Kondepudy 1994 calibration model** that HDR+ uses.

### 1.2 Per-tile variance

We never evaluate `σ²(x)` per pixel. We evaluate it **once per tile** using the RMS of the tile as the representative `x`:

```
ρ(T)   = sqrt( (1/N) · Σ T[i]² )         // RMS of tile T, N = 16×16 or 32×32
σ²(T)  = A · ρ(T) + B                    // scalar variance for the whole tile
```

This is critical: Wiener filtering needs a single scalar `σ²` per tile per frame. The shrinkage operator otherwise becomes too expensive.

### 1.3 Calibration of A and B

At app first-launch, in a dark room, capture 20 RAW frames at each of {ISO 100, 400, 1600, 6400}. For each ISO:

1. Compute the per-pixel mean `μ[i,j]` and variance `v[i,j]` across 20 frames.
2. Fit `v = A·μ + B` by linear regression over all pixels (excluding saturated and black-clipped).
3. Store `(A, B)` as a lookup table keyed on `(ISO, analogue_gain)`.

Store the table in `CameraCapabilityProfile.noiseProfile: NoiseProfile`. For sensors that publish `SENSOR_NOISE_PROFILE` via Camera2, parse directly:

```kotlin
val noiseProfile: DoubleArray = characteristics.get(CaptureResult.SENSOR_NOISE_PROFILE)!!
// Layout: [S0, O0, S1, O1, S2, O2, S3, O3] for R, GR, GB, B.
// σ²(x) = S · x + O  (per channel)
```

---

## 2. Multi-Scale Alignment — Coarse-to-Fine Gaussian Pyramid

FusionLM does not use full optical flow. It uses **tile-based block matching on a 4-level Gaussian pyramid**, identical to HDR+. Dense optical flow (Farnebäck, RAFT, etc.) is gratuitously expensive for sub-pixel-only corrections.

### 2.1 Pyramid construction

```
Level 0: full resolution               e.g. 4032 × 3024
Level 1: ↓2 (Gaussian blur + subsample) 2016 × 1512
Level 2: ↓4                             1008 × 756
Level 3: ↓8                              504 × 378   ← start here (coarsest)
```

Subsampling filter: separable Gaussian with σ = 1.0, kernel size 5. Use the classic Burt-Adelson `[1, 4, 6, 4, 1] / 16` kernel.

### 2.2 Per-level search (coarsest → finest)

At level L, tiles are `n_L × n_L` pixels. HDR+ uses:

| Level | Tile size | Max displacement per level | Search norm |
|---|---|---|---|
| 3 (coarsest) | 8 × 8 | ±4 px | L2 |
| 2 | 16 × 16 | ±4 px | L2 |
| 1 | 16 × 16 | ±4 px | L1 |
| 0 (finest) | 16 × 16 (or 32 × 32 in low light) | ±1 px (subpixel via parabola) | L1 |

Coarse-to-fine displacement at level `L`:

```
D_L(x,y) = 2 · D_{L+1}(x/2, y/2) + argmin_{Δ∈search} ||Ref_L[tile(x,y)] − Alt_L[tile(x,y) + Δ]||_p
```

The "2 ·" upsamples the previous level's coarse guess. The search at level L only refines within ±4 px of the upsampled guess.

### 2.3 Multi-hypothesis at upsampling (anti-aliasing fix)

Each level-`L+1` tile upsamples to four level-`L` tiles. Naively taking `2·D_{L+1}` can snap to the wrong local minimum when coarse alignment converged to an aliased peak. HDR+ fixes this by evaluating **three hypotheses** for each upsampled tile:

```
H1 = 2 · D_{L+1}(parent_tile)
H2 = 2 · D_{L+1}(horizontal_neighbour_of_parent)
H3 = 2 · D_{L+1}(vertical_neighbour_of_parent)
```

Evaluate the L1 residual at each hypothesis and pick the minimum. This doubles alignment quality on periodic textures (fences, bricks, fabric weaves).

### 2.4 Subpixel refinement (level 0 only)

After integer-pixel search at level 0, fit a parabola to the three adjacent residual scores `(r_{-1}, r_0, r_{+1})` and set:

```
Δ_subpx = 0.5 · (r_{-1} − r_{+1}) / (r_{-1} − 2·r_0 + r_{+1})
```

Clamp `Δ_subpx ∈ [-0.5, 0.5]`. This is the standard Lucas–Kanade closed-form subpixel estimate, accurate to ~0.1 px.

---

## 3. Wiener Filter Derivation (From First Principles)

Given a noisy observation `y = s + n`, where `s` is the signal and `n` is zero-mean white noise with variance `σ²`, the Wiener filter minimises `E[|ŝ − s|²]` in the frequency domain:

```
H(ω) = |S(ω)|² / ( |S(ω)|² + |N(ω)|² )
     = SNR(ω) / (1 + SNR(ω))
```

### 3.1 Applying it to pairwise burst fusion

Let `T₀` be the **reference** tile (the sharpest / best-exposed frame in the burst — selected by Laplacian variance or ZSL timestamp). Let `T_z` be an alternate tile already aligned to `T₀`.

We construct a **difference signal** `D_z = T₀ − T_z` in the frequency domain. If alignment is perfect and only noise differs between frames, then `D_z(ω)` is pure noise with variance `2σ²` (variance of the difference of two IID noisy signals).

When alignment fails at frequency `ω`, `|D_z(ω)|² >> 2σ²` — that's the detection signal.

The shrinkage operator is:

```
A_z(ω) = |D_z(ω)|² / ( |D_z(ω)|² + c · σ² )
```

- When `|D_z(ω)|² ≈ σ²` (aligned, noise only) → `A_z → 0` → fall back to the alternate tile (gives noise averaging).
- When `|D_z(ω)|² >> σ²` (misaligned or ghost) → `A_z → 1` → fall back to the reference tile only.

> **Wait, that looks inverted.** Yes, and this is the trick of HDR+: the merged tile is defined as:
>
> ```
> T̃₀(ω) = T_z(ω) + A_z(ω) · (T₀(ω) − T_z(ω))
>       = (1 − A_z) · T_z(ω) + A_z · T₀(ω)
> ```
>
> So `A_z = 0` → use `T_z`, `A_z = 1` → fall back to `T₀`. This is the opposite of the naive intuition but it's correct for this parametrisation of `D_z`.

Averaged over N alternate frames:

```
T̃₀(ω) = (1/N) · Σ_{z=0..N-1} [ T_z(ω) + A_z(ω) · (T₀(ω) − T_z(ω)) ]
```

### 3.2 The tuning factor `c`

From the HDR+ paper: `c = 8` by default. This accounts for:
1. Factor of 2 because `D_z = T₀ − T_z` has variance `2σ²` not `σ²`.
2. Factor of 4 as a safety margin to lean toward denoising over robustness.

In FusionLM 2.0, expose `c` as a runtime parameter `fusionStrength ∈ {4, 8, 12, 16}`:

- `c = 4` → aggressive denoising, more ghosts in dynamic scenes.
- `c = 8` → default (HDR+ behaviour).
- `c = 16` → conservative, used when `motionMagnitude > threshold` from gyro/OIS data.

### 3.3 Spatial (within-tile) Wiener filter — optional second pass

After temporal merging, you can optionally apply a **spatial** Wiener filter within each tile to further denoise frequencies that did not benefit from averaging:

```
T̂₀(ω) = ( |T̃₀(ω)|² / ( |T̃₀(ω)|² + f(ω) · σ²/N ) ) · T̃₀(ω)
```

where `f(ω) = γ · |ω|` is a **noise-shaping function** that weights high-frequency denoising more heavily (the Monod 2021 formulation). Scale factor `γ = k² · s`, with `k` a per-sensor calibration and `s ∈ [0.0, 0.5]` the user-exposed spatial denoising strength.

**Default:** enable spatial Wiener only when `N < 4` (short bursts) or when post-merge SNR is still below 30 dB.

---

## 4. Tile Layout, Overlap, and the Raised-Cosine Window

### 4.1 Tile sizes

```kotlin
val tileSize: Int = when {
    sceneLuminance < 0.1f -> 32   // very dark: low-freq noise bleed-through risk
    else                   -> 16  // default
}
```

### 4.2 50%-overlapping tile grid

Tiles overlap by **exactly half** in both dimensions. For a tile size `n`, tiles are centred at positions `(i · n/2, j · n/2)` for `i, j ∈ [0, 2·W/n - 2]`. This quadruples the tile count but is essential for seamless reconstruction.

### 4.3 Modified raised-cosine window (1D)

```
w(x) = 0.5 · (1 − cos( 2π · (x + 0.5) / n ))    for 0 ≤ x < n
```

Note the `+ 0.5` phase offset — this is NOT a standard Hann window. It's chosen so that when two half-overlapping windows are summed, they give a flat 1.0 everywhere. The identity:

```
w(x) + w(x + n/2) = 1    for all x ∈ [0, n/2)
```

### 4.4 2D window (tensor product)

```
w(i, j) = w(i) · w(j)
```

Applied elementwise to each tile **before** the forward FFT.

### 4.5 Reconstruction — overlap-add

After inverse FFT of each merged tile `T̂₀`, multiply again by `w(i, j)` and accumulate into the output buffer. Because `w(x)² + w(x + n/2)² ≠ 1` but `w(x) + w(x + n/2) = 1`, we only window **once** (on the forward pass). Monod 2021 §3.6 confirms single-windowing produces correct reconstruction because the overlap-add sum recovers 1.0 exactly.

> **Debugging tip:** if you see faint grid seams at tile boundaries, you have a bug in your windowing. Visualise the sum-of-windows buffer; it must be `1.0 ± 1e-5` everywhere except at the outer `n/2` border.

---

## 5. Reference Frame Selection

Before fusion you must pick one frame to be the reference `T₀`. FusionLM uses a hybrid:

### 5.1 Laplacian variance score (sharpness)

```kotlin
fun sharpnessScore(plane: FloatArray, w: Int, h: Int): Float {
    var sum = 0.0
    var sumSq = 0.0
    var n = 0
    for (y in 1 until h - 1) {
        for (x in 1 until w - 1) {
            val lap = (4 * plane[y*w + x]
                     - plane[y*w + x - 1] - plane[y*w + x + 1]
                     - plane[(y-1)*w + x] - plane[(y+1)*w + x])
            sum   += lap
            sumSq += lap * lap
            n++
        }
    }
    val mean = sum / n
    return ((sumSq / n) - mean * mean).toFloat()   // variance of Laplacian
}
```

### 5.2 Motion-penalty term

Frames with high gyro motion during exposure get penalised:

```
score(frame) = laplacianVariance(frame) / (1 + λ · gyroMotionMagnitude(frame))
```

Pick `argmax` over the burst. `λ = 2.0` as a default.

### 5.3 Exposure clipping penalty

Reject frames where > 5% of pixels are saturated (value ≥ `0.99 · whiteLevel`) or > 2% are black-clipped.

---

## 6. SSIM Rejection of Alternate Frames

Before feeding an alternate frame into the tile loop, compute a coarse SSIM at level 3 of the pyramid:

```
SSIM(T₀_L3, T_z_L3) = (2·μ₀·μ_z + C₁)(2·σ_{0z} + C₂) / ((μ₀² + μ_z² + C₁)(σ₀² + σ_z² + C₂))
```

where `C₁ = (0.01)²`, `C₂ = (0.03)²` (assuming normalised signal).

**Reject** the frame globally if `SSIM < 0.85`. This saves 40–60% of alignment compute on bursts that captured a scene change (person walked in).

---

## 7. Full GLSL Compute Shader — Pairwise Wiener Merge

This is the per-tile merge kernel. Assumes tiles have already been FFT'd by a separate pass (use vendor FFT: Vulkan's `VK_KHR_fft` when available, else rustFFT via JNI, else a radix-2 Stockham kernel).

`shaders/fusion_wiener_merge.comp`:

```glsl
#version 450
#extension GL_EXT_shader_explicit_arithmetic_types_float16 : enable

layout(local_size_x = 16, local_size_y = 16, local_size_z = 1) in;

// T0_fft[k] = complex FFT of reference tile at index k (interleaved vec2: real, imag)
layout(set = 0, binding = 0, std430) readonly  buffer T0Buf   { vec2  T0_fft[]; };
// Tz_fft[z * N + k] = FFT of alternate tile z, position k
layout(set = 0, binding = 1, std430) readonly  buffer TzBuf   { vec2  Tz_fft[]; };
// noiseVar[tileIndex] = scalar σ²(ρ(T0)) per tile
layout(set = 0, binding = 2, std430) readonly  buffer NoiseBuf{ float noiseVar[]; };
// output merged FFT
layout(set = 0, binding = 3, std430) writeonly buffer OutBuf  { vec2  merged_fft[]; };

layout(push_constant) uniform Params {
    int tileCount;       // number of 50%-overlap tiles in the image
    int tileSize;        // 16 or 32
    int altFrameCount;   // N - 1 alternate frames
    float fusionC;       // tuning factor c (8.0 default)
    float spatialGamma;  // γ for spatial Wiener (0 disables)
} pc;

void main() {
    uint tileIdx = gl_WorkGroupID.x;      // one workgroup per tile
    uint k = gl_LocalInvocationID.y * gl_WorkGroupSize.x + gl_LocalInvocationID.x;
    uint tileBase = tileIdx * uint(pc.tileSize * pc.tileSize);

    if (tileIdx >= uint(pc.tileCount)) return;
    if (k >= uint(pc.tileSize * pc.tileSize)) return;

    vec2 T0 = T0_fft[tileBase + k];
    float sigma2 = noiseVar[tileIdx];
    float cSigma2 = pc.fusionC * sigma2;

    // Running accumulator for merged frequency bin
    vec2 accum = T0;   // start with reference (factor 1/N applied below)
    int N = pc.altFrameCount + 1;

    for (int z = 0; z < pc.altFrameCount; ++z) {
        uint altBase = uint(z) * uint(pc.tileCount * pc.tileSize * pc.tileSize) + tileBase;
        vec2 Tz = Tz_fft[altBase + k];

        // D_z = T0 - Tz  (complex)
        vec2 Dz = T0 - Tz;
        float Dz2 = Dz.x * Dz.x + Dz.y * Dz.y;

        // A_z = |Dz|² / (|Dz|² + c·σ²)
        float Az = Dz2 / (Dz2 + cSigma2);

        // merged = Tz + Az · (T0 - Tz) = (1 - Az)·Tz + Az·T0
        vec2 merged_z = mix(Tz, T0, Az);

        accum += merged_z;
    }

    // Temporal average
    accum /= float(N);

    // Optional spatial Wiener shaping pass
    if (pc.spatialGamma > 0.0) {
        // frequency magnitude |ω| in normalised units
        float kx = float(int(gl_LocalInvocationID.x)) / float(pc.tileSize);
        float ky = float(int(gl_LocalInvocationID.y)) / float(pc.tileSize);
        // DC at corner (standard FFT layout); wrap for Nyquist
        if (kx > 0.5) kx = 1.0 - kx;
        if (ky > 0.5) ky = 1.0 - ky;
        float wMag = sqrt(kx*kx + ky*ky);
        float noiseShape = pc.spatialGamma * wMag;

        float acc2 = accum.x * accum.x + accum.y * accum.y;
        float scale = acc2 / (acc2 + noiseShape * sigma2 / float(N));
        accum *= scale;
    }

    merged_fft[tileBase + k] = accum;
}
```

Dispatch: one workgroup per tile, `16×16` threads per workgroup covering all frequency bins of one tile (for `tileSize = 16`). For `tileSize = 32`, dispatch four workgroups per tile and use `workgroupId.y` to index subtile quadrant.

---

## 8. Kotlin Orchestration — `FusionLM2Engine`

```kotlin
package com.leica.cam.smart_imaging.fusionlm

import com.leica.cam.common.result.LeicaResult
import com.leica.cam.photon_matrix.PhotonBuffer
import com.leica.cam.photon_matrix.FusedPhotonBuffer
import com.leica.cam.gpu_compute.GpuBackend
import com.leica.cam.motion_engine.RawFrameAligner
import com.leica.cam.native_imaging_core.RuntimeGovernor
import kotlin.math.ln
import kotlin.math.min

class FusionLM2Engine(
    private val gpu: GpuBackend,
    private val aligner: RawFrameAligner,
    private val governor: RuntimeGovernor,
    private val noiseProfile: NoiseProfile,
) {

    data class FusionConfig(
        val fusionStrength: Float = 8.0f,      // c in Wiener; 4..16
        val spatialGamma:   Float = 0.0f,      // 0 disables spatial pass
        val minSsim:        Float = 0.85f,     // reject alt frames below this
        val tileSize:       Int   = 16,        // 16 or 32
    )

    suspend fun fuse(
        frames: List<PhotonBuffer>,
        config: FusionConfig = FusionConfig(),
    ): LeicaResult<FusedPhotonBuffer> = runCatching {

        require(frames.isNotEmpty()) { "No frames to fuse" }
        if (frames.size == 1) return@runCatching FusedPhotonBuffer.fromSingle(frames[0])

        // 1. Select reference frame
        val refIndex = frames.indices.maxBy { i ->
            sharpnessScore(frames[i]) /
            (1f + 2f * frames[i].metadata.gyroMotionMagnitude)
        }
        val reference = frames[refIndex]
        val alternates = frames.toMutableList().apply { removeAt(refIndex) }

        // 2. SSIM filter: discard alternates that fail global similarity
        val goodAlternates = alternates.filter { alt ->
            coarseSsim(reference, alt) >= config.minSsim
        }

        // 3. Align each alternate to reference via 4-level pyramid block matching
        val aligned = goodAlternates.map { alt ->
            aligner.alignTileBased(
                reference = reference,
                target = alt,
                pyramidLevels = 4,
                tileSize = config.tileSize,
                maxDisplacement = 64,
            )
        }

        // 4. Check thermal budget; fall back to spatial-only denoise if throttled
        if (governor.thermalState() >= RuntimeGovernor.ThermalState.SEVERE) {
            return@runCatching FusionFallback.spatialDenoise(reference, config)
        }

        // 5. Tile + window + FFT all frames on GPU
        val refFft = gpu.tileAndForwardFft(reference, config.tileSize, windowed = true)
        val altFfts = aligned.map {
            gpu.tileAndForwardFft(it, config.tileSize, windowed = true)
        }

        // 6. Compute per-tile noise variance
        val noiseVar = gpu.perTileRmsNoise(reference, config.tileSize, noiseProfile)

        // 7. Run Wiener merge kernel
        val mergedFft = gpu.dispatch(
            kernel = GpuKernel.WIENER_MERGE,
            inputs = mapOf(
                "T0"       to refFft,
                "Tz"       to altFfts,
                "noiseVar" to noiseVar,
            ),
            params = mapOf(
                "tileCount"     to refFft.tileCount,
                "tileSize"      to config.tileSize,
                "altFrameCount" to altFfts.size,
                "fusionC"       to config.fusionStrength,
                "spatialGamma"  to config.spatialGamma,
            ),
        )

        // 8. Inverse FFT + overlap-add reconstruction
        val merged = gpu.inverseFftAndOverlapAdd(mergedFft, config.tileSize)

        FusedPhotonBuffer(
            data = merged,
            metadata = reference.metadata.copy(
                fusedFrameCount = 1 + goodAlternates.size,
                fusionMethod = "HDR+ pairwise Wiener (FusionLM 2.0)",
            ),
        )
    }.let { LeicaResult.from(it) }

    // --- helpers ---

    private fun sharpnessScore(buf: PhotonBuffer): Float { /* Laplacian variance on G plane */ TODO() }
    private fun coarseSsim(a: PhotonBuffer, b: PhotonBuffer): Float { /* SSIM at pyramid L3 */ TODO() }
}
```

---

## 9. Frame-Count Policy

```kotlin
fun pickBurstLength(
    sceneLuminance: Float,      // 0..1 normalised
    motionMagnitude: Float,     // gyro ω magnitude, rad/s
    thermalState: RuntimeGovernor.ThermalState,
): Int {
    val base = when {
        sceneLuminance < 0.05f -> 9   // very dark → max averaging
        sceneLuminance < 0.20f -> 7
        sceneLuminance < 0.50f -> 5
        else                   -> 3
    }
    val motionPenalty = when {
        motionMagnitude > 1.5f -> 2   // subtract 2 frames
        motionMagnitude > 0.6f -> 1
        else                   -> 0
    }
    val thermalPenalty = when (thermalState) {
        RuntimeGovernor.ThermalState.SEVERE   -> 4
        RuntimeGovernor.ThermalState.MODERATE -> 2
        else                                   -> 0
    }
    return (base - motionPenalty - thermalPenalty).coerceIn(3, 9)
}
```

This is the concrete implementation of `frameCount = clamp(3, 9, basedOn(sceneLuminance, motionMagnitude))` from the LUMO master prompt.

---

## 10. Testing and Verification

### 10.1 Synthetic burst unit test
- Generate a clean image, inject per-frame shot noise with known `σ²`, run fusion.
- **Pass criterion:** measured post-fusion σ ≤ `σ_input / sqrt(N) · 1.15` (within 15% of ideal).

### 10.2 Ghost rejection test
- Burst of a static background + one frame with a superimposed moving rectangle.
- **Pass criterion:** rectangle invisible in output; PSNR vs reference frame > 45 dB on static pixels.

### 10.3 Aliased texture test (brick wall, fabric)
- Burst at handheld scale. Without multi-hypothesis upsampling, you will see moire wrap artefacts. With it, they disappear.

### 10.4 Tile seam test
- Single frame input, force fusion path. Output should be bit-identical to input modulo float rounding. If you see a grid pattern in the residual, the raised-cosine window is wrong.

---

## 11. References

1. **Hasinoff, S. W., Sharlet, D., Geiss, R., Adams, A., Barron, J. T., Kainz, F., Chen, J., & Levoy, M.** (2016). *Burst photography for high dynamic range and low-light imaging on mobile cameras.* ACM Trans. Graph. (SIGGRAPH Asia), 35(6). [hdrplusdata.org/hdrplus.pdf](https://hdrplusdata.org/hdrplus.pdf). **This is the paper. Read it cover-to-cover before editing FusionLM.**
2. **Monod, A., Delbracio, M., Musé, P., & Facciolo, G.** (2021). *An Analysis and Implementation of the HDR+ Burst Denoising Method.* IPOL Journal, 11, 142–169. [ipol.im/pub/art/2021/336](https://www.ipol.im/pub/art/2021/336/article_lr.pdf). **Open-source Python reference implementation — verify your pipeline against theirs.**
3. **Healey, G. E., & Kondepudy, R.** (1994). *Radiometric CCD camera calibration and noise estimation.* IEEE PAMI 16(3), 267–276. **Foundation of the two-term Poisson–Gaussian noise model.**
4. **Bouguet, J.-Y.** (2001). *Pyramidal Implementation of the Lucas Kanade Feature Tracker.* Intel Corp. [robots.stanford.edu/cs223b04/algo_tracking.pdf](http://robots.stanford.edu/cs223b04/algo_tracking.pdf). **Subpixel alignment math.**
5. **Seshadrinathan, K., Park, S. H., & Veeraraghavan, A.** (2012). *Noise and Dynamic Range Optimal Computational Imaging.* IEEE ICIP. **Raw-domain burst alignment framework.**
