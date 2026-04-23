# Plan: LeicaCam — Codebase Repair, Model Integration & Advanced Imaging Upgrade

## Context

LeicaCam is a multi-module Android computational-photography stack (Kotlin 2.0,
C++17, Vulkan compute, LiteRT + MediaPipe) that implements the **LUMO** imaging
platform on a MediaTek Dimensity device with 8 sensors (S5KHM6, OV64B40/50D40/
08D10/16A1Q, GC16B3, SC202CS/PCS). The work targets `/home/user/webapp` on
branch `genspark_ai_developer` → PR to `main`.

This plan is produced **after** the audit in `Problems list.md` (same
directory). Read that file first. This plan is organised into seven
sub-plans (P0 → P6) that must be executed **in order**. Each sub-plan is
sized to be completed in one executor pass (≤ 2 hours of focused work) and
has its own verification gate. **Stop and verify between sub-plans.**

The sub-plans are:

| # | Name | Acceptance |
|---|---|---|
| **P0** | Build-breakers (compile the app, period) | `./gradlew assemble` green |
| **P1** | Wire live AI runners into the orchestrator | `AiEngineOrchestrator` calls LiteRT models |
| **P2** | Wire the new HDR path into `ImagingPipeline` | Old `ProXdrHdrEngine.kt` deleted |
| **P3** | SDK hardening — LiteRT + MediaPipe no longer via reflection | `litert:*` + `tasks-vision` on compileOnly |
| **P4** | Native runtime + ZSL concurrency fixes | No use-after-free, no ANR |
| **P5** | UI → capture wiring (Flash/HDR/Zoom/Gallery/Switch) | All top-bar buttons produce real capture intents |
| **P6** | Advanced imaging — bokeh, super-res, seamless zoom | Bokeh Engine produces a real CoC output |

A parallel workstream (**P-DOCS**) refreshes `project-structure.md` to match
the current tree; this is documented separately but is required for the PR
that lands P0–P2 (otherwise every reviewer is reading out-of-date context).

---

## Which agent skill to use for which sub-plan

These skills live in `.agents/skills/`. Activate with
`activate_ai_developer_skill` before each sub-plan — they carry
domain-specific context that this plan assumes the executor has.

| Sub-plan | Primary skill | Supporting skill(s) |
|---|---|---|
| P0 (build) | `kotlin-specialist` | `systematic-debugging`, `verification-before-completion` |
| P1 (AI wiring) | `Leica Cam Upgrade skill` | `Lumo Imaging Engineer`, `kotlin-specialist` |
| P2 (HDR rebuild) | `Lumo Imaging Engineer` | `Leica Cam Upgrade skill`, `coding-standards` |
| P3 (SDK hardening) | `kotlin-specialist` | `security-patterns`, `error-handling` |
| P4 (concurrency) | `kotlin-specialist` | `systematic-debugging`, `test-driven-development` |
| P5 (UI wiring) | `Frontend-design` | `Leica Cam Upgrade skill` (for the imaging callbacks), `designing-architecture` |
| P6 (advanced imaging) | `Lumo Imaging Engineer` | `optimizing-performance`, `designing-architecture` |
| P-DOCS | `analyzing-projects` | `writing-plans` |

Additionally the following skills apply across every sub-plan:

- **`coding-standards`** — enforce the LUMO rules (no `!!`, no bare
  `catch (e: Exception)`, `CancellationException` re-throw, no
  `GlobalScope`, `LeicaResult` return type for every fallible function).
- **`managing-git`** — follow the genspark_ai_developer branch workflow:
  commit immediately after each step, push, and rebase onto `main`
  before opening the PR.
- **`verification-before-completion`** — run the verification block at
  the end of each sub-plan before declaring it done.

---

## Stack & Assumptions (applies to every sub-plan)

- **Language/runtime:** Kotlin 2.0 (gradle/libs.versions.toml says
  `kotlin = "1.9.24"` — verify; if 1.9.x, use compatible syntax), JDK
  17, Android SDK compile 35, min 29.
- **Framework:** Android + Compose (BOM 2024.06.00), Hilt 2.51.1,
  CameraX 1.3.4, coroutines 1.8.1.
- **Key deps (already in `libs.versions.toml`):**
  - `com.google.ai.edge.litert:litert:1.0.1`
  - `com.google.ai.edge.litert:litert-gpu:1.0.1`
  - `com.google.ai.edge.litert:litert-support:1.0.1`
  - `com.google.mediapipe:tasks-vision:0.10.14`
- **Key deps to add in P3:** none — they're already declared; we just
  need to reference them from the right modules.
- **ENV:**
  - JDK 17 must be on `JAVA_HOME`.
  - Android NDK r27 for `:native-imaging-core:impl`.
- **Working directory:** `/home/user/webapp`.

---

## Global files-you-will-touch checklist

- [ ] `app/build.gradle.kts` (modify — P0)
- [ ] `app/src/main/kotlin/com/leica/cam/LeicaCamApp.kt` (modify — P0, P1)
- [ ] `app/src/main/kotlin/com/leica/cam/di/AssetsModule.kt` (**delete** — P0)
- [ ] `app/src/main/kotlin/com/leica/cam/MainActivity.kt` (modify — P5)
- [ ] `engines/ai-engine/api/src/main/kotlin/com/leica/cam/ai_engine/api/AiInterfaces.kt` (modify — P1)
- [ ] `engines/ai-engine/api/src/main/kotlin/com/leica/cam/ai_engine/api/AiContracts.kt` (modify — P1)
- [ ] `engines/ai-engine/api/src/main/kotlin/com/leica/cam/ai_engine/api/AiNeuralPredictions.kt` (new — P1)
- [ ] `engines/ai-engine/impl/src/main/kotlin/com/leica/cam/ai_engine/impl/di/AiEngineModule.kt` (modify — P0, P1)
- [ ] `engines/ai-engine/impl/src/main/kotlin/com/leica/cam/ai_engine/impl/pipeline/AiEngineOrchestrator.kt` (rewrite — P1)
- [ ] `engines/ai-engine/impl/src/main/kotlin/com/leica/cam/ai_engine/impl/registry/ModelRegistry.kt` (rewrite — P0, P3)
- [ ] `engines/ai-engine/impl/src/main/kotlin/com/leica/cam/ai_engine/impl/runtime/LiteRtSession.kt` (rewrite — P3)
- [ ] `engines/ai-engine/impl/src/main/kotlin/com/leica/cam/ai_engine/impl/runtime/VendorDelegateLoader.kt` (modify — P3)
- [ ] `engines/ai-engine/impl/src/main/kotlin/com/leica/cam/ai_engine/impl/models/AwbModelRunner.kt` (modify — P1)
- [ ] `engines/ai-engine/impl/src/main/kotlin/com/leica/cam/ai_engine/impl/models/FaceLandmarkerRunner.kt` (modify — P3)
- [ ] `engines/ai-engine/impl/src/main/kotlin/com/leica/cam/ai_engine/impl/models/MicroIspRunner.kt` (modify — P2)
- [ ] `engines/ai-engine/impl/src/main/kotlin/com/leica/cam/ai_engine/impl/models/SceneClassifierRunner.kt` (modify — P1)
- [ ] `engines/ai-engine/impl/src/main/kotlin/com/leica/cam/ai_engine/impl/models/SemanticSegmenterRunner.kt` (modify — P1)
- [ ] `engines/ai-engine/impl/build.gradle.kts` (modify — P3)
- [ ] `engines/hypertone-wb/impl/src/main/kotlin/com/leica/cam/hypertone_wb/pipeline/HyperToneWhiteBalanceEngine.kt` (modify — P0, P1)
- [ ] `engines/imaging-pipeline/impl/src/main/kotlin/com/leica/cam/imaging_pipeline/pipeline/ImagingPipeline.kt` (modify — P0, P2)
- [ ] `engines/imaging-pipeline/impl/src/main/kotlin/com/leica/cam/imaging_pipeline/pipeline/ProXdrHdrEngine.kt` (**delete** — P2)
- [ ] `engines/imaging-pipeline/impl/src/main/kotlin/com/leica/cam/imaging_pipeline/di/ImagingPipelineModule.kt` (modify — P2)
- [ ] `engines/imaging-pipeline/impl/build.gradle.kts` (modify — P0)
- [ ] `engines/imaging-pipeline/impl/src/main/kotlin/com/leica/cam/imaging_pipeline/hdr/ProXdrOrchestrator.kt` (modify — P0)
- [ ] `engines/imaging-pipeline/impl/src/main/kotlin/com/leica/cam/imaging_pipeline/hdr/MertensFallback.kt` (modify — P2)
- [ ] `engines/imaging-pipeline/impl/src/main/kotlin/com/leica/cam/imaging_pipeline/hdr/GhostMaskEngine.kt` (modify — P2)
- [ ] `platform-android/native-imaging-core/impl/src/main/kotlin/com/leica/cam/native_imaging_core/impl/nativeimagingcore/ImagingRuntimeOrchestrator.kt` (rewrite worker loop — P4)
- [ ] `platform-android/sensor-hal/src/main/kotlin/com/leica/cam/sensor_hal/session/Camera2CameraController.kt` (modify — P4)
- [ ] `platform-android/sensor-hal/src/main/kotlin/com/leica/cam/sensor_hal/zsl/ZeroShutterLagRingBuffer.kt` (modify — P4)
- [ ] `features/camera/src/main/kotlin/com/leica/cam/feature/camera/ui/CameraScreen.kt` (modify — P5)
- [ ] `features/camera/src/main/kotlin/com/leica/cam/feature/camera/preview/CameraPreview.kt` (modify — P4)
- [ ] `features/settings/src/main/kotlin/com/leica/cam/feature/settings/preferences/CameraPreferencesRepository.kt` (modify — P0 add `@Inject`)
- [ ] `engines/bokeh-engine/impl/src/main/kotlin/com/leica/cam/bokeh_engine/impl/BokehEngineOrchestrator.kt` (rewrite — P6)
- [ ] `common` & `hardware-contracts` — new `ThermalState` enum (P2)
- [ ] `project-structure.md` (rewrite sections 2, 3, 4, 6, 7 — P-DOCS)

