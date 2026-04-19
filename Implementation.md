Project context:
╔══════════════════════════════════════════════════════════════════════════╗
║          ANDROID ULTRA-PRO CAMERA SYSTEM — FULL ENGINEERING BRIEF        ║
║          Version 3.0 · Complete Architecture + Implementation Guide       ║
╚══════════════════════════════════════════════════════════════════════════╝

You are a world-class computational photography engineer, senior Android
systems architect, and imaging science researcher. Your task is to design,
architect, and implement a complete, production-ready Android camera
application that exceeds the imaging quality of every commercially available
smartphone camera as of 2023-2026, including Google Pixel 9 Pro, Samsung Galaxy S26 Ultra,Redmi Note 13 5G,Xiaomi 17 Ultra Leica,Vivo X300 Pro, and Sony Xperia 1 VI.
Should be optimized for all kinds of sensors from Omnivision,Samsung Isocell,Sony and Smartsens.
Also should be optimized for Mediatek,Snapdragon,Exynos.

This is not a prototype. This is a production-grade application. Every
algorithm, every pipeline stage, every API call, and every UI decision must
be justified by engineering principles, perceptual quality science, or
established computational photography research.

Read this entire document before writing a single line of code.

══════════════════════════════════════════════════════════════════════════
CHAPTER 0 — ENGINEERING PHILOSOPHY & NON-NEGOTIABLE PRINCIPLES
══════════════════════════════════════════════════════════════════════════

0.1 Guiding Design Principles
─────────────────────────────
P1. Photon-to-pixel integrity: Every processing decision must be traceable
    back to the physics of the original scene. No processing should invent
    detail that was not present in the RAW sensor data.

P2. Perceptual priority: Optimize for human visual perception, not
    mathematical metrics. A technically lower PSNR image that looks better
    to human observers is the correct output.

P3. Graceful degradation: Every feature must degrade gracefully when the
    underlying hardware capability is absent. Never crash; always fall
    back to the next-best algorithm.

P4. Determinism under identical conditions: Given the same RAW input, the
    same scene metadata, and the same user settings, the pipeline must
    produce bit-identical output every time. This is essential for testing.

P5. Latency transparency: The user must always receive visual feedback
    within 80ms of shutter press, even if full processing takes 5 seconds.

P6. Physics-first color: All color transformations must occur in a
    physically meaningful color space (linear light, wide gamut) and must
    be reversible to the original scene illuminant.

0.2 Code Quality Standards
───────────────────────────
- 100% Kotlin. No Java interop except where mandatory for C++ JNI bridges.
- All public APIs must have KDoc documentation.
- Cyclomatic complexity must not exceed 15 per function.
- All I/O operations must be on non-main threads. Zero ANRs tolerated.
- Memory leaks are build-blocking. LeakCanary must be integrated.
- All RenderScript/Vulkan kernels must have CPU fallback paths.
- Every algorithm constant (thresholds, coefficients, curve parameters)
  must be a named constant with a comment explaining its derivation.

══════════════════════════════════════════════════════════════════════════
CHAPTER 1 — PROJECT ARCHITECTURE & MODULE STRUCTURE
══════════════════════════════════════════════════════════════════════════

1.1 Module Dependency Graph
────────────────────────────
Build a Gradle (KTS) multi-module project with the following modules.
No circular dependencies are permitted.

:app
  └─ depends on: :feature:camera, :feature:gallery, :feature:settings

:feature:camera
  └─ depends on: :camera-core, :imaging-pipeline, :color-science,
                 :hypertone-wb, :ai-engine, :neural-isp, :ui-components

:feature:gallery
  └─ depends on: :imaging-pipeline, :color-science, :ui-components

:feature:settings
  └─ depends on: :camera-core, :ui-components

:camera-core
  └─ depends on: :sensor-hal, :lens-model, :common

:imaging-pipeline
  └─ depends on: :camera-core, :gpu-compute, :common

:color-science
  └─ depends on: :gpu-compute, :common

:hypertone-wb
  └─ depends on: :color-science, :ai-engine, :common

:ai-engine
  └─ depends on: :gpu-compute, :common

:neural-isp
  └─ depends on: :camera-core, :gpu-compute, :ai-engine, :common

:sensor-hal
  └─ depends on: :common
  (wraps Camera2 API; owns all CameraDevice/CameraCaptureSession logic)

:lens-model
  └─ depends on: :common
  (distortion correction, PSF modeling, CA correction, vignette)

:gpu-compute
  └─ depends on: (nothing — pure compute primitives)
  (RenderScript RS allocations, Vulkan compute pipeline, OpenGL ES 3.2
   utilities, shared GPU buffer management)

:ui-components
  └─ depends on: :common
  (Compose design system, custom Canvas views, overlays)

:common
  └─ depends on: (nothing)
  (shared models, Result types, extensions, constants, Logger)

1.2 Dependency Injection
─────────────────────────
Use Hilt for DI. Every module must expose its dependencies via a Hilt
@InstallIn(SingletonComponent::class) module. Camera session objects must
be scoped to a custom @CameraSessionScope that is created when the camera
opens and destroyed when it closes.

1.3 Build Variants
───────────────────
- debug: Full logging, LeakCanary, StrictMode enabled, no R8.
- staging: Partial R8, Firebase Performance enabled, no logging.
- release: Full R8 shrinking + obfuscation, no debug symbols in APK,
  ProGuard rules for TFLite models and RenderScript.

NDK: Use NDK r26b. Enable LTO (Link Time Optimization) for release.
ABI Filters: arm64-v8a only for release (add x86_64 for CI emulator tests).

══════════════════════════════════════════════════════════════════════════
CHAPTER 2 — SENSOR HARDWARE ABSTRACTION LAYER (:sensor-hal)
══════════════════════════════════════════════════════════════════════════

2.1 Camera Session State Machine
──────────────────────────────────
Implement a formal finite-state machine for camera session management.
States: CLOSED → OPENING → CONFIGURING → IDLE → CAPTURING →
        PROCESSING → PAUSED → CLOSING → CLOSED.

Every state transition must be logged with a timestamp, the triggering
event, and the previous state. Invalid transitions (e.g., CAPTURING →
OPENING) must throw IllegalStateException.

Implement a CameraSessionManager that:
- Opens the correct physical camera ID based on CameraSelector policy.
- Creates a CameraCaptureSession with TEMPLATE_PREVIEW for the
  preview stream and TEMPLATE_ZERO_SHUTTER_LAG for the capture stream.
- Handles CameraAccessException, CameraDisconnectedException, and
  session errors with automatic retry (max 3 retries, exponential
  backoff: 100ms, 400ms, 1600ms).
- Manages CaptureCallback priorities: analysis < preview < capture.

2.2 Multi-Camera Physical Lens Fusion
───────────────────────────────────────
Query CameraCharacteristics.LOGICAL_MULTI_CAMERA_PHYSICAL_IDS.
For each physical camera, read and store:
- LENS_INFO_AVAILABLE_FOCAL_LENGTHS
- LENS_INFO_AVAILABLE_APERTURES
- LENS_INFO_MINIMUM_FOCUS_DISTANCE
- SENSOR_INFO_PHYSICAL_SIZE
- SENSOR_INFO_PIXEL_ARRAY_SIZE
- SENSOR_INFO_ACTIVE_ARRAY_SIZE
- LENS_INTRINSIC_CALIBRATION (intrinsic matrix K)
- LENS_POSE_TRANSLATION (translation vector from reference)
- LENS_POSE_ROTATION (rotation quaternion from reference)
- LENS_DISTORTION (radial + tangential coefficients)
- LENS_RADIAL_DISTORTION (deprecated fallback)
- COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES
- SENSOR_NOISE_PROFILE (per-channel noise model coefficients)
- SENSOR_CALIBRATION_TRANSFORM1/2 (XYZ calibration transform)
- SENSOR_COLOR_TRANSFORM1/2 (color correction matrix at D50/D65)
- SENSOR_FORWARD_MATRIX1/2 (forward matrix for DNG)
- SENSOR_REFERENCE_ILLUMINANT1/2

Build a CameraCapabilityProfile data class for each physical camera
storing all of the above. This profile drives all downstream decisions.

2.3 Optimal Capture Configuration
───────────────────────────────────
At session creation, configure multiple ImageReader targets:

RAW_SENSOR reader:
  - Size: SENSOR_INFO_ACTIVE_ARRAY_SIZE (maximum)
  - MaxImages: 20 (ring buffer for zero-shutter-lag burst)
  - Usage: HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE |
           HardwareBuffer.USAGE_CPU_READ_OFTEN

JPEG / HEIC reader:
  - Size: Largest available JPEG or HEIC output size
  - MaxImages: 2 (double-buffered)
  - For HEIC: use ImageFormat.HEIC on API 30+

YUV_420_888 reader (for AI/analysis):
  - Size: 1280x960 (or nearest 4:3 equivalent)
  - MaxImages: 4 (ring buffer)

DEPTH16 reader (if DEPTH_OUTPUT supported):
  - Size: best available depth size
  - MaxImages: 3

PRIVATE reader (for preview surface):
  - Attach to SurfaceView via Surface.

Use CameraDevice.createCaptureSessionByOutputConfigurations() with
OutputConfiguration and setPhysicalCameraId() for multi-camera routing.
Enable HDR_SENSOR_MODE if CameraExtensionCharacteristics supports it.

2.4 Advanced Metering System
─────────────────────────────
Implement a 5-mode metering system:

ZONE_MATRIX (default):
  - Divide frame into 64 zones (8x8 grid).
  - Sample luminance from the YUV analysis stream at 4fps.
  - Weight zones by importance: center zones 3x, edge zones 1x,
    detected face zones 8x (using face rectangles from AF metadata).
  - Compute scene luminance as weighted average.
  - Target luminance: 0.46 (slightly under-exposed for highlights).

ZONE_CENTER:
  - Use a center 25% (by area) circular region.
  - Compute luminance from that region only.
  - Apply +0.3EV bias for backlit subjects.

ZONE_SPOT:
  - Use the current AF region (CaptureResult.CONTROL_AF_REGIONS).
  - Size: 5% of frame area, centered on AF point.
  - No bias correction (user assumes responsibility).

ZONE_HIGHLIGHT_WEIGHTED:
  - Compute luminance histogram (256 bins) from full YUV frame.
  - Find the 98th percentile luminance value.
  - Set exposure so 98th percentile maps to 95% on the output scale.
  - This protects highlights at the cost of underexposing shadows.

ZONE_SHADOW_BIASED:
  - Find 5th percentile luminance.
  - Set exposure so 5th percentile maps to 8% on output scale.
  - Used for nighttime photography.

For each mode, output a recommended CaptureRequest.CONTROL_AE_EXPOSURE_
COMPENSATION value (as an integer fraction from AE_COMPENSATION_RANGE)
and report the scene luminance, EV value, and chosen mode in a
MeteringResult data class.

Lock AE, AF, and AWB simultaneously 250ms before shutter release using
CaptureRequest.CONTROL_AF_TRIGGER = AF_TRIGGER_START followed by
CONTROL_AE_PRECAPTURE_TRIGGER = PRECAPTURE_TRIGGER_START. Wait for
CaptureResult.CONTROL_AF_STATE = AF_STATE_FOCUSED_LOCKED and
CONTROL_AE_STATE = AE_STATE_CONVERGED before allowing shutter.

2.5 Advanced Autofocus System
───────────────────────────────
Implement a hybrid AF system with four layers:

Layer 1: Hardware PDAF (Phase Detection AF)
  - Use CONTROL_AF_MODE_CONTINUOUS_PICTURE for stills.
  - Use CONTROL_AF_MODE_CONTINUOUS_VIDEO for video.
  - Read STATISTICS_AF_STATE to detect PASSIVE_FOCUSED state.
  - On PASSIVE_UNFOCUSED with face present, trigger AF immediately.

Layer 2: Contrast AF (fallback)
  - Run a Laplacian filter on the center 50% of the YUV analysis
    frame at 10fps.
  - Compute the variance of the Laplacian as a sharpness metric.
  - Use a ternary search over LENS_FOCUS_DISTANCE range to find the
    focus distance that maximizes sharpness variance.
  - Step size: 0.02 diopters per iteration.
  - Convergence criteria: variance change < 0.5% over 3 steps.

Layer 3: Neural AF Prediction
  - Train or use a pretrained TFLite model (MobileNetV2 backbone,
    regression head) that predicts the optimal focus distance from
    a 224x224 YUV patch centered on the AF target.
  - Model outputs: [focus_distance (normalized 0-1), confidence (0-1)].
  - Use neural prediction to seed the ternary search (Layer 2) within
    ±0.1 diopters of the predicted distance, reducing search time.
  - Only activate when PDAF confidence < 0.7.

Layer 4: Predictive AF (Action/Sports)
  - Maintain a FocusTrackingBuffer of the last 10 focus distance
    measurements (with timestamps).
  - Fit a Kalman filter (constant velocity motion model) to the buffer.
  - State vector: [distance, velocity]. Process noise Q = diag(0.01, 0.1).
  - Observation noise R = 0.02.
  - Predict the focus distance N frames ahead, where N = estimated
    shutter delay in frames (typically 3-5 frames at 30fps).
  - Pre-position the lens to the predicted distance before shutter.

