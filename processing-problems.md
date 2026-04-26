# Processing Problems — What the App *Claims* to Run vs. What It *Actually* Runs

> **Companion to `Plan.md`.** `Plan.md` fixes the build and DI so the app can **compile, install and launch**. This document explains why, *even once the app runs*, none of the marquee imaging features (FusionLM 2.0, ColorLM 2.0, HyperTone AWB, ProXDR, advanced focus, skin-tone rendering, MicroISP, Image Classifier, Scene Understanding, neural AWB) are actually engaged on the capture path — and exactly what to change to engage them.
>
> Read this **after** completing Plan.md sub-plans P0–P3 (the build/DI gates). Plan.md sub-plan **P4** references this document as the algorithmic spec; many of the per-engine fixes below are the concrete implementation behind P4's high-level steps.

## Skill / Agent ownership

Every section below names the skill that owns the fix. Skills live under `.agents/skills/`. Activation: `activate_ai_developer_skill(skill_name="<name>")` in Claude Code, or read `.agents/skills/<name>/SKILL.md` directly for other executors.

| Section | Primary skill / agent | Secondary |
|---|---|---|
| §1 The top-level truth: capture flow is a narrator, not a pipeline | `the-advisor` | `analyzing-projects` |
| §2 FusionLM 2.0 — fuses one frame with itself | `Lumo Imaging Engineer` | `android-app-builder` |
| §3 ProXDR — selects a strategy, never executes it | `Lumo Imaging Engineer` | `Leica Cam Upgrade skill` |
| §4 HyperTone AWB — neural prior never reaches the WB engine | `color-science-engineer` | `Backend Enhancer` |
| §5 ColorLM 2.0 — engine runs, but input is wrong | `color-science-engineer` | `kotlin-specialist` |
| §6 ToneLM 2.0 / Durand bilateral — shadowed by a local tone mapper | `Lumo Imaging Engineer` | `Leica Cam Upgrade skill` |
| §7 Advanced Focus System — Kalman filter runs on synthetic inputs | `android-app-builder` | `systematic-debugging` |
| §8 Skin-tone rendering — face detector never routed to SkinToneProcessor | `color-science-engineer` | `Lumo Imaging Engineer` |
| §9 MicroISP — runner exists, sensor-id gate never consulted | `Lumo Imaging Engineer` | `android-app-builder` |
| §10 Image Classifier / Scene Understanding — downsampler returns zero | `Lumo Imaging Engineer` | `kotlin-specialist` |
| §11 Neural AWB model — loaded and warmed, then ignored | `color-science-engineer` | `Backend Enhancer` |
| §12 ZSL ring buffer — nothing ever adds a frame | `android-app-builder` (ARIA-X) | `Lumo Imaging Engineer` |
| §13 Native (Vulkan/C++) runtime — never fed a preview frame | `android-app-builder` | `devops-infrastructure` |
| §14 Consolidated fix ordering | `the-advisor` | `executing-plans` |

For the pipeline itself the `Lumo Imaging Engineer` and `Leica Cam Upgrade skill` are the two deepest-domain skills — every section that rewrites algorithmic code should open its `SKILL.md` before editing. For Kotlin/DI/build glue, `Backend Enhancer` + `android-app-builder` are faster.

---

## §1 The top-level truth: capture flow is a narrator, not a pipeline

The `CaptureProcessingOrchestrator.processCapture()` method in `core/capture-orchestrator/src/main/kotlin/com/leica/cam/capture/orchestrator/CaptureProcessingOrchestrator.kt` narrates a 15-stage pipeline via `logger.info(...)` calls:

```
ZSL → AF → Metering → ISP detect → Ingest → Align → Fuse → AI (scene/depth/face/colour) → HyperTone WB → Bokeh → Perceptual Tone (A-E) → Neural ISP → Skin Tone → 3D LUT → Film Grain → Output Encode
```

and every one of those log lines fires on every capture. But:

- **Six of those stages are stubs** — they log but do not call the engine the log line names.
- **Four stages** call a class from `capture-orchestrator`'s own package (`PerceptualToneMapper`, `SkinToneProcessor`, `Lut3DEngine`, `FilmGrainProcessor`) — local reimplementations that are **siblings** of the "real" engines, not a shim over them. They work but they are not what the README promises ("FusionLM 2.0", "Durand bilateral", "cinematic S-curve with face override").
- **Five stages** delegate to the correct interface (`IAiEngine`, `IColorLM2Engine`, `IDepthEngine`, `IFaceEngine`, `IMotionEngine`, `IBokehEngine`, `IHyperToneWB2Engine`) — but three of those interfaces have no real implementation (see Plan.md §P1) and the others get **degenerate input** because Stage 6 (fusion) returns the first frame verbatim.

**Net effect:** every photo the app captures today is:
1. Whatever the first preview-surface JPEG is that `ImageCapture.takePicture(...)` produces via CameraX (`Camera2CameraController.capture()`).
2. Plus some no-op post-processing on a `PhotonBuffer(Any())` stub that is not connected to the real frame.

The "pipeline result" is thrown away — see the shutter handler in `features/camera/src/main/kotlin/com/leica/cam/feature/camera/ui/CameraScreen.kt`:

```kotlin
val pipelineResult = deps.captureOrchestrator.processCapture(captureRequest)
when (pipelineResult) {
    is LeicaResult.Success -> {
        // Toast the scene label; the actual saved file is from sessionManager.capture()
    }
    is LeicaResult.Failure -> {
        val fallbackResult = deps.sessionManager.capture()   // the real file
        // …
    }
}
```

When `processCapture` succeeds, **no file is written from the pipeline output.** When it fails, `sessionManager.capture()` (pure CameraX takePicture) saves the JPEG. So in both cases the saved file is CameraX's, and none of the LUMO processing is in it.

### Fix ownership

| Layer | Skill | Action |
|---|---|---|
| Architecture — is "pipeline output" the same as "saved file"? | `the-advisor` + `designing-architecture` | Decide, document in an ADR. The default Advisor answer: **yes** — `processCapture` must return a `PhotonBuffer` that `OutputEncoder` writes to `DCIM/Camera`, and `sessionManager.capture()` is the fallback only. |
| Shutter handler — write the pipeline's output file, not a CameraX file | `android-app-builder` | Replace the `LeicaResult.Success` branch with an `OutputEncoder.writeToMediaStore(result.finalBuffer, result.outputMode)` call. |
| All the §2–§13 items below | Per-section skill | Each section states the exact engine-level fix. |

---

## §2 FusionLM 2.0 — fuses one frame with itself

**Claim (README):** *"A multi-frame RAW fusion engine (FusionLM 2.0) with physics-grounded Wiener weights derived from `SENSOR_NOISE_PROFILE` — never hard-coded constants."*

**Reality:**

```kotlin
// core/capture-orchestrator/.../CaptureProcessingOrchestrator.kt
private fun fusionFrames(
    aligned: AlignedBuffer,
    request: CaptureRequest,
): FusedPhotonBuffer {
    val firstFrame = aligned.frames.first()          // ← this is the fusion
    val fusionQuality = if (aligned.frames.size >= FUSION_MIN_FRAMES) 1.0f else 0.7f
    return FusedPhotonBuffer(
        underlying = firstFrame,
        fusionQuality = fusionQuality,
        frameCount = aligned.frames.size,
        motionMagnitude = 0f,
    )
}
```

There is no Wiener merge, no shot-noise model, no SENSOR_NOISE_PROFILE read. The log line `"Fusion complete: quality=… frameCount=N"` is a lie against the ground truth.

The **real** engine is `engines/imaging-pipeline/impl/src/main/kotlin/com/leica/cam/imaging_pipeline/pipeline/FusionLM2Engine.kt`. It is provided by `ImagingPipelineModule`, so once the DI fixes from Plan.md P1 land, it is injectable.

### What's wrong