---

# Sub-plan P0 — Build-breakers (fix compile, then stop)

**Goal:** Make `./gradlew assemble` succeed. No behavioural changes beyond
what's needed to compile.

**Skill:** `kotlin-specialist`. Supporting: `systematic-debugging`.

## P0 files to touch (subset of the global list)

- [ ] `app/build.gradle.kts` (modify)
- [ ] `app/src/main/kotlin/com/leica/cam/di/AssetsModule.kt` (delete)
- [ ] `app/src/main/kotlin/com/leica/cam/LeicaCamApp.kt` (modify — only
      the comment; functional change defers to P1)
- [ ] `engines/imaging-pipeline/impl/build.gradle.kts` (add
      `:ai-engine:api` for contracts — not `:impl`)
- [ ] `engines/imaging-pipeline/impl/src/main/kotlin/com/leica/cam/imaging_pipeline/pipeline/ImagingPipeline.kt`
      (replace `com.leica.cam.ai_engine.impl.models.*` imports with
      `:api` interfaces; temporarily make the new params `Any?` if the
      interfaces aren't ready — but the cleaner fix is to land P0 and
      P1 together in the same PR)
- [ ] `engines/hypertone-wb/impl/src/main/kotlin/com/leica/cam/hypertone_wb/pipeline/HyperToneWhiteBalanceEngine.kt`
      (replace `ai_engine.impl.models.AwbModelRunner/AwbPrediction` with
      `:api` types)
- [ ] `engines/ai-engine/api/src/main/kotlin/com/leica/cam/ai_engine/api/AiNeuralPredictions.kt` (new file)
- [ ] `engines/ai-engine/api/src/main/kotlin/com/leica/cam/ai_engine/api/AiInterfaces.kt` (extend)
- [ ] `engines/imaging-pipeline/impl/src/main/kotlin/com/leica/cam/imaging_pipeline/hdr/ProXdrOrchestrator.kt`
      (fix line 123–125)
- [ ] `engines/ai-engine/impl/src/main/kotlin/com/leica/cam/ai_engine/impl/di/AiEngineModule.kt`
      (fix `provideModelDirectory`)
- [ ] `engines/ai-engine/impl/src/main/kotlin/com/leica/cam/ai_engine/impl/registry/ModelRegistry.kt`
      (stop reading filesystem; use AssetManager)
- [ ] `features/settings/src/main/kotlin/com/leica/cam/feature/settings/preferences/CameraPreferencesRepository.kt`
      (add `@Inject constructor`)

## Steps

### Step P0.1 — Delete the duplicate Hilt provider

- [ ] Delete file `app/src/main/kotlin/com/leica/cam/di/AssetsModule.kt`.
- [ ] In `app/src/main/kotlin/com/leica/cam/LeicaCamApp.kt`, verify the
      class still compiles — it injects `@Named("assetBytes") Function1<...>`,
      and the only provider left is the one in `:ai-engine:impl` (see P0.2).

### Step P0.2 — Make `:ai-engine:impl` visible to `:app`

- [ ] Open `app/build.gradle.kts`.
- [ ] Locate the block:
      ```kotlin
      dependencies {
          implementation(libs.androidx.navigation.compose)
          implementation(libs.accompanist.permissions)
          implementation(project(":feature:camera"))
          implementation(project(":feature:gallery"))
          implementation(project(":feature:settings"))
          implementation(project(":ui-components"))
      ```
- [ ] Insert the following project deps **before** the `libs.*` lines:
      ```kotlin
      // Engine impl modules — required for Hilt graph aggregation.
      implementation(project(":ai-engine:impl"))
      implementation(project(":imaging-pipeline:impl"))
      implementation(project(":hypertone-wb:impl"))
      implementation(project(":color-science:impl"))
      implementation(project(":depth-engine:impl"))
      implementation(project(":face-engine:impl"))
      implementation(project(":neural-isp:impl"))
      implementation(project(":photon-matrix:impl"))
      implementation(project(":smart-imaging:impl"))
      implementation(project(":bokeh-engine:impl"))
      implementation(project(":motion-engine:impl"))
      implementation(project(":native-imaging-core:impl"))
      implementation(project(":camera-core:impl"))
      ```
      Keep existing `:feature:*` and `:ui-components` lines; they're
      already there.

### Step P0.3 — Fix `ProXdrOrchestrator.kt` type error

- [ ] Open `engines/imaging-pipeline/impl/src/main/kotlin/com/leica/cam/imaging_pipeline/hdr/ProXdrOrchestrator.kt`.
- [ ] Locate the block (line 122-126):
      ```kotlin
      is LeicaResult.Failure -> return alignResult.map { it.mergedFrame }.flatMap {
          LeicaResult.Failure.Pipeline(PipelineStage.IMAGING_PIPELINE, "Alignment failed")
      }
      ```
- [ ] Replace with:
      ```kotlin
      is LeicaResult.Failure -> return LeicaResult.Failure.Pipeline(
          PipelineStage.IMAGING_PIPELINE, "Alignment failed",
      )
      ```

### Step P0.4 — Create `:ai-engine:api` contracts so `:impl` cross-deps go away

The core of P0 is: **types shared between `:imaging-pipeline:impl`,
`:hypertone-wb:impl`, and the AI runners must live in `:ai-engine:api`.**
Move them now so the `impl → impl` imports can be replaced with
`impl → api` imports.

- [ ] Create file
      `engines/ai-engine/api/src/main/kotlin/com/leica/cam/ai_engine/api/AiNeuralPredictions.kt`
      with the exact contents below:
      ```kotlin
      package com.leica.cam.ai_engine.api

      import com.leica.cam.common.result.LeicaResult
      import java.nio.ByteBuffer

      /** Neural AWB model output shared between :ai-engine:impl and :hypertone-wb:impl. */
      data class AwbNeuralPrior(
          val cctKelvin: Float,
          val tintDuv: Float,
          val confidence: Float,
      )

      /**
       * Pure contract — the predictor interface that HyperToneWhiteBalanceEngine consumes.
       * The LiteRT-backed implementation lives in :ai-engine:impl as AwbModelRunner.
       */
      interface AwbPredictor {
          /**
           * @param tileRgb 224*224*3 interleaved RGB float tile, linear scene-referred, un-biased.
           * @param sensorWbBias Per-sensor RGB gain bias. Applied AFTER inference, not before.
           */
          fun predict(tileRgb: FloatArray, sensorWbBias: FloatArray): LeicaResult<AwbNeuralPrior>
      }

      /** Neural ISP refinement contract — ultra-wide / front cameras only. */
      interface NeuralIspRefiner {
          /** True if this sensor is on the MicroISP allow-list. */
          fun isEligible(sensorId: String): Boolean
          /**
           * Refine one 256*256*4 Bayer tile. Returns a new 256*256*4 float array.
           * Caller is responsible for tiling and overlap-blending the frame.
           */
          fun refine(bayerTile: FloatArray): LeicaResult<FloatArray>
      }

      /** Semantic segmentation contract. */
      interface SemanticSegmenter {
          /**
           * Run segmentation on a pre-downsampled 257*257*3 RGB float tile.
           * @param sensorIso Used to relax the confidence threshold at high ISO.
           */
          fun segment(tileRgb: FloatArray, originalWidth: Int, originalHeight: Int, sensorIso: Int): LeicaResult<SemanticSegmentationOutput>
      }

      /**
       * Platform-neutral segmentation output. Consumers in :imaging-pipeline:impl
       * adapt this to their local `SemanticMask` type.
       *
       * `zones` is a flat (originalWidth * originalHeight) array where each
       * element is a [SemanticZoneCode] ordinal.
       */
      data class SemanticSegmentationOutput(
          val width: Int,
          val height: Int,
          val zoneCodes: IntArray,
      )

      enum class SemanticZoneCode { BACKGROUND, MIDGROUND, PERSON, SKY, UNKNOWN }

      /** Scene classification contract. */
      interface SceneClassifier {
          fun classify(tileRgb: FloatArray): LeicaResult<SceneClassificationOutput>
      }

      data class SceneClassificationOutput(
          val primaryLabel: SceneLabel,
          val primaryConfidence: Float,
          val top5: List<Pair<SceneLabel, Float>>,
      )

      /** Face landmark contract (478-point MediaPipe mesh). */
      interface FaceLandmarker {
          fun detect(bitmapArgb8888: IntArray, width: Int, height: Int, isFrontCamera: Boolean): LeicaResult<FaceLandmarkerOutput>
      }

      data class FaceLandmarkerOutput(
          val faces: List<FaceMesh>,
          val imageWidth: Int,
          val imageHeight: Int,
      ) {
          val hasFaces: Boolean get() = faces.isNotEmpty()
      }

      data class FaceMesh(
          val points478Normalized: FloatArray, // x0,y0,z0, x1,y1,z1, ... length = 478*3
          val confidence: Float,
      )

      /** Loaded model asset — bytes-in-memory contract used by :ai-engine:api consumers. */
      typealias AssetBytesLoader = (path: String) -> ByteBuffer
      ```

