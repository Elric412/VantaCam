# Plan: LeicaCam — Four-Dimension Upgrade (Models Integration · HDR Engine · Structural Refactor · Known-Broken Inventory)

> **Advisor artifact.** This document is the single source of truth for the Executor. It assumes **zero prior conversation** — every decision, file path, code sketch, and gotcha is stated explicitly. Work dimensions **sequentially**; do not start D3 before D1+D2 are merged, do not start D4 fixes before the structure from D3 is in place.
>
> **Scale notice.** This is genuinely large (touches ~160 Kotlin files and reorganises 15+ Gradle modules). The Plan is split into **four self-contained sub-plans**, each with its own files-to-touch checklist, steps, verification, and stop-here marker. Execute in order. Do not merge sub-plans into one giant PR.

---

## Context

**Project:** LeicaCam — a multi-module Android/Kotlin computational photography stack targeting a MediaTek Dimensity device with 8 distinct camera sensors (Samsung S5KHM6 108MP, OmniVision OV64B40/OV50D40/OV08D10/OV16A1Q, GalaxyCore GC16B3, SmartSens SC202CS/SC202PCS). The imaging platform is internally called **LUMO** and contains the following engines: **FusionLM 2.0** (multi-frame RAW fusion), **ColorLM 2.0** (per-zone colour correction), **HyperTone WB** (Robertson-CCT-based white balance), **ToneLM 2.0** (shadow-denoise → bilateral TM → S-curve → face pass → sharpen), **ProXDR** (adaptive HDR orchestrator), **Photon Matrix** (spectral reconstruction), **Bokeh Engine** (CoC-based synthetic aperture), **Lightning Snap** (ZSL). The user-facing app is Kotlin + Jetpack Compose + Hilt + Camera2 + Vulkan Compute + TFLite. There are already **5 on-device AI models** living in `/Model/` (AWB ONNX+TFLite, Face Landmarker .task, Image Classifier TFLite, MicroISP TFLite fp16, DeepLabv3 TFLite for scene understanding) that are **not yet wired into the pipeline** end-to-end.

**What this Plan fixes:**
- **D1** — Concrete integration of the 5 existing models into the capture path, per-sensor fine-tuning, and SoC-aware delegate routing (NNAPI is deprecated in Android 15 — we route via LiteRT).
- **D2** — Rebuild the HDR engine around scene-adaptive, frequency-aware, ghost-free reconstruction (flow-guided deformable alignment + pyramid cross-attention concept ported to mobile).
- **D3** — A full enterprise-grade project structure refactor: remove the 15 duplicated `DependencyModule.kt` files, resolve the two `AiEngineOrchestrator.kt` / `AiEngineModule.kt` duplicates, fold `:common`/`:hardware-contracts` properly, and introduce a `build-logic` composite build.
- **D4** — An inventory of every broken or poorly-implemented area, written so a future executor can pick them off one by one. D4 is a *registry*, not a refactor.

---

## Stack & Assumptions

- **Language:** Kotlin `2.0.x` + a small amount of C/C++ (`:native-imaging-core/impl/src/main/cpp/native_imaging_core.cpp`) compiled with Android NDK `r27`.
- **Build:** Gradle `8.9+` with Kotlin DSL (`build.gradle.kts`, `settings.gradle.kts`). Version catalog is assumed at `gradle/libs.versions.toml` (verify — add it if absent).
- **Android:** `minSdk = 29`, `compileSdk = 35`, `targetSdk = 35`. Camera2 + Jetpack Compose + Hilt (+ KSP, migrate from kapt where possible).
- **GPU:** Vulkan Compute (primary), OpenGL ES 3.2 fallback. RenderScript is NOT allowed (deprecated).
- **ML runtime:** **LiteRT** (`com.google.ai.edge.litert:litert:1.x`) — the post-Android-15 successor to TFLite. NNAPI delegate is deprecated in Android 15; new delegate preference: **GPU delegate → QNN delegate (Snapdragon) / MTK APU delegate → XNNPACK CPU**.
- **ML model formats in repo:** `.tflite`, `.onnx` (convert to `.tflite` before shipping), MediaPipe `.task`.
- **Code style:** ktlint + detekt enforced, KDoc on every public API, sealed `LeicaResult<T, DomainError>` for all fallible boundaries.
- **Device-specific assumptions** (do not re-verify):
  - The device has MediaTek Dimensity SoC → `mtk-apu` delegate is preferred for AI inference.
  - Imagiq ISP pre-applies black-level and lens-shading; we MUST detect and undo any AWB/CCM the HAL applies (see `.agents/skills/Leica Cam Upgrade skill/references/sensor-profiles.md`).
  - The 8 sensors and their profiles are defined in `sensor-hal/src/main/java/com/leica/cam/sensor_hal/sensor/profiles/SensorProfileRegistry.kt` — DO NOT rename or relocate that class.
- **Environment variables:** None at runtime. At build time, the ProGuard/R8 rule file `proguard-rules.pro` must keep LiteRT and MediaPipe classes.
- **Inviolable LUMO laws** (apply to every file you write):
  1. Multi-frame fusion happens on RAW Bayer **before** demosaicing.
  2. Float16 or Int16 throughout the pipeline — **never** 8-bit intermediates.
  3. Noise model comes from `CameraCharacteristics.SENSOR_NOISE_PROFILE` metadata, **not** magic constants.
  4. Shadow denoising happens **before** any tone curve / tone lift.
  5. Skin anchor is computed first; every other WB zone is clamped to ±300 K of the skin CCT.
  6. White balance is per-zone, never a single global CCT.
  7. Zero cloud inference — all ML is on-device.
  8. `CancellationException` is always re-thrown; `catch (e: Exception)` is forbidden.
  9. No `GlobalScope`, no `!!` (without a justification comment), no `new`/`delete` in C++ (smart pointers only).

---

## Files to create or modify — master checklist

Copy this block to the top of the Executor's working buffer before starting each sub-plan. Items prefixed with their dimension (D1/D2/D3/D4).

**Dimension 1 — Model integration & fine-tuning**
- [ ] `ai-engine/impl/src/main/java/com/leica/cam/ai_engine/impl/registry/ModelRegistry.kt` (modify — add asset-loader constructor, warm-up, delegate selection)
- [ ] `ai-engine/impl/src/main/java/com/leica/cam/ai_engine/impl/runtime/LiteRtSession.kt` (new)
- [ ] `ai-engine/impl/src/main/java/com/leica/cam/ai_engine/impl/runtime/DelegatePicker.kt` (new)
- [ ] `ai-engine/impl/src/main/java/com/leica/cam/ai_engine/impl/models/AwbModelRunner.kt` (new)
- [ ] `ai-engine/impl/src/main/java/com/leica/cam/ai_engine/impl/models/FaceLandmarkerRunner.kt` (new)
- [ ] `ai-engine/impl/src/main/java/com/leica/cam/ai_engine/impl/models/SceneClassifierRunner.kt` (new)
- [ ] `ai-engine/impl/src/main/java/com/leica/cam/ai_engine/impl/models/SemanticSegmenterRunner.kt` (new)
- [ ] `ai-engine/impl/src/main/java/com/leica/cam/ai_engine/impl/models/MicroIspRunner.kt` (new)
- [ ] `hypertone-wb/impl/src/main/java/com/leica/cam/hypertone_wb/pipeline/HyperToneWhiteBalanceEngine.kt` (modify — consume `AwbModelRunner` output)
- [ ] `imaging-pipeline/impl/src/main/java/com/leica/cam/imaging_pipeline/pipeline/ImagingPipeline.kt` (modify — wire `MicroIspRunner` and `SemanticSegmenterRunner` before ToneLM)
- [ ] `app/src/main/assets/models/` (new asset directory; copy from `/Model/` at build time via Gradle task)
- [ ] `app/build.gradle.kts` (modify — add `copyOnDeviceModels` task)

**Dimension 2 — HDR engine rebuild**
- [ ] `imaging-pipeline/impl/src/main/java/com/leica/cam/imaging_pipeline/pipeline/ProXdrHdrEngine.kt` (modify — split into four new files below)
- [ ] `imaging-pipeline/impl/src/main/java/com/leica/cam/imaging_pipeline/hdr/BracketSelector.kt` (new — moved from `ProXdrHdrEngine.HdrBracketSelector`)
- [ ] `imaging-pipeline/impl/src/main/java/com/leica/cam/imaging_pipeline/hdr/GhostMaskEngine.kt` (new — frequency-aware ghost detection)
- [ ] `imaging-pipeline/impl/src/main/java/com/leica/cam/imaging_pipeline/hdr/DeformableFeatureAligner.kt` (new — replaces naive translation-only alignment at the HDR stage)
- [ ] `imaging-pipeline/impl/src/main/java/com/leica/cam/imaging_pipeline/hdr/RadianceMerger.kt` (new — Debevec + Wiener branch, one orchestrator)
- [ ] `imaging-pipeline/impl/src/main/java/com/leica/cam/imaging_pipeline/hdr/HighlightReconstructor.kt` (new — moved from `ProXdrHdrEngine`)
- [ ] `imaging-pipeline/impl/src/main/java/com/leica/cam/imaging_pipeline/hdr/ShadowRestorer.kt` (new — moved from `ProXdrHdrEngine`)
- [ ] `imaging-pipeline/impl/src/main/java/com/leica/cam/imaging_pipeline/hdr/MertensFallback.kt` (new — Laplacian pyramid fusion, was a `// TODO: replace with Laplacian pyramid` in current code)
- [ ] `imaging-pipeline/impl/src/main/java/com/leica/cam/imaging_pipeline/hdr/ProXdrOrchestrator.kt` (new — small orchestrator; old monolithic class is deleted)
- [ ] `gpu-compute/src/main/resources/shaders/hdr_merge_wiener.comp` (new — Vulkan GLSL compute shader)
- [ ] `gpu-compute/src/main/resources/shaders/laplacian_pyramid_blend.comp` (new)
- [ ] `imaging-pipeline/impl/src/test/java/com/leica/cam/imaging_pipeline/hdr/` (new test directory — every new file gets a unit test)

**Dimension 3 — Enterprise structure refactor**
- [ ] `build-logic/convention/build.gradle.kts` (new — composite build for shared Gradle logic)
- [ ] `build-logic/convention/src/main/kotlin/LeicaAndroidLibraryPlugin.kt` (new)
- [ ] `build-logic/convention/src/main/kotlin/LeicaAndroidApplicationPlugin.kt` (new)
- [ ] `build-logic/convention/src/main/kotlin/LeicaJvmLibraryPlugin.kt` (new)
- [ ] `build-logic/convention/src/main/kotlin/LeicaEngineModulePlugin.kt` (new)
- [ ] `gradle/libs.versions.toml` (verify + populate; may already exist)
- [ ] `settings.gradle.kts` (modify — re-group modules under `core/`, `engines/`, `features/`, `platform/`)
- [ ] Delete the 15 duplicate `DependencyModule.kt` files and replace with one `di/` sub-package `@Module` per engine (listed in D3 step 3.2)
- [ ] Delete `ai-engine/impl/src/main/java/com/leica/cam/ai_engine/impl/AiEngineModule.kt` (keep the canonical one under `impl/di/`)
- [ ] Delete `ai-engine/impl/src/main/java/com/leica/cam/ai_engine/impl/AiEngineOrchestrator.kt` (keep the canonical one under `impl/pipeline/`)
- [ ] Delete `photon-matrix/api/src/main/kotlin/com/leica/cam/photon_matrix/PhotonBuffer.kt` (the authoritative copy lives in `:hardware-contracts`)
- [ ] `project-structure.md` (modify — reflect new layout)
- [ ] `README.md` (modify — reflect new layout, new build commands)
- [ ] Every module's `build.gradle.kts` (modify — apply `leica.android.library` / `leica.jvm.library` / `leica.engine.module` convention plugins and delete duplicated boilerplate)