1. `CaptureProcessingOrchestrator` does not depend on `FusionLM2Engine` at all — neither in its constructor nor in `CaptureOrchestratorModule`.
2. `core/capture-orchestrator/build.gradle.kts` only depends on `:imaging-pipeline:api` — `FusionLM2Engine` is in `:imaging-pipeline:impl`. Adding `implementation(project(":imaging-pipeline:impl"))` creates an architectural-boundary exception (see Plan.md §P4.1 for the documented carve-out).
3. `FusionConfig` must be chosen based on `captureMode` + `thermalBudget`. Today nothing builds a `FusionConfig` — the default constructor is used, which means `enableWienerMerge=false`, `enableDebevecMerge=false`, etc. (check the class for the exact field layout — this is why reading the real source before editing is mandatory).
4. The `NoiseModel` that `FusionLM2Engine` needs is supposed to come from `CameraCharacteristics.SENSOR_NOISE_PROFILE`. Today it is constructed via `NoiseModel.fromIsoAndExposure(iso, exposureNs)` which is a *synthetic* approximation — OK for tests, but the real per-sensor noise profile must come from the Camera2 metadata pipeline in `:sensor-hal`.

### How to fix (owned by `Lumo Imaging Engineer`)

1. **Wire FusionLM2Engine into the orchestrator** — full code in Plan.md §P4.1. Summary: inject `FusionLM2Engine`, replace the body of `fusionFrames()` with a real `fusionEngine.fuse(aligned, fusionConfig)` call.
2. **Build the FusionConfig from runtime state**. Add a helper:
    ```kotlin
    private fun buildFusionConfig(
        mode: CaptureMode,
        thermalBudget: ThermalBudget,
        frames: List<PipelineFrame>,
    ): FusionConfig {
        val evSpread = frames.maxOf { it.evOffset } - frames.minOf { it.evOffset }
        val strategy = when {
            evSpread < 0.5f && frames.size >= 3 -> FusionStrategy.WIENER_BURST
            evSpread >= 0.5f && thermalBudget.tier.allowsHeavyProcessing() -> FusionStrategy.DEBEVEC_LINEAR
            else -> FusionStrategy.MERTENS_FUSION  // Laplacian pyramid fallback
        }
        return FusionConfig(
            strategy = strategy,
            enableGhostMask = true,
            noiseSource = NoiseSource.SENSOR_METADATA,  // read from Camera2 at capture time
        )
    }
    ```
    Exact field names must match the actual `FusionConfig` — open the file and verify before editing.
3. **Feed the real noise profile**. `CameraCharacteristics.SENSOR_NOISE_PROFILE` is a `Float[]` with 2 × number-of-channels values (shot noise A, read noise B per channel). Parse it in `:sensor-hal` at camera-open time and pass it through `CaptureRequest.noiseProfile: FloatArray?` into `fusionEngine.fuse()`. Until that plumbing exists, pass `null` and let `FusionLM2Engine` fall back to `NoiseModel.fromIsoAndExposure()` — which is still better than the current stub.

### Verification

- Unit test: `FusionLM2EngineTest` already exists — run `./gradlew :engines:imaging-pipeline:impl:test --tests "FusionLM*"` and confirm it passes.
- Integration: on device, capture one photo in NIGHT mode (which asks for 8 burst frames, `ZSL_NIGHT_FRAME_COUNT = 8`). Filter logcat for `FusionLM2Engine`. **Expect** a line showing strategy + frame count. **Red-flag:** if `fused.frameCount == 1` in the "Fusion complete" log, ZSL is not feeding frames — this is the `§12` problem, not the FusionLM2 problem. Fix §12 first.

---

## §3 ProXDR — selects a strategy, never executes it

**Claim (README):** *"A scene-adaptive HDR engine (ProXDR) combining HDR+ burst merge, Debevec radiance recovery, and Mertens exposure fusion fallback — with ghost-aware MTB masking, highlight reconstruction from cross-channel ratios, and shadow restoration that runs before any tone lift."*

**Reality:**

`CaptureProcessingOrchestrator.processCapture()` builds an `HdrStrategy` via `HdrStrategyEngine.selectStrategy(...)` and logs it:

```
logger.info(TAG, "HDR strategy: ${hdrStrategy.javaClass.simpleName}, frames=${hdrStrategy.frameCount}, reason=${hdrStrategy.reason}")
```

And then **never uses `hdrStrategy` again.** There is no `proXdrOrchestrator.orchestrate(...)` call. The fused buffer from §2 (the first-frame stub) is handed straight to Stage 7 (parallel AI analysis).

The `ProXdrOrchestrator` exists and is provided by `ImagingPipelineModule`. Its `orchestrate()` method composes: `BracketSelector` → `MultiScaleFrameAligner` → `GhostMaskEngine` → `RadianceMerger` or `MertensFallback` → `HighlightReconstructor` → `ShadowRestorer`. That is the real ProXDR chain. It is fully implemented in `engines/imaging-pipeline/impl/src/main/kotlin/com/leica/cam/imaging_pipeline/hdr/`. The HDR types and constants are in `HdrTypes.kt`.

### What's wrong

1. `CaptureProcessingOrchestrator` does not depend on `ProXdrOrchestrator` (same `:impl`-dependency story as §2).
2. The branch logic that *should* route to ProXDR is absent. Under `HdrCaptureMode.PRO_XDR` the pipeline does exactly the same thing as `HdrCaptureMode.OFF`.
3. `ShadowRestorer` has the specific rule *"shadow denoise runs BEFORE the tone curve"* (README Law 4) — if ProXDR runs, this rule is enforced; without it, Stage 10 (tone mapping) operates on still-noisy shadows, and the later `FfdNetNoiseReductionEngine` denoises *after* the lift, amplifying noise ~4×.
4. `HighlightReconstructor` uses cross-channel ratios — if it is skipped, clipped highlights (sky, sun) stay clipped even in PRO_XDR mode.

### How to fix (owned by `Lumo Imaging Engineer`)

Full step-by-step in Plan.md §P4.2. Summary:

1. Inject `ProXdrOrchestrator` into `CaptureProcessingOrchestrator`.
2. Between Stage 6 (fusion) and Stage 7 (AI), insert:
    ```kotlin
    val postHdr: FusedPhotonBuffer = when (hdrMode) {
        HdrMode.PRO_XDR, HdrMode.ON -> proXdrOrchestrator.orchestrate(aligned.frames, hdrStrategy).getOrElse {
            logger.warn(TAG, "ProXDR failed (${it.message}); falling back to fused buffer")
            fused
        }
        HdrMode.SMART ->
            if (parallelResults.scene.dynamicRangeEstimate() > SMART_HDR_THRESHOLD_STOPS)
                proXdrOrchestrator.orchestrate(aligned.frames, hdrStrategy).getOrElse { fused }
            else fused
        HdrMode.AUTO, HdrMode.OFF -> fused
    }
    ```
    **Trap:** `proXdrOrchestrator.orchestrate` may return a `LeicaResult<HdrMergeResult>` (with ghost mask) rather than a raw `FusedPhotonBuffer` — unwrap to the `mergedFrame` as shown in `ImagingPipeline.kt`.
3. In SMART mode, the gating needs a *real* dynamic-range estimator. Today `runPreCaptureAnalysis` does not compute one; `CaptureRequest.dynamicRangeStops` is hardcoded to `8f`. Add a helper that reads the AE histogram from `CaptureResultProxy.histogramLuma` (Camera2 proxy) and returns the 99th - 1st percentile in EV stops.
4. The `PRO_XDR` branch should additionally invoke `HighlightReconstructor` + `ShadowRestorer` in that order **before** returning. The `ProXdrOrchestrator` already composes these — verify by reading the class, and do not re-implement them here.

### Verification

