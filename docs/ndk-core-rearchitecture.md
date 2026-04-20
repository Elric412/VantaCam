# NDK Core Re-Architecture Blueprint (Telegram-Style Optimization)

## 1) Target Outcomes

- Move all heavy image operations (RAW ingest, demosaic hooks, HDR merge, HyperTone WB, Leica color transforms, denoise/sharpen/tone-map) from Kotlin/Java into a native C++ core.
- Keep Kotlin responsible for orchestration only (session lifecycle, permissions, UI state, Camera2/CameraX setup, and native task submission).
- Guarantee a zero-jank preview/capture UX by isolating camera callbacks, processing, and encode/write stages behind lock-free queues and dedicated worker pools.
- Scale quality dynamically by capability tiers (Flagship / Upper-mid / Mid) using GPU/DSP availability and thermal headroom.

---

## 2) High-Level Architecture

```text
UI (Compose/View)
   │
   ▼
Camera Orchestrator (Kotlin)
   │ submit jobs (JNI, non-blocking)
   ▼
Native Bridge (JNI)
   │
   ▼
C++ Imaging Runtime (single owner)
   ├─ Capture Ingest Queue (AImageReader / AHardwareBuffer handles)
   ├─ HDR Burst Manager (10-bit frames, timestamps, metadata)
   ├─ HyperTone WB Engine
   ├─ Leica Color Science Engine (LUT/Matrix/Curves)
   ├─ Compute Backends
   │   ├─ CPU SIMD (NEON)
   │   ├─ Vulkan compute
   │   └─ GLES compute fallback
   ├─ Device Capability + Thermal Governor
   └─ Output Queue (JPEG/HEIF + metadata)
```

### Ownership Rule

**Only C++ owns pixel buffers once handed off.** Kotlin only holds opaque handles (`Long nativePtr` / token IDs).

---

## 3) Module Layout (Recommended)

```text
:camera-core                 (Kotlin orchestration)
:native-imaging-core         (C++17, NDK, CMake)
:native-compute-vulkan       (optional split for Vulkan kernels)
:native-color-science        (LUTs, spectral transforms, WB models)
:feature:camera              (UI + use-cases)
```

### JNI Surface (Minimal and Stable)

Expose a narrow API surface to reduce JNI overhead/churn:

- `nativeCreateSession(config)`
- `nativeQueueFrame(frameHandle, captureMeta)`
- `nativeQueueBurst(burstHandle[], metadata[])`
- `nativeRequestProcess(requestId, mode)`
- `nativePollResult(timeoutMs)`
- `nativeRelease(handle)`
- `nativeDestroySession()`

---

## 4) Zero-Jank Pipeline Design

## Thread Model

1. **Camera callback thread** (very short work only)
   - Receives image handle + metadata.
   - Pushes lightweight descriptors to lock-free ingest queue.
2. **Ingest/normalize thread**
   - Maps hardware buffers, validates bit depth/stride, aligns metadata.
3. **Processing pool** (N workers)
   - HDR merge + WB + color science + local tone map.
4. **Encode/output thread**
   - HEIF/JPEG encode, EXIF/XMP tagging, disk I/O.
5. **Result callback thread (JNI -> Kotlin)**
   - Posts completion state to UI (never blocks render thread).

## Queueing Rules

- Use bounded MPSC ring buffers per stage (`capacity` tuned per tier).
- Backpressure policy:
  - Preview: drop oldest unprocessed frames.
  - Capture burst: never drop; pre-allocate before shutter.
- Stage deadlines:
  - Preview path budget: 12-16 ms target.
  - Capture HDR path budget: device-tier dependent (e.g., 80-220 ms).

## Scheduling

- Pin critical workers to big cores on capable devices.
- Isolate encode/write to avoid starving merge workers.
- Use fence/timeline semaphores for GPU jobs; avoid `glFinish`-style global stalls.

---

## 5) HAL3 + 10-bit HDR Memory Strategy

## Buffering Principles

- Prefer `AImageReader` with `PRIVATE`/`YCBCR_P010` (where supported) and `AHardwareBuffer` interop.
- Keep buffers in GPU-importable format to avoid extra copies.
- Pre-allocate burst pools at session start based on selected capture template.

## Suggested Pooling

- **Preview pool**: small rotating set (e.g., 3-4 buffers).
- **Burst pool (10-bit)**: `burstLength + safetyMargin` (e.g., 9 + 2).
- **Scratch/intermediate pool**: reusable tiles/pyramids for merge.
- **Output pool**: encoded output staging buffers.

## Copy-Avoidance Rules

- Import `AHardwareBuffer` directly to Vulkan/GLES external memory when possible.
- Use planar/tiled processing to avoid full-frame temporary clones.
- Reuse histogram/luma/chroma workspaces across frames.

## Lifetime Management

