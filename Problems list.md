# LeicaCam — Problems List

> Deep audit of the LeicaCam codebase (excluding `/GSD 2.0/` and `/.agents/skills/`).
> Generated: 2026-04-23.
>
> Every entry names **file**, **line(s)**, **category**, and the concrete failure
> mode. Severity bands:
> - **P0** — build-breaking or runtime-crashing; the app will not start / compile.
> - **P1** — silent correctness bug, resource leak, or dead-wired feature that
>   masquerades as working.
> - **P2** — quality/physics/algorithmic defect that ships a wrong image.
> - **P3** — structure, docs, dead-code, or style drift.
>
> Line numbers reference the current `main` checkout in `/home/user/webapp`.

---

## Table of contents

1. [P0 — Build & wiring breakers](#p0--build--wiring-breakers)
2. [P1 — Dead-wired features & runtime crashes](#p1--dead-wired-features--runtime-crashes)
3. [P2 — Imaging / AI correctness bugs](#p2--imaging--ai-correctness-bugs)
4. [P2 — HDR, alignment, ghost, merge](#p2--hdr-alignment-ghost-merge)
5. [P2 — Concurrency, lifecycle, resources](#p2--concurrency-lifecycle-resources)
6. [P3 — Structure, duplication, dead code](#p3--structure-duplication-dead-code)
7. [P3 — Documentation drift](#p3--documentation-drift)
8. [Cross-cutting tech-debt inventory](#cross-cutting-tech-debt-inventory)

---

## P0 — Build & wiring breakers

These will fail `./gradlew assemble` — do **not** assume any part of the
runtime behaves correctly until these are fixed. They invalidate every
downstream test result.

### P0-1 — `:app` imports `:ai-engine:impl` but declares no dependency on it

- **File:** `app/src/main/kotlin/com/leica/cam/LeicaCamApp.kt:5`
- **Import:** `import com.leica.cam.ai_engine.impl.registry.ModelRegistry`
- **Build file:** `app/build.gradle.kts` lines 89–93 only list
  `:feature:camera`, `:feature:gallery`, `:feature:settings`, `:ui-components`.
- **Effect:** Kotlin compile fails with "unresolved reference: impl" on
  every `:app` build. Hilt's generated graph also fails because
  `provideModelRegistry` (in `:ai-engine:impl`) is unreachable from the
  `SingletonComponent` of the `:app` module.
- **Trap:** You cannot fix this by adding a transitive dep on
  `:feature:camera` — `feature:camera` also only depends on `:ai-engine:api`.
- **Correct fix:** Add `implementation(project(":ai-engine:impl"))` (and all
  other `:impl` modules Hilt needs to aggregate bindings for) to
  `app/build.gradle.kts`. Hilt requires every `@Module` contributor to be
  on the `:app` classpath.

### P0-2 — `:imaging-pipeline:impl` imports `:ai-engine:impl` but has no dep on it

- **Files:**
  `engines/imaging-pipeline/impl/src/main/kotlin/com/leica/cam/imaging_pipeline/pipeline/ImagingPipeline.kt:1218,1220`
- **Imports:** `com.leica.cam.ai_engine.impl.models.MicroIspRunner`,
  `com.leica.cam.ai_engine.impl.models.SemanticSegmenterRunner`.
- **Build file:** `engines/imaging-pipeline/impl/build.gradle.kts` declares
  only `:imaging-pipeline:api` and `:common`.
- **Effect:** Unresolved reference at compile.
- **Architectural violation:** Even with the dep added, this is a
  cross-engine `:impl → :impl` import, forbidden by the dependency-rules
  section in `README.md` (line 78) and by ADR-002.
- **Correct fix:** Hoist the minimal contract that `ImagingPipeline` needs
  (e.g. `interface SemanticSegmenter { fun segment(...): SemanticMask? }`,
  `interface MicroIspRefiner { fun refine(...): FloatArray }`) into
  `:ai-engine:api`. Inject the interface, not the runner.

### P0-3 — `:hypertone-wb:impl` imports `:ai-engine:impl`

- **File:**
  `engines/hypertone-wb/impl/src/main/kotlin/com/leica/cam/hypertone_wb/pipeline/HyperToneWhiteBalanceEngine.kt:3-4`
- **Imports:** `com.leica.cam.ai_engine.impl.models.AwbModelRunner`,
  `AwbPrediction`.
- **Build file:** `engines/hypertone-wb/impl/build.gradle.kts` declares
  `:ai-engine:api` only.
- **Effect:** Same class as P0-2 — compile fails, architecture rule
  violated.
- **Correct fix:** Move `AwbPrediction` (pure data) into
  `:ai-engine:api` as `AwbNeuralPrior`. Expose the runner behind an
  `interface AwbPredictor` in `:ai-engine:api`. Leave the LiteRT-backed
  implementation in `:ai-engine:impl`.

### P0-4 — Duplicate `@Named("assetBytes")` Hilt provider

- **Files:**
  - `app/src/main/kotlin/com/leica/cam/di/AssetsModule.kt:17-29`
  - `engines/ai-engine/impl/src/main/kotlin/com/leica/cam/ai_engine/impl/di/AiEngineModule.kt:55-68`
- **Effect:** Both `@Provides @Singleton @Named("assetBytes")` for
  `@JvmSuppressWildcards Function1<String, ByteBuffer>`. Hilt aborts the
  build with `[Dagger/DuplicateBindings] ... is bound multiple times`.
- **Correct fix:** Delete `app/.../di/AssetsModule.kt` — the version in
  `:ai-engine:impl` is the canonical one.

### P0-5 — `ProXdrOrchestrator.processWienerBurst` calls `.mergedFrame` on `AlignmentResult`

- **File:** `engines/imaging-pipeline/impl/src/main/kotlin/com/leica/cam/imaging_pipeline/hdr/ProXdrOrchestrator.kt:122-126`
- **Code:**
  ```kotlin
  is LeicaResult.Failure -> return alignResult.map { it.mergedFrame }.flatMap {
      LeicaResult.Failure.Pipeline(PipelineStage.IMAGING_PIPELINE, "Alignment failed")
  }
  ```
- **`AlignmentResult` has fields:** `reference`, `alignedFrames`,
  `transforms` — **no** `mergedFrame`.
- **Effect:** Compile error: `Unresolved reference: mergedFrame`. The
  lambda body is type-checked regardless of which `LeicaResult` branch is
  active.
- **Correct fix:** Replace with a direct return:
  ```kotlin
  is LeicaResult.Failure -> return LeicaResult.Failure.Pipeline(
      PipelineStage.IMAGING_PIPELINE, "Alignment failed",
  )
  ```
  (The sibling branch `processEvBracket` on line 147-151 already uses this
  shape; they just diverged.)

### P0-6 — `ModelRegistry` is pointed at a non-existent directory

- **File:**
  `engines/ai-engine/impl/src/main/kotlin/com/leica/cam/ai_engine/impl/di/AiEngineModule.kt:29`
- **Code:** `fun provideModelDirectory(): File = File("models")`
- **Effect:** At runtime, `File("models")` resolves against the JVM
  process working directory (usually `/`). On Android this is **never**
  `<app>/files/models/` and never the APK assets. `scanAll()` returns an
  empty list; `catalogue` is empty; every model runner's
  `openSession()` fails with "No model asset found for role ..."; the
  entire AI pipeline is silently inert.
- **Root-cause trap:** Models live in `assets/models/**/*.tflite` inside
  the APK, not on the filesystem. The registry was written to scan a
  filesystem directory, but shipping uses AssetManager.
- **Correct fix:** Re-model the registry around AssetManager. Give it a
  `listAssets("models")` walker and a `loadDirect(path)` that returns
  `ByteBuffer` via `AssetManager.openFd().createInputStream().channel.map(...)`.
  Remove `File` entirely from the registry's ownership model.

### P0-7 — `ModelRegistry.warmUpAll()` uses 4-byte input/output for every model

- **File:**
  `engines/ai-engine/impl/src/main/kotlin/com/leica/cam/ai_engine/impl/registry/ModelRegistry.kt:350-353`
- **Code:**
  ```kotlin
  val dummyIn = ByteBuffer.allocateDirect(4).order(nativeOrder())
  val dummyOut = ByteBuffer.allocateDirect(4).order(nativeOrder())
  ```
- **Effect:** Every LiteRT `run(input, output)` call throws because input
  tensor shape mismatches 4 bytes (e.g. AWB expects `224*224*3*4 = 602112`
  bytes). Every model logs "Warm-up inference failed". Warmed count is
  always 0. The stated goal ("amortise JIT / delegate-compile cost off
  the capture path") is not met.
- **Correct fix:** Read the input/output tensor shapes from
  `Interpreter.getInputTensor(0).shape()` etc. *before* allocating
  buffers, or hard-code per-role sizes (we already know them).

### P0-8 — `LeicaCamApp.onCreate` comment contradicts code

- **File:** `app/src/main/kotlin/com/leica/cam/LeicaCamApp.kt:43-49`
- **Comment says:** "Only AwbModelRunner and FaceLandmarkerRunner are
  warmed eagerly; MicroIspRunner and SemanticSegmenterRunner are deferred
  until first shutter press."
- **Code does:** Calls `modelRegistry.warmUpAll(assetBytesLoader)` which
  iterates **every** role in the catalogue.
- **Effect:** Either the comment is wrong (most likely) or the code is;
  either way the contract is ambiguous and the behaviour we actually
  need (deferred MicroISP warm-up) is not implemented. Combined with
  P0-7 this is double-broken.

---

## P1 — Dead-wired features & runtime crashes

These compile, but they do something different — usually nothing — than
the description in the surrounding docs and comments claim.

### P1-1 — `AiEngineOrchestrator` returns hardcoded stubs; never calls any runner

- **File:**
  `engines/ai-engine/impl/src/main/kotlin/com/leica/cam/ai_engine/impl/pipeline/AiEngineOrchestrator.kt:15-70`
- **Observed:**
  - `InternalSceneClassifier.classify` → always returns
    `SceneLabel.LANDSCAPE`.
  - `InternalShotQualityEngine.score` → returns
    `QualityScore(0.88, 0.85, 0.9, 0.92)` regardless of input.
  - `ObjectTrackingEngine.track` → returns `emptyList()`.
  - `estimateIlluminant` → hard-coded CCT lookup, no histogram.
- **Missing:** Neither `SceneClassifierRunner`, `SemanticSegmenterRunner`,
  nor `FaceLandmarkerRunner` is injected into `AiEngineOrchestrator`.
- **Effect:** Every time the pipeline asks "what scene is this?", the
  answer is LANDSCAPE. HDR bracket selection, tone-map routing,
  computational-mode gating — all downstream decisions are made on a
  constant. The five on-disk models are present and loadable but unused
  by the orchestrator.
- **Correct fix:** Inject the four LiteRT-backed runners into
  `AiEngineOrchestrator`, wire `classify/score/illuminant/track` to real
  inference with graceful fallback to the current stub values when a
  model fails to open.

### P1-2 — `ImagingPipeline.applyMicroIsp` is a pass-through

- **File:**
  `engines/imaging-pipeline/impl/src/main/kotlin/com/leica/cam/imaging_pipeline/pipeline/ImagingPipeline.kt:1302-1308`
- **Code:**
  ```kotlin
  private fun applyMicroIsp(frame: PipelineFrame): PipelineFrame {
      val runner = microIspRunner ?: return frame
      // Simplified: in production this tiles the frame at 256x256 with overlap.
      // For now, we pass through ...
      return frame
  }
  ```
- **Effect:** The runner is injected, gated, and null-checked — then
  discarded. No neural refinement runs on ultra-wide or front captures.
  The entire D1.8 deliverable for MicroISP integration is a no-op.

### P1-3 — Two incompatible `SceneLabel` enums with no mapping

- **Files:**
  - `engines/ai-engine/api/src/main/kotlin/com/leica/cam/ai_engine/api/AiContracts.kt:17-27`
    defines `enum SceneLabel { PORTRAIT, LANDSCAPE, NIGHT, DOCUMENT, FOOD, PET, BACKLIT, MACRO, INDOOR, UNKNOWN }`
  - `engines/ai-engine/impl/src/main/kotlin/com/leica/cam/ai_engine/impl/models/SceneClassifierRunner.kt:132-135`
    defines `enum SceneLabel { GENERAL, PORTRAIT, LANDSCAPE, NIGHT, STAGE, SNOW, BACKLIT_PORTRAIT, FOOD, ARCHITECTURE, MACRO, DOCUMENT }`
- **Effect:** The orchestrator returns `api.SceneLabel.LANDSCAPE`; the
  runner produces `impl.models.SceneLabel.*`. Any consumer that tries to
  route on one enum cannot consume the other. If P1-1 is fixed naively
  (just "inject the runner") the result will be a type mismatch.
- **Correct fix:** Canonical `SceneLabel` lives in `:ai-engine:api`;
  delete the one in `SceneClassifierRunner`; give the runner a private
  `SceneClass` enum internally and map it to the API's `SceneLabel`
  at the public boundary.

### P1-4 — `SceneClassifierRunner` ImageNet→SceneLabel table has overlapping ranges — NIGHT branch is dead code

- **File:**
  `engines/ai-engine/impl/src/main/kotlin/com/leica/cam/ai_engine/impl/models/SceneClassifierRunner.kt:118-127`
- **Code:**
  ```kotlin
  in 970..979 -> SceneLabel.FOOD
  in 629..640 -> SceneLabel.LANDSCAPE
  in 975..980 -> SceneLabel.NIGHT         // ← overlaps 975-979 with FOOD
  in 560..570 -> SceneLabel.ARCHITECTURE
  ```
- **Effect:** Classes 975–979 always land in the FOOD branch (first
  match wins); 980 alone hits NIGHT. The NIGHT mapping is effectively
  disabled.
- **Correct fix:** Audit the COCO/ImageNet class list for the actual
  ranges you want to map, remove overlap, sort by specificity.

### P1-5 — `SemanticSegmenterRunner` reads int labels as `float`

- **File:**
  `engines/ai-engine/impl/src/main/kotlin/com/leica/cam/ai_engine/impl/models/SemanticSegmenterRunner.kt:78-84`
- **Code:**
  ```kotlin
  val output = ByteBuffer.allocateDirect(outputSize * FLOAT_BYTES).order(nativeOrder())
  // ...
  val rawLabels = IntArray(outputSize) { output.float.toInt() }
  ```
- **DeepLabv3 variant check:** The shipped `deeplabv3.tflite` outputs
  either `int64` argmax labels (mobile variant) or `float32` per-class
  scores (full variant). Neither is "read a float and call `.toInt()`":
  - If the model is `int64` → you get 4 garbage int-labels per 8-byte
    word, and the size buffer is half what you need.
  - If the model is `float32` per-class → you read one class score as
    a label, producing random numbers in `[0, 20]`.
- **Effect:** Semantic mask is always nonsense → priority tone mapping
  has no basis. Combined with P1-1 this is the main reason "priority
  zones" don't actually work in practice.
- **Correct fix:** Inspect the shipped model at startup:
  `Interpreter.getOutputTensor(0).dataType()`. Branch to the correct
  readout (`getLong`/`getInt`/argmax over float classes).

### P1-6 — `SemanticSegmenterRunner.confidenceThreshold` is computed and ignored

- **File:**
  `engines/ai-engine/impl/src/main/kotlin/com/leica/cam/ai_engine/impl/models/SemanticSegmenterRunner.kt:67-87`
- **Computed:** `val confidenceThreshold = if (sensorIso > 3200) 0.35f else 0.50f`
- **Passed into:** `upscaleMask(..., confidenceThreshold)` — but the
  `upscaleMask` implementation never references it (line 127-142).
- **Effect:** The per-ISO threshold adjustment promised in the KDoc never
  happens. At high ISO the mask inherits noisy low-confidence pixels at
  the original threshold.

### P1-7 — `AwbModelRunner` assumes interleaved RGB, feeds per-sensor bias pre-inference

- **File:**
  `engines/ai-engine/impl/src/main/kotlin/com/leica/cam/ai_engine/impl/models/AwbModelRunner.kt:60-66`
- **Code:**
  ```kotlin
  for (i in tile.indices) {
      val channel = i % 3
      input.putFloat(tile[i] * wbBias[channel])
  }
  ```
- **Problem 1 (layout):** `i % 3` assumes the tile is RGB-interleaved,
  but `HyperToneWhiteBalanceEngine.downsample224x224` (line 77 onward
  of `HyperToneWhiteBalanceEngine.kt`) is the only caller and may
  produce planar layout — the math silently works against the wrong
  memory order.
- **Problem 2 (physics):** Multiplying by `wbBias` **before** neural AWB
  inference defeats the model: the model was trained on raw un-corrected
  data and is supposed to predict the CCT of the illuminant. Applying a
  sensor-space gain first corrupts the illuminant signal the model is
  supposed to estimate. The correct place for `wbBias` is **after**
  model inference, as a per-sensor offset on the predicted gains.
- **Correct fix:**
  1. Document and enforce interleaved RGB layout at the caller.
  2. Move `wbBias` to the output blend step, not the input pre-process.

### P1-8 — `ImagingRuntimeOrchestrator` worker loop pairs frames 1:1 with processing requests, causing deadlocks

- **File:**
  `platform-android/native-imaging-core/impl/src/main/kotlin/com/leica/cam/native_imaging_core/impl/nativeimagingcore/ImagingRuntimeOrchestrator.kt:73-91`
- **Code:**
  ```kotlin
  while (true) {
      val ingest = ingestQueue.receive()             // blocks on frame
      bridge.queueFrame(ingest.frame, ingest.metadata)
      val processRequest = processingQueue.receive() // blocks on request
      bridge.requestProcess(processRequest)
      bridge.release(ingest.frame.hardwareBufferHandle)
      val result = bridge.pollResult(timeoutMs = 0L) // non-blocking, ~always null
      ...
  }
  ```
- **Failure modes:**
  1. **Frame without request** → worker forever parked on
     `processingQueue.receive()`; ingest queue fills, starts dropping
     frames (`DROP_OLDEST`). Preview freezes silently.
  2. **Request without frame** → opposite — processing requests pile up
     then drop.
  3. **Use-after-free:** `bridge.release(hardwareBufferHandle)` is called
     *before* `pollResult` — the native side may still be reading the
     buffer. Classic concurrent-resource bug; produces shredded
     output or segfault.
  4. **Leaked output handle:** `pollResult(0)` returns immediately; in
     practice it returns `Success(null)` because the native side hasn't
     produced output yet → the produced output is never released →
     native memory leak per frame.
  5. **No cancellation honouring:** `while (true)` without
     `ensureActive()` means `workerJob?.cancel()` only interrupts a
     pending `receive` — midway through the frame's processing we keep
     going.
- **Correct fix:** Decouple the two queues. Use `select { }` to pick
  whichever is ready, track in-flight frames by id, release a frame only
  after its matching result comes back from `pollResult`, and use
  `pollResult(timeoutMs = 10)` with a back-off.

### P1-9 — Camera2 characteristics read on main thread causes ANR

- **File:**
  `platform-android/sensor-hal/src/main/kotlin/com/leica/cam/sensor_hal/session/Camera2CameraController.kt:263-276`
- **Code (inside `selectorForCameraId`):**
  ```kotlin
  val hasMatch = runCatching {
      ProcessCameraProvider.getInstance(appContext).get()
          .availableCameraInfos.any { ... }
  }.getOrDefault(false)
  ```
  `ProcessCameraProvider.getInstance(...).get()` is a blocking
  ListenableFuture resolve; `selectorForCameraId` is invoked from
  `openCamera(cameraId)` which is called under
  `withContext(Dispatchers.Main.immediate)` on line 82.
- **Effect:** Cold-start ANR on devices where the camera service takes
  >100 ms to initialise. Also `getCameraCharacteristics(cameraId)` at
  line 270 is a synchronous IPC to camera service from Main.
- **Correct fix:** Move all Camera2 IPC and CameraX provider resolution
  to `Dispatchers.Default`; keep only the actual CameraX bind call on
  Main.

### P1-10 — `Camera2CameraController` leaks a single-thread executor on close

- **File:**
  `platform-android/sensor-hal/src/main/kotlin/com/leica/cam/sensor_hal/session/Camera2CameraController.kt:45`
- **Leak:** `captureExecutor = Executors.newSingleThreadExecutor()`.
  `closeCamera()` (line 134) unbinds CameraX but does not shut down
  this executor. Every Activity recreation (rotation, dark mode,
  system locale change) builds a new controller — old executor threads
  live until process death.
- **Correct fix:** Store executor in a field; shutdown in `closeCamera`
  with `captureExecutor.shutdown(); captureExecutor.awaitTermination(...)`.

### P1-11 — `Camera2CameraController.capture()` throws IllegalStateException across module boundary

- **File:**
  `platform-android/sensor-hal/src/main/kotlin/com/leica/cam/sensor_hal/session/Camera2CameraController.kt:113-114`
- **Code:** `val capture = imageCapture ?: error("imageCapture not initialised")`
- **LUMO law 8 (README line 243):** "No checked exceptions cross module
  boundaries." Every fallible function should return `LeicaResult`.
- **Effect:** If the user taps the shutter before `openCamera`
  completes, the app crashes instead of returning
  `LeicaResult.Failure.Hardware`.

### P1-12 — `ZeroShutterLagRingBuffer` is not thread-safe

- **File:**
  `platform-android/sensor-hal/src/main/kotlin/com/leica/cam/sensor_hal/zsl/ZeroShutterLagRingBuffer.kt:14-36`
- **Problem:** Uses plain `ArrayDeque` with no synchronisation.
  Camera2's image-available callback runs on a background HandlerThread
  while `snapshot()` and `latest(n)` are called from the capture
  coroutine — classic reader/writer race.
  `ConcurrentModificationException` is likely on snapshot(); silent
  inconsistency (dropped frames, duplicated frames) is the more common
  failure.
- **Correct fix:** Back with `java.util.concurrent.ConcurrentLinkedDeque`
  or guard push/snapshot with a `ReentrantLock`. Use a `copyOnWrite`
  snapshot pattern so the capture path never blocks on the camera
  callback.

### P1-13 — `CameraPreview` fires-and-forgets session open/close; swallows errors

- **File:**
  `features/camera/src/main/kotlin/com/leica/cam/feature/camera/preview/CameraPreview.kt:38-68`
- **Problems:**
  1. Each `ON_START`, `ON_STOP`, and "already started at compose time"
     launches a new coroutine on `rememberCoroutineScope()`. Rapid
     navigation (gallery ↔ camera) queues racing open/close pairs.
  2. Every call is wrapped in `runCatching { ... }` with **no error
     handling** — silent failure contract.
  3. `onDispose` also launches a close coroutine that may outlive the
     composition's scope cancellation when configuration changes.
- **Correct fix:** Funnel all session transitions through a single
  `StateFlow<SessionCommand>` collected on an outer scope; drop the
  `runCatching`.

### P1-14 — `CameraScreen` HUD buttons are all no-op stubs

- **File:**
  `features/camera/src/main/kotlin/com/leica/cam/feature/camera/ui/CameraScreen.kt:186,189,205,208,240,256,162,83`
- **Observed:** Flash, HDR, Lens, Menu, Filter wand, Gallery, Switch
  Camera buttons are all `IconButton(onClick = { })`. The Zoom pill
  only updates local state without touching the camera controller.
- **Effect:** The user-visible UI pretends to work. The only live path
  is the shutter button, which triggers `sessionManager.capture()`
  without propagating HDR / flash / zoom intent.

### P1-15 — `CameraScreen.overlayState` is built from hardcoded values

- **File:**
  `features/camera/src/main/kotlin/com/leica/cam/feature/camera/ui/CameraScreen.kt:110-119`
- **Values:** `LumaFrame(1, 1, byteArrayOf(0))`,
  `AfBracket(0.5f, 0.5f, 0.1f, false)`, `faces = emptyList()`,
  `shotQualityScore = 0.8f`, `horizonTiltDegrees = 0.5f`.
- **Effect:** The Phase-9 overlay — faces, AF bracket, luma histogram,
  horizon level, shot-quality meter — always renders the same placeholder
  scene. The entire observability layer the orchestrator was built for
  is not fed any live signal.

---

## P2 — Imaging / AI correctness bugs

These produce a technically-correct-looking image that is subtly wrong.

### P2-1 — `MertensFallback.fuse` drops exposure metadata on the merged frame

- **File:**
  `engines/imaging-pipeline/impl/src/main/kotlin/com/leica/cam/imaging_pipeline/hdr/MertensFallback.kt:62`
- **Code:** `return LeicaResult.Success(PipelineFrame(width, height, outR, outG, outB))`
- **Missed parameters:** `evOffset, isoEquivalent, exposureTimeNs`
  default to `0f, 100, 16_666_666L` — erases the capture ISO/exposure,
  which downstream noise-modelling and DNG metadata composition rely
  on.
- **Correct fix:** Pass the base frame's metadata through
  (preferentially from `frames.minByOrNull { abs(it.evOffset) }`).

### P2-2 — `GhostMaskEngine.binaryMtb` uses upper median on even-length arrays

- **File:**
  `engines/imaging-pipeline/impl/src/main/kotlin/com/leica/cam/imaging_pipeline/hdr/GhostMaskEngine.kt:68-72`
- **Code:** `val median = sorted[sorted.size / 2]`
- **Issue:** For a 12 MP frame (12e6 px, even length), `size/2` returns
  the upper of the two middle values, producing a slightly biased MTB
  mask. Ward 2003 specifies the true median.
- **Correct fix:** For even length, take `(sorted[n/2 - 1] + sorted[n/2]) / 2f`.

### P2-3 — `RadianceMerger.mergeWienerBurst` uses green-channel variance for the motion-score denominator across all channels

- **File:**
  `engines/imaging-pipeline/impl/src/main/kotlin/com/leica/cam/imaging_pipeline/hdr/RadianceMerger.kt:97`
- **Code:** `val sigma2Total = noise.green.varianceAt(luma) + lumaVar`
- **Issue:** Per-channel merging (D2.5 goal) requires per-channel
  expected variance; `noise.green.varianceAt(luma)` is fine for the
  green channel but undercounts R/B variance for sensors where
  `r_shot > g_shot` (common on OmniVision CFA). The 3-sigma gate then
  lets noisier R/B values through on frames that should be rejected as
  motion.
- **Correct fix:** Compute motion deviation per channel using its
  own variance (or at minimum use a luma-referred combined variance,
  not pure green).

### P2-4 — Two definitions of `SceneDescriptor`, `SceneCategory`, `ThermalState`, `HdrBracketSelector` — parallel, diverging truth

- **Old file (546 lines, dead path):**
  `engines/imaging-pipeline/impl/src/main/kotlin/com/leica/cam/imaging_pipeline/pipeline/ProXdrHdrEngine.kt:41-130`
- **New file (HDR package):**
  `engines/imaging-pipeline/impl/src/main/kotlin/com/leica/cam/imaging_pipeline/hdr/HdrTypes.kt`,
  `.../hdr/BracketSelector.kt`
- **No cross-references between them** (grep confirms). The old file
  is referenced only by its own `ProXdrHdrOrchestrator` (line 409) and
  by KDoc in the new HDR files saying "old code had XYZ bug, new code
  fixes it."
- **Effect:** Every future change to bracket selection or scene
  descriptors has to be made twice or drifts. Review fatigue makes
  this a time bomb.
- **Correct fix:** Delete `ProXdrHdrEngine.kt` entirely once the new
  HDR path is wired into `ImagingPipelineOrchestrator` (which is
  the real D2 acceptance gate).

### P2-5 — Thermal thresholds disagree across files

- **Files:**
  - `ProXdrOrchestrator.kt:12` → `THERMAL_SEVERE_ORDINAL = 4`
  - `ProXdrHdrEngine.kt:127-130` → `>=6 SEVERE, >=4 HIGH, >=2 ELEVATED`
  - `ImagingRuntimeOrchestrator.kt:118-120` → `HIGH=6, CRITICAL=8`
- **Android `PowerManager.THERMAL_STATUS_*`:** values are
  `NONE=0, LIGHT=1, MODERATE=2, SEVERE=3, CRITICAL=4, EMERGENCY=5, SHUTDOWN=6`
  — maximum is 6.
- **Effects:**
  - `CRITICAL_THERMAL_LEVEL = 8` is unreachable; the "throttle every
    other frame" branch never fires.
  - `ProXdrOrchestrator` gates on ordinal 4 (CRITICAL) while
    `ProXdrHdrEngine` gates on ordinal 6 (SHUTDOWN) for "SEVERE".
  - Devices get inconsistent thermal behaviour depending on which HDR
    path is running.
- **Correct fix:** Single source of truth — put `enum ThermalState`
  mapped from `PowerManager.THERMAL_STATUS_*` in `:common`, use it
  everywhere.

### P2-6 — `FaceLandmarkerRunner` catches generic `Exception`

- **File:**
  `engines/ai-engine/impl/src/main/kotlin/com/leica/cam/ai_engine/impl/models/FaceLandmarkerRunner.kt:135-138`
- **Code:**
  ```kotlin
  } catch (e: Exception) {
      System.err.println(...)
      null
  }
  ```
- **LUMO law (README line 244):** "`catch (e: Exception)` is forbidden
  for cancellable coroutine paths."
  - Although `openOrFail` is synchronous, it's called from a coroutine;
    if `CancellationException` propagates it must not be swallowed.
- **Correct fix:** Catch `Throwable` then explicitly re-throw
  `CancellationException` (the LiteRT session does this at line 48 of
  `LiteRtSession.kt`; replicate the pattern).

### P2-7 — `FaceLandmarkerRunner.detect` copies `IntArray` pixels to a `Bitmap` per call

- **File:** `FaceLandmarkerRunner.kt:151` — `Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)`
- **Effect:** Allocates a new `Bitmap` every capture (tens of MB at
  12 MP). Pressure on the graphics heap; GC stutters on the capture
  path.
- **Correct fix:** Use `MediaPipe`'s `MediaImage` from the capture
  `Image` directly (zero-copy) or pool a reusable `Bitmap`.

### P2-8 — `SceneClassifierRunner` re-normalises input without checking the model's metadata

- **File:** `SceneClassifierRunner.kt:54`
- **Code:** `input.putFloat((v.coerceIn(0f, 1f) - 0.5f) * 2f)`
- **Assumption:** Model expects `[-1, 1]` ImageNet normalisation.
- **Reality:** The shipped `1.tflite` may be a MobileNetV2 variant
  exported with `[0, 255]` → `[0, 1]` input, **not** `[-1, 1]`. The KDoc
  says "pre-processor adjusts automatically based on tensor metadata at
  session-open time" but the code hard-codes one convention.
- **Correct fix:** At session open, inspect
  `interpreter.getInputTensor(0).quantizationParameters()` to pick
  the right normalisation.

### P2-9 — `ImagingPipeline.process` never calls the new `ProXdrOrchestrator`

- **File:**
  `engines/imaging-pipeline/impl/src/main/kotlin/com/leica/cam/imaging_pipeline/pipeline/ImagingPipeline.kt:1252-1263`
- **Observed:** Still calls the old `alignmentEngine.align(frames)`
  (translation-only SAD via `FrameAlignmentEngine`) and
  `hdrMergeEngine.merge(alignedFrames, noise)` (old
  `HdrMergeEngine`). None of the new `hdr/` sub-package components
  (`DeformableFeatureAligner`, `GhostMaskEngine`, `RadianceMerger`,
  `MertensFallback`, `ProXdrOrchestrator`) is wired into the live path.
- **Effect:** D2 ("HDR engine rebuild") is implemented as new code
  sitting beside the old code; the app still runs the old HDR path.
  The new path is only exercised by unit tests.
- **Correct fix:** Replace the Stage 1 + Stage 2 block in
  `ImagingPipeline.process` with a single
  `proXdrOrchestrator.process(frames, scene, noiseModel, perChannelNoise, userHdrMode)`.

### P2-10 — `AwbModelRunner.predict` treats model output as three floats in a fixed order

- **File:** `AwbModelRunner.kt:72-81`
- **Code:**
  ```kotlin
  output.rewind()
  AwbPrediction(
      cctKelvin = output.float,
      tintDuv = output.float,
      confidence = output.float,
  )
  ```
- **Issue:** The shipped `awb_final_full_integer_quant.tflite` may have
  different output tensor order (many AWB models emit `[r_gain, g_gain,
  b_gain]` not `[cct, tint, conf]`). No validation against the model's
  output tensor metadata.
- **Correct fix:** At session-open, read the output tensor name and
  shape and either adapt the parser or log an assertion error.

---

## P2 — HDR, alignment, ghost, merge

### P2-11 — `DeformableFeatureAligner` copies border pixels *from the previous estimate* at every pyramid level

- **File:**
  `engines/imaging-pipeline/impl/src/main/kotlin/com/leica/cam/imaging_pipeline/hdr/DeformableFeatureAligner.kt:189-198`
- **Behaviour:** Pixels within `r=2` of the image border copy from
  `prevU/prevV`, which at the coarsest level is the zero-initialised
  array — so borders have zero flow, producing a 2-pixel frame of
  misregistered border pixels at the finest level. Over 4 pyramid levels
  this creates a 32-pixel wide unaligned ring.
- **Correct fix:** Either reflect-pad the input, or propagate
  flow estimates from the next-closer non-border pixel (Hampel filter
  style).

### P2-12 — `DeformableFeatureAligner` has no motion magnitude clamp

- **File:** `DeformableFeatureAligner.kt:178-184`
- **Issue:** LK can produce arbitrarily large `du/dv` on textureless
  edges when `det` is above `DET_THRESHOLD` but the system is
  ill-conditioned. No upper bound on `|du|` / `|dv|`.
- **Effect:** Occasional pixel-snap artefacts where aligned frame
  samples from far outside the valid region.
- **Correct fix:** Clamp per-pyramid-level flow update to e.g.
  `±(level_width / 16)`.

### P2-13 — `MertensFallback` writes a deep copy for every frame's channel

- **File:** `MertensFallback.kt:56-58`
- **Code:**
  ```kotlin
  val outR = pyramidBlend(frames.map { it.red }, normalised, w, h, levels)
  val outG = pyramidBlend(frames.map { it.green }, normalised, w, h, levels)
  val outB = pyramidBlend(frames.map { it.blue }, normalised, w, h, levels)
  ```
  Each `pyramidBlend` rebuilds the Laplacian pyramid of *every* frame's
  channel separately. That's 3× the pyramid work — for N=5 frames at
  12 MP this is ~240 MB of transient allocations.
- **Correct fix:** Build per-frame Laplacian pyramids once (packed RGB)
  and iterate across channels inside the blend loop.

---

## P2 — Concurrency, lifecycle, resources

### P2-14 — `CameraSessionManager.capture()` throws on failure across module boundary

- **File:**
  `platform-android/sensor-hal/src/main/kotlin/com/leica/cam/sensor_hal/session/CameraSessionManager.kt:46-55`
- **Code:**
  ```kotlin
  .onFailure {
      stateMachine.transition(CameraSessionEvent.ERROR)
      throw it
  }
  ```
- **LUMO rule violation** (same class as P1-11).

### P2-15 — `ModelRegistry.warmUpAll` calls `System.gc()` per model — explicit GC is an anti-pattern

- **File:** `ModelRegistry.kt:368`
- **Note:** Annotated `@Suppress("ExplicitGarbageCollectionCall")`, so
  the author knew. On modern ART, this is advisory at best and can
  actually *increase* jank by triggering a stop-the-world right before
  the next inference.
- **Correct fix:** Delete the call; rely on
  `TrimMemoryLevel.UI_HIDDEN` hooks or bounded session lifecycle.

### P2-16 — `CameraPreferencesRepository` has no `@Inject` constructor annotation

- **File:** `features/settings/src/main/kotlin/com/leica/cam/feature/settings/preferences/CameraPreferencesRepository.kt:13-17`
- **Hilt contract:** `@Singleton` is about scope, not construction.
  Without `@Inject constructor` or a `@Provides` binding, Hilt can't
  inject this. Need to verify whether a `@Provides` exists in
  `FeatureSettingsModule` or the repository lookup via
  `CameraScreenDeps` will fail at runtime.

### P2-17 — `LiteRtSession.close` swallows close failures with `runCatching`

- **File:** `LiteRtSession.kt:57-61, 176-179`
- **Effect:** If interpreter close or delegate close throws (native
  memory corruption, double-close), we silently continue and leak. No
  log, no metric.
- **Correct fix:** Log at WARN with delegate kind; increment a
  metric for close-failures so it's observable.

---

## P3 — Structure, duplication, dead code

### P3-1 — Legacy `ProXdrHdrEngine.kt` (546 lines) is unreachable dead code

- **File:** `engines/imaging-pipeline/impl/src/main/kotlin/com/leica/cam/imaging_pipeline/pipeline/ProXdrHdrEngine.kt`
- **Evidence:** No source file outside itself references
  `ProXdrHdrOrchestrator`. All KDoc in the new `hdr/` package
  positions it as the replacement.
- **Action:** Delete after verifying no test or external hook depends
  on the old names.

### P3-2 — Duplicate `assetBytes` provider

- Covered by P0-4. Listed here for structural completeness.

### P3-3 — `ModelRegistry.matchFilenameKeywords` — `"image classifier"` unreachable, `"isp"` over-matches

- **File:** `ModelRegistry.kt:240-261`
- **Issue A:** Keyword `"image classifier"` (line 258) is gated behind
  a prior match on `"classifier"` (line 250) which hits first. The
  IMAGE_CLASSIFIER role is effectively unassignable by filename.
- **Issue B:** `"isp"` at line 257 matches the substring inside
  `"classifier"` (no, actually it doesn't — but it *does* match inside
  `"vispnet"`, `"isp_cache"`, and similar plausible future filenames).
  Worse, `"isp"` is 3 chars, so any custom model with "ISP" in the
  filename gets forced into MICRO_ISP — but MicroISP eligibility is
  already gated per-sensor.
- **Action:** Split into `micro_isp` / `microisp` / `neural_isp` tokens;
  reorder to `IMAGE_CLASSIFIER` → `SCENE_CLASSIFIER`.

### P3-4 — `ModelRegistry.scanAll` accepts any file > 100 KB without extension

- **File:** `ModelRegistry.kt:211-213`
- **Effect:** Stray crash dumps, `.DS_Store`, `core`, etc. get listed
  as UNKNOWN models and logged at WARN on every startup.
- **Action:** Either require an extension, or reject files without
  one.

### P3-5 — `ModelRegistry` "Temp" filter matches by name only

- **File:** `ModelRegistry.kt:214`
- **Code:** `.filter { !it.name.equals("Temp", ignoreCase = true) }`
- **Issue:** Filters a file called "Temp" but not a directory. The
  `/Knowledge/Temp` 1-byte file would pass this filter if relocated.
- **Action:** Walk filter: `.filterNot { it.absolutePath.contains("/Temp/") }`.

### P3-6 — `app/src/main/kotlin/com/leica/cam/di/AssetsModule.kt` duplicates `provideAssetBytesLoader` from `:ai-engine:impl`

- Covered by P0-4.

### P3-7 — `DependencyModule.kt` files listed in project-structure.md Section 6 don't exist at the documented paths

- **Documented:** project-structure.md lines 306-322 name 15 files
  like `imaging-pipeline/.../impl/DependencyModule.kt`,
  `camera-core/.../impl/DependencyModule.kt`, etc.
- **Reality:** `find . -name DependencyModule.kt` returns **zero**
  results. All DI modules have been renamed to
  `<Module>Module.kt` with `impl/di/` sub-package.
- **Effect:** project-structure.md is factually wrong about DI
  layout — see P3-22 / P3-23.

### P3-8 — Two `package path` layouts — `impl/di/` vs `di/` (and `pipeline/` nested at two depths)

- **Observed:**
  - `engines/ai-engine/impl/src/main/kotlin/com/leica/cam/ai_engine/impl/di/AiEngineModule.kt`
    (with `impl` in the package)
  - `engines/hypertone-wb/impl/src/main/kotlin/com/leica/cam/hypertone_wb/di/HypertoneWbModule.kt`
    (no `impl`)
  - `engines/imaging-pipeline/impl/src/main/kotlin/com/leica/cam/imaging_pipeline/di/ImagingPipelineModule.kt`
    (no `impl`)
- **Inconsistency:** Some engines use `<module>.impl.*`, some use
  `<module>.*`. This makes imports confusing and invites collisions
  in `common-test` fixtures that cross engines.
- **Action:** Pick one — the `<module>.impl.*` convention is better
  because it mirrors the Gradle coordinate and prevents future
  merges with `:api` types.

### P3-9 — Three `build.gradle.kts` "root" stubs per engine

- **Files:**
  `engines/hypertone-wb/build.gradle.kts`,
  `engines/imaging-pipeline/build.gradle.kts`,
  `engines/neural-isp/build.gradle.kts`,
  `engines/smart-imaging/build.gradle.kts`,
  `core/camera-core/build.gradle.kts`,
  `core/color-science/build.gradle.kts`,
  `core/photon-matrix/build.gradle.kts`,
  `platform-android/native-imaging-core/build.gradle.kts`
- **Content:** Each is one line "`// Root empty project`" or nearly so.
- **Issue:** These are unnecessary — the "parent" directory isn't a
  Gradle project, only `:api` and `:impl` are. They're legal but
  confusing; every engine has different stubs.
- **Action:** Delete them.

### P3-10 — `core/camera-core/impl/src/main/kotlin/com/leica/cam/camera_core/di/CameraCoreModule.kt` has no `impl` in package

- Same family as P3-8 — camera-core `impl` uses `com.leica.cam.camera_core.impl.isp.*`
  for production classes but `com.leica.cam.camera_core.di.*` for DI.
  Pick one.

### P3-11 — `Constants.kt` is one constant deep

- **File:** `platform/common/src/main/kotlin/com/leica/cam/common/Constants.kt`
- **Content:** `const val MAX_CYCLOMATIC_COMPLEXITY = 15` — a detekt
  threshold, not a runtime constant.
- **Action:** Move into `config/detekt/detekt.yml` where it belongs,
  delete `Constants.kt`.

### P3-12 — `Logger.kt` and `LeicaLogger.kt` both exist in `:common`

- **Files:**
  - `platform/common/src/main/kotlin/com/leica/cam/common/Logger.kt`
  - `platform/common/src/main/kotlin/com/leica/cam/common/logging/LeicaLogger.kt`
- **Issue:** Two log facades in the same module. Pick one canonical
  implementation and delete the other.

### P3-13 — `PipelineErrorHandling.kt` lives at the top of `:common`, not in `result/`

- **File:** `platform/common/src/main/kotlin/com/leica/cam/common/PipelineErrorHandling.kt`
  vs `platform/common/src/main/kotlin/com/leica/cam/common/result/*.kt`
- **Action:** Move into `result/` or delete if superseded by
  `LeicaResult.kt`.

### P3-14 — `Result.kt` at top level conflicts with `result/LeicaResult.kt`

- **File:** `platform/common/src/main/kotlin/com/leica/cam/common/Result.kt`
- **Issue:** The canonical result type is `result.LeicaResult`; having
  a second `Result.kt` at the module root is either dead code or a
  parallel implementation (both bad).
- **Action:** Verify and delete or rename.

### P3-15 — `split_modules.sh`, `split_modules2.sh` are in repo root

- **Files:** `/split_modules.sh`, `/split_modules2.sh`
- **Purpose:** Appear to be one-off refactor scripts from a D3
  intermediate state.
- **Action:** Move into `scripts/` or delete.

### P3-16 — `.Jules/`, `.bolt/` directories at repo root

- **Purpose:** Unknown tool artefacts (`Jules`, `bolt.new`).
- **Action:** `.gitignore` them or delete.

### P3-17 — Orphan `src/` and `tests/` directories with `detect-antipatterns.mjs`

- **Files:**
  - `src/detect-antipatterns.mjs` (145 KB)
  - `src/detect-antipatterns-browser.js` (97 KB)
  - `tests/*.test.mjs`, `tests/*.test.js`
- **Issue:** These are JavaScript lint utilities that have nothing to
  do with the LeicaCam Android app. Likely a leftover from a parent
  tooling repo.
- **Action:** Move into a dedicated `/tooling/` subdir or delete.

### P3-18 — `lib/download-providers.js`, `scripts/bump-version.mjs`, etc.

- **Files:** `lib/download-providers.js`, `scripts/*.mjs`
- **Issue:** Node-based scripts shipped with an Android app repo. Both
  `package.json` + `node_modules/` are present. Either this is
  intentional repo tooling (declare it in README) or it's cruft.

### P3-19 — `source/skills/` parallel to `.agents/skills/`

- **Directories:**
  - `.agents/skills/` (55 entries)
  - `source/skills/...` (unknown content)
- **Issue:** Two locations for "skills". One is probably stale.
- **Action:** Pick one canonical location, delete the other.

### P3-20 — `Reference/`, `GSD 2.0/` at repo root

- **Excluded by user request from this review**, but note that their
  presence at the repo root inflates the dependency graph for IDEs and
  makes `./gradlew :allprojects` slow. Consider moving under `docs/`
  or a `/vendor/` subtree.

### P3-21 — `Implementation.md` is 111 KB, 2201 lines

- **File:** `Implementation.md`
- **Issue:** Historical phase-by-phase notes, per project-structure.md
  "phases 0 – 10". Useful as archive; harmful as onboarding doc.
- **Action:** Move to `docs/history/Implementation-phase0-10.md` and
  reference from README + project-structure.md.

### P3-22 — project-structure.md "DI modules" table names files that don't exist

- Covered by P3-7.

### P3-23 — project-structure.md "_Last updated: 2026-04-20_" predates the current tree

- **File:** `project-structure.md:3`
- **Current tree differs** on at least the following points (non-exhaustive):
  - Mentions `DependencyModule.kt` (none exist).
  - Lists `impl/.../pipeline/ProXdrHdrEngine.kt` but doesn't mention
    the new `impl/.../hdr/` sub-package (ProXdrOrchestrator,
    DeformableFeatureAligner, GhostMaskEngine, MertensFallback,
    RadianceMerger, HighlightReconstructor, ShadowRestorer, HdrTypes,
    BracketSelector).
  - Omits `core/camera-core/impl/.../isp/ChipsetDetector.kt`.
  - Omits `platform-android/gpu-compute/.../CpuFallbackBackend.kt`,
    `OpenGlEsBackend.kt`, `GpuComputeInitializer.kt`,
    `vulkan/ShaderRegistry.kt`.
  - Omits `platform-android/sensor-hal/session/Camera2CameraController.kt`
    (currently the active controller).
  - Omits `features/camera/src/main/kotlin/com/leica/cam/feature/camera/preview/CameraPreview.kt`.
  - Omits `features/camera/src/main/kotlin/com/leica/cam/feature/camera/controls/CaptureControlsViewModel.kt`.
  - Omits `features/settings/.../preferences/*.kt` (SharedPreferences
    store is now live).
  - Dependency map still uses "DI wiring with 15 duplicated
    `DependencyModule.kt` files" phrasing that's no longer true.
- **Action:** See separate PR "project-structure.md refresh" tracked
  in the updated plan.

### P3-24 — Gradle `build-logic/` plugin IDs are referenced but the plugin file paths aren't documented

- **Files:**
  - `build-logic/convention/src/main/kotlin/LeicaAndroidApplicationPlugin.kt`
  - `build-logic/convention/src/main/kotlin/LeicaAndroidLibraryPlugin.kt`
  - `build-logic/convention/src/main/kotlin/LeicaJvmLibraryPlugin.kt`
  - `build-logic/convention/src/main/kotlin/LeicaEngineModulePlugin.kt`
  - `build-logic/convention/src/main/kotlin/LeicaConventionSupport.kt`
- **Plugin IDs used in build files:** `leica.android.application`,
  `leica.android.library`. The mapping from plugin ID → class name
  lives in `build-logic/convention/build.gradle.kts` — **verify**
  all IDs are registered.

---

## P3 — Documentation drift

### P3-25 — README "`:bokeh-engine`" dependency claim is aspirational

- **File:** `README.md:70`
- **Claim:** `:bokeh-engine:*` is one of the shipped "imaging engines".
- **Reality:** `BokehEngineOrchestrator.kt` has a single `.kt` source
  file and depends only on `api` DTOs — it has no actual
  circle-of-confusion math, no synthetic-aperture kernel. The
  description in README line 37 ("computes a physically correct
  circle-of-confusion") is not implemented.

### P3-26 — README Roadmap Dimension 1 says "model files are present; runtime path is being integrated"

- **File:** `README.md:147`
- **Accurate** as a status statement. But as P1-1, P1-2, P1-5, P2-9
  show, the "runtime path integration" is incomplete — it adds
  new classes without plumbing them into `AiEngineOrchestrator` or
  `ImagingPipeline.process`.

### P3-27 — `changelog.md` / `docs/adr/*` not audited in this pass

- Outside audit scope but flag: every ADR references the old
  `DependencyModule.kt` layout. Refresh once P3-7 / P3-23 are fixed.

### P3-28 — `.github/workflows/` not audited

- Not read in this pass; likely contains skill/plan automation
  (`pr-risk-check.mjs`, `require-tests.sh`, `generate-changelog.mjs`,
  `docs-prompt-injection-scan.sh`, `secret-scan.sh`) that fail open
  on non-web-app repos like this one. Audit separately.

---

## Cross-cutting tech-debt inventory

### TD-1 — Reflection-heavy LiteRT loading is a ticking interface-break

- Files: `LiteRtSession.kt:134-181`, `VendorDelegateLoader.kt:41-54`,
  `FaceLandmarkerRunner.kt:89-132`.
- **Motivation (stated):** "so the module compiles even when the
  LiteRT SDK is not on the compile classpath".
- **Practical consequence:** Every LiteRT / MediaPipe release that
  changes a class name, method name, or signature silently breaks the
  runtime path. There is no compile-time safety net. This is the
  opposite of the LUMO principle "fail fast, loud, and early."
- **Recommendation:** Add `com.google.ai.edge.litert:*` and
  `com.google.mediapipe:tasks-vision` as **compileOnly** dependencies,
  or accept them as `implementation` (they're 15 MB ABI-wise) and
  delete every `Class.forName(...)` call. Reflection is only justified
  if the SDK is genuinely optional at runtime (it's not — the app is
  useless without it).

### TD-2 — "Physics constants" are duplicated across files

- `ImagingPipeline.kt`, `RadianceMerger.kt`, `native_imaging_core.cpp`,
  `MertensFallback.kt`, `GhostMaskEngine.kt`, `HdrTypes.kt` all redefine
  `LUM_R/G/B`, `TRAP_RAMP`, `GHOST_VARIANCE_THRESHOLD`.
- Different authors may drift them (`TRAP_RAMP = 0.10f` vs in cpp
  `kTrapRamp = 0.10f` — currently same, but nothing enforces parity).
- **Recommendation:** `ImagingPhysicsConstants.kt` in `:common` or
  `:hardware-contracts`; reference from Kotlin; mirror in the C++
  header with a doc reference.

### TD-3 — The `:common` module is a junk drawer

- Contains: `LeicaResult`, `DomainError`, `PipelineStage`,
  `LeicaLogger`, `Logger`, `Result`, `Constants`,
  `PipelineErrorHandling`, `CameraSessionScope`, `NonEmptyList`.
- Inconsistent shape — some concerns are bundled (`result/*`), others
  are bare top-level. Consider splitting into `:common-result`,
  `:common-logging`, `:common-types`.

### TD-4 — ktlint is "re-enabled everywhere" per D3 but 15 module paths are excluded

- project-structure.md line 477: "Currently excludes 15 module paths
  from ktlint (technical debt)". Re-enable and let the linter fail,
  then fix.

### TD-5 — No integration tests touching the capture-to-output flow

- All existing tests are unit tests against individual engines. The
  failure modes in P1-1, P1-2, P2-9 (new HDR path never invoked) all
  go unnoticed because no test composes `ImagingPipeline`,
  `ProXdrOrchestrator`, `AiEngineOrchestrator`, and
  `ImagingRuntimeOrchestrator` end to end.
- **Recommendation:** Add one golden-path integration test per
  `HdrMergeMode` using fixtures from `:common-test`.

---

## Summary by severity

| Band | Count | Indicative themes |
|---|---|---|
| P0 | 8 | Build breakers: missing `:impl` deps, duplicate Hilt bindings, type error in orchestrator, unreachable `File("models")`, broken warm-up |
| P1 | 15 | AI orchestrator is stubs; MicroISP never runs; Semantic output mis-parsed; Native runtime deadlocks + use-after-free; HUD buttons are no-ops |
| P2 | 17 | New HDR path never called; old HDR path still active; thermal thresholds disagree; Mertens drops metadata; motion-score uses wrong channel variance |
| P3 | 28 | Dead `ProXdrHdrEngine.kt`, duplicate assets module, stale project-structure.md, inconsistent package layouts, top-level junk files |

**Net state:** The repository currently does not compile (P0-1
through P0-5). Once compile errors are resolved, the
runtime path still routes almost entirely through stub implementations
(P1 cluster). The "D1 AI integration" and "D2 HDR rebuild" work items
claimed in the README / Plan.md are code-complete but not wired.

**Recommended order of repair:** fix all P0 first (one PR, no
behavioural changes), then P1-1 and P1-8 (wire AI orchestrator,
fix native runtime loop), then P1-2 / P2-9 (wire MicroISP and the
new HDR orchestrator into the live path), then the rest.