- Capture a high-DR scene (bright sky + shadowed foreground) in PRO_XDR mode. Expect logcat lines from `ProXdrOrchestrator`, `RadianceMerger`, `HighlightReconstructor`, `ShadowRestorer`.
- Compare the resulting JPEG vs. an OFF-mode capture of the same scene — sky should retain structure (not clipped white), shadows should show detail without colour noise.
- Regression unit test: `ProXdrOrchestratorTest` already exists — run it.

---

## §4 HyperTone AWB — neural prior never reaches the WB engine

**Claim (README):** *"A white-balance engine (HyperTone WB) anchored on skin tone (±300 K clamp around the skin CCT), using Robertson's method in CIE 1960 (u, v) space — not McCamy's approximation."*

Plus: *"every model runs on the device, every time"* — specifically the AWB model `awb_final_full_integer_quant.tflite`.

**Reality:**

Two WB orchestrators coexist, and **the production path uses the wrong one**:

1. `HyperToneWhiteBalanceEngine` (in `engines/hypertone-wb/impl/.../pipeline/HyperToneWhiteBalanceEngine.kt`) — the *correct* orchestrator. It has a constructor parameter `awbPredictor: AwbPredictor?`. In `process()`, if the predictor is non-null, it runs `AwbModelRunner.predict(tile, wbBias)` to get a neural CCT / D_uv / confidence tuple, blends it with the Robertson histogram estimator, and passes the blended CCT to `HyperToneWB2Engine.process(...)`.
2. `HyperToneWB2Engine` (same package, different file) — the per-zone bilateral WB engine. It takes an `AwbNeuralPrior?` as an optional parameter in its `process()` signature.

`CaptureProcessingOrchestrator.runWhiteBalanceCorrection()` goes through **neither**. It calls the `IHyperToneWB2Engine` interface's `correct(colour, skinZones, illuminant)` method:

```kotlin
val wbCorrected = wbEngine.correct(results.colour, skinZones, illuminantMap).getOrElse { ... }
```

And `illuminantMap` is constructed with `tiles = emptyList(), dominantKelvin = results.scene.illuminantHint.estimatedKelvin`. The `dominantKelvin` is the **AI engine's guess**, not the neural AWB model's prediction — and in the current orchestrator `AiEngineOrchestrator`, the `IlluminantEstimator.estimate()` is a **hardcoded if-else** on the scene label:

```kotlin
// engines/ai-engine/impl/.../AiEngineOrchestrator.kt
fun estimate(fused: FusedPhotonBuffer, fallbackLabel: SceneLabel): IlluminantHint {
    val kelvin = when (fallbackLabel) {
        SceneLabel.NIGHT -> 3000f
        SceneLabel.INDOOR -> 3200f
        SceneLabel.FOOD -> 3500f
        SceneLabel.PORTRAIT -> 5000f
        SceneLabel.LANDSCAPE, SceneLabel.OUTDOOR -> 5500f
        else -> 6500f
    }
    return IlluminantHint(estimatedKelvin = kelvin, confidence = 0.5f, isMixedLight = false)
}
```

So the flow is: scene label (from the Image Classifier — which receives zero-filled tiles, see §10) → a hard-coded CCT → a "WB correction" that is effectively a no-op.

The `AwbModelRunner` is:
- Loaded at app start (`LeicaCamApp.onCreate` → `ModelRegistry.warmUpAll`), ✓
- Warmed with a synthetic 224×224×3 tile to amortise delegate init cost, ✓
- Provided via Hilt as `AwbPredictor`, ✓
- **Never called during capture.** ✗

### What's wrong

1. `HyperToneWhiteBalanceEngine` is not in the Hilt graph-walk reachable from `CaptureProcessingOrchestrator`. It has `@Inject constructor(wb2Engine, awbPredictor)`, so Hilt *can* instantiate it — but nothing requests an instance.
2. The `IHyperToneWB2Engine` interface that the orchestrator uses does not accept an `AwbNeuralPrior` in its `correct()` signature. The adapter pattern introduced in Plan.md §P1.5 preserves that API — but the real fix is to route the AWB call through `HyperToneWhiteBalanceEngine.process(...)` **before** `wbEngine.correct(...)` and stash the neural CCT in the `IlluminantMap.dominantKelvin`.
3. The `sensorWbBias` parameter to `AwbPredictor.predict` is supposed to come from the `SensorProfile` registry (`platform-android/sensor-hal/.../sensor/profiles/SensorProfileRegistry.kt`). Today no caller populates it — the AWB runner would be called with an all-ones bias, which feeds the model a slightly-off illuminant estimate.
4. The `wbBias` in `HyperToneWhiteBalanceEngine.process()` is the per-sensor OV64B/OV50D/OV08 B-boost (README "Main secondary: B boost 1.03–1.05 at high CCT"). Never wired.

### How to fix (owned by `color-science-engineer`)

Algorithmic walk-through — the exact code is in Plan.md §P4.3.

1. **Wire `HyperToneWhiteBalanceEngine` into the orchestrator**. It needs three things:
   - An `RgbFrame` built from the fused photon buffer (add an extension function `FusedPhotonBuffer.toRgbFrame(): RgbFrame`).
   - A `sensorToXyz3x3: FloatArray(9)` matrix — read from `CameraCharacteristics.SENSOR_FORWARD_MATRIX1` at camera-open time; store on `CaptureRequest`; pass through.
   - A `wbBias: FloatArray?` — resolved from the active `SensorProfile` via `currentCameraId`.
2. **Replace `runWhiteBalanceCorrection()`**. After its output RGB frame, pass the resulting CCT as `IlluminantMap.dominantKelvin` to `IHyperToneWB2Engine.correct(...)`. Now `IHyperToneWB2Engine`'s per-zone bilateral gain-field uses the *neural* CCT as its anchor instead of the AI-engine hardcoded switch.
3. **Stop the AI-engine's `IlluminantEstimator` from setting `dominantKelvin`**. Either delete that class (its signal is redundant once the neural model runs) or demote it to a fallback that only activates when `AwbNeuralPrior.confidence < 0.2f`.
4. **Feed `sensorWbBias`**. In `runWhiteBalanceCorrection`:
    ```kotlin
    val sensorProfile = sensorProfileRegistry.profileFor(request.currentCameraId)
    val sensorWbBias = sensorProfile.wbBiasRgb  // e.g. OV64B: floatArrayOf(1.00f, 1.00f, 1.04f)
    ```

### Edge cases