- Ref-count at native layer only.
- Kotlin receives immutable status/events, not raw pixel arrays.
- Add hard caps and watermarks; trigger quality downgrade before OOM.

---

## 6) Performance Scaling (Capability-Aware)

## Device Profiling Inputs

- SoC/GPU family, Vulkan level, extension support.
- CPU cores + frequencies, SIMD availability.
- NNAPI/DSP presence and effective latency.
- RAM class, sustained performance mode, thermal status.

## Tiering Example

### Tier A (Flagship)

- Full 10-bit HDR merge with multi-scale alignment.
- High-resolution Leica color LUT + 3D LUT interpolation.
- Advanced HyperTone WB (regional + illuminant mix model).
- Temporal denoise + detail recovery enabled.

### Tier B (Upper-mid)

- Fewer exposure brackets or reduced pyramid depth.
- Hybrid 3D LUT with smaller cube / cached interpolation.
- WB model simplified to fewer local regions.
- Selective denoise at half-res guidance maps.

### Tier C (Mid/Entry)

- Fast HDR (2-3 frames), simplified motion mask.
- Matrix + curve color science fallback.
- Global HyperTone WB fallback.
- Aggressive tile processing and reduced preview effects.

## Runtime Adaptation Loop

- Re-evaluate tier every few seconds (thermal + latency + memory pressure).
- If P95 frame latency or thermal threshold exceeded:
  1. reduce algorithm depth,
  2. reduce burst count,
  3. disable expensive refinements.

---

## 7) Native Algorithm Pipeline (Capture)

1. Burst ingest + metadata sync (exposure, gain, focus, gyro).
2. Motion classification + reference frame selection.
3. Multi-scale alignment (feature + flow-lite fallback).
4. Exposure fusion / merge in linear domain.
5. HyperTone WB (global estimate + local correction map).
6. Leica color transform (camera space -> target color rendering).
7. Tone mapping + local contrast.
8. Denoise/sharpen (tier-dependent).
9. Output encode + metadata embedding.

---

## 8) Kotlin ↔ C++ Contract Guidelines

- Keep JNI calls coarse-grained (job-level, not pixel-level).
- Use direct `ByteBuffer`/native handles for metadata blobs.
- Return job tokens; poll or callback completion asynchronously.
- Enforce strict state machine:
  - `CREATED -> WARM -> RUNNING -> DRAINING -> CLOSED`.

Error classes to propagate:
- Recoverable (dropped preview frame, temporary resource pressure)
- Session recoverable (re-init processing graph)
- Fatal (driver incompatibility / unrecoverable allocation failure)

---

## 9) Profiling, Observability, and “Profileable Code”

## Build/Tracing

- Enable Perfetto/ATRACE spans for each pipeline stage.
- Add counters: queue depth, dropped preview frames, merge latency percentiles, memory watermark.
- Keep symbols for native profiling in non-release internal builds.

## Metrics to Gate Releases

- Preview jank (% frames > 16.6 ms)
- Capture-to-save latency (P50/P95)
- Native heap peak during 10-bit burst
- Thermal throttling onset time
- Crash-free session rate by device tier

## Data Structure Discipline

- Prefer contiguous storage (`std::vector`, fixed-size ring buffers) over pointer-heavy graphs.
- Object pools for frequently reused nodes.
- Avoid per-frame allocations in hot paths.
- Use SoA layouts for SIMD-heavy kernels.

---

## 10) Incremental Migration Plan (No Big-Bang)

### Phase 1
- Introduce `:native-imaging-core` and JNI skeleton.
- Move one deterministic stage first (e.g., histogram + tone pre-pass).

### Phase 2
- Migrate HDR merge path to C++ with Kotlin fallback toggle.
- Add A/B validation tests for output parity.

### Phase 3
- Migrate HyperTone WB + Leica color engine.
- Enable capability tiering + runtime adaptation.

### Phase 4
- Optimize memory pools with HAL3/AHardwareBuffer interop.
- Remove legacy Kotlin pixel processing paths.

### Phase 5
- Finalize profiling gates, per-tier presets, and crash/latency SLOs.

---

## 11) Risks and Mitigations

- **JNI complexity** -> keep interface tiny and versioned.
- **Vendor GPU/driver variability** -> Vulkan first, GLES fallback path with feature flags.
- **Thermal collapse on long sessions** -> adaptive governor with conservative fallback.
- **Color consistency regressions** -> golden-image suite across illuminants and skin-tone charts.

---

## 12) Definition of Done

- No UI thread stalls attributed to camera processing.
- >90% of heavy compute time in native layer on supported devices.
- 10-bit HDR burst path stays under defined native memory ceiling per tier.
- Automatic quality scaling proven across at least 3 device classes.
- Visual parity (or improvement) verified against reference Leica color targets.