2.6 Real-Time Sensor Diagnostics
──────────────────────────────────
On each CaptureResult, extract and log to a CircularBuffer (last 30
frames):
- SENSOR_TIMESTAMP (for frame timing jitter analysis)
- SENSOR_EXPOSURE_TIME
- SENSOR_SENSITIVITY (ISO)
- SENSOR_FRAME_DURATION
- LENS_FOCUS_DISTANCE
- LENS_APERTURE (if variable)
- CONTROL_AE_STATE, AF_STATE, AWB_STATE
- STATISTICS_FACES (count, bounds, confidence)
- SENSOR_TEMPERATURE (if available)
- REQUEST_PIPELINE_DEPTH (latency indicator)

Expose this buffer via a CameraMetadataStream (StateFlow<CaptureMetadata>)
for consumption by the UI module (histogram, waveform, level indicator).

══════════════════════════════════════════════════════════════════════════
CHAPTER 3 — NEURAL ISP (:neural-isp)
══════════════════════════════════════════════════════════════════════════

The Neural ISP is the most differentiating feature of this application.
It replaces or augments every traditional ISP stage with learned models.

3.1 Full Neural ISP Pipeline
─────────────────────────────
Implement the following pipeline, where each stage is a TFLite model:

Stage 0: Raw-to-Raw Denoising
  Model: Noise2Noise-inspired U-Net (encoder: 4 down-blocks, decoder:
  4 up-blocks with skip connections), 3.2M parameters, INT8 quantized.
  Input: Bayer RAW (RGGB pattern), packed as float32 RGGB planes,
  1/4 resolution (process in 256x256 tiles with 32px overlap).
  Conditioning: Per-pixel noise sigma map computed from SENSOR_NOISE_
  PROFILE: noise(I) = sqrt(A * I + B) where A, B are read from
  CameraCharacteristics and I is the per-pixel intensity.
  Output: Denoised Bayer RAW, same format as input.

Stage 1: Learned Demosaicing
  Model: CDM-inspired convolutional network (residual blocks after
  bilinear upsampling), 1.4M parameters, INT8 quantized.
  Input: Denoised Bayer RAW (single plane, full resolution downsampled
  to 512x512 tiles, 16px overlap).
  Output: Linear RGB at full resolution.
  Note: This replaces bilinear/AHD demosaicing. The learned model
  recovers ~0.3dB more PSNR and reduces color moiré on fine textures.

Stage 2: Color and Tone Mapping
  Model: A lightweight UNet-based "color network" (0.8M parameters,
  INT8) trained to map linear sensor RGB to display-referred sRGB,
  conditioned on the scene CCT (from HyperTone) and scene category
  (from AI engine). NOT a simple LUT—this model is spatially-aware
  and adapts color globally and locally.
  Input: Linear RGB + CCT scalar + scene_category one-hot vector.
  Output: Display-referred, gamma-encoded sRGB at full resolution.

Stage 3: Semantic Detail Enhancement
  Model: A detail enhancement CNN conditioned on the segmentation mask
  from the AI engine. Separate enhancement strengths per semantic class.
  Input: Stage 2 output + segmentation mask.
  Output: Final enhanced image.

3.2 Neural ISP vs Traditional ISP Routing
───────────────────────────────────────────
The Neural ISP is not always faster or better. Implement routing logic:

Use Neural ISP when:
  - Device SOC is Snapdragon 8 Gen 2+ or MediaTek Dimensity 9200+
    (detect via Build.SOC_MODEL or ro.hardware).
  - GPU temperature < 75°C (checked via PowerManager thermal API).
  - Image resolution ≤ 50MP.
  - Processing budget allows > 800ms for the full neural ISP pass.

Fall back to Traditional ISP when:
  - Thermal throttling detected.
  - Resolution > 50MP (neural ISP too slow/OOM).
  - Real-time video recording (latency too high).
  - User explicitly selects "Fast mode" in settings.

The pipeline must be a common interface (ImagePipelineProcessor) that
both the Neural ISP and the Traditional ISP implement, so the routing
is transparent to the caller.

3.3 Super Resolution (Neural SR)
──────────────────────────────────
Implement a 2x and 4x super-resolution module using a Real-ESRGAN-inspired
model (RRDB architecture, 3 residual blocks per RRDB, 6 RRDB blocks total,
~2.1M parameters, INT8 quantized).

Activation: Automatically applied when:
  - The user is shooting at less than full optical resolution and
    wants to output at a higher resolution (e.g., cropped telephoto).
  - The user explicitly enables it in settings.
  - Macro mode (extreme close-up, apply 2x SR to recover fine detail).

Processing: Apply in 256x256 tiles with 16px overlap. Blend tile
boundaries using a cosine window weight (smooth blend at ±8px from edge).

Quality gate: Compute BRISQUE score on the SR output. If BRISQUE > 45
(indicating over-hallucination), fall back to bicubic upscaling.

══════════════════════════════════════════════════════════════════════════
CHAPTER 4 — ADVANCED HDR ENGINE (:imaging-pipeline)
══════════════════════════════════════════════════════════════════════════

4.1 HDR Strategy Selection
───────────────────────────
Before capture, the HDR engine must select one of four strategies based
on scene analysis from the metering system and AI engine:

HDR_SINGLE: One RAW frame + local tone mapping. Use when:
  - Scene dynamic range < 7 stops.
  - Subject is moving at high velocity (motion_score > 0.7).
  - Processing budget < 1.5s.

HDR_MULTI_2: Two RAW frames (base + +2EV). Use when:
  - Scene DR ∈ [7, 10] stops.
  - Subject has moderate motion.

HDR_MULTI_9: Nine RAW frames (−4EV to +4EV in 1EV steps). Use when:
  - Scene DR > 10 stops (detected from preview histogram spread).
  - Scene is static or near-static (motion_score < 0.3).
  - Processing budget > 2.5s.

HDR_ADAPTIVE: Start with 9-frame burst, discard frames corrupted by
  motion, use remaining frames (minimum 3). This is the default mode.

Compute motion_score from the optical flow magnitude in the preview
at the time of shutter press: score = mean(|flow|) / 10.0, clamped 0..1.

4.2 RAW Burst Capture with Zero-Shutter-Lag
─────────────────────────────────────────────
Maintain a ring buffer (ImageRingBuffer) of 20 pre-captured RAW frames
at the rate of the preview frame rate. Each frame in the ring buffer:
- Is acquired via the RAW_SENSOR ImageReader.
- Has its CaptureResult stored alongside it (for metadata).
- Is held in a GPU memory allocation (HardwareBuffer) to avoid CPU copy.
- Is released from the ring buffer 3 seconds after capture if not used.

At shutter press, atomically:
1. Snapshot the current ring buffer state.
2. Issue a burst request for post-shutter frames if needed.
3. Select frames from the ring buffer based on the chosen HDR strategy.
   For HDR_MULTI_9: select frames with exposure times closest to the
   target EV offsets.
4. Tag each frame with its EV offset (computed from SENSOR_EXPOSURE_TIME
   and SENSOR_SENSITIVITY relative to the base 0EV frame).

4.3 Photon-Correct Frame Alignment
────────────────────────────────────
Alignment must happen in linear light (before gamma encoding).
Do NOT align gamma-encoded images (it introduces systematic errors
near dark areas).

Step 1: Build image pyramids.
  - Gaussian pyramid, 5 levels, each level half the previous.
  - All-in-GPU using RenderScript pyramid kernels.

Step 2: Hierarchical alignment (coarse to fine).
  - Start at level 4 (smallest, 1/16 resolution).
  - At each level, compute a per-patch alignment using normalized
    cross-correlation (NCC) over 16x16 patches with 8-pixel search.
    NCC(p, q) = (p·q) / (|p| * |q|)
  - Refine the alignment at each finer level using the previous
    level's alignment as a warm start.
  - Use a 3-level B-spline deformation field (not just rigid/affine)
    to handle local parallax and subject motion independently of the
    background. B-spline control point grid: 16x16 over full image.
  - Final alignment accuracy target: subpixel (< 0.5px residual error).

Step 3: Temporal consistency filter.
  - For each B-spline control point, compute the deformation magnitude.
  - If magnitude > 8px (significant local motion), mark that region
    as "ghost-contaminated" in a binary mask (GhostMask).
  - Dilate the GhostMask by 12px to handle mask boundary artifacts.

4.4 Robust Merging (Variance-Based)
─────────────────────────────────────
For each pixel position (x, y):

1. Collect aligned pixel values from all frames: v₁, v₂, ..., vₙ
   where each vᵢ has been normalized to the same 0EV equivalent by
   multiplying by its EV offset factor: vᵢ_norm = vᵢ / exp2(EVᵢ).

2. Compute the per-pixel weight for frame i:
   wᵢ = W_hat(vᵢ_norm) * W_ghost(x, y, i) * W_noise(vᵢ, σᵢ)

   where:
   W_hat(v) = exp(-12.5 * (v - 0.5)^2)   [Gaussian: favor mid-tones]
   W_ghost(x, y, i) = 1 - GhostMask[x,y,i]  [0 for ghost, 1 for clean]
   W_noise(v, σ) = 1 / (σ² + ε)           [inverse noise variance]
   σᵢ is computed from SENSOR_NOISE_PROFILE at pixel value vᵢ.

3. Merged value = Σ(vᵢ * wᵢ) / Σwᵢ

4. Normalize the merged result so the base (0EV) frame's histogram
   centroid is preserved (prevents systematic brightness shift).

All of the above runs as a RenderScript kernel operating on
HardwareBuffer allocations (zero-copy from Camera2 output).

4.5 Perceptual Tone Mapping
─────────────────────────────
The merged HDR image contains a high dynamic range that must be
mapped to the display range while preserving perceptual naturalness.

Implement the following tone mapping stages:

Stage A: Global Luminance Compression
  Compute a global tone curve based on the Reinhard extended operator:
    L_out = L_in * (1 + L_in / L_white²) / (1 + L_in)
  where L_white = 99th percentile luminance of the merged image.
  Apply in the luminance channel of CIELAB space (leaving a* and b*
  channels unchanged to avoid hue shifts).

Stage B: Local Contrast Amplification (Bilateral HDR)
  Decompose the luminance image into:
  - Base layer: bilateral filter (spatial sigma=40px, range sigma=0.3)
  - Detail layer: luminance / base_layer (ratio decomposition)
  Apply tone mapping only to the base layer (compress global contrast).
  Amplify the detail layer: detail_enhanced = detail^0.8
  (exponent < 1.0 boosts local contrast while compressing global DR).
  Final luminance = tone_mapped_base * detail_enhanced.

Stage C: Highlight Rolloff
  For pixels in the top 3% of luminance (near-white):
  Apply a smooth highlight rolloff using a gamma curve:
    if L > 0.85: L_out = 1 - (1 - L)^0.5 * 0.3 + 0.85
  This prevents harsh clipping and produces a film-like shoulder.

Stage D: Shadow Lift
  Apply a shadow lift to prevent pure blacks:
    L_out = max(L_in, 0.012) for L_in < 0.05
  This preserves detail in deep shadows without visible crush.

Stage E: Adaptive Contrast Enhancement (ACE)
  Compute a local contrast map using a difference-of-Gaussians (DoG)
  at two scales: sigma_fine=2px, sigma_coarse=20px.
  Blend ACE result into the final image at strength 0.25 (clamped to
  avoid halos > 1.5px at edges with contrast > 0.3).

4.6 HDR Video Pipeline
────────────────────────
For video, implement a real-time single-frame HDR tone mapping:

Use a spatial-temporal bilateral filter on the YCbCr stream:
  - Spatial sigma: 8px
  - Range sigma: 0.2 (in normalized luma)
  - Temporal alpha: 0.25 (blend 25% previous frame luma)
  - Runs in an OpenGL ES 3.2 fragment shader at 4K/60fps on Snapdragon.

Apply a real-time global tone curve stored as a 1D float texture
(1024 entries) sampled in the fragment shader.

Output: HLG (Hybrid Log-Gamma) for HDR10 displays on API 33+ devices.

══════════════════════════════════════════════════════════════════════════
CHAPTER 5 — LEICA / HASSELBLAD COLOR SCIENCE (:color-science)
══════════════════════════════════════════════════════════════════════════

5.1 Colorimetric Foundation
─────────────────────────────
All color processing must be anchored to colorimetric standards:

Reference illuminant: D65 (CIE standard for sRGB/Display P3/Rec.709).
Reference observer: CIE 1931 2° standard observer.
Working space: Linear ACEScg (AP1 primaries) for internal processing.
  · ACEScg primaries: R(0.713, 0.293), G(0.165, 0.830), B(0.128, 0.044)
  · These are wider than DCI-P3 and avoid gamut clipping during processing.

Input color transform chain:
  RAW_SENSOR (Bayer RGGB)
    → demosaic → Linear Sensor RGB (device-specific primaries)
    → [Sensor CCM from SENSOR_COLOR_TRANSFORM2 at D65] → XYZ_D65
    → [Bradford chromatic adaptation if CCT ≠ D65] → XYZ_D50
    → [D50 → ACEScg matrix] → Linear ACEScg
    → [Color science LUT + corrections] → Linear Display P3 or sRGB
    → [OETF: sRGB or HLG] → Display-referred output