- [ ] In `engines/ai-engine/api/src/main/kotlin/com/leica/cam/ai_engine/api/AiContracts.kt`,
      **extend** the existing `SceneLabel` enum to include the values the
      runner uses:
      ```kotlin
      enum class SceneLabel {
          PORTRAIT,
          LANDSCAPE,
          NIGHT,
          DOCUMENT,
          FOOD,
          PET,
          BACKLIT,
          BACKLIT_PORTRAIT,
          MACRO,
          INDOOR,
          OUTDOOR,
          GENERAL,
          STAGE,
          SNOW,
          ARCHITECTURE,
          UNKNOWN,
      }
      ```

### Step P0.5 — Remove the impl-package `SceneLabel` duplicate

- [ ] Open `engines/ai-engine/impl/src/main/kotlin/com/leica/cam/ai_engine/impl/models/SceneClassifierRunner.kt`.
- [ ] Delete lines 131-150 (the local `enum class SceneLabel`,
      `data class SceneLabelScore`, `data class SceneClassification`).
- [ ] Replace every `SceneLabel.*` reference in this file with
      `com.leica.cam.ai_engine.api.SceneLabel.*`.
- [ ] Replace the return type `LeicaResult<SceneClassification>` with
      `LeicaResult<com.leica.cam.ai_engine.api.SceneClassificationOutput>`
      and adjust the wrapping.

### Step P0.6 — Make `HyperToneWhiteBalanceEngine` depend on `:api` only

- [ ] Open `engines/hypertone-wb/impl/src/main/kotlin/com/leica/cam/hypertone_wb/pipeline/HyperToneWhiteBalanceEngine.kt`.
- [ ] Replace:
      ```kotlin
      import com.leica.cam.ai_engine.impl.models.AwbModelRunner
      import com.leica.cam.ai_engine.impl.models.AwbPrediction
      ```
      with:
      ```kotlin
      import com.leica.cam.ai_engine.api.AwbNeuralPrior
      import com.leica.cam.ai_engine.api.AwbPredictor
      ```
- [ ] Replace every `AwbModelRunner` type reference with `AwbPredictor`.
- [ ] Replace every `AwbPrediction` type reference with `AwbNeuralPrior`.
- [ ] The `build.gradle.kts` already has `implementation(project(":ai-engine:api"))`
      — no gradle change needed.

### Step P0.7 — Same move for `ImagingPipeline.kt`

- [ ] Open `engines/imaging-pipeline/impl/src/main/kotlin/com/leica/cam/imaging_pipeline/pipeline/ImagingPipeline.kt`.
- [ ] Replace (line 1217-1220):
      ```kotlin
      private val microIspRunner: com.leica.cam.ai_engine.impl.models.MicroIspRunner? = null,
      private val semanticSegmenterRunner: com.leica.cam.ai_engine.impl.models.SemanticSegmenterRunner? = null,
      ```
      with:
      ```kotlin
      private val microIspRefiner: com.leica.cam.ai_engine.api.NeuralIspRefiner? = null,
      private val semanticSegmenter: com.leica.cam.ai_engine.api.SemanticSegmenter? = null,
      ```
- [ ] Update references to `microIspRunner` → `microIspRefiner` and
      `semanticSegmenterRunner` → `semanticSegmenter` in `process()`
      (lines 1271-1280, 1302-1315).
- [ ] In `autoSegment`, adapt the `SemanticSegmentationOutput` to the
      local `SemanticMask`. Add this helper at the bottom of the class:
      ```kotlin
      private fun SemanticSegmentationOutput.toSemanticMask(): SemanticMask {
          val zones = Array(width * height) { i ->
              when (com.leica.cam.ai_engine.api.SemanticZoneCode.entries[zoneCodes[i].coerceIn(0, com.leica.cam.ai_engine.api.SemanticZoneCode.entries.size - 1)]) {
                  com.leica.cam.ai_engine.api.SemanticZoneCode.BACKGROUND -> SemanticZone.BACKGROUND
                  com.leica.cam.ai_engine.api.SemanticZoneCode.MIDGROUND -> SemanticZone.MIDGROUND
                  com.leica.cam.ai_engine.api.SemanticZoneCode.PERSON -> SemanticZone.PERSON
                  com.leica.cam.ai_engine.api.SemanticZoneCode.SKY -> SemanticZone.MIDGROUND // or add SKY to SemanticZone
                  com.leica.cam.ai_engine.api.SemanticZoneCode.UNKNOWN -> SemanticZone.UNKNOWN
              }
          }
          return SemanticMask(width, height, zones)
      }
      ```
- [ ] Add `implementation(project(":ai-engine:api"))` to
      `engines/imaging-pipeline/impl/build.gradle.kts` (before the
      existing `implementation(libs.androidx.core.ktx)` line).

### Step P0.8 — AwbModelRunner, MicroIspRunner, SemanticSegmenterRunner implement the new interfaces

- [ ] Open `engines/ai-engine/impl/src/main/kotlin/com/leica/cam/ai_engine/impl/models/AwbModelRunner.kt`.
- [ ] Change the class signature:
      ```kotlin
      class AwbModelRunner @Inject constructor(
          private val registry: ModelRegistry,
          private val assetBytes: (path: String) -> ByteBuffer,
      ) : AutoCloseable, com.leica.cam.ai_engine.api.AwbPredictor {
      ```
- [ ] Rename `fun predict(tile: FloatArray, wbBias: FloatArray): LeicaResult<AwbPrediction>`
      to `override fun predict(tileRgb: FloatArray, sensorWbBias: FloatArray): LeicaResult<AwbNeuralPrior>`.
- [ ] Return `AwbNeuralPrior(...)` instead of `AwbPrediction(...)`.
- [ ] **Fix P1-7** while here: remove the `wbBias` pre-multiplication
      (lines 60-66) — just write the raw tile bytes. The caller will
      apply `wbBias` on the output gains in `HyperToneWB2Engine`.
      Replace the loop with:
      ```kotlin
      for (v in tileRgb) input.putFloat(v)
      input.rewind()
      ```
- [ ] **Delete** the standalone `data class AwbPrediction` at line 106-117.

- [ ] Same pattern for
      `engines/ai-engine/impl/src/main/kotlin/com/leica/cam/ai_engine/impl/models/MicroIspRunner.kt`:
      ```kotlin
      class MicroIspRunner @Inject constructor(
          private val registry: ModelRegistry,
          private val assetBytes: (path: String) -> ByteBuffer,
      ) : AutoCloseable, com.leica.cam.ai_engine.api.NeuralIspRefiner {
          // ...existing body, rename refine → override fun refine(bayerTile)
      }
      ```

- [ ] Same pattern for `SemanticSegmenterRunner.kt`:
      ```kotlin
      class SemanticSegmenterRunner @Inject constructor(
          private val registry: ModelRegistry,
          private val assetBytes: (path: String) -> ByteBuffer,
      ) : AutoCloseable, com.leica.cam.ai_engine.api.SemanticSegmenter {
          override fun segment(
              tileRgb: FloatArray,
              originalWidth: Int,
              originalHeight: Int,
              sensorIso: Int,
          ): LeicaResult<com.leica.cam.ai_engine.api.SemanticSegmentationOutput> {
              // adapt the existing body: return zone codes as IntArray, not SemanticMask
          }
      }
      ```

### Step P0.9 — Register the new interface bindings in Hilt

- [ ] Open `engines/ai-engine/impl/src/main/kotlin/com/leica/cam/ai_engine/impl/di/AiEngineModule.kt`.
- [ ] After the existing `provideAwbModelRunner` etc., add these
      `@Binds`-equivalent `@Provides`:
      ```kotlin
      @Provides
      @Singleton
      fun provideAwbPredictor(runner: AwbModelRunner): com.leica.cam.ai_engine.api.AwbPredictor = runner

      @Provides
      @Singleton
      fun provideNeuralIspRefiner(runner: MicroIspRunner): com.leica.cam.ai_engine.api.NeuralIspRefiner = runner

      @Provides
      @Singleton
      fun provideSemanticSegmenter(runner: SemanticSegmenterRunner): com.leica.cam.ai_engine.api.SemanticSegmenter = runner

      @Provides
      @Singleton
      fun provideSceneClassifier(runner: SceneClassifierRunner): com.leica.cam.ai_engine.api.SceneClassifier = runner

      @Provides
      @Singleton
      fun provideFaceLandmarker(runner: FaceLandmarkerRunner): com.leica.cam.ai_engine.api.FaceLandmarker = runner
      ```
      (You'll need to make `SceneClassifierRunner` and `FaceLandmarkerRunner`
      implement those interfaces too — same pattern as P0.8.)