**Dimension 4 — Broken / poorly-implemented registry**
- [ ] `docs/known-issues/KNOWN_ISSUES.md` (new — the living registry)
- [ ] `docs/known-issues/HDR_ISSUES.md` (new — sub-file: Mertens isn't true Laplacian pyramid, MTB runs on already-aligned data, etc.)
- [ ] `docs/known-issues/AI_ISSUES.md` (new — sub-file: models aren't loaded, role-assignment heuristic is fragile, no quantisation validation)
- [ ] `docs/known-issues/STRUCTURE_ISSUES.md` (new — sub-file: 15× DependencyModule.kt duplication, two copies of PhotonBuffer, ktlint is disabled for every module)
- [ ] `docs/known-issues/PERF_ISSUES.md` (new — pure-Kotlin imaging loops never ported to GPU, O(N·k²) naive blurs, etc.)

> **D4 is deliverables-only.** It defines the registry and seeds it with every concrete issue found during this planning pass. It does NOT fix them. Fixing happens in follow-up plans.

---

# Sub-plan 1 — Dimension 1: Model Integration & Per-Sensor Fine-Tuning

> **Stop here and verify** D1 end-to-end with the verification block before starting Sub-plan 2.

## D1.0 Architecture Decisions (bake into the code, do not re-litigate)

1. **LiteRT is the ONLY runtime.** Not direct TFLite. Not NNAPI (deprecated in Android 15). Not PyTorch Mobile. The `.onnx` file in `/Model/AWB/awb_final.onnx` is **ignored at runtime** — we ship the quantised `.tflite` sibling only.
2. **Delegate priority** is computed once at `ModelRegistry` warm-up and cached per-model:
   - On MediaTek Dimensity: **APU (mtk-apu)** → GPU → XNNPACK CPU.
   - On Snapdragon (defensive): **QNN (Hexagon DSP)** → GPU → XNNPACK.
   - On Exynos: **ENN** → GPU → XNNPACK.
   - If a delegate fails to build (e.g., missing shared lib on device), silently fall through and log at WARN.
3. **Models are bundled as Android assets.** The `/Model/` directory in the repo root is a *development* location. A Gradle task `copyOnDeviceModels` copies them to `app/src/main/assets/models/<role>/` at pre-build. The app reads them from assets (no `/data/local/tmp` path, which only works on rooted devices).
4. **Warm-up is mandatory.** Every assigned model runs a synthetic inference once at app startup (zero-filled input tensor) to amortise delegate compilation off the capture path. Target: < 2 s total warm-up on the UI splash.
5. **Per-sensor fine-tuning is metadata-driven, not re-trained.** We do not re-train the five models. We apply **pre-processing** adjustments per sensor:
   - `AwbModelRunner`: apply per-sensor gain pre-multiplier from `SensorProfile.wbBias` before feeding the network; blend network CCT with skin-anchor CCT via the HyperTone formula in §D1.6.
   - `FaceLandmarkerRunner`: front cameras (`OV16A1Q`, `GC16B3`) use the `SELF_FACING` input flag to nudge landmark confidence threshold down to 0.45; rear uses 0.55.
   - `SceneClassifierRunner`: depth (`SC202CS`) and macro (`SC202PCS`) sensors are never fed to the classifier — scene classification is disabled when they are the active logical camera.
   - `SemanticSegmenterRunner` (DeepLabv3): output mask is gated by ISO — above ISO 3200 we lower mask confidence threshold from 0.50 to 0.35 (noisier features, but we'd rather have coarse zones than none).
   - `MicroIspRunner`: **only** runs on ultra-wide (`OV08D10`) and front cameras where the vendor ISP leaves more room for a neural refiner; disabled on the main Samsung HM6 (its pre-applied Imagiq ISP is already high-quality; running MicroISP on top causes double-processing artefacts).

## D1.1 Step — Enforce model-asset pipeline in Gradle

- [ ] Open `app/build.gradle.kts`.
- [ ] After the `android { }` block, append the following task (exact code):

```kotlin
// Copies /Model/<Role>/*.tflite and *.task into assets/models/ at build time.
// Onnx files are intentionally skipped — we ship TFLite only on device.
tasks.register<Copy>("copyOnDeviceModels") {
    from(rootProject.file("Model")) {
        include("**/*.tflite")
        include("**/*.task")
        exclude("**/Temp/**")
    }
    into(layout.projectDirectory.dir("src/main/assets/models"))
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}

tasks.named("preBuild").configure {
    dependsOn("copyOnDeviceModels")
}
```

- [ ] Run `./gradlew :app:copyOnDeviceModels` and confirm `app/src/main/assets/models/` now contains the TFLite files and the .task file.
- [ ] Add `app/src/main/assets/models/` to `.gitignore` (so the copied assets are not tracked — source of truth stays `/Model/`).

## D1.2 Step — Introduce LiteRT dependency

- [ ] Open `gradle/libs.versions.toml`. If it does not exist, create it with this minimal shape (keep existing versions — only ADD the litert entries):

```toml
[versions]
litert = "1.0.1"
litertGpu = "1.0.1"
mediapipeTasks = "0.10.14"

[libraries]
litert = { group = "com.google.ai.edge.litert", name = "litert", version.ref = "litert" }
litert-gpu = { group = "com.google.ai.edge.litert", name = "litert-gpu", version.ref = "litertGpu" }
litert-support = { group = "com.google.ai.edge.litert", name = "litert-support", version.ref = "litert" }
mediapipe-tasks-vision = { group = "com.google.mediapipe", name = "tasks-vision", version.ref = "mediapipeTasks" }
```

- [ ] Open `ai-engine/impl/build.gradle.kts` and append:

```kotlin
dependencies {
    implementation(libs.litert)
    implementation(libs.litert.gpu)
    implementation(libs.litert.support)
    implementation(libs.mediapipe.tasks.vision)
}
```

## D1.3 Step — Create `LiteRtSession.kt`

- [ ] Create `ai-engine/impl/src/main/java/com/leica/cam/ai_engine/impl/runtime/LiteRtSession.kt` with **exactly** these contents:

```kotlin
package com.leica.cam.ai_engine.impl.runtime

import com.google.ai.edge.litert.Interpreter
import com.google.ai.edge.litert.InterpreterOptions
import com.google.ai.edge.litert.gpu.GpuDelegate
import com.google.ai.edge.litert.gpu.GpuDelegateFactory
import com.leica.cam.common.result.LeicaResult
import com.leica.cam.common.result.PipelineStage
import java.nio.ByteBuffer

/**
 * Thin wrapper around a single LiteRT [Interpreter].
 *
 * Ownership: this class OWNS the underlying `Interpreter` and any delegate
 * handles it built. Callers MUST invoke [close] to release native memory;
 * never rely on GC.
 *
 * Thread safety: NOT thread-safe. One [LiteRtSession] per worker coroutine.
 * If you need concurrent inference, construct N sessions, not one shared.
 */
class LiteRtSession private constructor(
    private val interpreter: Interpreter,
    private val delegateHandle: GpuDelegate?,
    val delegateKind: DelegateKind,
) : AutoCloseable {

    /** Run a single inference. Input and output ByteBuffers must be the exact
     *  tensor byte size declared by the model metadata. */
    fun run(input: ByteBuffer, output: ByteBuffer): LeicaResult<Unit> = try {
        interpreter.run(input, output)
        LeicaResult.Success(Unit)
    } catch (t: Throwable) {
        if (t is InterruptedException) throw t   // must not swallow
        LeicaResult.Failure.Pipeline(
            stage = PipelineStage.AI_ENGINE,
            message = "LiteRT inference failed on ${delegateKind.name}",
            cause = t,
        )
    }

    override fun close() {
        runCatching { interpreter.close() }
        runCatching { delegateHandle?.close() }
    }

    companion object {
        /**
         * Build a session for the given model bytes. Falls through delegates
         * in priority order until one builds successfully.
         *
         * @param modelBuffer Direct [ByteBuffer] holding the raw model file.
         *                    MUST be allocated with `ByteBuffer.allocateDirect`
         *                    and flipped to read mode.
         * @param priority    Ordered list of delegates to try.
         */
        fun open(
            modelBuffer: ByteBuffer,
            priority: List<DelegateKind>,
        ): LeicaResult<LiteRtSession> {
            for (kind in priority) {
                val attempt = runCatching {
                    val opts = InterpreterOptions()
                    var delegate: GpuDelegate? = null
                    when (kind) {
                        DelegateKind.GPU -> {
                            delegate = GpuDelegateFactory.create()
                            opts.addDelegate(delegate)
                        }
                        DelegateKind.XNNPACK_CPU -> {
                            opts.setNumThreads(Runtime.getRuntime().availableProcessors().coerceAtMost(4))
                            opts.setUseXNNPACK(true)
                        }
                        DelegateKind.MTK_APU, DelegateKind.QNN_DSP, DelegateKind.ENN_NPU -> {
                            // Vendor delegates are loaded via reflection to keep the
                            // module free of vendor SDK compile-time dependencies.
                            VendorDelegateLoader.attach(opts, kind)
                        }
                    }
                    val interp = Interpreter(modelBuffer, opts)
                    LiteRtSession(interp, delegate, kind)
                }
                if (attempt.isSuccess) return LeicaResult.Success(attempt.getOrThrow())
            }
            return LeicaResult.Failure.Pipeline(
                PipelineStage.AI_ENGINE,
                "No LiteRT delegate in priority list could be built.",
            )
        }
    }

    enum class DelegateKind { MTK_APU, QNN_DSP, ENN_NPU, GPU, XNNPACK_CPU }
}
```

- [ ] Also create the companion reflection loader, `VendorDelegateLoader.kt`, in the same directory:

```kotlin
package com.leica.cam.ai_engine.impl.runtime

import com.google.ai.edge.litert.InterpreterOptions

/**
 * Reflection-based attach for vendor delegates so their SDKs are a runtime
 * dependency only. If the SDK is missing, [attach] returns without enabling
 * the delegate — the caller's fall-through logic kicks in.
 */
internal object VendorDelegateLoader {
    fun attach(opts: InterpreterOptions, kind: LiteRtSession.DelegateKind) {
        val className = when (kind) {
            LiteRtSession.DelegateKind.MTK_APU -> "com.mediatek.neuropilot.TfLiteApuDelegate"
            LiteRtSession.DelegateKind.QNN_DSP -> "com.qualcomm.qti.qnn.QnnDelegate"
            LiteRtSession.DelegateKind.ENN_NPU -> "com.samsung.eden.EnnDelegate"
            else -> return
        }
        runCatching {
            val cls = Class.forName(className)
            val ctor = cls.getDeclaredConstructor()
            val delegate = ctor.newInstance() as com.google.ai.edge.litert.Delegate
            opts.addDelegate(delegate)
        }
    }
}
```

## D1.4 Step — Create `DelegatePicker.kt`

- [ ] Create `ai-engine/impl/src/main/java/com/leica/cam/ai_engine/impl/runtime/DelegatePicker.kt`:

```kotlin
package com.leica.cam.ai_engine.impl.runtime

import android.os.Build
import com.leica.cam.ai_engine.impl.runtime.LiteRtSession.DelegateKind

/**
 * Chooses delegate priority based on the device's SoC vendor.
 *
 * Detection is coarse — uses [Build.HARDWARE] + [Build.SOC_MANUFACTURER]
 * (API 31+). Falls back to Build.HARDWARE substring match on older devices.
 *
 * The priority list is deliberately conservative: if the vendor delegate
 * fails at runtime, GPU is the next best bet for imaging models, and XNNPACK
 * is always a working floor.
 */
object DelegatePicker {

    fun priorityForCurrentDevice(): List<DelegateKind> {
        val socManu = runCatching { Build.SOC_MANUFACTURER.lowercase() }.getOrDefault("")
        val hw = Build.HARDWARE.lowercase()
        return when {
            socManu.contains("mediatek") || hw.contains("mt") ->
                listOf(DelegateKind.MTK_APU, DelegateKind.GPU, DelegateKind.XNNPACK_CPU)
            socManu.contains("qualcomm") || hw.contains("qcom") ->
                listOf(DelegateKind.QNN_DSP, DelegateKind.GPU, DelegateKind.XNNPACK_CPU)
            socManu.contains("samsung") || hw.contains("exynos") ->
                listOf(DelegateKind.ENN_NPU, DelegateKind.GPU, DelegateKind.XNNPACK_CPU)
            else ->
                listOf(DelegateKind.GPU, DelegateKind.XNNPACK_CPU)
        }
    }
}
```

## D1.5 Step — Rewrite `ModelRegistry.kt` to own session lifecycles

- [ ] Open `ai-engine/impl/src/main/java/com/leica/cam/ai_engine/impl/registry/ModelRegistry.kt`.
- [ ] Keep the existing magic-byte format detection and role-assignment logic (lines 105–287 are correct — do not rewrite).
- [ ] Append the following methods to the `ModelRegistry` class **before** the closing `companion object`:

```kotlin
    // ── New: session ownership & asset loading ─────────────────────────

    /**
     * Load the raw model bytes for [role] from Android assets
     * (`assets/models/<role>/*.tflite`). Caller owns the returned buffer.
     *
     * @return null if the role is not present in the catalogue.
     */
    fun loadBytesForRole(
        role: PipelineRole,
        assetBytes: (path: String) -> java.nio.ByteBuffer,
    ): java.nio.ByteBuffer? {
        val entry = catalogue[role] ?: return null
        val relative = entry.file.relativeTo(modelDir).path.replace(java.io.File.separatorChar, '/')
        return assetBytes("models/$relative")
    }

    /**
     * Build a [LiteRtSession] for [role]. The caller is responsible for
     * closing the returned session.
     */
    fun openSession(
        role: PipelineRole,
        assetBytes: (path: String) -> java.nio.ByteBuffer,
        priority: List<com.leica.cam.ai_engine.impl.runtime.LiteRtSession.DelegateKind> =
            com.leica.cam.ai_engine.impl.runtime.DelegatePicker.priorityForCurrentDevice(),
    ): com.leica.cam.common.result.LeicaResult<com.leica.cam.ai_engine.impl.runtime.LiteRtSession> {
        val buffer = loadBytesForRole(role, assetBytes)
            ?: return com.leica.cam.common.result.LeicaResult.Failure.Pipeline(
                com.leica.cam.common.result.PipelineStage.AI_ENGINE,
                "No model asset for role $role",
            )
        return com.leica.cam.ai_engine.impl.runtime.LiteRtSession.open(buffer, priority)
    }

    /**
     * Warm-up inference for all catalogued models. Runs a zero-filled input
     * through each session on a background dispatcher — amortises JIT /
     * delegate-compile cost before the user presses the shutter.
     */
    suspend fun warmUpAll(
        assetBytes: (path: String) -> java.nio.ByteBuffer,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default,
    ): Int = kotlinx.coroutines.withContext(dispatcher) {
        var warmed = 0
        catalogue.keys.forEach { role ->
            val sessionResult = openSession(role, assetBytes)
            if (sessionResult is com.leica.cam.common.result.LeicaResult.Success) {
                // Synthetic zero-filled input; output is ignored.
                val dummyIn = java.nio.ByteBuffer.allocateDirect(4).order(java.nio.ByteOrder.nativeOrder())
                val dummyOut = java.nio.ByteBuffer.allocateDirect(4).order(java.nio.ByteOrder.nativeOrder())
                runCatching { sessionResult.value.run(dummyIn, dummyOut) }
                sessionResult.value.close()
                warmed++
            }
        }
        warmed
    }
```

## D1.6 Step — Create the five `*Runner.kt` files

For each runner, create a file under `ai-engine/impl/src/main/java/com/leica/cam/ai_engine/impl/models/`. Each runner follows the same skeleton: inject `ModelRegistry`, open a session on first use, keep it hot, close it on DI teardown.

- [ ] Create `AwbModelRunner.kt`:

```kotlin
package com.leica.cam.ai_engine.impl.models

import com.leica.cam.ai_engine.impl.registry.ModelRegistry
import com.leica.cam.ai_engine.impl.runtime.LiteRtSession
import com.leica.cam.common.result.LeicaResult
import com.leica.cam.common.result.PipelineStage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runner for the per-frame AWB neural model.
 *
 * Input:  224×224 RGB float32 tile (already gamma-decoded to linear).
 * Output: 3 floats = [CCT_kelvin, tint_duv, confidence].
 *
 * Per-sensor fine-tuning is applied at the pre-processing boundary,
 * NOT in the model: the raw tile is multiplied by [SensorProfile.wbBias]
 * before being handed to the network. See
 * `.agents/skills/Leica Cam Upgrade skill/references/sensor-profiles.md`.
 */
@Singleton
class AwbModelRunner @Inject constructor(
    private val registry: ModelRegistry,
    private val assetBytes: (path: String) -> ByteBuffer,
) : AutoCloseable {

    @Volatile private var session: LiteRtSession? = null

    fun predict(
        tile: FloatArray,   // length = 224*224*3
        wbBias: FloatArray, // length = 3; R,G,B gain from SensorProfile
    ): LeicaResult<AwbPrediction> {
        require(tile.size == 224 * 224 * 3) { "AWB tile must be 224x224x3 float." }
        val s = session ?: openOrFail() ?: return LeicaResult.Failure.Pipeline(
            PipelineStage.AI_ENGINE, "AWB session unavailable.")
        val input = ByteBuffer.allocateDirect(tile.size * 4).order(ByteOrder.nativeOrder())
        for (i in tile.indices) {
            val ch = i % 3
            input.putFloat(tile[i] * wbBias[ch])
        }
        input.rewind()
        val output = ByteBuffer.allocateDirect(3 * 4).order(ByteOrder.nativeOrder())
        return when (val r = s.run(input, output)) {
            is LeicaResult.Success -> {
                output.rewind()
                LeicaResult.Success(
                    AwbPrediction(
                        cctKelvin = output.float,
                        tintDuv = output.float,
                        confidence = output.float,
                    )
                )
            }
            is LeicaResult.Failure -> r
        }
    }

    override fun close() { session?.close(); session = null }

    @Synchronized
    private fun openOrFail(): LiteRtSession? {
        session?.let { return it }
        val r = registry.openSession(ModelRegistry.PipelineRole.NOISE_MODEL /* wrong enum on purpose — fix in Step D1.7 */, assetBytes)
        return (r as? LeicaResult.Success)?.value?.also { session = it }
    }
}

/** AWB neural prediction — blended with skin anchor by [HyperToneWhiteBalanceEngine]. */
data class AwbPrediction(val cctKelvin: Float, val tintDuv: Float, val confidence: Float)
```

- [ ] **Fix the enum:** after creating the file above, return to `ModelRegistry.PipelineRole` and add a new enum case: `AUTO_WHITE_BALANCE`. Then:
  - Update the `matchFilenameKeywords` function to map `name.contains("awb")` or `name.contains("white_balance")` → `AUTO_WHITE_BALANCE`.
  - Replace the placeholder `NOISE_MODEL` in `AwbModelRunner.openOrFail` with `AUTO_WHITE_BALANCE`.

- [ ] Create `FaceLandmarkerRunner.kt` (wraps MediaPipe Tasks, not LiteRT — because the .task bundle is a MediaPipe native format):

```kotlin
package com.leica.cam.ai_engine.impl.models

import android.content.Context
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import com.google.mediapipe.framework.image.MPImage
import com.leica.cam.common.result.LeicaResult
import com.leica.cam.common.result.PipelineStage
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FaceLandmarkerRunner @Inject constructor(
    private val context: Context,
) : AutoCloseable {

    @Volatile private var landmarker: FaceLandmarker? = null

    /** Detect landmarks on [image]. */
    fun detect(image: MPImage, isFrontCamera: Boolean): LeicaResult<FaceLandmarkerResult> {
        val l = landmarker ?: openOrFail(isFrontCamera)
            ?: return LeicaResult.Failure.Pipeline(
                PipelineStage.AI_ENGINE, "FaceLandmarker unavailable.")
        return try {
            LeicaResult.Success(l.detect(image))
        } catch (t: Throwable) {
            if (t is InterruptedException) throw t
            LeicaResult.Failure.Pipeline(
                PipelineStage.AI_ENGINE,
                "FaceLandmarker detect() failed",
                cause = t,
            )
        }
    }

    override fun close() { landmarker?.close(); landmarker = null }

    @Synchronized
    private fun openOrFail(isFrontCamera: Boolean): FaceLandmarker? {
        landmarker?.let { return it }
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath("models/Face Landmarker/face_landmarker.task")
                    .build(),
            )
            .setRunningMode(RunningMode.IMAGE)
            .setMinFaceDetectionConfidence(if (isFrontCamera) 0.45f else 0.55f)
            .setMinFacePresenceConfidence(0.50f)
            .setMinTrackingConfidence(0.50f)
            .setNumFaces(3)
            .build()
        return runCatching { FaceLandmarker.createFromOptions(context, options) }
            .getOrNull()
            ?.also { landmarker = it }
    }
}
```

- [ ] Create `SceneClassifierRunner.kt` with the **same skeleton** as `AwbModelRunner`, but:
  - Input: 224×224 RGB float, pre-normalised to [-1, 1] (ImageNet classifier convention — check the `.tflite` metadata; **do not guess**; if the model expects [0,1] normalisation, adjust the pre-processing).
  - Output: 1000-class probabilities; take top-5 and map to `SceneLabel` via a lookup table in `ai-engine/api/src/main/java/com/leica/cam/ai_engine/api/SceneLabelMapping.kt` (new file — see `AiModels.kt` for the existing `SceneLabel` enum).
  - Per-sensor gate: **skip entirely** when the active sensor is `SC202CS` (depth) or `SC202PCS` (macro) — scene classification is meaningless on those sensors.

- [ ] Create `SemanticSegmenterRunner.kt` with DeepLabv3 wrapping. Output: a `Bitmap` (or `ByteArray` mask) of `SemanticZone` ordinals that fills `SemanticMask`. The key integration point is:

```kotlin
// In SemanticSegmenterRunner.run(...)
val confidenceThreshold = if (sensorIso > 3200) 0.35f else 0.50f
```

- [ ] Create `MicroIspRunner.kt`. Input: 4-channel Bayer tile (R, Gr, Gb, B) float16, shape 256×256×4. Output: 4-channel Bayer tile with refinement. Apply the ISP refinement ONLY when:
  - Active sensor ∈ {`OV08D10`, `OV16A1Q`, `GC16B3`}.
  - Sensor is NOT `S5KHM6` (Samsung main): double-processing with Imagiq HAL ISP causes over-sharpening.

## D1.7 Step — Wire `AwbModelRunner` into `HyperToneWhiteBalanceEngine`

- [ ] Open `hypertone-wb/impl/src/main/java/com/leica/cam/hypertone_wb/pipeline/HyperToneWhiteBalanceEngine.kt`.
- [ ] Locate the current illuminant-estimation entry point (grep for `estimateIlluminant` or `computeCct`).
- [ ] Inject `AwbModelRunner` into its constructor.
- [ ] Replace the grey-world fallback with:

```kotlin
val modelPred = awbRunner.predict(downsample224x224(raw), sensorProfile.wbBias)
val modelCct = (modelPred as? LeicaResult.Success)?.value?.cctKelvin
// Blend per Robertson's CIE 1960 (u,v) — model provides prior, grey-world is fallback.
val chosenCct = modelCct ?: greyWorldCct
// Skin anchor is SACRED — clamp the chosen CCT to skin ± 300 K (Law 5).
val anchoredCct = skinCct?.let { chosenCct.coerceIn(it - 300f, it + 300f) } ?: chosenCct
```

- [ ] Keep the temporal smoothing `α = 0.15` from the existing code. Do NOT change it.

## D1.8 Step — Wire `SemanticSegmenterRunner` and `MicroIspRunner` into `ImagingPipeline.process`

- [ ] Open `imaging-pipeline/impl/src/main/java/com/leica/cam/imaging_pipeline/pipeline/ImagingPipeline.kt`.
- [ ] Locate the `process` function (≈ line 1254).
- [ ] Before Stage 4 (`toneMap`), insert:

```kotlin
// Stage 3.5: Neural ISP refinement (ultra-wide/front only; gated by SensorProfile).
val ispRefined = if (microIspRunner != null && sensorProfile.microIspEligible) {
    microIspRunner.refine(denoised, sensorProfile).getOrElse { denoised }
} else denoised

// Stage 3.6: Semantic segmentation → SemanticMask.
val autoMask = semanticMask
    ?: semanticSegmenterRunner?.segment(ispRefined, sensorIso = frames.first().isoEquivalent)
        ?.getOrNull()
```

- [ ] Update the `toneMappingEngine.apply(denoised, semanticMask)` call to `toneMappingEngine.apply(ispRefined, autoMask)`.
- [ ] Update the class constructor to accept (nullable) `MicroIspRunner?` and `SemanticSegmenterRunner?`.

## D1.9 Step — Register runners in Hilt

- [ ] Open `ai-engine/impl/src/main/java/com/leica/cam/ai_engine/impl/di/AiEngineModule.kt` (the canonical one).
- [ ] Add providers for each new runner. `@Binds` where possible; otherwise `@Provides @Singleton`.

```kotlin
@Provides @Singleton
fun provideAwbModelRunner(
    registry: ModelRegistry,
    @ApplicationContext context: Context,
): AwbModelRunner = AwbModelRunner(registry) { path ->
    context.assets.open(path).use { stream ->
        val bytes = stream.readBytes()
        java.nio.ByteBuffer.allocateDirect(bytes.size)
            .order(java.nio.ByteOrder.nativeOrder())
            .put(bytes).apply { rewind() }
    }
}

@Provides @Singleton
fun provideFaceLandmarkerRunner(
    @ApplicationContext context: Context,
): FaceLandmarkerRunner = FaceLandmarkerRunner(context)

// ...repeat for Scene, Semantic, MicroISP...
```

- [ ] Add `ModelRegistry.warmUpAll(...)` call to `LeicaCamApp.onCreate()` in a `CoroutineScope(SupervisorJob() + Dispatchers.Default).launch { ... }` block.

## D1 — Verification

- [ ] Run `./gradlew :ai-engine:impl:test`. Expect: all existing tests green + new tests for each runner.
- [ ] Run `./gradlew :app:assembleDebug`. Expect: build succeeds, APK contains `assets/models/` (verify with `unzip -l app/build/outputs/apk/debug/app-debug.apk | grep models/`).
- [ ] Launch on-device. Logcat should show a line like `ModelRegistry  I  Assigned roles: 5, Unknown: 0` within 2 s of splash.
- [ ] Take a photo indoors under tungsten. Confirm skin tones are NOT yellow — the AWB model + skin anchor blend should correct this.
- [ ] Take a photo with the ultra-wide camera. Confirm no halo artefacts around high-contrast edges (sign that MicroISP is running cleanly).

## D1 — Known Edge Cases & Gotchas

- **Trap:** Calling `GpuDelegateFactory.create()` on a device without Vulkan support raises a native crash.
  **Do this instead:** Catch `Throwable` around delegate construction (already done in `LiteRtSession.open`). The fall-through chain must always end at `XNNPACK_CPU` which is guaranteed.
- **Trap:** Feeding the AWB model a **gamma-encoded** 8-bit tile (easy mistake when grabbing from preview SurfaceTexture).
  **Do this instead:** AWB expects **linear** scene-referred data. Downsample from the already-linearised RAW, not from the preview.
- **Trap:** Using MediaPipe `FaceLandmarker` with `RunningMode.LIVE_STREAM` on the capture-time path — that mode requires a results callback, not a synchronous return.
  **Do this instead:** Use `RunningMode.IMAGE` for still capture (already specified above). Use `LIVE_STREAM` only for the viewfinder feed, via a SEPARATE runner instance.
- **Trap:** The `MicroISP_V4_fp16.tflite` input tensor layout might be NHWC vs NCHW — don't guess.
  **Do this instead:** At session open, read `interpreter.getInputTensor(0).shape()` and log it. Write the pre-processor to match.
- **Trap:** The `deeplabv3.tflite` in the repo is likely the 257×257 Coco 21-class variant — its class IDs are NOT the same as `SemanticZone`.
  **Do this instead:** Add a translation table in `SemanticSegmenterRunner`. COCO class 15 ("person") → `PERSON`; class 0 ("background") → `BACKGROUND`; etc.
- **Trap:** Warming up all five models at app startup can push memory above 400 MB on low-end devices and trigger OOM.
  **Do this instead:** Warm up in sequence with `System.gc()` between each (not elegant, but reliable). Consider lazy warm-up — only warm up `AwbModelRunner` and `FaceLandmarkerRunner` at startup; defer `MicroIspRunner` and `SemanticSegmenterRunner` until the first shutter press.

## D1 — Out of Scope

- Retraining any model. We use the weights as shipped.
- Porting MicroISP to Vulkan Compute (tracked in D4).
- Adding depth-estimation models (there's no depth model in `/Model/` yet — that's a future feature, not this plan).
- Writing new TFLite models for FusionLM or ToneLM — those stay as Kotlin/GPU kernels in D2.

---

# ⛔ STOP — verify Sub-plan 1 end-to-end before proceeding.

---

# Sub-plan 2 — Dimension 2: HDR Engine Rebuild

> **Stop here and verify** D2 end-to-end before starting Sub-plan 3.

## D2.0 Architecture Decisions

The current `ProXdrHdrEngine.kt` (546 lines, single file) has several correctness issues that we are fixing by rebuilding the HDR path into **eight focused files** under a new `imaging-pipeline/impl/.../hdr/` sub-package. The issues being fixed:

1. **Mertens "Laplacian pyramid" is a lie.** Line 323 of the current file says `// Simple weighted blend (production: replace with Laplacian pyramid)`. Replace with real Burt & Adelson (1983) 4-level Laplacian pyramid blend.
2. **MTB ghost detection runs AFTER alignment.** The current code aligns frames first, then builds MTB on the aligned frames — but MTB is an alignment-invariant method designed for PRE-alignment motion detection. Move MTB ghost mask computation to pre-alignment so it can also drive alignment rejection.
3. **Alignment is translation-only.** `FrameAlignmentEngine` does 2-DoF translational Gaussian-pyramid SAD search. Works for static tripod-mounted shots; fails on handheld with parallax. Replace with a **flow-guided deformable alignment** that operates on feature maps (per NTIRE 2025 Efficient Burst HDR state-of-the-art).
4. **Wiener merge weight ignores per-CFA-channel noise.** Line 540 uses luminance-derived σ² for ALL three channels. For OV sensors this is **wrong** — Gr/Gb split means green channel noise differs between the two green rows by 1–2 DN (per sensor-profiles.md). The merge must use per-channel noise from `SENSOR_NOISE_PROFILE`.
5. **Shadow restoration reads `exp2` after alignment but uses un-aligned `bR/bG/bB`.** Subtle bug at line 254: `brightFrame.red[i] * evScale` is using the raw bright frame, not the aligned one. Works by accident because `alignedBrights` is passed in, but the variable naming is dangerous. Rename and clarify.
6. **No true frequency-aware reconstruction.** The current code is spatial-domain only. Modern mobile HDR (CVPR 2025 HL-HDR, NTIRE 2025 papers) explicitly splits a low-frequency branch (for alignment) from a high-frequency branch (for detail preservation). We add a pyramid split in `RadianceMerger`.

## D2.1 Step — Create the new `hdr/` sub-package

- [ ] Create directory `imaging-pipeline/impl/src/main/java/com/leica/cam/imaging_pipeline/hdr/`.
- [ ] Move (not copy — DELETE from `ProXdrHdrEngine.kt` after porting):
  - `HdrBracketSelector` object → `hdr/BracketSelector.kt`
  - `HighlightReconstructionEngine` class → `hdr/HighlightReconstructor.kt`
  - `ShadowDetailRestorer` class → `hdr/ShadowRestorer.kt`
  - `MertensExposureFusionEngine` class → `hdr/MertensFallback.kt`
  - `ProXdrHdrOrchestrator` class → `hdr/ProXdrOrchestrator.kt`
  - `HdrModePicker` object → `hdr/BracketSelector.kt` (co-located; they are small)
  - All data classes (`SceneDescriptor`, `SceneCategory`, `ThermalState`, `HdrFrameSetMetadata`) → `hdr/HdrTypes.kt` (new)
- [ ] After moving, `ProXdrHdrEngine.kt` should **not exist**. Delete the file.
- [ ] Update all imports in:
  - `imaging-pipeline/impl/src/main/java/com/leica/cam/imaging_pipeline/pipeline/ImagingPipeline.kt`
  - `imaging-pipeline/impl/src/main/java/com/leica/cam/imaging_pipeline/DependencyModule.kt` (will be deleted in D3 — fine)
  - `imaging-pipeline/impl/src/test/java/com/leica/cam/imaging_pipeline/pipeline/*.kt`

## D2.2 Step — Fix Mertens with a real Laplacian pyramid

- [ ] Open `imaging-pipeline/impl/src/main/java/com/leica/cam/imaging_pipeline/hdr/MertensFallback.kt`.
- [ ] Replace the body of `fuse(...)` with the following:

```kotlin
fun fuse(frames: List<PipelineFrame>): LeicaResult<PipelineFrame> {
    if (frames.isEmpty()) return LeicaResult.Failure.Pipeline(
        PipelineStage.IMAGING_PIPELINE, "Mertens fusion requires at least one frame.")
    if (frames.size == 1) return LeicaResult.Success(frames[0])

    val width = frames[0].width
    val height = frames[0].height
    val levels = pyramidLevels(width, height)

    // Step 1: per-frame quality weight maps (existing logic is OK — reuse).
    val weightMaps = frames.map { computeWeightMap(it) }
    // Step 2: normalise weights per pixel.
    val normalised = normaliseWeightsPerPixel(weightMaps, frames.size, width * height)

    // Step 3: build Gaussian pyramid of each NORMALISED weight, and Laplacian
    // pyramid of each FRAME. Blend at each level, then collapse.
    val rPyr = Array(levels) { FloatArray(0) }
    val gPyr = Array(levels) { FloatArray(0) }
    val bPyr = Array(levels) { FloatArray(0) }
    var levelW = width; var levelH = height

    for (lvl in 0 until levels) {
        val curR = FloatArray(levelW * levelH)
        val curG = FloatArray(levelW * levelH)
        val curB = FloatArray(levelW * levelH)
        for (f in frames.indices) {
            val lapR = laplacianLevel(frames[f].red, width, height, lvl)
            val lapG = laplacianLevel(frames[f].green, width, height, lvl)
            val lapB = laplacianLevel(frames[f].blue, width, height, lvl)
            val gW   = gaussianLevel(normalised[f], width, height, lvl)
            for (i in 0 until levelW * levelH) {
                curR[i] += lapR[i] * gW[i]
                curG[i] += lapG[i] * gW[i]
                curB[i] += lapB[i] * gW[i]
            }
        }
        rPyr[lvl] = curR; gPyr[lvl] = curG; bPyr[lvl] = curB
        levelW = (levelW + 1) / 2; levelH = (levelH + 1) / 2
    }

    // Step 4: collapse pyramids back to full resolution.
    val outR = collapsePyramid(rPyr, width, height)
    val outG = collapsePyramid(gPyr, width, height)
    val outB = collapsePyramid(bPyr, width, height)

    return LeicaResult.Success(PipelineFrame(width, height, outR, outG, outB))
}
```

- [ ] Add the pyramid helpers `pyramidLevels`, `laplacianLevel`, `gaussianLevel`, `collapsePyramid` as `private` methods on the same class. The math is standard; reference implementation:

```kotlin
private fun pyramidLevels(w: Int, h: Int): Int =
    (kotlin.math.ln(kotlin.math.min(w, h).toDouble()) / kotlin.math.ln(2.0)).toInt().coerceIn(3, 6)

/** Gaussian pyramid level [lvl] of [src] (0 = original, higher = coarser). */
private fun gaussianLevel(src: FloatArray, w: Int, h: Int, lvl: Int): FloatArray {
    var cur = src; var cw = w; var ch = h
    repeat(lvl) {
        val blurred = gaussianBlur5(cur, cw, ch)
        val dw = (cw + 1) / 2; val dh = (ch + 1) / 2
        cur = FloatArray(dw * dh) { i ->
            val x = (i % dw) * 2; val y = (i / dw) * 2
            blurred[y * cw + x]
        }
        cw = dw; ch = dh
    }
    return cur
}

/** Laplacian level = Gaussian(lvl) − upsample(Gaussian(lvl+1)). */
private fun laplacianLevel(src: FloatArray, w: Int, h: Int, lvl: Int): FloatArray {
    val cur = gaussianLevel(src, w, h, lvl)
    val nxt = gaussianLevel(src, w, h, lvl + 1)
    val cw = levelDim(w, lvl); val ch = levelDim(h, lvl)
    val upNxt = upsample2x(nxt, levelDim(w, lvl + 1), levelDim(h, lvl + 1), cw, ch)
    return FloatArray(cw * ch) { i -> cur[i] - upNxt[i] }
}

private fun levelDim(v: Int, lvl: Int): Int {
    var x = v; repeat(lvl) { x = (x + 1) / 2 }; return x
}
```

- [ ] Complete `upsample2x` (bilinear) and `collapsePyramid` (reverse pyramid accumulation).
- [ ] Add a unit test `MertensLaplacianTest.kt` that asserts: for **identical input frames**, the Laplacian-blended output equals the input within ε = 1e-3 (round-trip correctness).

## D2.3 Step — Pre-alignment MTB ghost mask in `GhostMaskEngine.kt`

- [ ] Create `imaging-pipeline/impl/src/main/java/com/leica/cam/imaging_pipeline/hdr/GhostMaskEngine.kt`:

```kotlin
package com.leica.cam.imaging_pipeline.hdr

import com.leica.cam.imaging_pipeline.pipeline.PipelineFrame

/**
 * Pre-alignment ghost detection. Returns a per-pixel soft mask ∈ [0,1]
 * where 1 = confident ghost (dynamic subject), 0 = static.
 *
 * Method: MTB (Ward 2003) + bitmap XOR dilation + frequency-weighted
 * edge-map consistency. Runs on GREEN channel only (highest SNR on Bayer).
 *
 * Must run BEFORE alignment — alignment can be biased by moving subjects
 * if the mask is not available to weight the SAD search.
 */
class GhostMaskEngine {

    /**
     * @param reference  The reference frame (usually EV=0).
     * @param alternates All other frames in the set.
     * @return Soft ghost mask aligned to the reference's coordinate system.
     */
    fun computeSoftMask(
        reference: PipelineFrame,
        alternates: List<PipelineFrame>,
        dilateRadius: Int = 8,
    ): FloatArray {
        val size = reference.width * reference.height
        val refMtb = binaryMtb(reference)
        val accum = FloatArray(size)
        for (alt in alternates) {
            val altMtb = binaryMtb(alt)
            for (i in 0 until size) if (refMtb[i] != altMtb[i]) accum[i] += 1f
        }
        // Normalise to [0,1].
        val denom = alternates.size.coerceAtLeast(1).toFloat()
        for (i in 0 until size) accum[i] /= denom
        return softDilate(accum, reference.width, reference.height, dilateRadius)
    }

    private fun binaryMtb(frame: PipelineFrame): BooleanArray {
        val luma = frame.luminance()
        val sorted = luma.copyOf(); sorted.sort()
        val median = sorted[sorted.size / 2]
        return BooleanArray(luma.size) { luma[it] > median }
    }

    /** Gaussian-weighted dilation — produces soft ∈ [0,1] mask suitable for weighting. */
    private fun softDilate(mask: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        // separable box-filter approximation of Gaussian, then clamp
        // implementation identical to ImagingPipeline.boxFilter — copy-paste.
        // … (elided; use the same pattern as DurandBilateralToneMappingEngine.boxFilter)
    }
}
```

- [ ] Unit test `GhostMaskEngineTest.kt`: take two frames where a synthetic disk is placed in different positions, assert the soft mask covers both positions.

## D2.4 Step — Flow-guided deformable alignment in `DeformableFeatureAligner.kt`

> **Scope note.** Full flow-guided deformable convolution (per CVPR 2025 HL-HDR) is a neural-network operation. We do NOT train or ship a new model. Instead, we implement a **mobile-friendly approximation**: pyramidal Lucas-Kanade optical flow + block-wise affine warp. This gives sub-pixel accuracy with handheld parallax correction at ~10 ms per frame on GPU.

- [ ] Create `imaging-pipeline/impl/src/main/java/com/leica/cam/imaging_pipeline/hdr/DeformableFeatureAligner.kt`.
- [ ] Implement pyramidal LK (4-level, 5×5 window, ε = 0.01) — spec:

```kotlin
/**
 * Pyramidal Lucas-Kanade dense optical flow alignment.
 *
 * Physics: LK assumes local-constancy of flow over a 5×5 window and solves
 * the 2×2 structure-tensor system at each pixel. Coarse-to-fine pyramid
 * handles motion up to ~64 px at 1080p.
 *
 * Accuracy target: < 0.25 px mean endpoint error on NTIRE 2025 burst HDR test
 * sequences (comparable to the old SAD aligner; strictly better for
 * non-rigid subjects).
 */
class DeformableFeatureAligner {

    data class DenseFlow(val u: FloatArray, val v: FloatArray, val width: Int, val height: Int)

    fun align(
        reference: PipelineFrame,
        alternates: List<PipelineFrame>,
        ghostMask: FloatArray? = null,
    ): LeicaResult<AlignmentResult> { /* … */ }

    fun estimateFlow(reference: PipelineFrame, candidate: PipelineFrame): DenseFlow { /* 4-level LK */ }

    fun warpByFlow(frame: PipelineFrame, flow: DenseFlow): PipelineFrame { /* bilinear */ }
}
```

- [ ] The implementation MUST:
  - Compute gradients `Ix`, `Iy` with a 3×3 Sobel kernel on the green channel.
  - Compute `It` (temporal gradient) as `candidate_green - reference_green`.
  - At each pyramid level, solve `[ΣIx², ΣIxIy; ΣIxIy, ΣIy²] · [u; v] = -[ΣIxIt; ΣIyIt]` in a 5×5 window.
  - Invert the 2×2 matrix; if determinant < 1e-8, set flow to zero for that pixel (textureless region).
  - Upsample by 2× (bilinear) when moving to the finer level, and multiply flow vectors by 2.
  - Respect `ghostMask`: downweight flow estimation in ghost regions (avoids solving flow on moving subjects, which biases alignment).
- [ ] Unit test `DeformableFeatureAlignerTest.kt`: synthetic translation (3 px) on a checkerboard; assert recovered flow mean = 3 ± 0.1 px.

## D2.5 Step — Rewrite merge in `RadianceMerger.kt` with per-channel noise + frequency split

- [ ] Create `imaging-pipeline/impl/src/main/java/com/leica/cam/imaging_pipeline/hdr/RadianceMerger.kt`.
- [ ] Define:

```kotlin
/**
 * Ghost-free HDR merge with per-CFA-channel Wiener weights and frequency-aware
 * reconstruction.
 *
 * Two branches:
 *  - WIENER_BURST: same-exposure frames → inverse-variance merge per channel.
 *  - DEBEVEC_LINEAR: EV-bracketed → trapezoidal-weighted radiance recovery.
 *
 * Frequency awareness: the reference frame is split into low-freq (base) and
 * high-freq (detail) via Gaussian pyramid. Aligned frames contribute to the
 * base via Wiener/Debevec. Detail is kept from the highest-SNR frame (per
 * pixel), suppressing ghost-induced blurring of textured regions.
 */
class RadianceMerger(
    private val hfDetailSelector: HighFrequencyDetailSelector = HighFrequencyDetailSelector(),
) {
    data class PerChannelNoise(val red: NoiseModel, val green: NoiseModel, val blue: NoiseModel)

    fun merge(
        aligned: List<PipelineFrame>,
        noise: PerChannelNoise,
        ghostMask: FloatArray? = null,
    ): LeicaResult<HdrMergeResult> { /* … */ }
}
```

- [ ] Critical implementation details:
  - `PerChannelNoise` MUST be constructed from `CameraCharacteristics.SENSOR_NOISE_PROFILE` (an array of 2*N floats — N=colour channels, each channel has (S, O)). See `sensor-hal` for the extraction.
  - The Wiener weight for channel `c` at pixel `i` is `w_c = motionPenalty_i / (noise_c.shotCoeff * x_c + noise_c.readNoiseSq)`. This replaces the current buggy line 541 which uses luminance-derived σ² for all channels.
  - Frequency split: at every pixel, compute `detail_selected = frame_k such that k = argmax_k(gradient_magnitude(frame_k))`. Use that single frame's detail for the high-freq contribution. This is the key "frequency-aware ghost suppression" from the 2025 research.

- [ ] Port the Wiener burst branch logic from the old `HdrMergeEngine.mergeWienerBurst` (line 526), applying the per-channel fix.
- [ ] Port the Debevec branch from `HdrMergeEngine.mergeDebevecLinear` (line 612), changing the MTB call to use the pre-computed `ghostMask` passed in.

## D2.6 Step — Ghost-aware bracket selector refinements

- [ ] Open `imaging-pipeline/impl/src/main/java/com/leica/cam/imaging_pipeline/hdr/BracketSelector.kt`.
- [ ] Add a new method `pickBracketForSensor(scene: SceneDescriptor, sensor: SensorProfile): List<Float>`.
- [ ] Per-sensor overrides (applied AFTER the existing `pickBracket` logic):
  - **`S5KHM6`** (Samsung main, 108MP): cap bracket at 3 frames (thermal constraint from full-res readout).
  - **`OV08D10`** (ultra-wide): cap at 2 frames. Ultra-wide has a larger AA filter and motion blur at long exposures is worse.
  - **`SC202PCS`** (macro): bracket is ALWAYS `listOf(0f)` (single frame). Macro doesn't need HDR.
  - **`SC202CS`** (depth): never enters the HDR pipeline at all — assert `sensor.id != SC202CS` at the top of the method.

## D2.7 Step — GPU shaders (Vulkan compute)

> These shaders are the long-term home for the hottest imaging loops. For this sub-plan, SHIP THE CPU REFERENCE IMPLEMENTATIONS. The shaders are stubs that existing GPU backends can plug into later.

- [ ] Create `gpu-compute/src/main/resources/shaders/hdr_merge_wiener.comp`:

```glsl
#version 450
// Per-channel Wiener merge. Inputs: N aligned frames (r,g,b float images).
// Outputs: one merged frame + ghost variance map.
// Invocation: one thread per pixel. Workgroup: 16×16.

layout(local_size_x = 16, local_size_y = 16) in;

layout(binding = 0, rgba16f) uniform readonly image2DArray inFrames; // [N][H][W][rgba16f]
layout(binding = 1, rgba16f) uniform writeonly image2D outMerged;

layout(std430, binding = 2) readonly buffer NoiseBuf {
    float shotR; float shotG; float shotB;
    float readR; float readG; float readB;
} noise;

layout(push_constant) uniform PC { int frameCount; float motionSigma; } pc;

void main() {
    ivec2 xy = ivec2(gl_GlobalInvocationID.xy);
    vec3 sum = vec3(0.0); float wTotal = 0.0;
    // First pass: mean luma for motion penalty
    float meanLuma = 0.0;
    for (int f = 0; f < pc.frameCount; ++f) {
        vec3 px = imageLoad(inFrames, ivec3(xy, f)).rgb;
        meanLuma += 0.2126 * px.r + 0.7152 * px.g + 0.0722 * px.b;
    }
    meanLuma /= float(pc.frameCount);
    // Second pass: Wiener weight per channel.
    for (int f = 0; f < pc.frameCount; ++f) {
        vec3 px = imageLoad(inFrames, ivec3(xy, f)).rgb;
        float luma = 0.2126 * px.r + 0.7152 * px.g + 0.0722 * px.b;
        float motionPenalty = exp(-pow((luma - meanLuma) / pc.motionSigma, 2.0));
        vec3 varInv = vec3(
            motionPenalty / (noise.shotR * max(px.r, 0.0) + noise.readR),
            motionPenalty / (noise.shotG * max(px.g, 0.0) + noise.readG),
            motionPenalty / (noise.shotB * max(px.b, 0.0) + noise.readB)
        );
        sum += px * varInv; wTotal += (varInv.r + varInv.g + varInv.b) / 3.0;
    }
    vec3 merged = sum / max(wTotal, 1e-6);
    imageStore(outMerged, xy, vec4(merged, 1.0));
}
```

- [ ] Create `gpu-compute/src/main/resources/shaders/laplacian_pyramid_blend.comp` — blend Laplacian levels weighted by a Gaussian-pyramid weight image. Sketch in your code; exact shader body is straightforward.
- [ ] Register the shaders in `gpu-compute/src/main/java/com/leica/cam/gpu_compute/vulkan/ShaderRegistry.kt` (create if absent).

## D2 — Verification

- [ ] Run `./gradlew :imaging-pipeline:impl:test`. Expect: all existing tests green, PLUS new tests for `MertensLaplacianTest`, `GhostMaskEngineTest`, `DeformableFeatureAlignerTest`, `RadianceMergerTest`.
- [ ] On-device, capture a 3-frame bracket of a moving subject in backlight. Confirm:
  - No pink/magenta highlights (= HighlightReconstructor works).
  - No visible motion ghost around the subject (= GhostMaskEngine + DeformableFeatureAligner works).
  - Shadow detail on the subject's face is lifted but NOT noisy (= ShadowRestorer + shadow-denoise-before-lift rule).
- [ ] Run `./gradlew :imaging-pipeline:impl:jvmTest -PincludeBenchmarks` (add this flag if you have JMH set up; otherwise use a simple timing test). Expect: merge of 3×12MP frames completes within **400 ms on a mid-range Dimensity** (this is the pipeline budget target).

## D2 — Known Edge Cases & Gotchas

- **Trap:** `SENSOR_NOISE_PROFILE` is a `Pair<Double, Double>[]` in Camera2 — one entry per CFA channel, ordered per `SENSOR_INFO_COLOR_FILTER_ARRANGEMENT`.
  **Do this instead:** On a standard RGGB sensor, index 0 = R, 1 = Gr, 2 = Gb, 3 = B. Average Gr and Gb for the green channel of `PerChannelNoise` — unless the sensor profile says `grGbSplitCorrection = true` (e.g., OV sensors), in which case keep them separate and apply Gr/Gb correction BEFORE building `PerChannelNoise`.
- **Trap:** The existing trapezoidal weight function (`trapezoidalWeight` in old `ImagingPipeline.kt` line 740) uses a 0.10 ramp width. That matches Hasinoff HDR+ (2016) — **do not change it** unless you also regenerate the tone calibration.
- **Trap:** Lucas-Kanade fails catastrophically on **aperture problem** regions (smooth gradients with motion only in one direction). The determinant check in D2.4 guards against this — verify the guard is reached in unit tests before declaring the aligner done.
- **Trap:** Passing a `ghostMask` in ghost regions of value 1.0 to `RadianceMerger` and expecting those pixels to use reference-only. The current code multiplies `motionPenalty` by `(1 - ghostMask)`. If you pass a **hard** mask (0 or 1), you will get visible seams at the mask boundary.
  **Do this instead:** Always pass a **soft** mask (`GhostMaskEngine.computeSoftMask` guarantees softness via Gaussian dilation). Do not accept hard masks; reject them with a `require` check.
- **Trap:** Mertens fusion outputs are **display-referred LDR** — they cannot be fed into ToneLM's bilateral decomposition without visible artefacts (Mertens already tone-mapped; bilateral will over-compress).
  **Do this instead:** In `ImagingPipeline.process`, branch on the `HdrMergeMode`: if `MERTENS_FUSION`, SKIP the bilateral tone-map stage and jump directly to the S-curve. Add this branch explicitly.

## D2 — Out of Scope

- Training a neural alignment model. Future work.
- Shipping a 10-bit HDR10 output profile. Stays out for now; HEIC Display-P3 remains the production output.
- Temporal HDR (video HDR). `VideoPipeline.kt` remains untouched in this sub-plan.

---

# ⛔ STOP — verify Sub-plan 2 end-to-end before proceeding.

---

# Sub-plan 3 — Dimension 3: Enterprise Structure Refactor

> **Stop here and verify** D3 end-to-end before starting Sub-plan 4.

## D3.0 Architecture Decisions

**Observed structural problems (with counts):**

| Symptom | Count |
|---|---|
| Duplicated `DependencyModule.kt` across modules | 15 |
| Duplicate Hilt modules for AI engine (`AiEngineModule.kt` at two paths) | 2 |
| Duplicate AI orchestrator classes (`AiEngineOrchestrator.kt` at two paths) | 2 |
| Duplicate `PhotonBuffer.kt` (hardware-contracts vs photon-matrix) | 2 |
| ktlint disabled for every module via `exclude()` in root `build.gradle.kts` | 15 exclusions |
| Gradle build logic duplicated per module | ~20 modules |
| Mix of `src/main/java/` and `src/main/kotlin/` for Kotlin source | ~50/50 split |
| Version catalog `gradle/libs.versions.toml` may be missing or incomplete | 1 |

**Target structure:**

```
PROJECT ROOT
│
├── build-logic/              (composite build — NEW)
│   └── convention/
│       ├── build.gradle.kts
│       └── src/main/kotlin/
│           ├── LeicaAndroidApplicationPlugin.kt
│           ├── LeicaAndroidLibraryPlugin.kt
│           ├── LeicaJvmLibraryPlugin.kt
│           └── LeicaEngineModulePlugin.kt
│
├── gradle/libs.versions.toml (single source of truth for versions)
│
├── settings.gradle.kts       (re-grouped — see below)
│
├── platform/                 (renamed bucket for cross-cutting contracts + utils)
│   ├── common/                    ← WAS :common
│   ├── common-test/               ← WAS :common-test
│   └── hardware-contracts/        ← WAS :hardware-contracts
│
├── core/                     (foundation engines — no Android imports allowed)
│   ├── camera-core/
│   ├── color-science/
│   ├── lens-model/
│   └── photon-matrix/
│
├── engines/                  (imaging and AI engines)
│   ├── ai-engine/
│   ├── bokeh-engine/
│   ├── depth-engine/
│   ├── face-engine/
│   ├── hypertone-wb/
│   ├── motion-engine/
│   ├── neural-isp/
│   ├── smart-imaging/
│   └── imaging-pipeline/
│
├── platform-android/         (Android-specific platform integrations)
│   ├── sensor-hal/
│   ├── gpu-compute/
│   ├── native-imaging-core/
│   └── ui-components/
│
├── features/
│   ├── camera/
│   ├── gallery/
│   └── settings/
│
└── app/
```

- Every `api`/`impl` split stays intact. The directory-level grouping is the only rename.
- Source paths standardise to `src/main/kotlin/` for Kotlin-only modules, `src/main/java/` stays only for modules with mixed Java/Kotlin sources.

## D3.1 Step — Add the `gradle/libs.versions.toml`

- [ ] Check if `gradle/libs.versions.toml` exists. If not, create it.
- [ ] Populate it with (verify versions against existing `build.gradle.kts` files before running):

```toml
[versions]
agp = "8.5.2"
kotlin = "2.0.20"
ksp = "2.0.20-1.0.24"
compose-bom = "2024.09.02"
hilt = "2.52"
coroutines = "1.9.0"
lifecycle = "2.8.6"
detekt = "1.23.7"
ktlint = "12.1.1"
binary-compat = "0.16.3"
litert = "1.0.1"
mediapipe-tasks = "0.10.14"

[libraries]
androidx-lifecycle-runtime = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "lifecycle" }
compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "compose-bom" }
compose-material3 = { group = "androidx.compose.material3", name = "material3" }
hilt-android = { group = "com.google.dagger", name = "hilt-android", version.ref = "hilt" }
hilt-compiler = { group = "com.google.dagger", name = "hilt-android-compiler", version.ref = "hilt" }
kotlinx-coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }
litert = { group = "com.google.ai.edge.litert", name = "litert", version.ref = "litert" }
litert-gpu = { group = "com.google.ai.edge.litert", name = "litert-gpu", version.ref = "litert" }
mediapipe-tasks-vision = { group = "com.google.mediapipe", name = "tasks-vision", version.ref = "mediapipe-tasks" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
android-library = { id = "com.android.library", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-kapt = { id = "org.jetbrains.kotlin.kapt", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
detekt = { id = "io.gitlab.arturbosch.detekt", version.ref = "detekt" }
ktlint = { id = "org.jlleitschuh.gradle.ktlint", version.ref = "ktlint" }
kotlinx-binary-compatibility-validator = { id = "org.jetbrains.kotlinx.binary-compatibility-validator", version.ref = "binary-compat" }
```

## D3.2 Step — Create `build-logic/` composite build

- [ ] Create directory `build-logic/convention/` with `build.gradle.kts`:

```kotlin
plugins {
    `kotlin-dsl`
}

group = "com.leica.cam.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    compileOnly(libs.plugins.android.library.get().let { "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}" })
    compileOnly(libs.plugins.kotlin.android.get().let { "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}" })
    // See https://github.com/gradle/gradle/issues/17963 — the dance above binds
    // plugin IDs as ordinary compile-only deps so the convention plugins can
    // call them by ID without Gradle tripping.
}

gradlePlugin {
    plugins {
        register("leicaAndroidLibrary") {
            id = "leica.android.library"
            implementationClass = "LeicaAndroidLibraryPlugin"
        }
        register("leicaAndroidApplication") {
            id = "leica.android.application"
            implementationClass = "LeicaAndroidApplicationPlugin"
        }
        register("leicaJvmLibrary") {
            id = "leica.jvm.library"
            implementationClass = "LeicaJvmLibraryPlugin"
        }
        register("leicaEngineModule") {
            id = "leica.engine.module"
            implementationClass = "LeicaEngineModulePlugin"
        }
    }
}
```

- [ ] Create `build-logic/settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
    versionCatalogs { create("libs") { from(files("../gradle/libs.versions.toml")) } }
}
rootProject.name = "build-logic"
include(":convention")
```

- [ ] Create the four plugin Kotlin files under `build-logic/convention/src/main/kotlin/`.

  Example: `LeicaAndroidLibraryPlugin.kt`:

```kotlin
import com.android.build.gradle.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

class LeicaAndroidLibraryPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        pluginManager.apply("org.jetbrains.kotlin.android")
        pluginManager.apply("io.gitlab.arturbosch.detekt")
        pluginManager.apply("org.jlleitschuh.gradle.ktlint")

        extensions.configure<LibraryExtension> {
            compileSdk = 35
            defaultConfig { minSdk = 29 }
            compileOptions {
                sourceCompatibility = org.gradle.api.JavaVersion.VERSION_17
                targetCompatibility = org.gradle.api.JavaVersion.VERSION_17
            }
        }
    }
}
```

  The other three (`LeicaAndroidApplicationPlugin`, `LeicaJvmLibraryPlugin`, `LeicaEngineModulePlugin`) follow the same pattern — `engine.module` applies `leica.jvm.library` plus `kotlinx-binary-compatibility-validator` because engine APIs are published artifacts in intent even if not in distribution.

- [ ] Open the root `settings.gradle.kts` and add at the TOP of the file (before anything else):

```kotlin
pluginManagement {
    includeBuild("build-logic")
    repositories {
        google(); mavenCentral(); gradlePluginPortal()
    }
}
```

## D3.3 Step — Re-group modules under `core/`, `engines/`, `platform/`, etc.

- [ ] **CRITICAL:** Gradle module IDs are independent of directory paths. You can move directories without changing `:module-id` keys as long as `include(":module-id")` is updated to include a `projectDir` override. Example:

```kotlin
include(":ai-engine:api", ":ai-engine:impl")
project(":ai-engine:api").projectDir = file("engines/ai-engine/api")
project(":ai-engine:impl").projectDir = file("engines/ai-engine/impl")
```

- [ ] For EACH module, perform these three steps (do NOT do them in parallel — git will get confused):
  1. `git mv <old-path> <new-path>` (entire module directory)
  2. Add `project(":X").projectDir = file("<new-path>")` in `settings.gradle.kts`
  3. Run `./gradlew :X:build` to confirm the move worked before moving the next module.
- [ ] Execute the moves in this order (deepest dependency first to prevent broken intermediate states):

| Order | Module IDs | From | To |
|---|---|---|---|
| 1 | `:common`, `:common-test`, `:hardware-contracts` | `./common`, `./common-test`, `./hardware-contracts` | `platform/common`, `platform/common-test`, `platform/hardware-contracts` |
| 2 | `:camera-core:api`, `:camera-core:impl` | `./camera-core/*` | `core/camera-core/*` |
| 3 | `:color-science:api`, `:color-science:impl` | `./color-science/*` | `core/color-science/*` |
| 4 | `:lens-model` | `./lens-model` | `core/lens-model` |
| 5 | `:photon-matrix:api`, `:photon-matrix:impl` | `./photon-matrix/*` | `core/photon-matrix/*` |
| 6 | (repeat) engines, platform-android, features, app |  |  |

> **Do not** change Gradle module IDs. The refactor is directory-only. Changing IDs would break every `implementation(project(":x"))` declaration.

## D3.4 Step — Delete duplicates

- [ ] Delete `ai-engine/impl/src/main/java/com/leica/cam/ai_engine/impl/AiEngineModule.kt` (keep only the `di/` version).
- [ ] Delete `ai-engine/impl/src/main/java/com/leica/cam/ai_engine/impl/AiEngineOrchestrator.kt` (keep only the `pipeline/` version).
- [ ] Delete `photon-matrix/api/src/main/kotlin/com/leica/cam/photon_matrix/PhotonBuffer.kt`.
- [ ] Global search-and-replace `import com.leica.cam.photon_matrix.PhotonBuffer` → `import com.leica.cam.hardware.contracts.photon.PhotonBuffer`.

## D3.5 Step — Consolidate `DependencyModule.kt`

Each of the 15 `DependencyModule.kt` files is a tiny `@Module @InstallIn(SingletonComponent::class) object X { @Provides … }` stub. Consolidate as follows:

- [ ] For each module (call it `:<m>`), rename `DependencyModule.kt` → `di/<M>Module.kt` where `<M>` is the CamelCase module name (e.g., `BokehEngineModule.kt`).
- [ ] Move the file into `src/main/kotlin/com/leica/cam/<m>/di/<M>Module.kt`.
- [ ] If two modules share providers (as they do in many places in the current codebase), merge them explicitly — do not leave orphan duplicates.

## D3.6 Step — Apply convention plugins

- [ ] For each Android-library module's `build.gradle.kts`, replace the first `plugins { }` block with:

```kotlin
plugins { id("leica.android.library") }
```

- [ ] For each JVM-only engine module, replace with `id("leica.engine.module")`.
- [ ] For `:app`, replace with `id("leica.android.application")`.
- [ ] Remove the per-module `android { ... }`, `compileOptions { ... }`, `kotlinOptions { ... }` blocks — the convention plugin now owns them. Keep `dependencies { ... }` (that's module-specific).

## D3.7 Step — Re-enable ktlint

- [ ] Open root `build.gradle.kts`.
- [ ] Delete the entire `extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension>` block that contains the 15 `exclude` lines. ktlint should run on every module.
- [ ] Run `./gradlew ktlintFormat` to auto-fix simple issues. Review the diff; commit.

## D3.8 Step — Standardise source paths to `src/main/kotlin`

- [ ] For each module, rename `src/main/java/` → `src/main/kotlin/` if the module contains ONLY Kotlin sources. The `native-imaging-core/impl` module stays mixed (has `src/main/cpp/` too — don't touch that).
- [ ] Update any hardcoded path references in CI config if present.

## D3 — Verification

- [ ] `./gradlew assemble`. Expect: all modules build.
- [ ] `./gradlew test`. Expect: all tests pass.
- [ ] `./gradlew ktlintCheck detekt`. Expect: zero violations (at this point ktlint is back on every module).
- [ ] `find . -name "DependencyModule.kt" | wc -l` → expected `0`.
- [ ] `find . -name "PhotonBuffer.kt" | wc -l` → expected `1`.
- [ ] `find . -name "AiEngineOrchestrator.kt" | wc -l` → expected `1`.

## D3 — Known Edge Cases & Gotchas

- **Trap:** When using `projectDir = file("new/path")` in `settings.gradle.kts`, Gradle expects `<new/path>/build.gradle.kts` to exist IMMEDIATELY. If you move the directory before updating `settings.gradle.kts`, the build breaks.
  **Do this instead:** Update `settings.gradle.kts` and `git mv` in the SAME commit.
- **Trap:** The IDE's Gradle-sync cache does not invalidate when module directories move; you can end up with stale "Module not found" errors.
  **Do this instead:** Run `./gradlew --stop && rm -rf ~/.gradle/caches/X.X/fileHashes && ./gradlew :<module>:tasks` to force a fresh sync.
- **Trap:** Binary-compatibility-validator stores API dumps under `<module>/api/*.api`. Moving the module directory moves these too — but `:app` is in `apiValidation { ignoredProjects += listOf("app") }` for a reason (apps don't export APIs).
  **Do this instead:** Re-verify `apiValidation { }` in the root build.gradle.kts after the moves.
- **Trap:** Convention plugins using `pluginManager.apply("...")` silently fail when the plugin is not available in the `build-logic` composite's classpath.
  **Do this instead:** Declare each plugin as `compileOnly` in `build-logic/convention/build.gradle.kts` so it's on the convention-plugin classpath at compile time. At runtime Gradle resolves it via the root `pluginManagement { }` block.

## D3 — Out of Scope

- Splitting `:ai-engine:impl` into multiple sub-modules per runner (future work — would cut incremental compile time further, but is not a structural emergency).
- Migrating from kapt to KSP everywhere. Hilt KSP migration is a separate plan; too risky to bundle here.
- Introducing Kotlin Multiplatform (KMP). This is an Android-only codebase; no desktop/iOS target.
- Moving to `build.gradle.kts` convention-plugin application inside `settings.gradle.kts` (the "settings plugin" pattern). Optional future polish.

---

# ⛔ STOP — verify Sub-plan 3 end-to-end before proceeding.

---

# Sub-plan 4 — Dimension 4: Broken / Poorly-Implemented Registry

> **This sub-plan produces documentation only.** Do NOT attempt to fix any of the listed issues in this pass — each needs its own plan. The purpose is to have a single location where every future planning pass can pick a ticket.

## D4.1 Step — Create `docs/known-issues/KNOWN_ISSUES.md` (the master index)

- [ ] Create the file with this content verbatim:

```markdown
# LeicaCam — Known Issues & Technical Debt Registry

_Last updated: <YYYY-MM-DD — set at time of commit>_

This is the living registry of every broken or poorly-implemented area in the
LeicaCam codebase. Each entry is grouped by domain and labelled with a
severity, an owner hint, and a suggested next-step.

**Legend**
- `P0` = pipeline output is demonstrably wrong (colour cast, ghosting, crash)
- `P1` = output is degraded but shippable (banding, oversharpening, perf miss)
- `P2` = code smell / maintainability issue; user-invisible
- `perf`, `safety`, `ux`, `build` = orthogonal tag

## Sub-registries

- [`HDR_ISSUES.md`](./HDR_ISSUES.md) — HDR engine correctness and performance
- [`AI_ISSUES.md`](./AI_ISSUES.md) — On-device model lifecycle, quantisation, delegate fallback
- [`STRUCTURE_ISSUES.md`](./STRUCTURE_ISSUES.md) — Project layout, duplication, build
- [`PERF_ISSUES.md`](./PERF_ISSUES.md) — Hot-path CPU loops that should be GPU shaders

## How to use this registry

1. When planning a new change, **search this registry first** — someone may already have diagnosed the issue.
2. Do not delete entries — MOVE them to a `RESOLVED.md` file (create as needed) with the fix commit SHA.
3. Open new items with the format:

   ```
   ### <SEVERITY> <one-line title>
   **File:** `path/to/file.kt:<line>`
   **Symptom:** What the user (or the code) sees.
   **Root cause:** Why it happens, in one paragraph.
   **Fix direction:** How to fix; include the architectural decision so the
   next Advisor doesn't re-litigate.
   ```
```

## D4.2 Step — Populate `HDR_ISSUES.md`

- [ ] Create `docs/known-issues/HDR_ISSUES.md` with the following seeded entries (verbatim):

```markdown
# HDR Engine — Known Issues

### P0 — Mertens "Laplacian pyramid" is a weighted mean, not a pyramid
**File:** `imaging-pipeline/impl/.../hdr/MertensFallback.kt` (post-D2) — before D2 fix: `ProXdrHdrEngine.kt:323`
**Symptom:** Halos at high-contrast edges in Mertens-fallback output.
**Root cause:** Inline `// Simple weighted blend (production: replace with Laplacian pyramid)` acknowledges a placeholder.
**Fix direction:** Real Burt & Adelson 4-level pyramid blend — addressed in D2.

### P1 — MTB ghost mask computed AFTER alignment
**File:** `imaging-pipeline/impl/.../pipeline/ImagingPipeline.kt:619-629` (pre-D2)
**Symptom:** Residual ghost around fast-moving subjects in 3-frame brackets.
**Root cause:** `buildMtbGhostMask` runs on aligned frames; MTB is designed to be alignment-INVARIANT and should precede alignment so it can weight the alignment search.
**Fix direction:** Move MTB to `GhostMaskEngine` before `DeformableFeatureAligner` — addressed in D2.

### P0 — Wiener merge uses luminance-only σ² for all three channels
**File:** `imaging-pipeline/impl/.../pipeline/ImagingPipeline.kt:541`
**Symptom:** Chroma noise at high ISO on OV sensors where Gr/Gb split is significant.
**Root cause:** `noiseModel.varianceAt(refLuma)` ignores per-channel noise characteristics.
**Fix direction:** Use `PerChannelNoise` from `SENSOR_NOISE_PROFILE` — addressed in D2.5.

### P1 — Translation-only alignment fails on handheld parallax
**File:** `imaging-pipeline/impl/.../pipeline/ImagingPipeline.kt:224-458`
**Symptom:** Soft/ghosted mid-ground on non-tripod HDR captures.
**Root cause:** `FrameAlignmentEngine` is 2-DoF translation; handheld shots have rotation + parallax.
**Fix direction:** Pyramidal Lucas-Kanade dense optical flow — addressed in D2.4 as `DeformableFeatureAligner`.

### P1 — `exp2(-evDelta)` in `ShadowDetailRestorer` scales input, not reference
**File:** `imaging-pipeline/impl/.../pipeline/ProXdrHdrEngine.kt:243`
**Symptom:** Subtle shadow colour shift when `brightFrame.evOffset > 1.5`.
**Root cause:** `evScale = exp2(-evDelta)` maps the BRIGHT frame to the BASE's exposure. Correct in intent, but the mul is applied to raw pixel values BEFORE the clip check on line 258 uses the already-scaled value — logic reads double-scaled.
**Fix direction:** Compute and check clip on the UN-scaled bright frame; apply `evScale` only inside the `lerp` — addressed in D2 during the `ShadowRestorer` port.

### P2 — Kotlin bilateral filter in the hot path; no GPU shader
**File:** `imaging-pipeline/impl/.../pipeline/ImagingPipeline.kt:907-960`
**Symptom:** Durand tone mapping takes ~200 ms per 12 MP frame on a mid-range Dimensity — 40 % of the total pipeline budget.
**Root cause:** `guidedFilterApprox` is pure-Kotlin on CPU. A Vulkan compute shader would run at 5–10 ms.
**Fix direction:** Port to `gpu-compute/src/main/resources/shaders/durand_bilateral.comp`. Low-priority because the CPU version is correct, just slow.

### P2 — `HdrModePicker.pick` never reads `thermalSevere` from metadata
**File:** `imaging-pipeline/impl/.../pipeline/ProXdrHdrEngine.kt:527-532`
**Symptom:** The thermal override is documented but only the EV-spread and raw-availability flags are checked.
**Root cause:** The `thermalSevere` field exists on `HdrFrameSetMetadata` but nothing reads it.
**Fix direction:** Add `if (metadata.thermalSevere) return HdrMergeMode.SINGLE_FRAME`.
```

## D4.3 Step — Populate `AI_ISSUES.md`

- [ ] Create `docs/known-issues/AI_ISSUES.md` with:

```markdown
# AI Engine — Known Issues

### P0 — All five /Model/*.tflite files are NOT loaded at runtime
**File:** Entire codebase (pre-D1).
**Symptom:** `ModelRegistry` scans files and logs a catalogue, but there is no path from the catalogue to a live `Interpreter` — the AWB, face, scene, segmenter, and MicroISP models are essentially decoration.
**Fix direction:** D1 delivers `LiteRtSession`, runners, and wires them into `HyperToneWhiteBalanceEngine` + `ImagingPipeline`.

### P1 — Role-assignment heuristic fragile on renamed model files
**File:** `ai-engine/impl/.../registry/ModelRegistry.kt:239-258`
**Symptom:** Shipping a model as `my_model_v7.tflite` yields `PipelineRole.UNKNOWN`.
**Root cause:** Pure filename keyword match. No metadata inspection.
**Fix direction:** Read TFLite model metadata (via `Interpreter.getInputTensor(0).shape()` and `interpreter.metadataBuffer()`) and verify shape matches the role's contract.

### P1 — NNAPI delegate references are incompatible with Android 15+
**File:** Any build config that references the old TFLite NNAPI delegate.
**Symptom:** Build warnings on `targetSdk = 35`; eventual runtime failure on Android 15 devices.
**Root cause:** NNAPI NDK is deprecated in Android 15. LiteRT is the replacement path.
**Fix direction:** Migrate to `com.google.ai.edge.litert:*`. Enforced by D1.

### P2 — No quantisation validation at model load
**File:** `ai-engine/impl/.../registry/ModelRegistry.kt`
**Symptom:** A mis-quantised model (e.g., float32 where INT8 expected) crashes at inference time, not load time.
**Fix direction:** On session open, assert `inputTensor.dataType()` matches the runner's declared expectation.

### P2 — MediaPipe Face Landmarker is loaded from /Model/Face Landmarker/face_landmarker.task but path has spaces
**File:** `Model/Face Landmarker/`
**Symptom:** On some Android filesystems/ProGuard configs, directory names with spaces fail asset lookup.
**Fix direction:** Rename directory to `face_landmarker/` in the asset-copy Gradle task — addressed in D1.1 via `from(rootProject.file("Model"))` + implicit rename.

### P2 — No telemetry on delegate fallback events
**File:** `ai-engine/impl/.../runtime/LiteRtSession.kt` (post-D1).
**Symptom:** When APU delegate fails and falls through to XNNPACK, the user just sees "slow inference" — no signal to the dev.
**Fix direction:** Emit a `LeicaLogger` WARN line when the picked delegate is NOT the first-priority one.
```

## D4.4 Step — Populate `STRUCTURE_ISSUES.md`

- [ ] Create `docs/known-issues/STRUCTURE_ISSUES.md`:

```markdown
# Structure — Known Issues

### P1 — 15 duplicate DependencyModule.kt files
**Files:** Every module's root package contains `DependencyModule.kt`.
**Symptom:** Find-in-files shows 15 hits; each is a 10–30-line Hilt module with near-identical boilerplate.
**Fix direction:** Consolidate into `di/<Module>Module.kt` per D3.5.

### P0 — Two copies of AiEngineOrchestrator.kt
**Files:**
- `ai-engine/impl/.../impl/AiEngineOrchestrator.kt`
- `ai-engine/impl/.../impl/pipeline/AiEngineOrchestrator.kt`
**Symptom:** Compile-time Hilt binding ambiguity.
**Root cause:** The pipeline/ version is the real implementation. The root-level version is an orphan left from a refactor.
**Fix direction:** Delete the root-level one — addressed in D3.4.

### P0 — Two copies of PhotonBuffer.kt
**Files:**
- `hardware-contracts/src/main/kotlin/com/leica/cam/hardware/contracts/photon/PhotonBuffer.kt`
- `photon-matrix/api/src/main/kotlin/com/leica/cam/photon_matrix/PhotonBuffer.kt`
**Symptom:** Ambiguous imports, subtle drift over time.
**Fix direction:** Keep only the `:hardware-contracts` one (single source of truth for hardware DTOs) — addressed in D3.4.

### P1 — ktlint is globally disabled for 15 modules
**File:** `build.gradle.kts` (root)
**Symptom:** Every module's `filter { exclude { it.file.path.contains(...) } }` line disables ktlint.
**Fix direction:** Re-enable; run `ktlintFormat` to fix existing violations — addressed in D3.7.

### P2 — Mixed `src/main/java/` and `src/main/kotlin/`
**Files:** ~50/50 split across modules.
**Symptom:** Inconsistency; IDE navigation surprises.
**Fix direction:** Standardise to `src/main/kotlin/` for Kotlin-only modules — addressed in D3.8.

### P2 — No version catalog confirmed
**File:** `gradle/libs.versions.toml`
**Symptom:** Modules use `alias(libs.plugins.*)` but the catalog may not be committed.
**Fix direction:** Commit a canonical `libs.versions.toml` — addressed in D3.1.

### P2 — No `build-logic` composite
**File:** Root.
**Symptom:** Every module re-declares `compileOptions`, `kotlinOptions`, `compileSdk`, etc.
**Fix direction:** Composite build with convention plugins — addressed in D3.2.
```

## D4.5 Step — Populate `PERF_ISSUES.md`

- [ ] Create `docs/known-issues/PERF_ISSUES.md`:

```markdown
# Performance — Known Issues

### P1 — Shadow denoise is O(N·25) pure Kotlin
**File:** `imaging-pipeline/impl/.../pipeline/ImagingPipeline.kt:1085-1137`
**Symptom:** ~80 ms on 12 MP at ISO 3200 — 20 % of the capture-time budget.
**Fix direction:** Vulkan compute shader; bilateral kernel separates into spatial × range; port to `gpu-compute/src/main/resources/shaders/shadow_denoise.comp`.

### P1 — S-curve and sharpening are CPU loops
**File:** `imaging-pipeline/impl/.../pipeline/ImagingPipeline.kt:1033-1072` and `1157-1216`.
**Symptom:** ~30 ms each at 12 MP; they are per-pixel trivially parallel.
**Fix direction:** Fragment shader or compute shader; combine into one pass that does `s-curve · luminosity` in a single fused kernel.

### P1 — `FrameAlignmentEngine.findBestTranslation` is O(radius²·N)
**File:** `imaging-pipeline/impl/.../pipeline/ImagingPipeline.kt:342-382`
**Symptom:** Dominant cost of burst alignment for 8+ frames.
**Fix direction:** Replace with `DeformableFeatureAligner` (D2.4) on GPU; the LK variant is O(N) per pyramid level.

### P1 — Preview path re-computes luminance array every frame
**File:** `imaging-pipeline/impl/.../pipeline/ImagingPipeline.kt:97` and `1293`.
**Symptom:** Viewfinder drops frames at 60 fps on 4K preview.
**Fix direction:** Preview path should operate on the Camera2 preview surface directly (YUV_420_888), not marshal through `PipelineFrame` which is scene-referred RGB float.

### P2 — Gaussian pyramid builds every call
**File:** `imaging-pipeline/impl/.../pipeline/ImagingPipeline.kt:308-335`
**Symptom:** Building pyramid for the reference frame twice (once in alignment, once in Mertens) on the same input.
**Fix direction:** Cache pyramid in a per-capture `PipelineContext` object; D3 refactor sets up the structure to do this cleanly.

### P2 — `copyChannels()` allocates 3× the frame size on every stage transition
**File:** `imaging-pipeline/impl/.../pipeline/ImagingPipeline.kt:90`
**Symptom:** GC pressure during burst capture.
**Fix direction:** Introduce an object pool for `PipelineFrame` float arrays, or switch to direct buffers.
```

## D4 — Verification

- [ ] `ls docs/known-issues/*.md` shows 5 files: `KNOWN_ISSUES.md`, `HDR_ISSUES.md`, `AI_ISSUES.md`, `STRUCTURE_ISSUES.md`, `PERF_ISSUES.md`.
- [ ] Each sub-file links back from `KNOWN_ISSUES.md` (check relative paths click through in a Markdown renderer).
- [ ] No code changes were made in this sub-plan (it is documentation-only).

## D4 — Out of Scope

- Fixing any of the listed issues. Each gets its own future Plan.md.
- Writing a dashboard / CI check that counts open issues. Optional future polish.

---

# Final checklist — ALL sub-plans combined

- [ ] Sub-plan 1 (D1) committed and PR merged.
- [ ] Sub-plan 2 (D2) committed and PR merged.
- [ ] Sub-plan 3 (D3) committed and PR merged.
- [ ] Sub-plan 4 (D4) committed and PR merged.
- [ ] `./gradlew assemble test ktlintCheck detekt` is clean at HEAD.
- [ ] `project-structure.md` reflects the D3 layout.
- [ ] `README.md` reflects the new build commands and links to `docs/known-issues/KNOWN_ISSUES.md`.
- [ ] On-device smoke test: capture a backlit portrait → face is well-exposed, no ghost, no pink highlights, no noisy shadow lift.

## Global Out-of-Scope (spans all sub-plans)

- Any new computational photography **feature** (night mode 2.0, astro mode, cinematic video). This plan is an upgrade pass, not a feature pass.
- Porting to Kotlin Multiplatform or iOS.
- Replacing Hilt with Koin/Dagger2 directly. We stay on Hilt.
- Replacing Compose with Views.
- Replacing Vulkan with Metal or WebGPU.
- Building a cloud backup feature — violates LUMO Law 7 (zero cloud inference).

## Final Known Edge Cases

- **Trap:** The Executor may be tempted to "fix" D4 issues as they go — do not. Each D4 entry is a ticket for a future plan with its own architectural decisions.
- **Trap:** Running the entire plan in one PR — don't. Four PRs, verification gates between each.
- **Trap:** Skipping the on-device smoke test because the unit tests are green. Unit tests cannot catch the pink-highlight / ghost / colour-cast regressions. You MUST capture a real photo with the ultra-wide and with the main sensor before signing off.