Every matrix in the chain must be verified to be invertible
(det ≠ 0) at module initialization.

5.2 Spectral Color Accuracy Calibration
─────────────────────────────────────────
The sensor's spectral sensitivities are not perfectly matched to the
CIE color matching functions. This causes metamerism (colors that look
identical in real life appearing different in the photo).

Implement a spectral color calibration procedure:
- At build time, use a 24-patch ColorChecker Classic reference target.
- For each patch, the known CIE XYZ values (from spectrophotometer
  measurements) and the captured sensor RGB values are used to compute
  a 3x4 polynomial color correction matrix (CCM):
    XYZ_out = M * [R, G, B, R*G, G*B, R*B, R², G², B², 1]^T
  where M is a 3x10 matrix computed via least-squares.
- This polynomial CCM is stored per-device as a JSON asset file
  (covering major device models) and loaded at runtime.
- For unsupported devices, fall back to the 3x3 linear CCM from
  CameraCharacteristics.SENSOR_COLOR_TRANSFORM2.

5.3 3D LUT Engine (Tetrahedral Interpolation)
──────────────────────────────────────────────
Implement a 65x65x65 node 3D LUT renderer using tetrahedral interpolation.
This is more accurate than trilinear for color transformations.

Tetrahedral interpolation algorithm:
  Given input RGB coordinates (r, g, b) in [0, 64]:
    i = floor(r), j = floor(g), k = floor(b)
    dr = r - i, dg = g - j, db = b - k
    Determine which tetrahedron contains (dr, dg, db):
      if dr ≥ dg ≥ db: case 1
      if dr ≥ db > dg: case 2
      if dg > dr ≥ db: case 3
      (4 more cases for other orderings)
    Each case uses 4 vertices of the unit cube containing (dr, dg, db).
    Interpolate using barycentric weights derived from the orderings.

Implement as a Vulkan compute shader (primary path) and a RenderScript
kernel (fallback). The Vulkan version samples the LUT from a 3D texture
(VK_IMAGE_TYPE_3D) for hardware-accelerated interpolation.

Ship the following 3D LUTs as binary float16 files in assets/:

"Leica M Classic" (Inspired by Leica M-series film emulation):
  Characteristics:
    · Shadow lift: raise the black point to ~0.04 in linear light
      (corresponds to +12 on 0-255 scale). Gives "Leica's lifted blacks."
    · Highlight shoulder: custom S-curve in the top 20% of luminance.
      Gentle rolloff, not harsh clipping.
    · Skin tone hue rotation: rotate hues in the 15°–35° range (orange-
      yellow skin tones) by +2.5° toward yellow/warmth.
    · Green desaturation: reduce saturation in 90°–160° hue range by 12%.
      Gives Leica's characteristic muted but natural greens.
    · Red contrast: increase local contrast in 350°–15° hue range by 8%.
    · Overall contrast: S-curve with toe at 15%, shoulder at 85%.
    · Luminance in LAB: L_out = 1.04 * L_in - 0.005 * L_in² (very subtle).

"Hasselblad Natural" (Inspired by HNCS — Hasselblad Natural Colour Solution):
  Characteristics:
    · Skin accuracy: the primary objective. Minimize ΔE₀₀ for skin tones.
      Skin hues are mapped to the Munsell 2.5YR–5YR axis.
    · Sky and water: deep, saturated blues (+18% saturation in 190°–250°
      hue range) with slightly increased luminance (atmosphere rendering).
    · White precision: near-neutral colors (saturation < 0.08 in HSL)
      are de-saturated slightly further to ensure clean whites.
    · Micro-contrast in midtones: 5% local contrast enhancement for
      luminance 0.25–0.75.
    · No black lift (unlike Leica Classic) — pure blacks preserved.
    · Neutral shadow color: any color cast in deep shadows is gently
      removed (blend 10% toward neutral in L < 0.12 region).

"Portra Film" (Kodak Portra 400 emulation):
  Characteristics:
    · Warm bias: +180K equivalent color temperature shift overall.
    · Compressed highlights: very gentle shoulder, starts at 70% luminance.
    · Pastel shift: reduce saturation by 6% globally, then recover reds
      and oranges specifically (+4% saturation for 10°–45° hue range).
    · Grain: see Section 5.7.

"Velvia Film" (Fujifilm Velvia 50 emulation):
  Characteristics:
    · Vivid: +22% global saturation.
    · Deep shadows: black point crush (floor at 0.0, no lift).
    · Blue shift in greens: rotate greens (100°–140°) by +8° toward cyan.
    · Red boost: +15% saturation for 340°–20° hue range.
    · High sharpness appearance: local contrast +12%.

"HP5 B&W" (Ilford HP5 emulation):
  Characteristics:
    · B&W conversion: L = 0.299R + 0.587G + 0.114B (BT.601 luma).
    · User-adjustable channel mixer (6 channels: R, O, Y, G, Cy, B).
    · Default mixer: R=+10%, O=+5%, Y=-5%, G=-8%, Cy=0%, B=+8%.
    · Gives the classic HP5 look: slightly warm highlights, slightly
      green-shifted shadows.
    · High grain (see Section 5.7).
    · Contrast: 10-point S-curve.

5.4 Per-Hue HSL Control Engine
────────────────────────────────
Implement a 360-segment continuous HSL control system:
  - No abrupt boundaries between hue segments.
  - Each of the 8 base hue ranges (Red, Orange, Yellow, Green, Aqua,
    Blue, Purple, Magenta) uses a smooth Gaussian envelope:
    weight(h, center, sigma) = exp(-(h - center)² / (2 * sigma²))
    where sigma is chosen so adjacent ranges sum to 1.0 everywhere.
  - For each hue range: allow ±45° hue shift, ±100% saturation change,
    ±100% luminance change.
  - Apply all adjustments simultaneously in a single pass in HSL space.
  - Then convert back to RGB and apply the 3D LUT.

5.5 Perceptual Color Uniformity
─────────────────────────────────
All saturation adjustments must be applied in CIECAM02 color appearance
model space (or its simplified version, CAM16) rather than HSL space.
This prevents "posterization" and unnatural hue shifts when pushing
saturation hard.

CIECAM02 input conditions: LA = 64 cd/m², Yb = 20, surround = average.
Implement CIECAM02 → CAM16 forward and inverse transforms using the
standard matrix formulations. Store as Kotlin object ColorAppearanceModel.

The vibrance slider must:
1. Compute per-pixel saturation in CAM16.
2. Compute a protection factor: P = sigmoid(saturation - 0.5) where
   P ≈ 0 for already-saturated colors and ≈ 1 for desaturated ones.
3. Apply saturation boost proportional to P * vibrance_amount.
4. Additional skin protection: multiply P by 0.25 for skin-hue pixels.

5.6 Accurate Skin Tone Rendering
──────────────────────────────────
Skin tone rendering is the most perceptually sensitive aspect of color.
Implement a multi-stage skin protection pipeline:

Stage 1: Detection
  - Segment skin pixels using a combination of:
    · YCbCr range: Cb ∈ [77, 127], Cr ∈ [133, 173] (Kovac et al.)
    · HSL range: H ∈ [0°, 50°] ∪ [330°, 360°], S ∈ [15%, 75%]
    · CIECAM02 hue angle range: h ∈ [20°, 70°] (yellowish-red region)
  - Combine all three detectors using an AND operation (all three must
    agree) to reduce false positives.
  - Dilate the skin mask by 8px. Erode by 4px (morphological opening).

Stage 2: Anchor Point Correction
  - For each connected skin region, compute its mean CIE XYZ values.
  - Look up the nearest skin tone on the Munsell skin tone axis
    (precomputed table of 12 Munsell skin chips).
  - Compute the correction vector in CIELAB: ΔE₀₀ between current
    and target. If ΔE₀₀ > 4.0, blend 30% of the correction.
  - This gently shifts skin toward natural without harsh correction.

Stage 3: Micro-Detail Preservation
  - In skin regions, reduce sharpening to 30% of the non-skin value.
  - Apply a 3px Gaussian blur to the color channels (a*, b*) only,
    while keeping the luminance channel (L*) sharp.
  - This gives the "Leica skin rendering" effect: soft, even tones
    with sharp eyes and hair, avoiding the plastic look.

5.7 Film Grain Synthesis
─────────────────────────
Implement a physically-accurate film grain model:

Step 1: Generate base grain field using a 3D blue-noise texture
(512x512x64 frames) sampled at the current frame index (mod 64).
Blue noise is perceptually better than white noise (more uniform
spatial distribution, avoids clumping).

Step 2: Modulate grain intensity by local luminance:
  grain_strength(L) = grain_amount * L^0.5 * (1 - L)^0.25
  (grain is strongest in midtones, weaker in shadows and highlights).

Step 3: Scale grain to match film stock characteristics:
  - Portra 400: grain_amount = 0.012, grain_size = 1.2px
  - Velvia 50: grain_amount = 0.006, grain_size = 0.8px
  - HP5 B&W: grain_amount = 0.025, grain_size = 1.5px

Step 4: For color films, apply grain per-channel with slight
color variation (±15% random variation between R, G, B channels),
replicating the look of independent silver halide crystals.

Step 5: Optionally halftone-dither the grain for large-format print
output mode.

5.8 Color Accuracy Verification
─────────────────────────────────
Implement a ColorAccuracyBenchmark that:
- Loads a set of 24 reference XYZ patches (ColorChecker Classic values).
- Simulates the full pipeline on these patches.
- Computes ΔE₀₀ for each patch and reports mean, max, and 90th percentile.
- Fails (logs an error + fires a Firebase Analytics event) if:
  mean ΔE₀₀ > 3.0 OR max ΔE₀₀ > 8.0.
Run this benchmark at startup in debug/staging builds.

══════════════════════════════════════════════════════════════════════════
CHAPTER 6 — HYPERTONE WHITE BALANCE SYSTEM (:hypertone-wb)
══════════════════════════════════════════════════════════════════════════

6.1 Multi-Stage Illuminant Estimation
───────────────────────────────────────
The HyperTone WB system estimates the scene illuminant through four
independent methods, then fuses their outputs:

Method A: Gray World + Edge Exclusion
  Compute the mean R, G, B of all pixels where:
    - Luminance ∈ [0.05, 0.85] (exclude over/under-exposed)
    - Spatial gradient magnitude < 30 (exclude edges)
    - Not in the skin mask (exclude skin tones, which bias gray world)
  Estimated illuminant: [mean_R, mean_G, mean_B].

Method B: Max-RGB (White Patch)
  Find the 99.5th percentile of R, G, B independently.
  Estimated illuminant: [R_max, G_max, B_max].