### Step P0.10 — Point `ModelRegistry` at the APK's assets, not a filesystem directory

- [ ] Open `engines/ai-engine/impl/src/main/kotlin/com/leica/cam/ai_engine/impl/di/AiEngineModule.kt`.
- [ ] **Delete** the `provideModelDirectory` `@Provides` at line 26-29.
- [ ] **Delete** the `@Named("modelDir")` parameter from
      `provideModelRegistry`.
- [ ] Rewrite `provideModelRegistry` to scan assets instead of the
      filesystem:
      ```kotlin
      @Provides
      @Singleton
      fun provideModelRegistry(
          @ApplicationContext context: Context,
      ): ModelRegistry {
          val registry = ModelRegistry.fromAssets(
              assetManager = context.assets,
              logger = { level, tag, message ->
                  android.util.Log.println(
                      when (level) {
                          ModelRegistry.LogLevel.DEBUG -> android.util.Log.DEBUG
                          ModelRegistry.LogLevel.INFO  -> android.util.Log.INFO
                          ModelRegistry.LogLevel.WARN  -> android.util.Log.WARN
                          ModelRegistry.LogLevel.ERROR -> android.util.Log.ERROR
                      },
                      tag,
                      message,
                  )
              },
          )
          registry.logCatalogue()
          return registry
      }
      ```

- [ ] Open `engines/ai-engine/impl/src/main/kotlin/com/leica/cam/ai_engine/impl/registry/ModelRegistry.kt`.
- [ ] **Add** a new companion-object constructor `fromAssets` that walks
      `AssetManager.list("models")` recursively:
      ```kotlin
      companion object {
          private const val TAG = "ModelRegistry"
          private const val MODELS_ROOT = "models"

          fun fromAssets(
              assetManager: android.content.res.AssetManager,
              logger: (level: LogLevel, tag: String, message: String) -> Unit = { _, _, m -> println(m) },
          ): ModelRegistry {
              return ModelRegistry(
                  modelDirOrNull = null,
                  assetManagerOrNull = assetManager,
                  logger = logger,
              )
          }
      }
      ```
- [ ] Change the primary constructor to accept both sources, with
      `assetManagerOrNull` preferred:
      ```kotlin
      class ModelRegistry internal constructor(
          private val modelDirOrNull: File? = null,
          private val assetManagerOrNull: android.content.res.AssetManager? = null,
          private val logger: (level: LogLevel, tag: String, message: String) -> Unit = { _, _, m -> println(m) },
      ) {
          // ...
      }
      ```
- [ ] Add `scanAllFromAssets()` that walks assets and builds entries with
      `ModelEntry.assetPath` (add a new field) instead of `File`.
      Minimum compilable scaffold:
      ```kotlin
      data class ModelEntry(
          val assetPath: String,
          val fileOrNull: File?,
          val format: ModelFormat,
          val role: PipelineRole,
          val sizeBytes: Long,
          val roleSource: String,
      ) {
          val displayName: String get() = fileOrNull?.name ?: assetPath.substringAfterLast('/')
      }
      ```
- [ ] Implement asset walking:
      ```kotlin
      private fun listAssetsRecursive(am: android.content.res.AssetManager, dir: String): List<String> {
          val children = am.list(dir) ?: return emptyList()
          if (children.isEmpty()) return listOf(dir) // leaf file
          return children.flatMap { child ->
              val full = if (dir.isEmpty()) child else "$dir/$child"
              listAssetsRecursive(am, full)
          }
      }
      ```
- [ ] Update `loadBytesForRole` to use the asset path form.
- [ ] **Keep** the old `File`-based code path behind the
      `modelDirOrNull != null` branch so unit tests (which use
      temporary directories) still work.

### Step P0.11 — Fix warm-up tensor sizes

- [ ] In `ModelRegistry.warmUpAll` (line 341-378), **delete** the
      hard-coded 4-byte dummy buffers.
- [ ] Replace with per-role warm-up sizes:
      ```kotlin
      private fun warmUpInputSize(role: PipelineRole): Int = when (role) {
          PipelineRole.AUTO_WHITE_BALANCE   -> 224 * 224 * 3 * 4
          PipelineRole.SCENE_CLASSIFIER,
          PipelineRole.IMAGE_CLASSIFIER     -> 224 * 224 * 3 * 4
          PipelineRole.SEMANTIC_SEGMENTER   -> 257 * 257 * 3 * 4
          PipelineRole.MICRO_ISP            -> 256 * 256 * 4 * 4
          PipelineRole.FACE_LANDMARKER      -> 256 * 256 * 4    // MediaPipe uses bitmap, not ByteBuffer
          else                              -> 0
      }
      private fun warmUpOutputSize(role: PipelineRole): Int = when (role) {
          PipelineRole.AUTO_WHITE_BALANCE   -> 3 * 4
          PipelineRole.SCENE_CLASSIFIER,
          PipelineRole.IMAGE_CLASSIFIER     -> 1000 * 4
          PipelineRole.SEMANTIC_SEGMENTER   -> 257 * 257 * 4
          PipelineRole.MICRO_ISP            -> 256 * 256 * 4 * 4
          else                              -> 0
      }
      ```
- [ ] In the loop, skip any role whose `warmUpInputSize` is 0 (e.g.
      FACE_LANDMARKER uses MediaPipe, not LiteRT).

### Step P0.12 — Fix `CameraPreferencesRepository` Hilt injection

- [ ] Open `features/settings/src/main/kotlin/com/leica/cam/feature/settings/preferences/CameraPreferencesRepository.kt`.
- [ ] Add `@Inject` to the constructor:
      ```kotlin
      @Singleton
      class CameraPreferencesRepository @Inject constructor(
          private val store: SharedPreferencesCameraStore,
      ) { ... }
      ```
- [ ] Verify `SharedPreferencesCameraStore` also has `@Inject constructor`.
      If not, add it. If it needs `Context`, use `@ApplicationContext`.

### Step P0.13 — Resolve remaining compile errors

Run `./gradlew :app:compileDevDebugKotlin` and iterate on any
remaining "unresolved reference" errors. Expected residuals after the
steps above: none. If any appear, they'll be in test files referencing
the old types — update the test to the new interface names.

## P0 Verification gate

- [ ] `./gradlew clean` succeeds.
- [ ] `./gradlew assembleDevDebug` succeeds with no Kotlin or Hilt
      errors. Expect: `BUILD SUCCESSFUL`.
- [ ] `./gradlew :app:kaptGenerateStubsDevDebugKotlin` succeeds (Hilt
      stubs compile — this is the canary for DI graph validity).
- [ ] `grep -rn "com.leica.cam.ai_engine.impl" engines/hypertone-wb/impl engines/imaging-pipeline/impl`
      returns **zero** results. (No engine `:impl` directly imports
      another engine's `:impl`.)
- [ ] `grep -rn "com.leica.cam.ai_engine.impl" app/src` returns **only**
      the `ModelRegistry` import in `LeicaCamApp.kt` — which is fine
      because `:app` now depends on `:ai-engine:impl` for Hilt
      aggregation.
- [ ] `find . -name AssetsModule.kt -path '*/app/*'` returns empty.
- [ ] Existing unit tests under `engines/imaging-pipeline/impl/src/test/`
      still pass: `./gradlew :imaging-pipeline:impl:test`.

**Stop here, commit, push, open the PR. Do not proceed to P1 until
reviewers have signed off on P0.**

---

# Sub-plan P1 — Wire the live AI orchestrator

**Goal:** `AiEngineOrchestrator.classifyAndScore` runs real inference
through `SceneClassifier`, `SemanticSegmenter`, `FaceLandmarker`, and
uses the results to build a real `SceneAnalysis`. No more stubs.

**Skill:** `Leica Cam Upgrade skill` (for the LUMO-specific decisions
about skin-anchor blending, per-sensor gates), supporting
`Lumo Imaging Engineer` and `kotlin-specialist`.

## Steps

### Step P1.1 — Extend `IAiEngine` to accept a pre-downsampled tile

- [ ] In `AiInterfaces.kt`, add:
      ```kotlin
      interface IAiEngine {
          suspend fun classifyAndScore(
              fused: FusedPhotonBuffer,
              captureMode: CaptureMode,
              sceneTileRgb224x224: FloatArray? = null,
              segTileRgb257x257: FloatArray? = null,
              faceArgb8888: IntArray? = null,
              faceWidth: Int = 0,
              faceHeight: Int = 0,
              isFrontCamera: Boolean = false,
          ): LeicaResult<SceneAnalysis>
      }
      ```
      The extra tile params let callers supply pre-downsampled buffers
      when they've already paid the resize cost (e.g. on the preview
      path). If null, `AiEngineOrchestrator` downsamples from `fused`.

### Step P1.2 — Rewrite `AiEngineOrchestrator`