- **Skin anchor rule (Law 5)**: `HyperToneWB2Engine.computeSkinAnchorCct(frame, skinMask, sensorToXyz3x3)` needs a **real** skin mask. Today the orchestrator passes `results.face.skinZones.mask` (from `:face-engine:impl`'s `SkinZoneMapper`). Confirm that `SkinZoneMapper` actually produces a non-empty mask; if it does not (face mesh never ran — see §8), the skin-anchor clamp silently degrades to "no clamp".
- **Mixed-light scenes**: `HyperToneWB2Engine` has a `MixedLightSpatialWbEngine`. It relies on the 4×4 tile illuminant map. Today `IlluminantMap.tiles = emptyList()` in the orchestrator — fix by asking `PartitionedCTSensor.sample()` for the 16 tiles. That requires the `TrueColourHardwareSensor` default from Plan.md §P1.3 to return *plausible* (non-neutral) tile data — update the `NoSpectralSensor` default to distribute tiles around the global CCT with light-source priors, or tag this as a known limitation in `docs/known-issues/processing.md`.
- **Temporal smoothing**: `prevCctKelvin` lives on `HyperToneWB2Engine`. The engine must therefore be `@Singleton` (Plan.md §P2.1 enforces this). Do not inadvertently scope it to `@ActivityRetainedScoped`.

### Verification

- Add a unit test `HyperToneWhiteBalanceEngineIntegrationTest` that:
  - Mocks an `AwbPredictor` returning `AwbNeuralPrior(cctKelvin = 3200f, tintDuv = 0.003f, confidence = 0.8f)`.
  - Feeds a synthetic frame.
  - Asserts the resulting CCT is within 300 K of 3200 K (skin-anchor rule should not pull it far, since no face is detected).
- On device, point the camera at a tungsten lamp. Toggle the neural AWB on vs. off (wire a debug flag). Without it, the JPEG has a strong orange cast; with it, the image renders close to neutral.

---

## §5 ColorLM 2.0 — engine runs, but input is wrong

**Claim (README):** *"A colour-science layer (ColorLM 2.0) with two-illuminant CCM interpolation, CUSP gamut mapping, and extended-gamut Display P3 output."*

**Reality:** the engine `ColorLM2EngineImpl` is implemented (`core/color-science/impl/.../pipeline/ColorLM2EngineImpl.kt`). It is bound via `ColorScienceModule.bindColorLM2Engine(impl: ColorLM2EngineImpl): IColorLM2Engine`. It *is* invoked from `CaptureProcessingOrchestrator.runParallelAnalysis`:

```kotlin
val colourDeferred = async { colourEngine.mapColours(fused, sceneContext) }
```

So — unlike FusionLM2 / ProXDR / ToneLM2 / HyperToneWhiteBalanceEngine — this one actually runs.

However, the **inputs** are degenerate:

- `fused` is the first-frame stub (§2) — no multi-frame merge happened.
- `sceneContext = SceneContext(sceneLabel = "auto", illuminantHint = IlluminantHint(6500f, 0.5f, false))` — the scene label is the string `"auto"` regardless of what the classifier said, and the illuminant hint is hardcoded 6500 K. So the two-illuminant CCM interpolation blends between the A-illuminant and D65 CCMs using `t = 1.0` (pure D65) always.
- The `DngDualIlluminantInterpolator` reads `SENSOR_FORWARD_MATRIX1` / `SENSOR_FORWARD_MATRIX2` from the active sensor. The relocated `docs/color-science/Plan-CS.md` (née `Plan.md` before this remediation) gives the full wiring there. **Today it is still hardcoded defaults** (`defaultSensorForwardMatrixA/D65`), as called out in CS-2.

### What's wrong

1. Scene label and illuminant hint are not fed from the AI engine's results.
2. Dual-illuminant CCMs are synthetic defaults — they produce a valid output colour space, just not *this device's* colour space.
3. `ColourMappedBuffer` is handed to the WB correction step which then discards it (the WB adapter in Plan.md §P1.5 just passes the underlying photon buffer forward; the colour-mapped result is ignored). That means the two-illuminant CCM's output is lost.

### How to fix (owned by `color-science-engineer`)

1. **Wire scene label correctly.**
    ```kotlin
    private fun sceneContextFromAi(scene: SceneAnalysis, illum: com.leica.cam.ai_engine.api.IlluminantHint): SceneContext {
        return SceneContext(
            sceneLabel = scene.sceneLabel.name.lowercase(),   // e.g. "portrait", "landscape"
            illuminantHint = com.leica.cam.color_science.api.IlluminantHint(
                estimatedKelvin = illum.estimatedKelvin,
                confidence = illum.confidence,
                isMixedLight = illum.isMixedLight,
            ),
            captureMode = scene.captureMode?.name ?: "auto",
        )
    }
    ```
    **Trap:** `SceneContext` in `:color-science:api` has a **different** `IlluminantHint` from the AI-engine one (Plan.md §P2 Appendix item 19). Use the explicit mapper above; do not rely on type-alias luck.
2. **Fix dual-illuminant CCMs.** Follow the relocated `docs/color-science/Plan-CS.md` CS-2: implement `Camera2ColorCalibrationReader` that reads `SENSOR_FORWARD_MATRIX1`, `SENSOR_FORWARD_MATRIX2`, `SENSOR_CALIBRATION_TRANSFORM1/2`, and the black/white levels from `CameraCharacteristics` at camera-open time, and exposes them via a `SensorColorCalibration` data class. Pass it through `ColorLM2EngineImpl`.
3. **Route the ColourMappedBuffer correctly.** The WB adapter in Plan.md §P1.5 is a placeholder — replace its body to pass the **ColourMappedBuffer's transformed RGB** through `HyperToneWB2Engine.process()` (not the fused photon buffer). The correct sequencing is: Fusion → Colour Mapping (ColorLM2) → White Balance (HyperTone) → Tone (ToneLM2). The current sequencing skips the colour mapping's output.

### Verification

- ΔE2000 regression using the existing `ColorAccuracyBenchmark` (`core/color-science/impl/.../pipeline/ColorScienceEngines.kt`). Feed the X-Rite ColorChecker 24-patch synthetic, under D65, check that ΔE2000 mean < 3.0 and max < 5.5. Same under A illuminant (tungsten) with a different `SceneContext.illuminantHint`. If the interpolation is correctly wired, both scenes pass. If only D65 passes, the wiring is still halfway.

---

## §6 ToneLM 2.0 / Durand bilateral — shadowed by a local tone mapper

**Claim (README):** *"A perceptual tone pipeline (ToneLM 2.0): shadow denoise → local EV modulation by semantic priority → Durand bilateral base/detail decomposition → cinematic S-curve with face override → luminosity-only sharpening."*

**Reality:** `CaptureProcessingOrchestrator.Stage 10` calls `perceptualToneMapper.map(...)` — where `perceptualToneMapper` is the `PerceptualToneMapper` class **from the `capture-orchestrator` module itself** (`core/capture-orchestrator/src/main/kotlin/com/leica/cam/capture/tone/PerceptualToneMapper.kt`). That class exists and has its own A-E stage implementation (global Reinhard, bilateral local contrast, highlight roll-off, shadow lift, DoG ACE). It is competent code, but it is **not** ToneLM 2.0.

ToneLM 2.0 lives at `engines/imaging-pipeline/impl/.../pipeline/ToneLM2Engine.kt`. It is never injected into the orchestrator.

### Why this matters

- ToneLM 2.0 composes `DurandBilateralToneMappingEngine` + `CinematicSCurveEngine` + `ShadowDenoiseEngine` + `LuminositySharpener` — each has its own research citation (Durand & Dorsey SIGGRAPH 2002, Reinhard SIGGRAPH 2002 for the preview path, Burt-Adelson TCOM 1983 for the Laplacian pyramid used in Mertens fallback).
- The orchestrator's local `PerceptualToneMapper` uses a single bilateral with `BILATERAL_SIGMA_SPATIAL_FRAC = 0.02f`, `BILATERAL_SIGMA_RANGE = 0.4f` — these are *inside* `ImagingPipeline.kt` as module-level constants. Same numbers, but the composition is different: no face override on the S-curve (ToneLM2 has it; this one does not), no semantic-priority EV modulation, no shadow denoise before the lift (Law 4 violation).
- Therefore photos captured through the current path are merely "tone-mapped" — they are not "cinematic" or "skin-tone-protected".

### How to fix (owned by `Lumo Imaging Engineer`)

Full code in Plan.md §P4.4. Summary:

1. Inject `ToneLM2Engine` into `CaptureProcessingOrchestrator`.
2. Replace the `toneMapped = perceptualToneMapper.map(...)` line with a `toneMapped = toneEngine.apply(buffer, scene, toneConfig)` call, fallback to local `perceptualToneMapper.map(...)` on failure.
3. The `toneConfig` that reaches `ToneLM2Engine.apply()` must include the **face bounding box** so the S-curve knee softens over faces. Read `parallelResults.face.primaryFace.boundingBox` and attach it to `toneConfig.faceOverride`.

### Edge cases

- **Shadow denoise order**: ToneLM2's `ShadowDenoiseEngine` must run *before* the `CinematicSCurveEngine` — it does, inside `ToneLM2Engine.apply()`. Do not manually invoke them in the wrong order at the call site.
- **Luminosity-only sharpening**: the last step is a YCbCr Y-channel sharpener. Chroma is untouched. If the fallback `perceptualToneMapper.map(...)` runs instead, chroma is also affected — you will see a visible difference (slightly over-saturated highlight fringes) on synthetic resolution charts.

### Verification

- Capture a backlit portrait under mixed lighting (README says: "On-device smoke test is mandatory before any release"). Compare against a reference. ToneLM2 is correctly engaged if:
  - Shadow areas do not show amplified colour noise.
  - Face tones stay in the midtone region of the S-curve (the face override).
  - The sky-horizon transition is monotonic (Durand bilateral preserves edges).

---

## §7 Advanced Focus System — Kalman filter runs on synthetic inputs

**Claim (README):** The sensor-HAL ships with a `HybridAutoFocusEngine` and a `PredictiveAutoFocusEngine` (Kalman-filtered). These gate PDAF, contrast AF, and neural-subject confidence.

**Reality:** `runPreCaptureAnalysis()` calls both engines:

```kotlin
val afInput = AutoFocusInput(
    pdafPhaseError = request.pdafPhaseError,
    contrastMetric = request.contrastMetric,
    neuralSubjectConfidence = request.neuralSubjectConfidence,
)
val afDecision = hybridAutoFocus.evaluate(afInput)
val predictedFocus = predictiveAutoFocus.predict(...)
```

But `CaptureRequest.pdafPhaseError` / `contrastMetric` / `neuralSubjectConfidence` come from the **shutter-press `CaptureRequest` construction**:

```kotlin
// features/camera/.../CameraScreen.kt
val captureRequest = CaptureRequest(hdrMode = hdrMode)
```

All three AF inputs use the default values `0f`, `0.5f`, `0.7f`. So the Kalman filter predicts a focus position based on no real phase error and a constant contrast — it is not actually following the scene.

### What's wrong

1. CameraX/Camera2 preview frames carry `CaptureResult.keys` with PDAF phase error (vendor-specific but published on Dimensity / Snapdragon / Exynos). Nothing reads them.
2. The "neural subject confidence" is supposed to come from `IAiEngine.classifyAndScore(...).trackedObjects.firstOrNull()?.confidence` — but the AI engine hasn't run yet at the point `runPreCaptureAnalysis` fires (pre-capture happens *before* Stage 7). Fix by caching the **previous frame's** tracked-subject confidence in a `StateFlow` owned by `CameraUiOrchestrator` and reading it into `CaptureRequest`.
3. `PredictiveAutoFocusEngine.predict(currentConfidence, focusMode, timestampMs)` is a Kalman update. With constant input it predicts constant output. The filter state is *not* preserved across captures (new `CaptureRequest` every shutter press), so each capture starts fresh.

### How to fix (owned by `android-app-builder` (ARIA-X))

1. **Wire PDAF phase error from Camera2.** Add a `ContinuousCaptureMetadataListener` in `Camera2CameraController` that subscribes to `CaptureResult` for the preview session, extracts the vendor-specific PDAF key (MediaTek: `com.mediatek.control.capture.bestFocusPos`; Qualcomm: `org.codeaurora.qcamera3.stats.focusValue`), and exposes it through a `StateFlow<AfTelemetry>`.
2. **Read it into `CaptureRequest`.** At shutter-press:
    ```kotlin
    val afTelemetry = deps.sessionManager.currentAfTelemetry()  // new API
    val captureRequest = CaptureRequest(
        hdrMode = hdrMode,
        pdafPhaseError = afTelemetry.pdafPhaseError,
        contrastMetric = afTelemetry.contrastMetric,
        neuralSubjectConfidence = afTelemetry.subjectConfidence,
    )
    ```
3. **Preserve Kalman state across captures.** Scope `PredictiveAutoFocusEngine` as `@Singleton` (already done in `SensorHalModule`). Verify that its internal `previousState: Filter.State` field is not reset on each `predict()` call. Add an integration test that calls `predict()` 10 times with a linear phase-error ramp and asserts the predicted position tracks within ±2 px.

### Verification

- Enable `adb logcat -v time *:W HybridAutoFocusEngine:D PredictiveAutoFocusEngine:D` and capture while panning the camera across a scene with a moving subject (a person walking). The logs should show the focus mode transitioning PDAF → HYBRID → CONTRAST with non-zero phase errors.

---

## §8 Skin-tone rendering — face detector never routed to SkinToneProcessor

**Claim (README):** *"An AI engine that orchestrates on-device TFLite / MediaPipe models for … face landmarks, scene classification, semantic segmentation, and a MicroISP refiner"* — plus the Munsell/Fitzpatrick skin-tone anchor correction in `SkinToneProcessor`.

**Reality:** the face-detection flow is:

1. Camera preview fires. Nothing forwards the preview frame to `FaceLandmarkerRunner.detect(...)` — the MediaPipe Face Landmarker *is* loaded, but it has no input. It is only invoked from `AiEngineOrchestrator.classifyAndScore(...)` which receives a `faceArgb8888: IntArray?` parameter. In `CaptureProcessingOrchestrator.runParallelAnalysis()`, that parameter is **never populated** (the orchestrator calls `aiEngine.classifyAndScore(fused, request.captureMode)` — the short form, which defaults `faceArgb8888 = null`).

2. `AiEngineOrchestrator` handles the null case:
    ```kotlin
    val faceDeferred = async(Dispatchers.Default) {
        if (faceArgb8888 != null && faceWidth > 0 && faceHeight > 0) {
            faceLandmarker.detect(...).getOrDefault(emptyFaceOutput(faceWidth, faceHeight))
        } else {
            emptyFaceOutput(0, 0)
        }
    }
    ```
    So the `faceOutput.faces` is always empty. `results.face.skinZones.mask` is therefore empty. `SkinToneProcessor.process(...)` gets called with an empty face analysis and does nothing.

3. `FaceAnalysis.primaryFace` (used by `PortraitModeEngine`, `ToneLM2Engine`'s face override) is `null`.

### What's wrong

The missing wire is from the fused photon buffer back down to an ARGB8888 bitmap that MediaPipe can consume.

1. **A byte-layout conversion is needed.** `MediaPipe FaceLandmarker.detect(MPImage)` wants a bitmap; `FaceLandmarkerRunner` already does the bitmap conversion from `IntArray(ARGB8888)`. The missing piece is turning `FusedPhotonBuffer` → `IntArray(ARGB8888)`. The photon buffer is linear 16-bit RGB; to get a classifier-ready 8-bit ARGB you need a gamma curve and chroma clipping. Use the preview tone-mapped buffer, not the scene-referred linear one.
2. **Front-camera / back-camera asymmetry**. `FaceLandmarkerRunner.openOrFail(isFrontCamera)` sets `minFaceDetectionConfidence` to 0.45 (front) vs 0.55 (back). The orchestrator passes `request.captureResultProxy` which has no "is front" bit — thread `preferences.cameraFacing == CameraFacing.FRONT` through `CaptureRequest` instead.

### How to fix (owned by `color-science-engineer` + `Lumo Imaging Engineer`)

1. **Add a fused-to-argb preview conversion.** Extension:
    ```kotlin
    fun FusedPhotonBuffer.toArgb8888(): Pair<IntArray, Pair<Int, Int>> {
        // Quick gamma 2.2 + clip; build IntArray with 0xAARRGGBB packed pixels.
        // Target 1280×960 for face detection (MediaPipe's expected input range).
    }
    ```
2. **Populate the AI-engine call.** In `runParallelAnalysis`:
    ```kotlin
    val (faceArgb, dims) = fused.toArgb8888()
    val (faceWidth, faceHeight) = dims
    val sceneDeferred = async {
        aiEngine.classifyAndScore(
            fused = fused,
            captureMode = request.captureMode,
            faceArgb8888 = faceArgb,
            faceWidth = faceWidth,
            faceHeight = faceHeight,
            isFrontCamera = request.isFrontCamera,
        )
    }
    ```
3. **Route the resulting face analysis to SkinToneProcessor.** Already done in Stage 12 — once the input is non-empty, it starts working. No orchestrator change needed beyond §8.1/2.

### Edge cases

- **`FaceLandmarkerRunner.obtainBitmap` is synchronised on `this`.** Good — thread-safe. But it recycles the previous bitmap on resize; do not hold a reference to the previous bitmap outside the runner.
- **478-point landmark to skin-zone mask**: `:face-engine:impl`'s `SkinZoneMapper` converts the 478 mesh points into a skin-probability map. If the input points are in MediaPipe's (0,0)-(1,1) normalised space and `SkinZoneMapper` expects absolute pixels, the mask is off. Verify the coordinate convention in the existing unit test `FaceEngineTest`.

### Verification

- Capture a portrait at normal indoor light. Log `FaceLandmarker.detect(...)` and `SkinToneProcessor.process(...)` durations. Both should be non-zero, and `SkinToneProcessor` should report *N > 0 faces*. The resulting JPEG should show a slight chroma smoothing over skin without any visible "plastic doll" oversmoothing — the Munsell anchor correction should pull a green-cast indoor skin back toward a neutral Fitzpatrick II-III anchor.

---

## §9 MicroISP — runner exists, sensor-id gate never consulted

**Claim (README):** *"`Model/MicroISP/MicroISP_V4_fp16.tflite` — Learned Bayer-domain refinement — ultra-wide / front only (disabled on S5KHM6 to avoid double-processing Imagiq ISP output)."*

**Reality:** `MicroIspRunner` is:
- Loaded + warmed at app start ✓
- Provided as `NeuralIspRefiner` via Hilt ✓
- Has the correct eligibility logic:
    ```kotlin
    override fun isEligible(sensorId: String): Boolean {
        val lower = sensorId.lowercase()
        return lower.contains("ov08d10") || lower.contains("ov16a1q") || lower.contains("gc16b3")
    }
    ```

But `CaptureProcessingOrchestrator` never calls `isEligible()`. The `neuralIsp: INeuralIspOrchestrator` is the `SmartImagingOrchestrator`'s neural ISP — a different engine that does `RawDenoise → LearnedDemosaic → ColorTone → SemanticEnhancement`. That engine does not call `MicroIspRunner.refine(bayerTile)`.

So MicroISP runs **never**, regardless of sensor.

### What's wrong

1. The MicroISP step (`refine(bayerTile: FloatArray)`) needs to happen *inside* the Bayer/RAW domain, before demosaicing. The current capture orchestrator operates on `FusedPhotonBuffer` (post-demosaic linear RGB), so by the time it gets the buffer it is too late.
2. The **Bayer-domain data** is inside the native imaging runtime (`platform-android/native-imaging-core/impl/src/main/cpp/native_imaging_core.cpp`). Today `NativeImagingRuntimeFacade.submitPreviewFrame(...)` is *never called* (see §13) — so the native side never holds a Bayer tile to run MicroISP on.

### How to fix (owned by `Lumo Imaging Engineer`)

This is a larger refactor. The minimum viable shim (MVS):

1. **Invoke MicroISP via the `ImagingPipeline` class.** `ImagingPipeline.kt` has a field `microIspRefiner: NeuralIspRefiner?`. In the `run(...)` method it invokes `refine(bayerTile)` at the right pipeline position. If Plan.md §P4 routes the capture through `ImagingPipeline.run(...)` (rather than its current inline stages), MicroISP runs for free.
2. **Respect the sensor-id gate.** Wrap `refine()`:
    ```kotlin
    if (microIspRefiner != null && microIspRefiner.isEligible(currentSensorId)) {
        microIspRefiner.refine(bayerTile)
    }
    ```
    `currentSensorId` must be passed through `PipelineFrame` or `ImagingPipeline`'s context; today it is not. Add a `CaptureRequest.activeSensorId: String` field, and thread it through to `ImagingPipeline.run()`.
3. **Feed a real Bayer tile.** The `FusedPhotonBuffer` is RGB. `MicroIspRunner` expects a `256×256×4` float tile (R, Gr, Gb, B channels). The `FusionLM2Engine` in `:imaging-pipeline:impl` operates on Bayer; add a `fusion.lastBayerTile(): FloatArray?` accessor so downstream stages can refine it.

### Edge cases

- **Thermal budget**: run MicroISP only when `thermalBudget.tier >= ThermalTier.FULL`. Under thermal throttle, skip (log a warn).
- **Multi-camera seam**: on ultra-wide + main composite images, MicroISP runs only on the ultra-wide region. This is out of scope for Phase 0 wiring; track in `docs/known-issues/processing.md`.

### Verification

- Capture with the ultra-wide lens. Verify a logcat line from `MicroIspRunner.refine` with non-zero duration.
- Capture with the main (S5KHM6). Verify that `MicroIspRunner.refine` does **not** fire and `isEligible("s5khm6")` returns false.

---

## §10 Image Classifier / Scene Understanding — downsampler returns zero

**Claim (README):**
- *"`Model/Image Classifier/1.tflite` — Scene classification → `SceneLabel` → mode routing"*
- *"`Model/Scene Understanding/deeplabv3.tflite` — Semantic segmentation → `SemanticMask` for priority tone mapping"*

**Reality:** both models are loaded, both have correctly-written runners (`SceneClassifierRunner`, `SemanticSegmenterRunner`). The fatal flaw is in their *input*:

```kotlin
// engines/ai-engine/impl/.../pipeline/AiEngineOrchestrator.kt
private fun downsample(
    fused: FusedPhotonBuffer,
    targetWidth: Int,
    targetHeight: Int,
): FloatArray = FloatArray(targetWidth * targetHeight * 3)
```

Returns a zero-filled array. Every time. So:

- `SceneClassifier.classify(tile=zeros)` → mostly uniform output → `primaryLabel = GENERAL` always (or whichever class index happens to have the highest logit for all-zero input, which is stable per model but meaningless).
- `SemanticSegmenter.segment(tile=zeros, ...)` → all-BACKGROUND zones (or UNKNOWN) → `SemanticMask` is uniform.

Every downstream stage that reads scene label or semantic mask inherits this garbage:
- ColorLM2 two-illuminant interpolation (§5) uses the scene label.
- ToneLM2 local EV modulation uses the semantic mask for priority zones.
- HyperTone WB multi-modal fusion has a semantic predictor.
- `IlluminantEstimator.estimate()` uses the scene label.

### What's wrong

The downsampler is an obvious stub. Easy fix (see Plan.md §P2.2). Beyond the stub, there's a second subtler bug: `sceneTileRgb224x224` and `segTileRgb257x257` are declared as **optional** parameters to `classifyAndScore` — when non-null they bypass the local downsample. Nobody passes them. So even after §P2.2 fixes the internal downsampler, the RGB pipeline should pass the pre-computed tiles (which are already produced elsewhere: `HyperToneWB2Engine` downsamples to 224×224 for its AWB prior tile — reuse that output).

### How to fix (owned by `Lumo Imaging Engineer`)

1. **Fix the downsampler body.** Code in Plan.md §P2.2. Nearest-neighbour over the fused buffer's first frame.
2. **Reuse the AWB tile for the classifier.** Since AWB and scene classifier both want 224×224×3 RGB, build one tile and hand it to both:
    ```kotlin
    val tile224 = fused.downsampleRgb(224, 224)
    val sceneDeferred = async { sceneClassifier.classify(tile224) }
    val awbDeferred = async { awbPredictor?.predict(tile224, sensorWbBias) }
    ```
    **Trap:** AWB expects the raw linear tile; scene classifier expects whatever normalisation its metadata says. `SceneClassifierRunner.detectNormStyle` handles that internally — pass the linear [0,1] tile and let each runner apply its own normalisation.
3. **Map ImageNet class IDs → SceneLabels correctly.** `SceneClassifierRunner.mapClassIdToLabel` already has a reasonable mapping (the P2-fix commented: *"Food classes (ImageNet 924-950) — checked BEFORE night to avoid the old overlap"*). But the top-5 logic in `SceneClassificationOutput` sorts then maps → you can get 5 different labels in the top-5 (e.g. `PORTRAIT`, `PORTRAIT`, `PORTRAIT`, `GENERAL`, `PORTRAIT`) because ImageNet has 4 human-related classes in 0..19 and our mapper collapses them all to `PORTRAIT`. That is fine for the primary label but breaks confidence: `primaryConfidence = top5.firstOrNull()?.second ?: 0f` returns the #1 item's probability, but adjacent duplicates inflate nothing. Confidence stays low even when the scene is clearly a portrait. Fix by summing probabilities per unique `SceneLabel` before sorting.

### Verification

- Unit test: feed a known test image (a landscape JPEG converted to 224×224 RGB float) and assert `primaryLabel == LANDSCAPE`, confidence > 0.3.
- On-device: stare at a food plate for 2 seconds after opening the app; the scene badge should update to "Food".

---

## §11 Neural AWB model — loaded and warmed, then ignored

Already covered implicitly by §4. Summary (for quick reference):

| State | Where |
|---|---|
| Asset present on disk | `Model/AWB/awb_final_full_integer_quant.tflite` |
| Copied into APK | `app:preBuild` → `copyOnDeviceModels` (works; verify after Plan.md §P3.2 lands) |
| Discovered by ModelRegistry | Yes, role = `AUTO_WHITE_BALANCE` via filename match in `matchFilenameKeywords()` |
| LiteRT session opened | On first `AwbPredictor.predict(...)` call (lazy) |
| Warmed at app start | Yes, `LeicaCamApp.onCreate` → `ModelRegistry.warmUpAll` with a synthetic 224×224×3 tile |
| Provided via Hilt as `AwbPredictor` | Yes (`AiEngineModule.provideAwbPredictor`) |
| Injected into `HyperToneWhiteBalanceEngine` | Yes, nullable `AwbPredictor?` |
| `HyperToneWhiteBalanceEngine` called from production code | **No.** |

The fix (wire `HyperToneWhiteBalanceEngine` into `CaptureProcessingOrchestrator.runWhiteBalanceCorrection`) is §4.

### Bonus: output-layout validation

`AwbModelRunner.validateOutputLayout` uses Java reflection to read `interpreterHandle` out of `LiteRtSession` (private field):

```kotlin
val field = s.javaClass.getDeclaredField("interpreterHandle")
field.isAccessible = true
field.get(s) as? com.google.ai.edge.litert.Interpreter
```

This works in debug builds but **fails silently** in release with R8 minification unless there is a `-keep` rule. The runner's `getOrNull()` path swallows the `NoSuchFieldException`, `outputLayoutValidated` stays false forever, and the `validateOutputLayout` warning never fires — so a swapped-in AWB model file with `[r_gain, g_gain, b_gain]` output instead of `[cct, tint, conf]` is silently misinterpreted.

Fix in Plan.md §P5 / Appendix A item 17:
```
# app/proguard-rules.pro
-keepclassmembers class com.leica.cam.ai_engine.impl.runtime.LiteRtSession {
    private <fields>;
}
```

A cleaner long-term fix (owned by `Backend Enhancer`): add a typed accessor on `LiteRtSession`:
```kotlin
fun outputTensorShape(index: Int = 0): IntArray? = (interpreterHandle as? Interpreter)?.getOutputTensor(index)?.shape()
```
and delete the reflection.

---

## §12 ZSL ring buffer — nothing ever adds a frame

The `ZeroShutterLagRingBuffer<Any>` is provided with `capacity = 15` (in `CaptureOrchestratorModule.ZSL_BUFFER_CAPACITY`). `CaptureProcessingOrchestrator.retrieveZslFrames()` calls `zslBuffer.latest(frameCount)`:

```kotlin
private fun retrieveZslFrames(request: CaptureRequest): List<Any> {
    val frameCount = when (request.captureMode) { ... }
    val bufferedFrames = zslBuffer.latest(frameCount)
    return if (bufferedFrames.isEmpty()) {
        logger.warn(TAG, "ZSL buffer empty, using fallback single-frame capture")
        listOf(createFallbackFrame())
    } else {
        bufferedFrames.map { it.payload }
    }
}

private fun createFallbackFrame(): Any = Any()
```

But **nowhere in the project does anyone call `zslBuffer.add(...)`**. The preview frames coming out of CameraX via `CameraPreview.kt` / `PreviewView` are never routed to the ZSL buffer. So the buffer is always empty on shutter press → fallback → `Any()` → every downstream stage receives a nonsense frame.

The `FusedPhotonBuffer(underlying = Any())` then propagates through all of §2–§11 as pure noise.

### What's wrong

Search for callers:
```
$ grep -rn "zslBuffer.add\|zslBuffer\\.push\|ZeroShutterLagRingBuffer\\(.*\\.add" --include="*.kt"
# (no output)
```

No-one. The fallback path is the only active path.

### How to fix (owned by `android-app-builder` (ARIA-X))

This requires a frame-in callback from CameraX `ImageAnalysis` → `Camera2CameraController` → `CaptureFrameIngestor` → `ZeroShutterLagRingBuffer.add`.

1. **Register an `ImageAnalysis` use case** on the active `Camera` in `Camera2CameraController.openCamera`. Backpressure `STRATEGY_KEEP_ONLY_LATEST`, output `OUTPUT_IMAGE_FORMAT_YUV_420_888`.
2. **Set the analyser** to forward every frame to a `ZslFrameIngestor` injected via Hilt. That ingestor converts the YUV frame into a `Bayer16Frame` (or whatever `PipelineFrame` the orchestrator expects) and calls `zslBuffer.add(ZslEntry(frame, timestampNs))`.
3. **Unbind/rebind** the `ImageAnalysis` use case when the user toggles `CameraFacing` (`switchCameraFacing`). Otherwise the old sensor keeps feeding the buffer with old-facing frames until the first capture.

### Edge cases

- **`ImageAnalysis` steals preview bandwidth.** On Dimensity devices, CameraX's `preview + imageCapture + imageAnalysis` triple use-case runs at ~24 fps on mid-range SoCs. This is acceptable for ZSL but watch for drop-frame logs.
- **Frame ownership**: `ImageAnalysis.Analyzer.analyze(ImageProxy)` must `close()` each image. Copy the YUV buffer into your own owned byte array before closing; `ZslEntry.payload` holds a *copy*, not a proxy.

### Verification

- `./gradlew :core:capture-orchestrator:test` — add a unit test that asserts `zslBuffer.latest(5).isNotEmpty()` after 10 simulated add() calls.
- On device: take 10 photos in rapid succession. Logcat should show `ZSL: Retrieved 5 burst frames` (or whatever count the mode selects), never `ZSL buffer empty`.

This is a **BLOCKER-level** problem that Plan.md explicitly declares out of scope for P0–P5 (it crosses :sensor-hal, :feature:camera, :core:capture-orchestrator). Treat it as a separate, mid-priority follow-up plan. Without this fix, §2–§11's fixes operate on a single CameraX JPEG rather than a true burst — the improvements are still visible (better tone mapping, correct WB) but the burst-mode benefits (Wiener denoise, HDR+, Night mode) remain inaccessible.

---

## §13 Native (Vulkan/C++) runtime — never fed a preview frame

`platform-android/native-imaging-core/impl/src/main/cpp/native_imaging_core.cpp` + the Kotlin bridges (`NativeImagingBridge.kt`, `ImagingRuntimeOrchestrator.kt`) form the low-latency preview path. `NativeImagingRuntimeFacade.submitPreviewFrame(...)` is the entry point. Nothing calls it.

This is the same class of problem as §12 but on the preview (fast) path rather than the capture (slow) path.

Consequences:
- The GPU compute pipeline (`VulkanBackend` + `OpenGlEsBackend` + `CpuFallbackBackend`) that renders the Reinhard-extended preview tone map is not exercised. The preview shown to the user is raw CameraX — no LUMO preview-path processing.
- `RealTimeLutPreviewEngine` (provided by `ImagingPipelineModule`) is never invoked.

### Fix ownership

The fix touches `CameraPreview.kt`'s `AndroidView { previewView }` setup: add a `SurfaceTextureListener` that forwards each preview frame's `HardwareBuffer` handle to `NativeImagingRuntimeFacade.submitPreviewFrame(frameId, handle, w, h)`.

This is strictly out of scope for Plan.md's P0–P5. Track in `docs/known-issues/wiring.md` at MAJOR severity.

---

## §14 Consolidated fix ordering

Read top-down. Each row assumes the row above is green.

| # | Fix | Plan.md ref | Skill | Gate |
|---|---|---|---|---|
| 1 | P0 — kapt/hilt plugins on 3 modules | `Plan.md §P0` | `android-app-builder` | Gradle launches kapt on `:core:capture-orchestrator`, `:core:photon-matrix:impl`, `:engines:smart-imaging:impl`. |
| 2 | P1 — complete Hilt graph | `Plan.md §P1` | `Backend Enhancer` | `./gradlew :app:kaptDebugKotlin` exits 0 with no `[Dagger/MissingBinding]`. |
| 3 | P2 — constructor / type fixes | `Plan.md §P2` | `kotlin-specialist` | `./gradlew :app:compileDebugKotlin` exits 0. |
| 4 | P3 — SDK / manifest / NDK | `Plan.md §P3` | `android-app-builder` | `./gradlew :app:assembleDevDebug` exits 0; APK installs. |
| 5 | §10 — Fix `downsample()` stub | `Plan.md §P2.2` | `Lumo Imaging Engineer` | Scene Classifier + Semantic Segmenter outputs become non-uniform. |
| 6 | §4 / §11 — Wire `HyperToneWhiteBalanceEngine` into orchestrator | `Plan.md §P4.3` | `color-science-engineer` | Logcat shows `AwbModelRunner.predict` on each capture. |
| 7 | §2 — Wire `FusionLM2Engine` | `Plan.md §P4.1` | `Lumo Imaging Engineer` | Logcat shows `FusionLM2Engine.fuse`; `fused.frameCount > 1` when ZSL has frames. |
| 8 | §3 — Wire `ProXdrOrchestrator` | `Plan.md §P4.2` | `Lumo Imaging Engineer` | Capture in PRO_XDR mode logs `ProXdrOrchestrator`. |
| 9 | §6 — Wire `ToneLM2Engine` | `Plan.md §P4.4` | `Lumo Imaging Engineer` | Logcat shows `ToneLM2Engine.apply`; face override visible on portrait. |
| 10 | §5 — Fix ColorLM2 input (scene label + illuminant hint) | §5 above | `color-science-engineer` | ΔE2000 ColorChecker benchmark passes on D65 and A illuminants. |
| 11 | §8 — Route face detection into orchestrator | §8 above | `color-science-engineer` | SkinToneProcessor reports N > 0 faces. |
| 12 | §7 — Real PDAF + contrast AF telemetry | §7 above | `android-app-builder` | PDAF-on logs show non-zero phase errors during pan. |
| 13 | §9 — MicroISP sensor-id gate | §9 above | `Lumo Imaging Engineer` | Ultra-wide capture logs `MicroIspRunner.refine`; main sensor does not. |
| 14 | §12 — ZSL frame feed (deferred / separate plan) | Out of scope | `android-app-builder` | `zslBuffer.latest(5).isNotEmpty()` on shutter. |
| 15 | §13 — Native runtime preview feed (deferred / separate plan) | Out of scope | `android-app-builder` | `NativeImagingRuntimeFacade` is fed preview frames. |

Rows 1–4 are Plan.md P0–P3 in order. Rows 5–13 are Plan.md P4 + this document's algorithm-level extensions. Rows 14–15 are tracked as out-of-scope items in `docs/known-issues/wiring.md`.

---

## Verification matrix (cross-sectional)

Once §14 row 13 is green, run the following matrix on a real device. Each cell must pass.

| Scene | Mode | Must-see logcat lines | Must-see JPEG property |
|---|---|---|---|
| Indoor tungsten — white wall | AUTO | `AwbModelRunner.predict`, `HyperToneWhiteBalanceEngine` | Wall renders near R≈G≈B (neutral), not orange |
| Sunny outdoor portrait | AUTO | `FaceLandmarker.detect N=1`, `SkinToneProcessor process faces=1`, `ToneLM2Engine face override` | Face tone near Fitzpatrick anchor, no clipped cheek highlights |
| Night street | NIGHT | `FusionLM2Engine strategy=WIENER_BURST frames=8`, `ShadowRestorer` | Shadow detail visible without amplified chroma noise |
| High-DR landscape (backlit mountain) | PRO_XDR | `ProXdrOrchestrator`, `HighlightReconstructor`, `RadianceMerger strategy=DEBEVEC_LINEAR` | Sky retains gradient, mountain shadows have structure |
| Ultra-wide cityscape | AUTO | `MicroIspRunner.refine eligible=true sensor=ov08d10` | No Bayer-artifact fringing at contrast edges |
| Main sensor (S5KHM6) close-up | AUTO | `MicroIspRunner.refine eligible=false sensor=s5khm6` | (MicroISP is correctly *not* applied — this is success) |
| Scene change — pan from wall to food | AUTO | `SceneClassifier top1=FOOD confidence=0.4+`, `IlluminantEstimator kelvin=3500` | Scene badge updates to "Food"; WB shifts toward warmer |

---

## References

- `README.md` — architectural pillars, model catalogue, LUMO laws.
- `project-structure.md` — module graph, runtime capture flow.
- `Plan.md` — engineering remediation plan (P0–P5).
- `docs/color-science/Plan-CS.md` (relocated from the old `Plan.md`) — colour-science CS-1..CS-6.
- `.agents/skills/Lumo Imaging Engineer/` — deep imaging pipeline.
- `.agents/skills/color-science-engineer/` — CCM, OKLAB, ΔE.
- `.agents/skills/Leica Cam Upgrade skill/references/sensor-profiles.md` — per-sensor tuning (WB bias, MicroISP eligibility).
- `.agents/skills/android-app-builder/references/camera-advanced.md` — CameraX / Camera2 patterns for ZSL frame feed (§12).
- `.agents/skills/android-app-builder/references/crash-patterns.md` — run-time failure modes to anticipate once the pipeline starts firing (ImageReader exhaustion, SurfaceTexture recycling, thermal throttling).
- `.agents/skills/analyzing-projects/SKILL.md` — if the Executor needs to triage a new module before editing.
- `.agents/skills/systematic-debugging/SKILL.md` — when a pipeline stage crashes mid-capture.

---

## Out of scope for this document

- UI/UX of the capture and gallery screens. Use `critique` + `impeccable` skills.
- Release / Play Store / signing / telemetry. See `.agents/skills/android-app-builder/references/release-checklist.md`.
- Privacy / on-device guarantees beyond "the models already run on-device" (handled by `ImagingPipelineModule.providePrivacyMetadataPolicy`).
- Per-sensor fine-tuning beyond what `SensorProfileRegistry` already ships. See the Leica Cam Upgrade skill's `sensor-profiles.md`.
- Cloud inference — explicitly forbidden by README Law 7.

---

*End of processing-problems.md.*