Method C: Gamut Mapping (Finlayson's method)
  Project all pixel colors onto the Planckian locus in the rg
  chromaticity plane. Find the blackbody locus point that minimizes
  the sum of squared distances from all neutral-colored pixels
  (selected using a gamut constraint: pixels within ±15% of the gray
  world estimate). Use Brent's method for 1D optimization along the
  Planckian locus from 2000K to 12000K.

Method D: Deep Learning (CNN Illuminant Estimator)
  Deploy a confidence-weighted ensemble of 3 TFLite models:
    · Global model (EfficientNet-Lite0, takes full 224x224 thumbnail)
    · Local model (processes 5x5 grid of 64x64 patches, takes the
      median prediction with IQR filtering)
    · Semantic model (takes 224x224 thumbnail + scene type one-hot)
  Each model outputs (CCT, Tint, confidence).
  Weighted ensemble: final = Σ(model_output * confidence) / Σ confidence.

Fusion of A, B, C, D:
  Assign base weights: A=0.15, B=0.10, C=0.25, D=0.50.
  Modulate by confidence signals:
    - If all four methods agree within ±300K: increase D's weight to 0.65.
    - If CNN confidence < 0.6 (uncertain scene): increase C's weight to 0.40.
    - If scene is "mixed light" (CNN's uncertainty > 0.3): reduce D to 0.30.
  Compute final CCT and Tint as weighted average.

6.2 Kelvin-to-CCM Computation
───────────────────────────────
Given a final CCT estimate (K) and Tint (T):

Step 1: Convert CCT to CIE xy chromaticity using the Robertson (1968)
  reciprocal megakelvin method. Store a lookup table of 25 calibration
  points from 2000K to 12000K, each with [CCT, ud, vd, slope].

Step 2: Apply the Tint correction perpendicular to the Planckian locus:
  Move the estimated chromaticity by T * 0.0025 in the v direction
  (positive T = green shift, negative = magenta).

Step 3: Convert (x, y) to CIE XYZ via:
  X = x/y, Y = 1, Z = (1-x-y)/y.

Step 4: Compute the chromatic adaptation matrix using the CAT16 model:
  CAT16_MA matrix → cone space → scale to match D65 → invert → RGB space.
  This gives a 3x3 CCM_WB that undoes the scene illuminant.

Step 5: Compose the final CCM: CCM_final = CCM_WB × CCM_sensor_to_XYZ.

Step 6: Decompose CCM_final into a Camera2-compatible color correction
  mode (COLOR_CORRECTION_MATRIX) and apply via CaptureRequest.
  For post-processing, apply CCM_final as a matrix multiply in the
  RenderScript kernel before the 3D LUT stage.

6.3 Mixed Light Spatial WB
───────────────────────────
When mixed_light_detected = true (from Section 6.1 Method D):

Step 1: SLIC superpixel segmentation on the YUV analysis frame
  (640x480). Parameters: desired_segments=300, compactness=20.
  Use OpenCV via JNI for SLIC (ship OpenCV 4.8.0 static library,
  opencv-core, opencv-imgproc only — ~4MB stripped).

Step 2: For each superpixel, run the gray-world + gamut illuminant
  estimation independently. Get per-superpixel CCT estimate.

Step 3: Spatial smoothing: apply a 50-superpixel bilateral smoothing
  to the CCT map (spatial sigma=5 superpixels, range sigma=500K).

Step 4: At full resolution, bilinearly interpolate the smoothed CCT
  map back to image dimensions.

Step 5: For each pixel, compute the local CCM from the local CCT
  (using Section 6.2) and apply during the ISP pipeline.

The per-pixel CCM is encoded as a compressed 3x3 matrix field (stored
as a GPU texture, sampled in the color science RenderScript kernel).

6.4 WB Memory & Temporal Consistency
──────────────────────────────────────
Scene-lock memory:
  Store the last 8 WB estimates (CCT, Tint, timestamp, scene_hash)
  in a DataStore<Preferences> (persists across app restarts).
  A scene_hash is computed from: (a) scene category from AI engine,
  (b) hour of day (±2hr granularity), (c) location geohash (1km cell).
  If the current scene matches a stored hash (all three agree) and
  the time delta < 2 hours, blend 25% of the stored WB estimate into
  the current estimate.

Temporal stabilization:
  Between frames during video recording, apply a 1st-order IIR filter
  on CCT and Tint: CCT_smooth[t] = 0.9 * CCT_smooth[t-1] + 0.1 * CCT[t].
  This prevents WB "hunting" under flickering lights.

WB confidence signal:
  Compute WB confidence = (1 - std_dev_of_methods / mean_CCT).
  If confidence < 0.5, show a "WB uncertain" indicator in the UI.
  If confidence < 0.3, fall back to CONTROL_AWB_MODE_AUTO from Camera2
  and overlay a warning in the professional mode histogram panel.

══════════════════════════════════════════════════════════════════════════
CHAPTER 7 — AI ENGINE (:ai-engine)
══════════════════════════════════════════════════════════════════════════

7.1 Model Management
─────────────────────
All TFLite models must be managed by an AiModelManager that:
- Loads models lazily (only when first needed).
- Uses the TFLite GPU Delegate for models > 500K parameters.
- Uses the TFLite NNAPI Delegate (with CPU fallback) on API 29+.
- Maintains a model priority queue: if memory < 256MB available,
  unload the lowest-priority model.
- Logs inference latency via Firebase Performance for each model.

Model download strategy: ship the smallest 3 models in the APK
(<3MB total). Download larger models (>3MB) on first launch via
Firebase Remote Config + WorkManager (require WiFi, require charging).
Never block the user experience waiting for model downloads.

7.2 Scene Understanding Network
─────────────────────────────────
Primary scene classifier: EfficientNet-Lite4 (INT8, ~19MB).
Input: 300x300 RGB thumbnail.
Output: 35-class scene probability distribution + scene complexity score.

Scene classes (35):
  Portrait, Group_Portrait, Pet, Wildlife, Macro, Food, Beverage,
  Night_Urban, Night_Nature, Astrophotography, Sunrise_Sunset,
  Landscape_Mountain, Landscape_Water, Landscape_Desert,
  Landscape_Forest, Architecture_Exterior, Architecture_Interior,
  Document_Text, Document_QRCode, Whiteboard, Product_Shot,
  Sports_Indoor, Sports_Outdoor, Concert_Stage, Fireworks, Snow,
  Beach, Underwater, Aerial, Vehicle, Abstract_Pattern, Silhouette,
  Fog_Mist, Rain, Bokeh_Heavy.

For each scene class, maintain a SceneProfile data class that stores:
  - recommendedHDRMode (HDRStrategy enum)
  - sharpnessTarget (0.0–1.0)
  - noiseReductionStrength (0.0–1.0)
  - saturationBias (-0.2 to +0.3)
  - contrastBias (-0.1 to +0.15)
  - defaultExposureBias (in EV)
  - preferredColorProfile (LUT name)
  - enabledSuperResolution (Boolean)
  - recommendedISO (Int)
  - recommendedMinShutterSpeed (Long, in nanoseconds)

These profiles are stored in a JSON config file (scene_profiles.json)
in assets/, not hard-coded, so they can be updated via Firebase Remote
Config OTA.

7.3 Real-Time Object Detection & Tracking
───────────────────────────────────────────
Model: YOLOv8-Nano (TFLite, INT8, ~3MB).
Input: 640x480 YUV → convert to 640x640 RGB with letterboxing.
Output: Bounding boxes, class IDs, confidence scores.
Inference rate: 10fps on main pipeline, 30fps via NNAPIDelegate on
  capable devices.

Supported object classes (80 COCO classes + 12 custom):
  Standard COCO: person, cat, dog, bird, car, bicycle, motorcycle,
  cup, phone, laptop, book, etc.
  Custom additions: document, food_dish, product_packaged, face_side,
  face_back, hand, eye_closed, eye_open, astronomical_object, drone.

Multi-object tracking:
  Use a ByteTrack-inspired tracker (Kalman filter per track + IoU
  association). Each track has:
    - Unique track_id (incremented monotonically).
    - Kalman state: [x, y, w, h, vx, vy, vw, vh].
    - Age (frames since last match) — unconfirmed until age > 3.
    - Confidence decay: 0.9x per unmatched frame.
  Maximum simultaneous tracks: 16.

7.4 Facial Analysis System
───────────────────────────
Run MediaPipe FaceMesh (478 landmarks, INT8 TFLite, ~2.2MB) at 15fps.

Extract from landmarks:
  - Eye openness: compute aspect ratio of upper/lower eyelid landmarks.
    Threshold: open if ratio > 0.2.
  - Gaze direction: 3D head pose from landmarks (roll, pitch, yaw).
  - Smile score: ratio of mouth width to neutral width.
  - Face sharpness: Laplacian variance of the face bounding box region.
  - Blink detection: eye openness < 0.12 for 2+ consecutive frames.

For each detected face, produce a FaceQuality score:
  FaceQuality = 0.40 * eye_openness + 0.25 * face_sharpness_normalized
              + 0.20 * gaze_score + 0.15 * (1 - head_tilt_normalized)
  where gaze_score = 1 - |yaw| / 30° (penalizes faces not looking at camera)
  and head_tilt_normalized = |roll| / 45°.

7.5 Real-Time Semantic Segmentation
─────────────────────────────────────
Model: DeepLabV3+ with MobileNetV3-Large backbone (INT8, ~5MB).
Input: 513x513 RGB (processed at 4fps from YUV analysis stream).
Output: 21-class per-pixel segmentation map (PASCAL VOC classes).

Expanded to 27 semantic classes by adding: hair, hands, cloth_upper,
cloth_lower, accessory_glasses, accessory_hat, accessory_bag.

Post-processing:
  - Apply conditional random field (CRF) smoothing via a 5-iteration
    dense CRF (Krähenbühl & Koltun, 2011). This sharpens mask edges
    using the RGB image as a guide.
  - Apply temporal smoothing: mask[t] = 0.7 * mask[t] + 0.3 * mask[t-1]
    (prevents flickering).
  - Upsample from 513x513 to analysis stream resolution using bilinear
    interpolation, then to full resolution via guided upsampling.

7.6 Monocular Depth Estimation
────────────────────────────────
Model: MiDaS v3.1 (DPT-Hybrid, INT8, ~13MB) — downloaded on-demand.
Fallback: MiDaS Small (INT8, ~3.2MB) — shipped in APK.
Input: 384x384 RGB.
Output: Inverse relative depth map (higher = closer).

Convert relative depth to metric depth using a scale-shift ambiguity
resolution: use LENS_INFO_MINIMUM_FOCUS_DISTANCE and the focused
object's bounding box size (if known from detection) to anchor the
scale. If unknown, store as relative depth only.

Apply temporal depth smoothing: depth[t] = 0.6 * depth[t] + 0.4 *
  warp_previous(depth[t-1]) where warp_previous uses the optical flow
  field from the video stabilization module to align the previous depth
  map to the current frame.

7.7 Intelligent Shot Scoring System
─────────────────────────────────────
Compute a real-time ShotQualityScore (0.0–1.0) at 5fps to assist
the user in finding the optimal capture moment:

Component scores:
  S_sharpness   = tanh(3 * laplacian_variance / 200)        · weight: 0.28
  S_exposure    = 1 - |histogram_mean - 0.46| / 0.46        · weight: 0.18
  S_composition = compute_composition_score()                · weight: 0.15
  S_faces       = mean(FaceQuality_scores) if faces > 0 else 0.5  · weight: 0.22
  S_motion_blur = 1 - motion_score (from optical flow)       · weight: 0.10
  S_noise       = 1 - estimate_noise_level(YUV_frame) / 0.1  · weight: 0.07

ShotQualityScore = Σ(Sᵢ * wᵢ) / Σwᵢ

compute_composition_score():
  - Evaluate rule of thirds: score is max overlap of primary subject
    bounding box with any of the 4 "power points" (1/3 intersections).
  - Evaluate horizon line: if sky class is present in segmentation, score
    the angle of the sky/ground boundary. Penalty for tilt > 2°.
  - Evaluate subject centering for portrait mode: score = 1 - dist(
    face_center, frame_center_upper_third) / frame_diagonal.
  - Return weighted combination.

Display the ShotQualityScore as:
  - A real-time bar indicator in the viewfinder (bottom edge).
  - Flash a green ring animation when score > 0.82 for > 0.5 seconds
    (candidate for "perfect moment" capture).
  - In auto-burst mode, automatically capture when score peaks and
    starts declining.

7.8 AI-Powered Burst Selection
────────────────────────────────
After a burst capture, rank frames using the ShotQualityScore system
applied to each frame post-hoc:

For portrait bursts:
  Primary sort: FaceQuality score (highest eye openness, no blinks).
  Secondary sort: ShotQualityScore.

For action bursts:
  Primary sort: S_sharpness + S_motion_blur composite.
  Secondary sort: best subject pose (from pose estimation if available).

For landscape bursts:
  Primary sort: S_exposure + S_sharpness.
  Secondary sort: minimal foreground motion (clouds, water).

Present top 5 frames to the user in a burst review carousel with
the quality score and specific quality issues (e.g., "Eyes partially
closed", "Slight motion blur", "Slightly over-exposed") displayed
as text badges below each thumbnail.

Allow the user to mark any frame as "Favorite" which feeds back into
the model's personalization (stored locally in Room DB, used to adjust
ShotQualityScore weights over time using gradient-free optimization).

══════════════════════════════════════════════════════════════════════════
CHAPTER 8 — NOISE REDUCTION & COMPUTATIONAL OPTICS (:imaging-pipeline)
══════════════════════════════════════════════════════════════════════════

8.1 Comprehensive Noise Model
──────────────────────────────
The sensor noise model from SENSOR_NOISE_PROFILE gives:
  σ²(I) = A * I + B
  where A = shot noise coefficient, B = read noise coefficient,
  I = normalized signal intensity [0, 1].

Build an extended noise model accounting for:
  - Quantization noise: σ²_q = (1 / (12 * (2^SENSOR_BIT_DEPTH - 1)²))
  - Fixed-pattern noise: estimate from dark frame (if available).
    Dark frame = average of 5 captures at ISO 50, 1/8000s (no light).
    FPN = mean(dark_frames). Subtract FPN from every RAW frame.
  - Row noise (banding): detect using per-row mean vs global mean.
    If |row_mean - global_mean| > 2 * σ_row, apply row correction.

Store the complete noise model as a SensorNoiseModel data class with
methods: estimateSigma(intensity, ISO, channel) and
estimateNoisePower(spectrum, ISO).

8.2 Deep Learning Noise Reduction
───────────────────────────────────
Primary model: FFDNet (TFLite, INT8, ~1.8MB):
  - Blind denoising: conditions on an estimated noise map.
  - Processes 256x256 tiles with 32px overlap.
  - Tile boundary blending using a Hann window weight.
  - Per-tile noise sigma estimated from the sensor noise model.
  - Separate models for: color (RGB input) and B&W (L channel only).

Secondary model: MPRNet (TFLite, INT8, ~4.1MB, downloaded on-demand):
  - Multi-stage progressive restoration.
  - Better for extreme noise (ISO > 3200).
  - Slower: ~2.5s for 12MP image on Snapdragon 8 Gen 2.
  - Applied when ISO > 3200 AND processing_budget > 4s.

NR strength adaptation:
  - Base NR strength: nr_strength = log10(ISO / 50) / log10(6400 / 50)
    (normalized 0–1 from ISO 50 to ISO 6400).
  - Per-semantic-class NR override:
      sky: 1.3x base strength (sky benefits from aggressive NR)
      face/skin: 0.4x base (preserve micro-texture)
      hair: 0.7x base
      vegetation: 0.9x base
      architecture/text: 0.5x base (preserve edge sharpness)
      water: 1.1x base

8.3 Optical PSF Deconvolution Sharpening
──────────────────────────────────────────
Every lens has a point spread function (PSF) that blurs the image due
to diffraction, aberrations, and defocus. Deconvolution can partially
undo this.

Step 1: PSF Estimation.
  - For devices with LENS_INTRINSIC_CALIBRATION + LENS_RADIAL_DISTORTION,
    compute a simplified PSF model: Gaussian with sigma = f(aperture).
    sigma = 0.5 * (wavelength / aperture_diameter) * focal_length_mm / pixel_pitch_um
    Use lambda = 550nm (green), pixel_pitch from SENSOR_INFO_PIXEL_ARRAY_SIZE
    and SENSOR_INFO_PHYSICAL_SIZE.
  - For known device models, load a precomputed PSF table (stored as
    assets/psf/{device_model}_{focal_length}_{aperture}.bin).
    Each PSF is a 32x32 float32 kernel.

Step 2: Wiener Deconvolution.
  - Compute FFT of the image tile (1024x1024 tiles, 64px overlap).
  - Compute FFT of the PSF (zero-padded to tile size).
  - Apply Wiener filter in frequency domain:
    G(u,v) = H*(u,v) / (|H(u,v)|² + K)
    where H = PSF FFT, K = noise-to-signal power ratio = σ²_noise / σ²_signal.
    Estimate σ²_signal from the DC component.
    Estimate K dynamically from the local noise model (Section 8.1).
  - Apply G to the image FFT: F_sharp = G * F_blurred.
  - Inverse FFT → sharpened image.
  - Blend: output = alpha * sharpened + (1 - alpha) * original
    where alpha = sharpness_target from scene profile (Section 7.2).

Step 3: Edge-Aware Strength Modulation.
  - Compute a per-pixel "sharpening mask" = sigmoid(Sobel_magnitude - 15).
  - Apply deconvolution output blended by this mask:
    · High mask (edges): apply full deconvolution.
    · Low mask (flat areas): apply only 20% of deconvolution.
  - This prevents noise amplification in smooth regions.

8.4 Comprehensive Lens Correction Suite
─────────────────────────────────────────
Apply in this strict order (order matters):

1. Dark frame subtraction (Section 8.1 FPN correction).
2. Lens shading correction:
   - Read STATISTICS_LENS_SHADING_MAP from CaptureResult.
   - Upsample the shading map to full image size using bilinear interp.
   - Multiply each RAW channel by the reciprocal of the shading map.
   - This corrects peripheral darkening (vignette) at the RAW stage.
3. Bad pixel correction:
   - Run a 3x3 median filter only on pixels where pixel value
     deviates > 5σ from the 3x3 neighborhood mean.
   - Replace bad pixels with the neighborhood median.
4. Chromatic aberration correction (lateral):
   - Read CA coefficients from the precomputed CA map in assets/.
   - For R channel: apply a radial scale factor (1 + CA_scale_R * r²).
   - For B channel: apply a radial scale factor (1 + CA_scale_B * r²).
   - Use bicubic interpolation to avoid aliasing during channel rescale.
   - Green channel: no rescale (reference channel).
5. Geometric distortion correction:
   - Read LENS_DISTORTION coefficients [k1, k2, k3, k4, k5, k6]
     plus tangential [p1, p2] from CameraCharacteristics.
   - Apply OpenCV undistortPoints() via JNI on a mesh grid (100x100)
     then warp the full image using cv::remap().
   - Use INTER_LANCZOS4 interpolation for sub-pixel accuracy.
6. Perspective correction (for wide-angle lenses):
   - Detect strong vertical/horizontal lines using Hough transform.
   - Classify as architectural scene if > 4 strong parallel lines.
   - Offer one-tap "auto perspective correction" (soft keystone).

8.5 Multi-Frame Super-Resolution (Pixel Shift)
───────────────────────────────────────────────
On devices that support sub-pixel frame-to-frame shifts (detected from
the optical flow between consecutive frames at very short exposures):

Capture 4 RAW frames with sub-pixel shifts induced by hand tremor
(natural ~0.3px shifts between consecutive frames at 1/30s).

Merge using a back-projection SR algorithm:
  1. Estimate sub-pixel shift between each frame and the reference
     using phase correlation in the Fourier domain (accurate to 1/16px).
  2. Create a virtual high-resolution grid at 2x the native resolution.
  3. Back-project each low-resolution frame onto the HR grid using
     bilinear interpolation weighted by shift accuracy.
  4. Apply a Laplacian regularization term to reduce ringing.
  5. Iterate 5 times (gradient descent on the reconstruction loss).

This can recover approximately 0.8x the native resolution limit
(approaching the Nyquist limit of the sensor).

══════════════════════════════════════════════════════════════════════════
CHAPTER 9 — COMPUTATIONAL PHOTOGRAPHY MODES
══════════════════════════════════════════════════════════════════════════

9.1 Advanced Portrait Mode
───────────────────────────
Depth computation (priority order):
  1. Hardware stereo depth (DEPTH16 from stereo cameras).
  2. ToF sensor depth (if DEPTH_OUTPUT + TIME_OF_FLIGHT capability).
  3. Phase detection depth map from Camera2 (DEPTH_JPEG extension).
  4. Monocular neural depth (Section 7.6).
  5. Segmentation-only (fall back to hard mask if nothing else available).

Depth map refinement:
  - Apply a 5-iteration Dense CRF to align depth edges with RGB edges.
  - Use edge-aware upsampling (Joint Bilateral Upsample): upsample
    depth from sensor resolution to full resolution guided by RGB image.
  - Apply temporal depth smoothing for video portrait (Section 7.6).

Bokeh rendering (physically-based):
  - Simulate a circular aperture with configurable F-stop (f/0.9 to f/16).
  - Blur radius for each pixel: r(d) = (focal_length * aperture_diameter
    * |d - focus_plane|) / (d * (d - focal_length))
    where d = depth, focus_plane = focused depth plane.
  - Clamp blur radius to [0, 35px] to avoid excessive blur.
  - Render using a layered approach (12 depth layers, rear-to-front).
  - For each layer: apply disc-shaped blur (approximated as 2 passes
    of 1D box filters for performance, with 6-polygon mask for
    bokeh shape simulation).
  - Bokeh highlight boost: multiply highly-blurred bright specular
    highlights (luminance > 0.8, blur_radius > 10px) by 1.3 to
    simulate lens transmission.

Hair and transparent object matting:
  - Use a TriMap-based alpha matting algorithm (closed-form matting).
  - TriMap generation: definite foreground (skin/hair mask, eroded 8px),
    definite background (background mask, eroded 12px), unknown region
    (everything else).
  - Apply closed-form alpha matting (Levin et al., 2008) on the unknown
    region only. This recovers semi-transparent hair and glasses edges.

9.2 Astrophotography Mode
──────────────────────────
Capture protocol:
  - ISO: 6400–25600 (user selectable, default 12800)
  - Shutter: 8–30 seconds (default 15s)
  - WB: Tungsten (2700K) as base, let HyperTone refine
  - Burst: 15–60 frames (user configurable)
  - AF: Infinity focus (LENS_FOCUS_DISTANCE = 0.0)

Star detection and tracking:
  - Detect stars as local maxima (SNR > 5σ) in the luminance channel.
  - Filter by shape: must be circular (eccentricity < 0.3) and
    small (radius < 5px in the reference frame).
  - For tracking: match stars between frames using a nearest-neighbor
    search (max 20px search radius). Require ≥ 8 matched stars.
  - Compute frame-to-frame rigid transform (translation + rotation)
    from matched star positions using RANSAC.

Frame stacking:
  - Kappa-sigma clipping: for each pixel position, compute the mean
    and std deviation of all N aligned frames. Reject frames where
    value > mean + 2.5σ (satellite trails, cosmic rays, planes).
  - Average remaining unclipped frames (minimum 5 required).

Light pollution gradient removal:
  - Fit a 2D polynomial (degree 3) to the background (non-star, non-
    galaxy) pixels after sigma-clipping.
  - Subtract the polynomial background gradient.

Star color rendering:
  - Enhance star colors by boosting saturation in star regions
    (star_mask dilated 2px) by 40%.
  - Apply a slight glow effect on brightest stars (Gaussian blur of
    20% of the star layer, blended at 15% opacity) to simulate bloom.

Milky Way enhancement:
  - Detect the Milky Way band using a segmentation network (custom
    TFLite model, ~1.2MB) trained on astrophotography datasets.
  - Apply selective color grading: warm (3200K bias) to Milky Way
    core, cool (8000K bias) to star background.

9.3 Ultra-High-Resolution Macro Mode
──────────────────────────────────────
Detect macro intent: lens focus distance < 3cm AND object occupies
> 20% of frame AND scene category = Macro.

Focus stacking (for extended depth of field):
  - Capture 10–20 frames at different focus distances spanning
    [min_focus, min_focus * 3] diopter range.
  - For each frame, compute a sharpness map: sum of Laplacian
    magnitudes in each 16x16 block.
  - Fuse frames: for each block, use the frame with the highest
    sharpness score. Blend block transitions using bilinear weights.
  - Apply Poisson blending at block boundaries to avoid visible seams.

Apply 2x super-resolution after focus stacking (Section 8.3).

9.4 Professional Cinema Video Mode
────────────────────────────────────
Codec options:
  - H.265 HEVC: up to 4K/60fps, HDR10, 10-bit.
  - If API 33+ and device supports it: AV1 hardware encoding (12-bit).
  - RAW Video (DNG sequence): capture RAW frames and encode as
    Android MediaMuxer DNG sequence. Requires fast storage (UFS 3.1+).
    Check write speed before enabling: write a 50MB test file and
    verify throughput > 300 MB/s.

Color profiles for video:
  "S-Log3" emulation (Sony-style flat profile):
    Gamma: E = 0.18 * (L^(1/2.5) - 1) + 0.18 for L > 0.008
           E = 0.18 * L / 0.008 for L ≤ 0.008
    Gamut: S-Gamut3.Cine (wide, covers almost all visible colors)
    White balance: neutral, no saturation boost.

  "ARRI LogC3" emulation:
    Cut: 0.010591, a=5.555556, b=0.052272, c=0.247190, d=0.385537,
    e=5.367655, f=0.092809 (per ARRI Technical Reference).

  "Rec.2020 HLG" (for HDR10 playback):
    Transfer: HLG OETF per ITU-R BT.2100-2.
    Gamut: BT.2020. Requires API 33+ and HEVC 10-bit support.

Real-time LUT preview:
  - Apply the selected LUT in real-time on the preview stream using
    an OpenGL ES 3.2 fragment shader.
  - The shader samples a 33x33x33 3D LUT texture (uploaded once at
    session start, ~1.1MB in GL_RGBA16F format).
  - Target latency: <1ms/frame for 4K input.

Focus breathing compensation:
  - Many lenses change apparent focal length as they focus (breathing).
  - Measure breathing by comparing the bounding box of a fixed
    calibration object at min focus vs. infinity focus.
  - Compute a per-focus-distance crop/scale factor.
  - Apply a real-time crop/scale in the GPU pipeline to counteract
    breathing during focus pulls.

9.5 Time-Lapse & Hyper-Lapse
──────────────────────────────
Time-lapse:
  - Configurable interval: 1s to 3600s.
  - Auto-exposure ramping (Day-to-Night transition):
    Compute a smooth exposure curve interpolating between successive
    AE estimates. Apply 15-frame weighted moving average to exposure
    to prevent flickering.
  - Auto-WB stabilization: apply the temporal WB smoothing
    (Section 6.4) between time-lapse frames.
  - Save RAW sequence for post-processing option.
  - Compile time-lapse to video using MediaMuxer + MediaCodec.

Hyper-lapse (walking stabilization):
  - Capture at 2–5fps while walking.
  - Align frames using homography estimation (feature matching via
    ORB + RANSAC, min 20 inlier matches required).
  - Apply warp stabilization with a smooth reference path (fit a
    polynomial of degree 4 to the accumulated transforms).
  - Output at 30fps (6x–15x speed-up).

9.6 Multi-Camera Seamless Zoom
────────────────────────────────
Implement smooth optical-to-digital zoom transitions across lenses:

At each focal length step:
  - Determine which physical camera provides the best optical zoom
    for the target focal length (compare zoom ratios from
    CameraCapabilityProfile for each physical camera).
  - Pre-capture a frame from both the current and next camera
    simultaneously (using MultiCapture on API 30+).
  - Compute a homography between the two cameras using precomputed
    extrinsic parameters (LENS_POSE_TRANSLATION, LENS_POSE_ROTATION).
  - At the transition point, blend between the two frames using a
    10-frame crossfade with homography correction.
  - Apply color matching between cameras: compute a per-image CCM
    that maps the new camera's color to match the old camera's color
    for the transition region.

Zoom-aware quality selection:
  - For 1x–3x: use main camera + digital zoom (crop + SR if enabled).
  - For 3x–5x: use periscope/telephoto lens if available.
  - For > 5x: use telephoto + digital zoom + SR.
  - For < 0.9x: use ultra-wide.
  - At each boundary: apply cross-blend.

══════════════════════════════════════════════════════════════════════════
CHAPTER 10 — VIDEO PIPELINE (:imaging-pipeline)
══════════════════════════════════════════════════════════════════════════

10.1 OIS + EIS Fusion Stabilization
─────────────────────────────────────
This is a key differentiator. Fuse hardware OIS and software EIS:

OIS data: Read LENS_STATE_OPTICAL_STABILIZATION_CORRECTION (if supported)
  to get the hardware OIS correction applied per frame.

Gyroscope fusion:
  - Subscribe to SensorManager with TYPE_GYROSCOPE_UNCALIBRATED at
    the maximum rate (TYPE_GYROSCOPE_UNCALIBRATED avoids calibration
    jumps that corrupt the rotation estimate).
  - Integrate angular velocity using a quaternion integrator
    (more numerically stable than Euler angles):
    q[t] = q[t-1] * exp(0.5 * dt * [ωx, ωy, ωz, 0])
    Normalize q after each step.
  - Use a complementary filter to fuse gyroscope and accelerometer:
    q_fused = (1 - alpha) * q_gyro + alpha * q_accel
    where alpha = 0.02 (high-pass for gyro, low-pass for accel).
  - Timestamp synchronization: use SENSOR_TIMESTAMP from CaptureResult
    to synchronize gyro timestamps to camera frame timestamps.

EIS computation:
  - Build a gyroscope-predicted transform: convert q_fused to a
    rotation matrix R, project onto the image plane given known
    intrinsics K: H = K * R * K^(-1) (virtual camera rotation).
  - The predicted warp H eliminates rotation-induced motion.
  - For residual translation (walking), compute optical flow over 200
    feature points (FAST corners + Lucas-Kanade tracking).
    Estimate a 2D translation from the flow vectors using RANSAC
    (inlier threshold 2px, max iterations 100).
  - Compose the final stabilization warp: T_stab = H * T_translation.

Rolling shutter correction:
  - The sensor reads rows sequentially. Each row is captured at a
    slightly different time: Δt_row = SENSOR_FRAME_DURATION / row_count.
  - Interpolate the gyroscope rotation at each row's capture time.
  - Apply a per-row horizontal shear to correct for rolling shutter.

Smooth reference path:
  - Accumulate all T_stab transforms over a 60-frame window.
  - Fit a cubic B-spline to the accumulated path (smoothness lambda=5).
  - Compute the correction: T_correction = T_smooth_path / T_accumulated.
  - Apply T_correction as the final stabilization warp.

Margin: reserve a 5% border on each side for stabilization headroom.
  Crop the output to (90% width, 90% height) to ensure no black borders.

10.2 Advanced Audio Pipeline
──────────────────────────────
Implement a professional audio pipeline using AudioRecord and
AudioTrack via MediaCodec:

Input: AudioFormat.CHANNEL_IN_STEREO, ENCODING_PCM_FLOAT, 48000Hz.
Use AudioSource.CAMCORDER (voice processing) for general recording.
Use AudioSource.UNPROCESSED for cinema/music recording.

Processing chain (in order, applied as a biquad filter chain):
  1. DC blocking filter: highpass at 5Hz, Q=0.707.
  2. Wind noise reduction: LMS adaptive filter on the wind detection
     band (2kHz–5kHz). Suppress by 18dB when wind_score > 0.6.
     wind_score = RMS(2k-5kHz) / RMS(100Hz-500Hz) > 3.0.
  3. Low-cut filter: 2nd-order Butterworth highpass at 80Hz.
  4. EQ: slight presence boost at 3kHz (+1.5dB) for voice clarity.
  5. Dynamic range compressor:
     Threshold: -18dBFS. Ratio: 3:1. Attack: 8ms. Release: 80ms.
     Knee: 6dB soft knee. Makeup gain: +3dB.
  6. True peak limiter: -1.0dBFS ceiling (inter-sample peak detection
     at 4x oversampling to prevent digital clipping on playback).

Audio-video sync: use MediaSync API to maintain A/V sync within ±1ms.
Write audio via MediaMuxer.addTrack() with AudioFormat descriptor.

External microphone support: detect USB/Lightning audio device via
AudioManager.getDevices() and prefer external mic if connected.

10.3 Video Quality Intelligence
─────────────────────────────────
Implement real-time VMAF-lite estimation (simplified VMAF using:
  VIF (Visual Information Fidelity) + ADM (Additional Detail Metric))
  computed on a 1/4-resolution downsampled frame at 1fps.
  If VMAF_lite < 60, suggest to the user: "Low video quality detected.
  Try: lower zoom level / better lighting / enable EIS."

Automatically reduce bitrate by 15% if CPU temperature > 75°C
(detected via /sys/class/thermal/thermal_zone*/temp on most devices).

══════════════════════════════════════════════════════════════════════════
CHAPTER 11 — USER INTERFACE (:ui-components, :feature:camera)
══════════════════════════════════════════════════════════════════════════

11.1 Viewfinder Design
───────────────────────
The viewfinder surface:
  - SurfaceView (NOT TextureView) for zero-copy preview rendering.
  - Aspect ratio: respect the device display aspect ratio, with
    letterboxing only for extreme formats.
  - Preview resolution: maximum YUV output size maintaining the
    display aspect ratio.
  - Overlay: a transparent Compose layer over the SurfaceView for
    all UI elements (AF bracket, face boxes, composition guides,
    histogram, shot quality bar).

Required overlay elements:
  AF bracket: animated ring + corner brackets (Canvas API).
    - Tapping any point: move AF bracket there with spring animation.
    - AF state: bracket is yellow while seeking, green on lock, red on fail.
    - Double-tap: toggle AF/AE lock. Show "AF/AE LOCK" text badge.
  Face detection boxes: thin rounded rects (1px stroke, white,
    fade in/out smoothly). Show gaze arrows on face boxes.
  Shot quality bar: gradient bar (red→yellow→green, 120px wide) at
    bottom, animated to current ShotQualityScore.
  Horizon level: a single horizontal line + degree readout that turns
    green when within ±0.5° of level.
  Histogram: mini RGB histogram (80x48px) in corner, toggleable.
  AI scene badge: pill-shaped badge (e.g., "Portrait" or "Night") in
    upper left, fades after 2 seconds.

Gestures:
  Pinch: smooth zoom (no snap) from ultra-wide to max telephoto.
  Tap: move AF point + expose for that region.
  Double-tap: return to center AF.
  Long-press: lock AF/AE at current position.
  Swipe up/down on shutter side: adjust EV in ±0.3EV steps.
  Swipe left/right: switch camera modes.
  Two-finger rotate: rotate camera lock indicator (accessibility).
  Volume up/down: take photo (configurable).

11.2 Mode Architecture
───────────────────────
Implement a modal camera system with these modes (Compose NavHost):

PHOTO: Main stills mode. Auto + HDR + AI all enabled.
VIDEO: Cinema video. EIS + log profiles available.
PORTRAIT: Face/pet detection required. Bokeh enabled.
NIGHT: Extended exposure stacking. Star detection optional.
PRO: Full manual controls. Histogram, waveform, zebras.
MACRO: Focus stacking. SR enabled. 10cm min focus warning.
ASTROPHOTO: Special long-exposure stack mode. 
TIME_LAPSE: Interval capture with auto-exposure ramping.
HYPER_LAPSE: Walking stabilization video.
SCAN: Document detection + OCR + PDF output.
SLOW_MOTION: 4x / 8x slow-mo at 120fps / 240fps.

Each mode persists its specific settings (ISO, WB, zoom, EV) independently.
Switching modes does not disturb other modes' settings.

11.3 Professional Controls Panel
──────────────────────────────────
In PRO mode, implement a fully-featured controls panel:

Exposure triangle:
  - ISO: circular wheel (not slider) from ISO_min to ISO_max.
    Tapping AUTO re-enables AE.
  - Shutter: circular wheel from 1/8000s to 30s, using standard
    photographic stop values (ISO stops: 50, 64, 80, 100, 125, 160,
    200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500,
    3200, 4000, 5000, 6400, 8000, 10000, 12800, 16000, 25600).
    BULB mode on long-press of 30s position.
  - EV compensation: ±4EV in 1/3-stop steps. Displayed as a numeric
    value and a ±EV progress arc around the shutter wheel.

White balance panel:
  - Two sliders: Kelvin (2000K–12000K) + Tint (-150 to +150).
  - Live color temperature preview swatch.
  - Preset buttons: Sunny, Cloudy, Tungsten, Fluorescent, Flash, Shade.
  - Eyedropper: tap a neutral area to set WB from that point.

Focus controls:
  - Hyperfocal marker on the focus wheel (computed from focal length,
    aperture, and circle of confusion = sensor_diagonal / 1500).
  - Focus distance in meters + feet (read from CaptureResult).
  - MF assist: auto-magnify (3x crop to AF area) when focus wheel moves.
  - Focus peaking: toggle on/off. Color selector: red/yellow/white.

Exposure aids panel (right edge, vertical):
  - RGB waveform (720px wide, 96px tall) — real-time from YUV.
  - RGB parade (3 stacked waveforms for R, G, B).
  - False color overlay: toggle on viewfinder.
  - Zebra stripes: toggle. Threshold selector.
  - Vectorscope: circular CbCr display showing color distribution.
    Draw skin tone line (from center at ~11 o'clock direction = skin
    vector in YCbCr space).

11.4 Post-Capture Edit Interface
──────────────────────────────────
After capture, the edit interface must be:

Non-destructive: all edits stored as parameters applied on export.
GPU-accelerated: all adjustments applied via OpenGL ES fragment shaders
  in real-time (target: < 8ms/frame for any adjustment).

Tool categories:
  Light: Exposure, Contrast, Highlights, Shadows, Whites, Blacks.
  Color: Temperature, Tint, Vibrance, Saturation.
  Detail: Texture, Clarity, Dehaze, Sharpening (Amount/Radius/Masking),
          Noise Reduction (Luminance/Color/Detail).
  Curves: RGB master + per-channel (R, G, B) + Hue vs Sat/Lum/Hue.
  HSL: per-hue Hue/Saturation/Luminance (8 color ranges).
  Color Grading: Split toning (shadows/midtones/highlights, each with
                 Hue/Saturation/Luminance + Blending/Balance).
  Geometry: Crop, Rotate, Flip, Perspective (vertical/horizontal),
            Guided Upright (automatic), Aspect (free/original/1:1/4:3/16:9).
  Effects: Grain (Amount/Size/Roughness), Vignette (Amount/Midpoint/
           Feather/Roundness/Highlights), Dehaze.
  Transform: Distortion correction slider.

All tools use a real-time preview with a before/after split-screen
comparison mode (tap and hold the compare icon).

11.5 Gallery & Metadata Viewer
────────────────────────────────
Gallery view:
  - Load thumbnails from MediaStore using Glide (with disk cache).
  - Animate item entry with a shared element transition from the
    post-capture review screen.
  - Support swipe-to-delete with undo snackbar (30 seconds to undo).
  - Filter by: date, mode, color profile, HDR/no-HDR.

Metadata panel (swipe up on any photo):
  Display all technical metadata in a structured card layout:
    · Camera info: focal length (real + 35mm equiv), aperture, ISO,
      shutter speed, EV, AWB mode, AF mode, flash.
    · HyperTone WB: CCT (K), Tint, illuminant classification,
      mixed_light flag, WB confidence.
    · HDR info: strategy used, frames merged, EV range, ghost
      mask coverage %, peak highlight recovery.
    · Neural ISP: whether Neural ISP was used, SR applied,
      NR model and strength, deconvolution PSF source.
    · Color: LUT profile, ΔE₀₀ accuracy score, color gamut
      coverage (% of P3, % of Rec.2020).
    · AI: scene category (top 3 + probabilities), face count,
      face quality scores, ShotQualityScore at time of capture.
    · Processing: total pipeline time (ms), GPU time (ms),
      CPU time (ms), memory peak (MB).

══════════════════════════════════════════════════════════════════════════
CHAPTER 12 — PERFORMANCE, MEMORY & THERMAL MANAGEMENT
══════════════════════════════════════════════════════════════════════════

12.1 Processing Budget System
──────────────────────────────
Implement a ProcessingBudget system that dynamically allocates
compute time based on available resources:

At capture time:
  1. Measure available memory: Runtime.getRuntime().freeMemory() +
     maxMemory() - totalMemory(). Add the memory available to the
     system: ActivityManager.MemoryInfo.availMem.
  2. Measure thermal headroom: PowerManager.getThermalHeadroom(30).
  3. Measure current CPU load: read /proc/loadavg.
  4. Measure GPU utilization (device-specific sysfs path).

Build a ProcessingBudget from these inputs:
  class ProcessingBudget(
    val totalTimeMs: Int,           // max pipeline time
    val enableNeuralISP: Boolean,   // enabled if headroom > 0.4
    val enableSuperResolution: Boolean,
    val enableDepthEstimation: Boolean,
    val hdrFrameCount: Int,         // 9 / 5 / 3 based on budget
    val nrModelQuality: NRQuality,  // FULL / FAST / NONE
  )

12.2 GPU Memory Management
───────────────────────────
Maintain a GpuMemoryPool with pre-allocated HardwareBuffer objects:
  - Pool size: 6 buffers at full RAW size (e.g., 50MP × 8 bytes = 400MB).
  - Use buffer_size = SENSOR_INFO_PIXEL_ARRAY_SIZE.width *
                      SENSOR_INFO_PIXEL_ARRAY_SIZE.height * 8.
    (8 bytes: float32 for 4-channel RGGB).
  - Buffer lifecycle: acquired from pool before processing, returned
    to pool after (never GC'd during capture, eliminates GC pauses).
  - If pool is exhausted, wait up to 500ms for a buffer to be returned.
    If still exhausted, reduce to 5-frame burst and log a warning.

12.3 Thermal Throttling Response
──────────────────────────────────
Register a PowerManager.OnThermalStatusChangedListener.

At each thermal level:
  THERMAL_STATUS_LIGHT (temperature rising):
    - Disable Super Resolution.
    - Reduce HDR burst to 5 frames.
    - Reduce AI inference to 1fps.
    - Log thermal event to Firebase Analytics.

  THERMAL_STATUS_MODERATE:
    - Disable Neural ISP (use Traditional ISP).
    - Reduce HDR to 3 frames.
    - Disable real-time face tracking during video.
    - Reduce video bitrate by 20%.

  THERMAL_STATUS_SEVERE:
    - Disable all AI inference.
    - Disable HDR (single-frame only).
    - Cap video bitrate at 25 Mbps.
    - Show an in-app banner: "Camera reduced to prevent overheating."

  THERMAL_STATUS_CRITICAL:
    - Force video recording to stop.
    - Force the camera to PAUSED state.
    - Show a full-screen warning dialog with a countdown until
      the camera can resume (estimate from thermal curve).

12.4 Thread Architecture
─────────────────────────
Every thread must have a named HandlerThread or CoroutineDispatcher
and a documented purpose:

"CameraHandler" (HandlerThread, THREAD_PRIORITY_URGENT_AUDIO):
  Camera2 callbacks exclusively. No processing allowed here.

"RawDecodeDispatcher" (Executors.newFixedThreadPool(2)):
  RAW frame decoding, sensor metadata extraction.

"GpuPipelineDispatcher" (single-threaded, GPU operations):
  All RenderScript / Vulkan / OpenGL ES calls.
  NEVER called from main thread.

"AiInferenceDispatcher" (Executors.newFixedThreadPool(2)):
  TFLite inference only. Separate from GPU pipeline to allow
  concurrent CPU + GPU processing.

"FileIoDispatcher" (Dispatchers.IO):
  MediaStore writing, EXIF embedding, thumbnail generation.

"UiDispatcher" (Dispatchers.Main):
  StateFlow consumption, Compose recomposition triggers only.

12.5 Startup Performance
─────────────────────────
Target: camera preview visible within 400ms of cold start.

Startup sequence (on main thread, minimal work):
  T+0ms:    setContentView() → show splash + skeleton UI.
  T+50ms:   Launch CameraHandler thread. Begin camera open.
  T+100ms:  Create SurfaceView. Attach preview surface.
  T+200ms:  CameraDevice.onOpened() → start preview stream.
  T+250ms:  First preview frame visible to user.
  T+400ms:  Load first AI model (scene classifier, async).
  T+1000ms: Load all AI models, initialize GPU pipeline.
  T+2000ms: All Neural ISP models loaded.

Use App Startup library (Jetpack) to sequence initializers.
Measure startup via Firebase Performance Trace: "cold_start_to_preview".

══════════════════════════════════════════════════════════════════════════
CHAPTER 13 — OUTPUT, FILE FORMATS & METADATA
══════════════════════════════════════════════════════════════════════════

13.1 Output Format Matrix
──────────────────────────
Format         Extension  Color Space  Bit Depth  Compression
─────────────────────────────────────────────────────────────────
JPEG           .jpg       sRGB         8-bit      Lossy 97%
HEIC (photo)   .heic      Display P3   10-bit     Lossy (HEVC)
HEIC (HDR)     .heic      Rec.2020     10-bit HDR Lossy (HEVC)
RAW DNG        .dng       Linear RAW   16-bit     Lossless LJPEG-92
RAW+JPEG       both       both         both       both
ProRAW-style   .dng       Linear wide  16-bit     Lossless + XMP
Video H.265    .mp4       Rec.709/P3   8/10-bit   HEVC CRF 24
Video AV1      .mp4       Rec.2020     12-bit     AV1 CRF 28

13.2 DNG Metadata Completeness
────────────────────────────────
All DNG files must be compliant with DNG Specification 1.6.
Include ALL of the following tags:
  - DNGVersion, DNGBackwardVersion.
  - AsShotNeutral (per-channel WB multipliers from HyperTone).
  - AsShotWhiteXY (scene white point in XY chromaticity).
  - ColorMatrix1/2 (from SENSOR_COLOR_TRANSFORM1/2).
  - CameraCalibration1/2 (from SENSOR_CALIBRATION_TRANSFORM1/2).
  - ForwardMatrix1/2 (from SENSOR_FORWARD_MATRIX1/2).
  - CalibrationIlluminant1/2.
  - SensorTemperature (from SENSOR_TEMPERATURE if available).
  - LensInfo (min focal, max focal, min aperture, max aperture).
  - LensModel (from Build.DEVICE + focal length).
  - NoiseProfile (from SENSOR_NOISE_PROFILE).
  - OpcodeList1/2/3 (lens correction opcodes):
    · WarpRectilinear opcode for geometric correction.
    · GainMap opcode for vignette correction (from shading map).
    · FixVignetteRadial opcode (radial vignette coefficients).
  - SubTileBlockSize (for tiled DNG).
  - PreviewColorSpace (set to sRGB).
  - Embed a full-resolution JPEG preview (using the processed output).
  - All standard EXIF tags from CaptureResult.

13.3 XMP Extended Metadata
───────────────────────────
Embed a custom XMP namespace (xmlns:provisioncam="http://ns.provisioncam.app/1.0/")
in every output file, with:
  - pc:LUTProfile: color profile name.
  - pc:HDRFrameCount: number of frames merged.
  - pc:HDRStrategy: strategy enum name.
  - pc:WBKelvin: final color temperature.
  - pc:WBTint: final tint.
  - pc:WBIlluminant: classified illuminant type.
  - pc:NRModel: NR model used (FFDNet / MPRNet / none).
  - pc:NRStrength: final NR strength 0.0–1.0.
  - pc:SRApplied: Boolean.
  - pc:NeuralISPUsed: Boolean.
  - pc:SceneCategory: top AI scene classification.
  - pc:FaceCount: number of faces detected.
  - pc:ShotQualityScore: 0.0–1.0.
  - pc:ProcessingTimeMs: total pipeline time.
  - pc:AppVersion: versionName + versionCode.

These tags allow full re-processing of any saved file using the same
pipeline parameters that produced it.

13.4 Privacy & Security
────────────────────────
Implement a privacy-first metadata policy:
  - GPS coordinates: only embedded if the user explicitly enables
    "Location tagging" in settings. Default: OFF.
  - Device identifiers: never embed device IMEI/IMSI or advertising ID.
  - Face data: face rectangle coordinates are NEVER written to file.
    The face analysis is ephemeral (in-memory only).
  - All file writes use ContentValues with PENDING state on API 29+:
    insert with IS_PENDING=1, write data, then update IS_PENDING=0.
    This prevents incomplete files from appearing in gallery.
  - Implement a PrivacyAuditLog (local Room DB, max 1000 entries)
    that records what metadata was embedded in each exported file.

══════════════════════════════════════════════════════════════════════════
CHAPTER 14 — TESTING & QUALITY ASSURANCE
══════════════════════════════════════════════════════════════════════════

14.1 Unit Tests (JUnit 5 + Mockk + Kotest)
────────────────────────────────────────────
Target: 85% line coverage on all non-UI, non-platform code.

Required test suites:

HyperToneWBTest:
  · testRobertsonCCTComputation: known xy → expected CCT ±5K.
  · testIlluminantFusion: mock 4 methods, verify weighted output.
  · testMixedLightDetection: synthetic scene with 2 illuminants.
  · testTemporalStabilization: CCT variance < 20K after filtering.

HDREngineTest:
  · testGhostDetectionWithMovingSubject: synthesize 9 frames with
    a displaced 100x100 rect in 3 frames. Verify ghost mask covers
    displaced region with 90% precision.
  · testMergingPreservesHighlights: merged output must not clip
    where any input frame was not clipped.
  · testExposureWeightSum: Σwᵢ ≠ 0 for all pixel positions.
  · testToneMappingMonotonicity: tone curve must be strictly monotonically
    increasing.

ColorScienceTest:
  · testLUTTrilinearVsTetrahedral: compare implementations on 1000
    random inputs. Δmax < 0.001 in linear light.
  · testSkinToneProtection: after LUT + vibrance, skin pixels must
    not shift > 5° in hue.
  · testColorAccuracyOnColorChecker: ΔE₀₀ mean < 3.0 (Section 5.8).
  · testGrainEnergyDistribution: grain spectrum must match blue-noise
    distribution (no spectral peaks above 2x mean).

NoiseModelTest:
  · testSensorNoiseProfileParsing: known A, B coefficients → σ(I) match.
  · testPSFDeconvolutionInvertibility: deconvolve a synthetically
    blurred image. Peak PSNR > 32dB vs original.

AIEngineTest:
  · testShotQualityScoreRange: output ∈ [0.0, 1.0] for all inputs.
  · testBestFrameSelectionDeterminism: same burst → same ranking.
  · testSceneClassifierLatency: < 80ms on Pixel 5 emulator.

14.2 Integration Tests (Robolectric + Camera2 Mock)
─────────────────────────────────────────────────────
Required integration test suites:

CameraSessionTest:
  · testStateMachineValidTransitions: all valid transitions succeed.
  · testStateMachineInvalidTransitions: all invalid transitions throw.
  · testCameraRecoveryOnDisconnect: session recovers within 2s.

PipelineIntegrationTest:
  · testFullPipelineOnSyntheticRAW: generate a synthetic 12MP RAW
    image with known content (ColorChecker patches + gray ramps).
    Run full pipeline. Verify color accuracy (ΔE₀₀ < 4.0) and
    that no JPEG artifacts are introduced.
  · testHDRMergingOnNFrames: for N ∈ {2, 3, 5, 9}, verify the
    merged image covers the full dynamic range of input frames.

FileOutputTest:
  · testDNGCompliance: output DNG is readable by LibRaw.
  · testXMPMetadataRoundtrip: all pc: tags survive write/read cycle.
  · testHEICColorSpaceEmbedding: output HEIC has embedded P3 ICC.

14.3 Instrumentation Tests (Espresso + UI Automator)
──────────────────────────────────────────────────────
On-device tests (run on Firebase Test Lab, Pixel 6, API 33):

CameraE2ETest:
  · testCapturePhotoInAllModes: capture one photo in each of the 11
    modes. Verify non-null file written to MediaStore.
  · testHDRActivatesCorrectly: in a high-DR synthetic scene (use a
    display showing a white-on-black pattern), verify HDR strategy
    selects HDR_MULTI_9 and outputs > 12 stops of DR.
  · testMemoryBudgetNotExceeded: capture 20 sequential photos.
    Peak memory (via Debug.MemoryInfo) must not exceed 550MB.
  · testThermalThrottlingResponse: simulate THERMAL_STATUS_MODERATE
    via a mock PowerManager. Verify Neural ISP is disabled.
  · testAFLockBehavior: tap to lock AF. Verify CONTROL_AF_STATE =
    AF_STATE_LOCKED_FOCUSED in next 10 capture results.

PerformanceTest (Macrobenchmark):
  · benchmarkColdStart: time from app launch to first preview frame.
    Target: < 400ms on Pixel 6.
  · benchmarkFullPipelineLatency: time from shutter press to JPEG
    written to MediaStore. Target: < 3500ms for 12MP on Pixel 6.
  · benchmarkHDRMerge9Frames: time for 9-frame HDR merge only.
    Target: < 1200ms on Snapdragon 8 Gen 2.
  · benchmarkNeuralNR: time for FFDNet on 12MP image.
    Target: < 600ms with GPU delegate.

14.4 Visual Regression Testing
────────────────────────────────
Implement a screenshot-based visual regression testing system:
  - Store reference screenshots (golden images) in assets/golden/.
  - For each test case, capture a screenshot and compare using
    a pixel-level diff (tolerance: ΔE₀₀ < 2.0 per pixel,
    max 0.5% of pixels may differ).
  - Run on Gradle check task in CI.
  - Generate an HTML diff report on failure.

14.5 CI/CD Pipeline (GitHub Actions)
─────────────────────────────────────
.github/workflows/ci.yml must include:

On every PR:
  - Kotlin compilation check.
  - Detekt static analysis (fail on error, warn on smell).
  - KtLint formatting check.
  - Unit tests (JUnit 5).
  - Robolectric integration tests.
  - Coverage report upload to Codecov (fail if < 80%).

On merge to main:
  - Full build (debug + release).
  - Instrumentation tests on Firebase Test Lab (Pixel 6, API 33).
  - Macrobenchmark run (upload results to Firebase Performance).
  - APK size check (fail if APK > 100MB after resource shrinking).
  - Upload debug APK to Firebase App Distribution for QA team.

══════════════════════════════════════════════════════════════════════════
CHAPTER 15 — ADVANCED FEATURES
══════════════════════════════════════════════════════════════════════════

15.1 Computational Clarity & Dehaze
─────────────────────────────────────
Dehaze (DCP — Dark Channel Prior, He et al. 2009):
  1. Compute dark channel J_dark(x) = min_{c∈{R,G,B}}(min_{y∈Ω(x)}(Jc(y)/Ac))
     where Ω(x) is a 15x15 patch centered at x.
  2. Estimate atmospheric light A: 99.9th percentile of J_dark-indexed pixels.
  3. Estimate transmission map t(x) = 1 - ω * J_dark (ω = 0.95).
  4. Refine t(x) using a guided image filter (r=60, ε=0.001).
  5. Recover scene radiance: J(x) = (I(x) - A) / max(t(x), 0.1) + A.
  Clamp output to [0, 1]. Apply only when haze_score > 0.3 (detected
  from: low contrast + high atmospheric uniformity in the image).

Clarity (local contrast mid-tones):
  Apply an unsharp mask to the luminance channel in ranges [0.25, 0.75]:
    detail = L - GaussianBlur(L, sigma=20px)
    L_out = L + clarity_amount * detail * ramp_function(L)
    ramp_function(L) = sin(π * L) (peaks at L=0.5, zero at 0 and 1)
  Clarity range: -100 to +100, default 0. +50 = clarity_amount=0.5.

15.2 Generative AI Features (Optional Download)
─────────────────────────────────────────────────
These features require ~120MB of additional on-demand models.
Download via WorkManager when on WiFi + charging.

Object Removal ("Magic Eraser" equivalent):
  - User selects an object by long-pressing.
  - Run segmentation to get the object mask.
  - Fill using a diffusion inpainting model (Stable Diffusion Inpainting
    quantized to 4-bit, ~80MB). Tile processing for large regions.
  - Input: image + mask (white = region to fill).
  - Post-process: Poisson blending to seamlessly merge the fill.
  - Show a preview before committing.

Sky Replacement:
  - Segment sky using semantic segmentation.
  - Provide 12 sky presets (stored as 2048x1536 HEIC assets).
  - Apply perspective-correct alignment: match the horizon line angle.
  - Blend using Poisson cloning with a 16px feather on sky edges.
  - Adjust sky lighting: match the luminance and color temperature of
    the original sky at the horizon using a gradient blend.

AI Image Enhancement ("Enhance" mode):
  - Run the full neural ISP pipeline in post (applies to existing photos
    from the gallery, not just newly-captured).
  - Show a before/after split-screen with processing time estimate
    before running.

15.3 Accessibility Features
─────────────────────────────
- VoiceOver/TalkBack support: all camera controls must have
  contentDescription labels. Announce: "Shutter button. Double-tap to
  take photo." for the shutter.
- One-handed mode: mirror the shutter and mode selector to either side.
- Haptic feedback: distinct haptic patterns for:
    · Shutter press (short, sharp).
    · Focus lock (double click).
    · AF failure (long buzz).
    · Shot quality peak (soft double click).
- Large text support: all UI text must scale with system font size.
  Use sp units everywhere.
- Audio feedback mode: announce detected scene category and face count
  via text-to-speech (optional, for visually impaired users).
- Assistive zoom: long-press on any UI element shows a 2x magnified
  tooltip for users with low vision.

15.4 RAW Processing SDK (Public API)
──────────────────────────────────────
Expose the processing pipeline as a Kotlin SDK for third-party
integrations:

interface RawProcessingSDK {
  suspend fun processRaw(
    rawFile: Uri,
    options: ProcessingOptions
  ): ProcessingResult

  suspend fun applyColorProfile(
    imageUri: Uri,
    profileName: String,
    strength: Float = 1.0f
  ): ProcessingResult

  fun getAvailableColorProfiles(): List<ColorProfile>

  suspend fun estimateWhiteBalance(imageUri: Uri): WBResult
}

Document the SDK with KDoc. Provide a sample app in a separate
:sample module.

══════════════════════════════════════════════════════════════════════════
CHAPTER 16 — ANALYTICS, CRASH REPORTING & REMOTE CONFIG
══════════════════════════════════════════════════════════════════════════

16.1 Firebase Integration
──────────────────────────
Crashlytics:
  - Attach all capture metadata (ISO, shutter, mode, HDR strategy) as
    Crashlytics custom keys before every pipeline operation.
  - Use Crashlytics.setCustomKey() with keys: capture_mode, hdr_strategy,
    ai_scene, iso, shutter_ns, neural_isp_enabled, nr_model.

Analytics:
  - Log these events (no PII, all anonymous):
    · photo_captured: {mode, hdr_strategy, scene, has_face,
                       processing_time_ms, color_profile}
    · video_recorded: {duration_s, resolution, codec, stabilization,
                       log_profile}
    · color_profile_selected: {profile_name}
    · feature_used: {feature_name} for each advanced feature.
    · wb_override: {kelvin, tint} when user overrides WB.
    · quality_warning_shown: {warning_type} for thermal/memory warnings.

Performance:
  - Custom traces: "full_pipeline", "hdr_merge", "nr_pass",
    "neural_isp", "color_lut_apply", "file_write".
  - Custom metrics inside traces: "frame_count", "peak_memory_mb".

Remote Config (update without APK update):
  - scene_profiles_json: override scene profiles remotely.
  - hdr_frame_counts: {HDR_MULTI_9: 9, HDR_MULTI_5: 5, ...}
  - tflite_model_versions: trigger model updates.
  - feature_flags: enable/disable features per device tier.
  - lut_adjustment_matrix: fine-tune LUT parameters without a release.

══════════════════════════════════════════════════════════════════════════
CHAPTER 17 — DELIVERABLES & IMPLEMENTATION ORDER
══════════════════════════════════════════════════════════════════════════

Implement in this exact order. Do not skip steps. Confirm completion
of each step before proceeding to the next.

PHASE 0 — INFRASTRUCTURE (Week 1)
  ✦ 0.1: Gradle multi-module project skeleton.
  ✦ 0.2: Hilt DI setup + CameraSessionScope.
  ✦ 0.3: :common module (Result types, extensions, Logger, constants).
  ✦ 0.4: :gpu-compute module (RenderScript setup + Vulkan skeleton).
  ✦ 0.5: CI/CD pipeline (GitHub Actions, Detekt, KtLint, unit test runner).

PHASE 1 — CAMERA CORE (Weeks 2–3)
  ✦ 1.1: :sensor-hal — CameraSessionManager + state machine.
  ✦ 1.2: :sensor-hal — CameraCapabilityProfile for all physical cameras.
  ✦ 1.3: :sensor-hal — Multi-mode metering system.
  ✦ 1.4: :sensor-hal — Hybrid AF system (PDAF + contrast + neural seed).
  ✦ 1.5: :sensor-hal — Zero-shutter-lag ring buffer.
  ✦ 1.6: Unit tests for all sensor-hal components.

PHASE 2 — IMAGING PIPELINE (Weeks 4–6)
  ✦ 2.1: :lens-model — Lens correction suite (FPN, shading, CA, distortion).
  ✦ 2.2: :imaging-pipeline — Frame alignment (Gaussian pyramid + B-spline).
  ✦ 2.3: :imaging-pipeline — HDR merge (variance-based, ghost mask).
  ✦ 2.4: :imaging-pipeline — Perceptual tone mapping (5 stages).
  ✦ 2.5: :imaging-pipeline — PSF deconvolution sharpening.
  ✦ 2.6: :imaging-pipeline — FFDNet noise reduction.
  ✦ 2.7: Unit + integration tests for pipeline.

PHASE 3 — COLOR SCIENCE (Weeks 7–8)
  ✦ 3.1: :color-science — 3D LUT engine (tetrahedral, Vulkan + RS).
  ✦ 3.2: :color-science — All 5 color profiles + film emulations.
  ✦ 3.3: :color-science — Per-hue HSL engine in CIECAM02.
  ✦ 3.4: :color-science — Skin tone protection pipeline.
  ✦ 3.5: :color-science — Film grain synthesis.
  ✦ 3.6: :color-science — ColorAccuracyBenchmark (ΔE₀₀ < 3.0 passes).

PHASE 4 — HYPERTONE WB (Week 9)
  ✦ 4.1: :hypertone-wb — All 4 illuminant estimation methods.
  ✦ 4.2: :hypertone-wb — Method fusion + Kelvin-to-CCM.
  ✦ 4.3: :hypertone-wb — Mixed light spatial WB.
  ✦ 4.4: :hypertone-wb — Temporal stabilization + WB memory.

PHASE 5 — AI ENGINE (Weeks 10–11)
  ✦ 5.1: :ai-engine — AiModelManager (lazy loading, memory policy).
  ✦ 5.2: :ai-engine — Scene classifier (EfficientNet-Lite4).
  ✦ 5.3: :ai-engine — Object detection + ByteTrack tracker.
  ✦ 5.4: :ai-engine — FaceMesh + face quality scoring.
  ✦ 5.5: :ai-engine — DeepLabV3+ segmentation + temporal smoothing.
  ✦ 5.6: :ai-engine — Monocular depth (MiDaS).
  ✦ 5.7: :ai-engine — ShotQualityScore + burst selection.

PHASE 6 — NEURAL ISP (Week 12)
  ✦ 6.1: :neural-isp — Raw-to-Raw denoising (Stage 0).
  ✦ 6.2: :neural-isp — Learned demosaicing (Stage 1).
  ✦ 6.3: :neural-isp — Color & tone network (Stage 2).
  ✦ 6.4: :neural-isp — Semantic enhancement (Stage 3).
  ✦ 6.5: :neural-isp — ISP routing logic + Traditional ISP fallback.

PHASE 7 — COMPUTATIONAL MODES (Weeks 13–15)
  ✦ 7.1: Portrait mode (all depth sources + physically-based bokeh).
  ✦ 7.2: Astrophotography mode.
  ✦ 7.3: Macro mode (focus stacking).
  ✦ 7.4: Night mode (MFNR + NR pass).
  ✦ 7.5: Multi-camera seamless zoom.
  ✦ 7.6: Super-resolution (Real-ESRGAN-inspired).

PHASE 8 — VIDEO PIPELINE (Week 16)
  ✦ 8.1: OIS + EIS fusion stabilization.
  ✦ 8.2: LOG + HLG color profiles.
  ✦ 8.3: Professional audio pipeline.
  ✦ 8.4: Real-time LUT preview (OpenGL ES 3.2).
  ✦ 8.5: Time-lapse + hyper-lapse.
  ✦ 8.6: Cinema video mode (RAW video option).

PHASE 9 — USER INTERFACE (Weeks 17–18)
  ✦ 9.1: Viewfinder + all overlays (AF bracket, faces, histogram,
          shot quality, horizon level, scene badge).
  ✦ 9.2: All gesture handlers.
  ✦ 9.3: All 11 camera modes + mode switcher.
  ✦ 9.4: PRO mode full control panel.
  ✦ 9.5: Post-capture edit interface (all 40+ tools, GPU-accelerated).
  ✦ 9.6: Gallery + metadata viewer.

PHASE 10 — OUTPUT & METADATA (Week 19)
  ✦ 10.1: Full DNG writing (all required tags + opcodes).
  ✦ 10.2: HEIC output (Display P3 + HDR10).
  ✦ 10.3: XMP extended metadata (all pc: tags).
  ✦ 10.4: Privacy policy implementation.

PHASE 11 — QUALITY & POLISH (Week 20)
  ✦ 11.1: Full test suite (unit, integration, E2E, benchmark).
  ✦ 11.2: Thermal management + processing budget system.
  ✦ 11.3: Firebase (Crashlytics, Analytics, Performance, Remote Config).
  ✦ 11.4: Accessibility features.
  ✦ 11.5: Color accuracy benchmark run. ΔE₀₀ < 3.0 required to ship.
  ✦ 11.6: Final performance benchmarks. All targets must pass.

══════════════════════════════════════════════════════════════════════════
REQUIRED PERFORMANCE TARGETS (must be met before shipping)
══════════════════════════════════════════════════════════════════════════

Metric                        Target          Device
──────────────────────────────────────────────────────────────────────────
Cold start to preview         < 400ms         Pixel 6, API 33
Shutter-to-thumbnail          < 80ms          any device
Full pipeline (12MP)          < 3.5s          Snapdragon 8 Gen 2
Full pipeline (12MP)          < 6.0s          Snapdragon 888
HDR 9-frame merge only        < 1.2s          Snapdragon 8 Gen 2
Neural NR (FFDNet, 12MP)      < 600ms         Snapdragon 8 Gen 2 GPU
3D LUT (65-node, 12MP)        < 120ms         GPU
WB estimation (HyperTone)     < 30ms          any device
AI scene classification       < 80ms          any device (CPU fallback)
Preview framerate             ≥ 30fps         any device (all modes)
Video 4K/60 sustained         ≥ 60fps         Snapdragon 8 Gen 2
Peak memory (capture)         < 550MB         any device
Color accuracy ΔE₀₀ mean      < 3.0           (simulated)
Color accuracy ΔE₀₀ max       < 8.0           (simulated)
APK size (release)            < 100MB         N/A
Crash-free rate               > 99.5%         Firebase Crashlytics
══════════════════════════════════════════════════════════════════════════

Begin with Phase 0. Present the complete module structure with
build.gradle.kts files for every module before writing any
application code. Ask for clarification only if a specific device
hardware capability creates an architectural ambiguity that cannot
be resolved by the graceful degradation principles in Chapter 0."