- [ ] Open `engines/ai-engine/impl/src/main/kotlin/com/leica/cam/ai_engine/impl/pipeline/AiEngineOrchestrator.kt`.
- [ ] Replace the entire file with:
      ```kotlin
      package com.leica.cam.ai_engine.impl.pipeline

      import com.leica.cam.ai_engine.api.CaptureMode
      import com.leica.cam.ai_engine.api.FaceLandmarker
      import com.leica.cam.ai_engine.api.IAiEngine
      import com.leica.cam.ai_engine.api.IlluminantHint
      import com.leica.cam.ai_engine.api.NormalizedBox
      import com.leica.cam.ai_engine.api.ObjectClass
      import com.leica.cam.ai_engine.api.QualityScore
      import com.leica.cam.ai_engine.api.SceneAnalysis
      import com.leica.cam.ai_engine.api.SceneClassifier
      import com.leica.cam.ai_engine.api.SceneLabel
      import com.leica.cam.ai_engine.api.SemanticSegmenter
      import com.leica.cam.ai_engine.api.TrackedObject
      import com.leica.cam.common.result.LeicaResult
      import com.leica.cam.photon_matrix.FusedPhotonBuffer
      import kotlinx.coroutines.Dispatchers
      import kotlinx.coroutines.async
      import kotlinx.coroutines.coroutineScope
      import kotlinx.coroutines.withContext
      import javax.inject.Inject
      import javax.inject.Singleton

      /**
       * Real AI orchestrator — fans out to SceneClassifier, SemanticSegmenter,
       * FaceLandmarker concurrently, then builds a SceneAnalysis.
       *
       * Fallbacks: every runner call is wrapped; on failure, the field
       * falls back to a neutral default so the capture path never fails
       * *because* of AI. (AI augments physics; it does not gate it.)
       */
      @Singleton
      class AiEngineOrchestrator @Inject constructor(
          private val sceneClassifier: SceneClassifier,
          private val semanticSegmenter: SemanticSegmenter,
          private val faceLandmarker: FaceLandmarker,
          private val objectTracker: ObjectTrackingEngine,
          private val illuminantEstimator: IlluminantEstimator,
          private val qualityEngine: ShotQualityEngine,
      ) : IAiEngine {

          override suspend fun classifyAndScore(
              fused: FusedPhotonBuffer,
              captureMode: CaptureMode,
              sceneTileRgb224x224: FloatArray?,
              segTileRgb257x257: FloatArray?,
              faceArgb8888: IntArray?,
              faceWidth: Int,
              faceHeight: Int,
              isFrontCamera: Boolean,
          ): LeicaResult<SceneAnalysis> = coroutineScope {
              val sceneTile = sceneTileRgb224x224 ?: downsample(fused, 224, 224, interleaved = true)

              val sceneDeferred = async(Dispatchers.Default) {
                  sceneClassifier.classify(sceneTile).getOrDefault(defaultSceneClassification())
              }
              val qualityDeferred = async(Dispatchers.Default) {
                  qualityEngine.score(fused)
              }
              val illuminantDeferred = async(Dispatchers.Default) {
                  illuminantEstimator.estimate(fused, fallbackLabel = SceneLabel.GENERAL)
              }
              val faceDeferred = async(Dispatchers.Default) {
                  if (faceArgb8888 != null && faceWidth > 0 && faceHeight > 0) {
                      faceLandmarker.detect(faceArgb8888, faceWidth, faceHeight, isFrontCamera)
                          .getOrDefault(emptyFaceOutput(faceWidth, faceHeight))
                  } else emptyFaceOutput(0, 0)
              }
              val tracked = objectTracker.track(fused)

              val scene = sceneDeferred.await()
              val quality = qualityDeferred.await()
              val illuminant = illuminantDeferred.await()
              val faceOut = faceDeferred.await()

              val trackedWithFaces = tracked + faceOut.faces.mapIndexed { i, f ->
                  TrackedObject(
                      trackId = (1_000 + i).toLong(),
                      label = ObjectClass.FACE,
                      confidence = f.confidence,
                      boundingBox = boundingBoxOf(f.points478Normalized),
                  )
              }

              LeicaResult.Success(
                  SceneAnalysis(
                      sceneLabel = scene.primaryLabel,
                      qualityScore = quality,
                      illuminantHint = illuminant,
                      trackedObjects = trackedWithFaces,
                  ),
              )
          }

          private fun <T> LeicaResult<T>.getOrDefault(default: T): T = when (this) {
              is LeicaResult.Success -> value
              is LeicaResult.Failure -> default
          }

          private fun defaultSceneClassification() =
              com.leica.cam.ai_engine.api.SceneClassificationOutput(
                  primaryLabel = SceneLabel.GENERAL,
                  primaryConfidence = 0f,
                  top5 = emptyList(),
              )

          private fun emptyFaceOutput(w: Int, h: Int) =
              com.leica.cam.ai_engine.api.FaceLandmarkerOutput(emptyList(), w, h)

          private fun boundingBoxOf(points478: FloatArray): NormalizedBox {
              var minX = 1f; var minY = 1f; var maxX = 0f; var maxY = 0f
              var i = 0
              while (i < points478.size) {
                  val x = points478[i]; val y = points478[i + 1]
                  if (x < minX) minX = x; if (x > maxX) maxX = x
                  if (y < minY) minY = y; if (y > maxY) maxY = y
                  i += 3
              }
              return NormalizedBox(minX, minY, maxX, maxY)
          }

          private fun downsample(
              fused: FusedPhotonBuffer, targetW: Int, targetH: Int, interleaved: Boolean,
          ): FloatArray {
              // TODO: replace with the real FusedPhotonBuffer → linear RGB tile path.
              // For now, zero-fill (graceful degradation — classifier returns GENERAL).
              return FloatArray(targetW * targetH * 3)
          }
      }

      @Singleton
      class ShotQualityEngine @Inject constructor() {
          fun score(fused: FusedPhotonBuffer): QualityScore =
              // TODO: use sharpness/exposure metrics from fused metadata.
              QualityScore(overall = 0.8f, sharpness = 0.8f, exposure = 0.8f, stability = 0.8f)
      }

      @Singleton
      class IlluminantEstimator @Inject constructor() {
          fun estimate(fused: FusedPhotonBuffer, fallbackLabel: SceneLabel): IlluminantHint {
              // TODO: replace with a real gray-world/Robertson estimation.
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
      }

      @Singleton
      class ObjectTrackingEngine @Inject constructor() {
          fun track(fused: FusedPhotonBuffer): List<TrackedObject> = emptyList()
      }
      ```

- [ ] **Delete** `InternalSceneClassifier` and `InternalShotQualityEngine`
      from the old file content (they're now replaced by the new
      `ShotQualityEngine` + the wired `sceneClassifier`).

### Step P1.3 — Fix `SceneClassifierRunner` dead NIGHT branch

- [ ] In `SceneClassifierRunner.kt` `mapClassIdToLabel` (line 118-127),
      remove the overlap. Replace the whole table with:
      ```kotlin
      private fun mapClassIdToLabel(classIdx: Int): SceneLabel = when (classIdx) {
          // Food
          in 924..950 -> SceneLabel.FOOD
          // Landscape / outdoor scenes
          in 629..640 -> SceneLabel.LANDSCAPE
          // Architecture
          in 560..570 -> SceneLabel.ARCHITECTURE
          // Night scenes (non-overlapping)
          981, 982, 983 -> SceneLabel.NIGHT
          else -> SceneLabel.GENERAL
      }
      ```
      (Exact ImageNet class ids: verify with the shipped `1.tflite`
      model's label file. The above is a conservative example.)

### Step P1.4 — Fix `SemanticSegmenterRunner` label-read

- [ ] Open `SemanticSegmenterRunner.kt`.
- [ ] In `segment(...)`, **at session-open** (not per-frame) read the
      output tensor type. Add a field:
      ```kotlin
      @Volatile private var outputIsInt64: Boolean = false
      ```
- [ ] When opening the session, also inspect output type. Since the
      LiteRtSession abstracts the Interpreter, add an accessor:
      ```kotlin
      // in LiteRtSession:
      fun outputTensorTypeName(outputIndex: Int = 0): String? = runCatching {
          val getOutputTensor = interpreterHandle.javaClass.getMethod("getOutputTensor", Int::class.java)
          val tensor = getOutputTensor.invoke(interpreterHandle, outputIndex)
          val dataTypeMethod = tensor.javaClass.getMethod("dataType")
          dataTypeMethod.invoke(tensor)?.toString()
      }.getOrNull()
      ```
- [ ] In `SemanticSegmenterRunner.segment`, branch on the cached
      `outputIsInt64`:
      ```kotlin
      val outputSize = SEGMENTATION_DIM * SEGMENTATION_DIM
      val outputBytes = if (outputIsInt64) outputSize * 8 else outputSize * 4
      val output = ByteBuffer.allocateDirect(outputBytes).order(ByteOrder.nativeOrder())

      // ...after s.run:
      output.rewind()
      val rawLabels = if (outputIsInt64) {
          IntArray(outputSize) { output.long.toInt() }
      } else {
          // float per-class output: we need argmax over 21 classes per pixel,
          // but our output buffer above is sized for a single float per pixel.
          // If the model emits float-per-class, we need 257*257*21*4 bytes.
          // Handle that case in a second branch with correct buffer.
          IntArray(outputSize) { output.float.toInt() }
      }
      ```
      **Then apply the `confidenceThreshold` for real** (P1-6): if the
      max-class probability at a pixel falls below the threshold, emit
      `SemanticZoneCode.UNKNOWN` instead of the argmax class.

### Step P1.5 — Wire the semantic segmenter into `ImagingPipeline.autoSegment`

- [ ] The `autoSegment` function in `ImagingPipeline.kt` (post-P0)
      already calls `semanticSegmenter.segment(...)`. Verify that the
      adapter `SemanticSegmentationOutput → SemanticMask` (added in
      P0.7) is wired.

### Step P1.6 — Fix `applyMicroIsp` to actually call the refiner

- [ ] In `ImagingPipeline.kt:1302-1308`, replace the pass-through with
      a real tile-based invocation. Exact body:
      ```kotlin
      private fun applyMicroIsp(frame: PipelineFrame): PipelineFrame {
          val refiner = microIspRefiner ?: return frame
          val tileSize = 256
          val stride = 224 // 32 px overlap for seam-free blending
          val out = frame.copyChannels()

          var ty = 0
          while (ty < frame.height) {
              var tx = 0
              while (tx < frame.width) {
                  val tile = extractBayerTile(frame, tx, ty, tileSize)
                  val result = refiner.refine(tile)
                  if (result is LeicaResult.Success) {
                      blendTile(out, result.value, tx, ty, tileSize)
                  }
                  tx += stride
              }
              ty += stride
          }
          return out
      }
      ```
      **Trap:** `extractBayerTile` and `blendTile` need to be careful
      about CFA pattern (RGGB vs BGGR). Add them as private helpers
      that mosaic from (R,G,B) → (R, Gr, Gb, B) by subsampling, and
      demosaic back. If the code reviewer can't defend the CFA choice
      against the `SensorProfile.cfaPattern` field, **don't ship P1.6**
      — gate it behind a feature flag and leave the pass-through
      in production. This is gnarly enough that P1.6 may spill into
      its own sub-PR.

### Step P1.7 — Wire `HyperToneWhiteBalanceEngine.runAwbModel` to apply `wbBias` AFTER inference

- [ ] In `HyperToneWhiteBalanceEngine.kt`, after obtaining the
      `AwbNeuralPrior`, apply the per-sensor bias to the predicted
      gains (not to the input tile). Existing code passes `wbBias` into
      the runner; move it into `HyperToneWB2Engine.process(..., neuralCctPrior = prediction)`
      as a separate parameter.

### Step P1.8 — `LeicaCamApp`: fix comment, document warm-up policy

- [ ] In `LeicaCamApp.kt:43-44`, replace the comment with one that
      matches the actual behaviour:
      ```kotlin
      // D1.9: Warm up all on-device AI models in a background coroutine so
      // the first shutter press after app start doesn't pay the delegate-compile cost.
      // Models are warmed sequentially with a System.gc() hint between each to
      // stay under the 400 MB OOM threshold on low-end devices.
      ```

## P1 Verification gate

- [ ] New unit test: `AiEngineOrchestratorTest` that mocks
      `SceneClassifier` (returns PORTRAIT), `SemanticSegmenter`,
      `FaceLandmarker` and asserts `SceneAnalysis.sceneLabel == PORTRAIT`.
- [ ] Existing tests still pass: `./gradlew :ai-engine:impl:test`.
- [ ] Manually: install debug APK, open camera, rotate through a few
      scenes, verify `logcat | grep 'SceneClassifier\|SemanticSegmenter'`
      shows real inference happening (non-zero output bytes, real class
      predictions).
- [ ] `grep -rn 'return SceneLabel\.LANDSCAPE' engines/ai-engine/` returns
      nothing (the hardcoded stub is gone).

---

# Sub-plan P2 — Wire the new HDR path & delete the old one

**Goal:** `ImagingPipeline.process` calls `ProXdrOrchestrator` for
Stage 1 + Stage 2. The legacy `ProXdrHdrEngine.kt` (546 lines) is
deleted.

**Skill:** `Lumo Imaging Engineer` (for the HDR physics),
`Leica Cam Upgrade skill`.

## Steps

### Step P2.1 — Create canonical `ThermalState` in `:common`

- [ ] New file
      `platform/common/src/main/kotlin/com/leica/cam/common/ThermalState.kt`:
      ```kotlin
      package com.leica.cam.common

      /**
       * LUMO canonical thermal state.
       * Maps from Android PowerManager.THERMAL_STATUS_* ordinal (0..6).
       */
      enum class ThermalState(val androidOrdinal: Int) {
          NONE(0), LIGHT(1), MODERATE(2),
          SEVERE(3), CRITICAL(4), EMERGENCY(5), SHUTDOWN(6);

          companion object {
              fun fromOrdinal(o: Int): ThermalState =
                  entries.firstOrNull { it.androidOrdinal == o } ?: NONE

              /** Engines should stop multi-frame HDR at or above this level. */
              val MULTI_FRAME_CUTOFF = SEVERE
              /** Processing runtime should drop every other frame at or above this level. */
              val FRAME_DROP_CUTOFF = CRITICAL
          }
      }
      ```
- [ ] In `ProXdrOrchestrator.kt`, replace `THERMAL_SEVERE_ORDINAL` and
      the `isThermalSevere` check with `ThermalState.fromOrdinal(level) >= ThermalState.MULTI_FRAME_CUTOFF`.
- [ ] In `ImagingRuntimeOrchestrator.kt`, replace
      `HIGH_THERMAL_LEVEL = 6; CRITICAL_THERMAL_LEVEL = 8` with
      references to `ThermalState.MULTI_FRAME_CUTOFF` and
      `ThermalState.FRAME_DROP_CUTOFF`.

### Step P2.2 — Wire `ProXdrOrchestrator` into the DI graph

- [ ] In `ImagingPipelineModule.kt`, add:
      ```kotlin
      @Provides
      @Singleton
      fun provideProXdrOrchestrator(): com.leica.cam.imaging_pipeline.hdr.ProXdrOrchestrator =
          com.leica.cam.imaging_pipeline.hdr.ProXdrOrchestrator()
      ```

### Step P2.3 — Replace `alignmentEngine + hdrMergeEngine` calls with `ProXdrOrchestrator`

- [ ] In `ImagingPipeline.kt`, change the constructor:
      ```kotlin
      class ImagingPipeline(
          private val proXdrOrchestrator: com.leica.cam.imaging_pipeline.hdr.ProXdrOrchestrator,
          private val toneMappingEngine: DurandBilateralToneMappingEngine,
          private val sCurveEngine: CinematicSCurveEngine,
          private val shadowDenoiser: ShadowDenoiseEngine,
          private val luminositySharpener: LuminositySharpener,
          private val microIspRefiner: com.leica.cam.ai_engine.api.NeuralIspRefiner? = null,
          private val semanticSegmenter: com.leica.cam.ai_engine.api.SemanticSegmenter? = null,
      ) { ... }
      ```
      (Remove `alignmentEngine` and `hdrMergeEngine` from the
      constructor.)
- [ ] In `ImagingPipeline.process`, replace Stage 1 + Stage 2 (lines
      1252-1263) with:
      ```kotlin
      // Stages 1+2: Delegate to ProXdrOrchestrator (alignment + HDR merge with
      // pre-alignment ghost detection per D2.3, dense optical flow per D2.4,
      // per-channel Wiener weights per D2.5).
      val effectiveNoise = noiseModel ?: NoiseModel.fromIsoAndExposure(
          frames.minOf { it.isoEquivalent },
          frames.minOf { it.exposureTimeNs },
      )
      val hdrResult = proXdrOrchestrator.process(
          frames = frames,
          scene = null,  // TODO (P1): wire SceneDescriptor from AiEngineOrchestrator
          noiseModel = effectiveNoise,
          perChannelNoise = null,  // TODO: derive from SENSOR_NOISE_PROFILE
          userHdrMode = com.leica.cam.imaging_pipeline.api.UserHdrMode.SMART,
      ).let { if (it is LeicaResult.Failure) return it else (it as LeicaResult.Success).value }
      ```
- [ ] Update `ImagingPipelineOrchestrator` constructor too — remove
      the `alignmentEngine` and `hdrMergeEngine` params.

### Step P2.4 — Delete `ProXdrHdrEngine.kt`

- [ ] `rm engines/imaging-pipeline/impl/src/main/kotlin/com/leica/cam/imaging_pipeline/pipeline/ProXdrHdrEngine.kt`
- [ ] Verify nothing else references `ProXdrHdrOrchestrator`,
      `HdrBracketSelector` (the old one), or `MertensExposureFusionEngine`:
      `grep -rn 'ProXdrHdrOrchestrator\|MertensExposureFusionEngine' --include='*.kt'`
- [ ] Delete the old `FrameAlignmentEngine` and `HdrMergeEngine`
      classes at the top of `ImagingPipeline.kt` (lines ~190–1000) —
      they are now superseded by `DeformableFeatureAligner` and
      `RadianceMerger` inside `ProXdrOrchestrator`. Move any types
      that other files still import (`AlignmentResult`, `AlignmentTransform`,
      `HdrMergeResult`, `HdrMergeMode`, `NoiseModel`, `PipelineFrame`)
      into a new file `hdr/PipelineTypes.kt` or keep them in
      `ImagingPipeline.kt` but strip the classes.

### Step P2.5 — Fix `MertensFallback` metadata preservation

- [ ] In `MertensFallback.fuse`, replace:
      ```kotlin
      return LeicaResult.Success(PipelineFrame(width, height, outR, outG, outB))
      ```
      with:
      ```kotlin
      val base = frames.minByOrNull { abs(it.evOffset) } ?: frames[0]
      return LeicaResult.Success(
          PipelineFrame(
              width = width, height = height,
              red = outR, green = outG, blue = outB,
              evOffset = 0f, // Mertens output is display-referred; EV=0 is conventional
              isoEquivalent = base.isoEquivalent,
              exposureTimeNs = base.exposureTimeNs,
          ),
      )
      ```

### Step P2.6 — Fix `GhostMaskEngine.binaryMtb` median

- [ ] Replace line 68-72 with:
      ```kotlin
      val luma = frame.luminance()
      val sorted = luma.copyOf()
      sorted.sort()
      val n = sorted.size
      val median = if (n % 2 == 0) (sorted[n / 2 - 1] + sorted[n / 2]) / 2f else sorted[n / 2]
      return BooleanArray(luma.size) { luma[it] > median }
      ```

### Step P2.7 — Fix `RadianceMerger` per-channel motion score

- [ ] In `RadianceMerger.mergeWienerBurst`, replace the line
      `val sigma2Total = noise.green.varianceAt(luma) + lumaVar` with a
      luma-referred *combined* variance that does not privilege green:
      ```kotlin
      val sigma2Combined =
          LUM_R * LUM_R * noise.red.varianceAt(f.red[i]) +
          LUM_G * LUM_G * noise.green.varianceAt(f.green[i]) +
          LUM_B * LUM_B * noise.blue.varianceAt(f.blue[i]) +
          lumaVar
      val motionPenalty = if (abs(luma - meanLuma) > 3f * sqrt(sigma2Combined)) 0f else 1f
      ```

## P2 Verification gate

- [ ] `./gradlew :imaging-pipeline:impl:test` passes.
- [ ] `grep -rn 'ProXdrHdrOrchestrator' --include='*.kt'` returns empty.
- [ ] `find . -name ProXdrHdrEngine.kt` returns empty.
- [ ] Manual golden-image test: capture a 3-frame bracket on a
      high-DR scene, verify the output is dimensionally identical to
      the old path, and visually compare face skin tone (should be
      within ±1 ΔE).

---

# Sub-plan P3 — SDK hardening (kill reflection)

**Goal:** LiteRT and MediaPipe SDKs are on the compile classpath; all
`Class.forName(...)` lookups are replaced with direct type references.

**Skill:** `kotlin-specialist`, `security-patterns`, `error-handling`.

## Steps

- [ ] In `engines/ai-engine/impl/build.gradle.kts`, add:
      ```kotlin
      dependencies {
          implementation(libs.litert)
          implementation(libs.litert.gpu)
          implementation(libs.litert.support)
          implementation(libs.mediapipe.tasks.vision)
          // ... existing deps
      }
      ```
- [ ] Rewrite `LiteRtSession.buildWithDelegate` to use typed calls:
      ```kotlin
      import com.google.ai.edge.litert.Interpreter
      import com.google.ai.edge.litert.InterpreterOptions
      import com.google.ai.edge.litert.gpu.GpuDelegateFactory

      private fun buildWithDelegate(modelBuffer: ByteBuffer, kind: DelegateKind): LiteRtSession {
          val opts = InterpreterOptions()
          val delegateHandle = when (kind) {
              DelegateKind.GPU -> GpuDelegateFactory.create().also { opts.addDelegate(it) }
              DelegateKind.XNNPACK_CPU -> {
                  opts.setNumThreads(Runtime.getRuntime().availableProcessors().coerceAtMost(4))
                  opts.setUseXNNPACK(true)
                  null
              }
              DelegateKind.MTK_APU, DelegateKind.QNN_DSP, DelegateKind.ENN_NPU ->
                  VendorDelegateLoader.attach(opts, kind)
          }
          val interp = Interpreter(modelBuffer, opts)
          return LiteRtSession(
              interpreterHandle = interp,
              delegateHandle = delegateHandle,
              delegateKind = kind,
              runFn = { input, output -> interp.run(input, output) },
              closeFn = {
                  runCatching { interp.close() }
                  if (delegateHandle is AutoCloseable) runCatching { delegateHandle.close() }
              },
          )
      }
      ```
- [ ] Vendor delegates (MTK/QNN/ENN) stay reflective because their
      SDKs aren't publicly available — but `VendorDelegateLoader.attach`
      returns `Any?` instead of mutating by reflection on typed opts.
- [ ] Rewrite `FaceLandmarkerRunner.openOrFail` and
      `invokeLandmarkerDetect` to use `com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker`
      directly (import, not Class.forName).
- [ ] Fix P2-6 while here: replace `catch (e: Exception)` in
      FaceLandmarkerRunner with `catch (t: Throwable) { if (t is CancellationException || t is InterruptedException) throw t; ... }`.
- [ ] Fix P2-7: pool a reusable `Bitmap` per runner instance rather
      than allocating per detect() call.

## P3 Verification gate

- [ ] `grep -rn 'Class.forName' engines/ai-engine/` returns only the
      vendor delegate loader (expected; those SDKs aren't in Maven).
- [ ] Build still succeeds. APK size grows by ~15-25 MB (expected).
- [ ] Unit tests pass.

---

# Sub-plan P4 — Concurrency & lifecycle

**Goal:** No more deadlocks, no more use-after-free in the native
runtime, no more cold-start ANRs, no more ZSL races.

**Skill:** `kotlin-specialist`, `systematic-debugging`,
`test-driven-development` (write the race tests first).

## Steps

### Step P4.1 — Rewrite `ImagingRuntimeOrchestrator` worker loop

- [ ] Replace the `startWorker` block (lines 73-91) with a
      `select`-based loop that decouples ingest from processing:
      ```kotlin
      private fun startWorker() {
          workerJob = runtimeScope.launch {
              val inFlight = mutableMapOf<Long, FrameTask>()
              while (isActive) {
                  kotlinx.coroutines.selects.select<Unit> {
                      ingestQueue.onReceive { ingest ->
                          bridge.queueFrame(ingest.frame, ingest.metadata)
                          inFlight[ingest.frame.frameId] = ingest
                      }
                      processingQueue.onReceive { request ->
                          bridge.requestProcess(request)
                      }
                  }
                  // Drain completed results (non-blocking poll with short timeout)
                  val result = bridge.pollResult(timeoutMs = 5L)
                  if (result is LeicaResult.Success) {
                      val value = result.value
                      if (value != null) {
                          inFlight.remove(value.requestId)?.let { task ->
                              bridge.release(task.frame.hardwareBufferHandle)
                          }
                          bridge.release(value.outputHandle)
                      }
                  }
              }
          }
      }
      ```
      **Assumption:** `ProcessingResult` has a `requestId` matching the
      originating frame. If it doesn't, add one to the native bridge
      contract.

### Step P4.2 — `ZeroShutterLagRingBuffer` thread safety

- [ ] Replace `ArrayDeque<BufferedFrame<T>>` with a
      `java.util.concurrent.ConcurrentLinkedDeque<BufferedFrame<T>>`
      and track `size` with an `AtomicInteger`.
- [ ] `push` pattern:
      ```kotlin
      fun push(frame: T) {
          frames.addLast(BufferedFrame(payload = frame, timestamp = Instant.now(clock)))
          while (sizeCounter.incrementAndGet() > capacity) {
              if (frames.pollFirst() != null) sizeCounter.decrementAndGet() else break
          }
      }
      ```
- [ ] `snapshot()` returns `frames.toList()` — ConcurrentLinkedDeque
      iterators are weakly consistent, no ConcurrentModificationException.

### Step P4.3 — Move Camera2 IPC off Main

- [ ] In `Camera2CameraController.openCamera`, change:
      ```kotlin
      override suspend fun openCamera(cameraId: String) = withContext(Dispatchers.Main.immediate) { ... }
      ```
      to a two-stage approach:
      ```kotlin
      override suspend fun openCamera(cameraId: String) {
          val capabilities = withContext(Dispatchers.Default) { loadCapabilities(cameraId) }
          val selector = withContext(Dispatchers.Default) { selectorForCameraId(cameraId) }
          val provider = awaitCameraProvider()
          withContext(Dispatchers.Main.immediate) {
              val owner = requireNotNull(lifecycleOwner)
              val view = requireNotNull(previewView)
              val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
              val capture = ImageCapture.Builder()
                  .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                  .setFlashMode(ImageCapture.FLASH_MODE_OFF)
                  .build()
              provider.unbindAll()
              val camera = provider.bindToLifecycle(owner, selector, preview, capture)
              cameraProvider = provider
              imageCapture = capture
              boundCamera = camera
              runtimeCapabilities = capabilities
              applyCurrentRequestState()
          }
      }
      ```
- [ ] Shutdown `captureExecutor` in `closeCamera`:
      ```kotlin
      captureExecutor.shutdown()
      runCatching { captureExecutor.awaitTermination(2, TimeUnit.SECONDS) }
      ```
      Initialise a fresh one if `closeCamera` is followed by another
      `openCamera` (lazy-init pattern).

### Step P4.4 — `Camera2CameraController.capture()` returns LeicaResult

- [ ] Change the signature:
      ```kotlin
      override suspend fun capture(): LeicaResult<Unit> = withContext(Dispatchers.Main.immediate) {
          val capture = imageCapture ?: return@withContext LeicaResult.Failure.Hardware(
              errorCode = ImageCapture.ERROR_CAMERA_CLOSED,
              message = "imageCapture not initialised",
          )
          // ... continue with existing coroutine logic but return LeicaResult at the end
      }
      ```
- [ ] Propagate the change up into `CameraSessionManager.capture`
      (currently throws); make it also return `LeicaResult<Unit>`.
- [ ] Update `CameraScreen.kt` shutter click handler to show a toast
      on Failure.

### Step P4.5 — `CameraPreview` collect one command stream, not N coroutines

- [ ] Introduce a `SessionCommandBus` in `:feature:camera`:
      ```kotlin
      @Singleton
      class SessionCommandBus @Inject constructor() {
          private val _commands = MutableSharedFlow<SessionCommand>(extraBufferCapacity = 8)
          val commands: SharedFlow<SessionCommand> = _commands.asSharedFlow()
          suspend fun send(cmd: SessionCommand) = _commands.emit(cmd)
      }
      sealed class SessionCommand { object Open : SessionCommand(); object Close : SessionCommand() }
      ```
- [ ] `CameraPreview` emits commands instead of launching coroutines;
      a dedicated `LaunchedEffect(Unit)` collects the flow and serially
      applies them with cancellation-safe structured concurrency.

## P4 Verification gate

- [ ] New test: `ImagingRuntimeOrchestratorRaceTest` — spin 10
      parallel `submitFrame` calls against 1 consumer, assert no
      crashes and zero leaked native handles.
- [ ] New test: `ZeroShutterLagRingBufferConcurrencyTest` — 10 writer
      threads + 10 reader threads for 100 ms, no exceptions.
- [ ] Manual: ANR detector (StrictMode in debug) reports zero main-thread
      violations on cold start.
- [ ] Rotate device 5 times rapidly; no executor leak (check with
      Profiler — thread count returns to baseline).

---

# Sub-plan P5 — UI wiring (Flash, HDR, Zoom, Gallery, Switch, Menu)

**Goal:** Every top-bar and bottom-bar button produces a real effect.

**Skill:** `Frontend-design`, supporting
`Leica Cam Upgrade skill` for the imaging callback semantics and
`designing-architecture` for the state model.

## Steps

- [ ] Extend `CameraPreferences` with `flashMode`, `userHdrMode`,
      `currentZoom`, `cameraFacing`.
- [ ] Wire Flash IconButton (CameraScreen.kt:186) to:
      ```kotlin
      IconButton(onClick = {
          deps.preferences.update { it.copy(flash = it.flash.next()) }
      }) { ... }
      ```
      and relay the new value into `Camera2CameraController` via a new
      `setFlashMode(FlashMode)` method.
- [ ] HDR IconButton → cycle `UserHdrMode` (OFF→ON→SMART→PRO_XDR) and
      persist; `CameraUiOrchestrator` reads preferences and feeds it
      into `ProXdrOrchestrator.process(userHdrMode = ...)`.
- [ ] Zoom pill (CameraScreen.kt:151) → call
      `Camera2CameraController.setZoomRatio(zoomValue)`
      (CameraX `CameraControl.setZoomRatio`).
- [ ] Gallery button → navigate to the gallery nav route.
- [ ] Switch Camera button → toggle `cameraFacing` preference and
      call `sessionManager.closeSession()` + `openSession()`.
- [ ] Replace hardcoded `overlayState` inputs (CameraScreen.kt:110-119)
      with real values from:
      - LumaFrame: subscribe to a `LumaFrameFlow` produced by
        `Camera2CameraController.setImageAnalysisAnalyzer`.
      - AfBracket: read from `applyCurrentRequestState`'s focus
        feedback.
      - faces: from `AiEngineOrchestrator.classifyAndScore(...)` via
        `CaptureControlsViewModel`.
      - shotQualityScore: from `SceneAnalysis.qualityScore.overall`.

## P5 Verification gate

- [ ] Manual: tap each HUD button, observe logcat confirming the
      downstream call. Rotate device, settings persist.
- [ ] New Compose UI test: `CameraScreenInteractionTest` clicks Flash
      and asserts `CameraPreferencesRepository.state.value.flash` cycled.

---

# Sub-plan P6 — Advanced imaging (Bokeh, super-res, seamless zoom)

**Goal:** Bokeh Engine produces a real CoC-based synthetic-aperture
output (per README line 37). Seamless zoom transitions between sensors
without a pop.

**Skill:** `Lumo Imaging Engineer`, `optimizing-performance`.

This sub-plan is large enough to split into its own Plan.md when
execution starts. High-level outline only here.

- [ ] Implement real CoC math in `BokehEngineOrchestrator` using
      depth from `:depth-engine` (MiDaS) and face/person mask from
      `:face-engine`.
- [ ] Add `SeamlessZoomEngine.process` — detect sensor crossover
      (0.6× → 1× or 1× → 2×), cross-fade with a 120 ms animation
      window, reproject features via the new sensor's intrinsics.
- [ ] Super-resolution: tile pyramid RAISR-style upscaler for the
      2× and 3× digital zoom range.

## P6 Verification gate

- Deferred to its own Plan.md.

---

# Sub-plan P-DOCS — Refresh `project-structure.md`

Tracked as a parallel workstream; covered in a separate artifact
landing in the same PR as P0+P1.

---

# Known Edge Cases & Gotchas (across sub-plans)

- **Trap:** Moving `SceneLabel` into `:ai-engine:api` breaks every
  downstream consumer. Grep for all usages before renaming and update
  them in the same commit.
  **Do this instead:** Add the new enum values to the existing API
  enum first, then flip the runner's enum to the API one, then delete
  the impl-local duplicate.

- **Trap:** `Interpreter.run(input, output)` where input is a direct
  ByteBuffer — LiteRT requires the ByteBuffer's `position()` to be 0
  and `limit()` to be the full tensor bytes. Use `.rewind()` not
  `.clear()` after writing.
  **Do this instead:** Always rewind before the run call; assert
  `input.remaining() == expectedInputBytes` in debug builds.

- **Trap:** `Class.forName("com.google.ai.edge.litert.Interpreter")`
  succeeds when the class is on classpath but silently fails if the
  SDK minor version differs (constructor signature mismatch). P3
  addresses this.

- **Trap:** `StrictMode` violations on main thread may silently ship
  in release builds if `ENABLE_STRICT_MODE = false` there. Tighten in
  debug; accept in release.

- **Trap:** `AssetManager.list("models")` returns subdirectory names
  *without trailing slash*. A directory "AWB" and a file "AWB" are
  indistinguishable. Use the hint that subdirs have `list().isNotEmpty()`
  or enforce that model files have a recognised extension.

- **Trap:** `ProcessCameraProvider.getInstance(ctx).get()` is
  blocking; never call from Main.

- **Trap:** `ImageCapture` throws via `OnImageSavedCallback.onError`
  in background; the coroutine continuation's `resumeWithException`
  must only fire if still active. Check `continuation.isActive`.

- **Trap:** `SharedPreferences.apply()` is async — on process kill
  the user's last toggle may not persist. Use `commit()` for
  user-visible toggles we don't want to lose.

- **Trap:** `System.gc()` in `ModelRegistry.warmUpAll` is a known
  anti-pattern but harmless with the `@Suppress` annotation. Keep
  for now; measure allocation after P3 and remove if steady-state
  usage stays < 400 MB.

- **Trap:** `LiteRtSession.DelegateKind.GPU` is a different delegate
  object than the one the vendor SDK creates (MTK/QNN/ENN). Never mix
  two delegates into the same `InterpreterOptions`; LiteRT will
  silently ignore the second.

---

# Out of Scope

- Rewriting the ProXDR C++ native kernel (`native_imaging_core.cpp`
  — 1012 lines). The JNI bridge stays as-is; only the Kotlin worker
  loop changes.
- Building the full 40-tool post-capture editor in P5 — only HUD
  wiring is in-scope here.
- Re-enabling ktlint everywhere (TD-4). That's its own cleanup PR.
- Replacing CameraX with raw Camera2 — CameraX + interop is fine.
- Adding new AI models. Only the 5 in `/Model/` are wired.
- The GSD 2.0 directory and `.agents/skills/` directory — explicitly
  excluded from this plan per user instruction.

---

# Verification (whole-plan acceptance)

After P0 → P5 are all complete and merged, the following must hold:

- [ ] `./gradlew assemble test ktlintCheck detekt` all green.
- [ ] `./gradlew :app:installDebug` installs and launches on a Dimensity
      device. Cold start < 1.5 s to first camera frame.
- [ ] Flash, HDR, Zoom, Switch Camera, Gallery buttons all produce
      visible behaviour change.
- [ ] Logcat on a backlit portrait shows:
      `SceneClassifier: primary=PORTRAIT`,
      `SemanticSegmenter: person pixels=~25%`,
      `FaceLandmarker: faces=1`,
      `ProXdrOrchestrator: mode=DEBEVEC_LINEAR`.
- [ ] `grep -rn 'ProXdrHdrOrchestrator\|InternalSceneClassifier\|InternalShotQualityEngine' --include='*.kt'`
      → empty.
- [ ] `grep -rn 'com.leica.cam.ai_engine.impl' engines/hypertone-wb engines/imaging-pipeline`
      → empty.
- [ ] Battery consumption for a 30 s capture session ≤ 4%
      (baseline measurement required before P0 for A/B comparison).

---

_End of Plan._

