# Plan: Wire & Harden the Advanced Smart Color-Science Engine for LeicaCam (Leica + Hasselblad HNCS)

> **Scope marker:** This plan is large. It is therefore split into **six sub-plans (CS-1 … CS-6)** that the Executor must complete in order, verifying each before starting the next. Each sub-plan is bite-sized (≤ 1 hour of focused work) and ends in a green build + a commit. **Stop after each sub-plan and verify before continuing.**
>
> **Why a plan, not code:** This is the Advisor handoff. The Executor model implements; the architectural decisions below have already been made and must not be re-debated.
>
> **Skill / Agent ownership table** (who owns what during execution):
>
> | Sub-plan | Primary skill / agent | Why |
> |---|---|---|
> | CS-1 — DI fix & contract surface | `Backend Enhancer` + `the-advisor` (this plan) | Fixes a build-breaking DI gap; pure Kotlin/Hilt wiring. |
> | CS-2 — Camera2 calibration ingestion | `color-science-engineer` | Reads CCMs / forward matrices / black/white levels from `CameraCharacteristics`. Color-science domain expertise required to map DNG `Rational[9]` → typed matrices correctly. |
> | CS-3 — Pipeline orchestration in `:imaging-pipeline:impl` | `color-science-engineer` + `the-advisor` | Decides the exact placement of color-science between WB and tone mapping; non-trivial. |
> | CS-4 — Documentation (`docs/Color Science Processing.md`) | `color-science-engineer` | Authoring authoritative pipeline doc grounded in the four research papers. |
> | CS-5 — Project structure update (`project-structure.md`) | `Backend Enhancer` | Mechanical doc edit; reflects the new wiring. |
> | CS-6 — Verification & calibration suite | `color-science-engineer` | ColorChecker + skin-tone benchmarks; ΔE2000 thresholds. |

---

## Context

LeicaCam is a Kotlin 2.0 + C++/Vulkan multi-module Android computational-photography stack (33 Gradle modules with strict `:api` / `:impl` separation). The repo already contains a substantial color-science implementation at `core/color-science/impl/.../pipeline/ColorScienceEngines.kt` (~1430 lines) with a `ColorSciencePipeline`, `PerZoneCcmEngine`, `TetrahedralLutEngine` (65³, tetrahedral interpolation), `CiecamCuspGamutMapper`, `PerHueHslEngine`, `SkinToneProtectionPipeline`, `FilmGrainSynthesizer`, and a `ColorAccuracyBenchmark`.

However, this engine is **not wired into the live capture flow**. Specifically:

1. `core/color-science/impl/.../di/ColorScienceModule.kt` does **not** provide `PerZoneCcmEngine`, `DngDualIlluminantInterpolator`, or `CiecamCuspGamutMapper` — yet `ColorSciencePipeline`'s constructor requires them. The DI graph cannot compile as written.
2. `engines/imaging-pipeline/impl/.../pipeline/ImagingPipeline.kt` never calls `ColorSciencePipeline.process(...)`. The runtime capture flow documented in `project-structure.md` §5 lists "color-science / metadata composers" as a metadata stage only — not a real color rendering stage.
3. The `IColorLM2Engine` API contract in `core/color-science/api/.../ColorScienceContracts.kt` is defined but has no implementation that satisfies it.
4. There is no Camera2 metadata reader feeding real `SENSOR_FORWARD_MATRIX1/2` into the dual-illuminant interpolator. `DngDualIlluminantInterpolator.Companion.defaultSensorForwardMatrixA/D65()` is hard-coded.
5. The four research papers in `Knowledge/` (Color science research paper.pdf, color_science_engine_leica_hasselblad_research.pdf, color_science_engine_research.pdf, Architecting a Hybrid Color Pipeline.pdf) define the target — RAW-first, 16-bit linear processing, dual-illuminant CCM, OKLAB perceptual operations, filmic tone curve with toe ≈ 50–55/255 / shoulder ≈ 75 % input, ΔE2000 < 3 average / < 5.5 max on D65 ColorChecker, skin patches < 4.0 — but none of these targets is currently being measured against the live pipeline.

The job is **not** to rewrite the color science (it is already 90 % built, and rewriting would lose the encoded HNCS / Leica look). The job is to **wire it correctly into the capture flow, feed it real Camera2 calibration data, validate it against the research-paper targets, and document it so any human or AI agent can understand both the math and the runtime flow.**

The Executor is also expected to produce, as part of CS-4 and CS-5, two long-form Markdown documents (`docs/Color Science Processing.md` and an updated `project-structure.md`). Their full bodies are pasted verbatim in this plan — the Executor copies them, does not invent them.

## Stack & Assumptions

- **Language / runtime:** Kotlin 2.0.x, JDK 17, AGP 8.x, Gradle Kotlin DSL.
- **Android:** compileSdk 35, NDK r27, minSdk 28+, target Vulkan 1.1+ device.
- **DI:** Dagger Hilt 2.51+ (already on classpath). Singletons in `SingletonComponent`.
- **Modules already on disk** (do not create):
  - `:color-science:api` → `core/color-science/api`
  - `:color-science:impl` → `core/color-science/impl`
  - `:imaging-pipeline:api` / `:imaging-pipeline:impl` → `engines/imaging-pipeline/...`
  - `:hypertone-wb:api` / `:hypertone-wb:impl` → `engines/hypertone-wb/...`
  - `:ai-engine:api` / `:ai-engine:impl` → `engines/ai-engine/...`
  - `:photon-matrix:api` → `core/photon-matrix/api`
  - `:common` → `platform/common`
  - `:sensor-hal` → `platform-android/sensor-hal`
- **Existing classes that must NOT be deleted or renamed:** `ColorSciencePipeline`, `PerZoneCcmEngine`, `DngDualIlluminantInterpolator`, `TetrahedralLutEngine`, `CiecamCuspGamutMapper`, `PerHueHslEngine`, `SkinToneProtectionPipeline`, `FilmGrainSynthesizer`, `ColorAccuracyBenchmark`, `ColorProfileLibrary`, `BradfordCat`, `IColorLM2Engine`, `ColourMappedBuffer`, `SceneContext`, `IlluminantHint`.
- **Research papers (read-only, in `Knowledge/`):**
  - `Color science research paper.pdf`
  - `color_science_engine_leica_hasselblad_research.pdf`
  - `color_science_engine_research.pdf`
  - `Architecting a Hybrid Color Pipeline_ Merging Perceptual Colorimetry and Generative AI for Android.pdf`
- **Skill references (read-only, in `.agents/skills/color-science-engineer/references/`):**
  - `pipeline-math.md`
  - `android-implementation.md`
  - `calibration-methodology.md`
  - `leica-hasselblad-rendering.md`
- **On-device models (read-only, in `Model/`):** AWB (`awb_final_full_integer_quant.tflite`), Face Landmarker (`.task`), Image Classifier (`1.tflite`), MicroISP (`MicroISP_V4_fp16.tflite`), Scene Understanding (`deeplabv3.tflite`). The color-science engine consumes the **outputs** of these (illuminant prior, semantic mask, face mask) — it does not re-run them.
- **Environment variables required:** none. All calibration data comes from `CameraCharacteristics`; runtime parameters come from the user-prefs `CameraPreferencesRepository`.
- **Branch:** all work happens on `genspark_ai_developer`. Commits are squashed before PR per the GenSpark workflow.

### Architectural decisions baked into this plan (do NOT re-debate)

1. **The color-science render order is sacred:** `Per-hue HSL → Vibrance/CAM → Skin protection → Per-zone CCM (with dual-illuminant DNG interpolation) → 3D LUT (tetrahedral, ACEScg linear in/out) → CIECAM02 CUSP gamut map → Film grain`. The order in `ColorSciencePipeline.process(...)` is correct as written; preserve it.
2. **Color-science runs AFTER HyperTone WB and BEFORE the global filmic tone curve.** White balance is calibration; tone mapping is rendering; per-zone CCM + LUT lives between them. This matches §6–7 of `color_science_engine_leica_hasselblad_research.pdf` and the `pipeline-math.md` reference chain. Do not move it.
3. **Calibration vs. Look are separate:** `PerZoneCcmEngine` does the colorimetric correction (sensor→sRGB-linear via interpolated DNG forward matrix + Bradford D50→D65). The `TetrahedralLutEngine` applies the aesthetic look. Never fold the look matrix into the CCM — it would prevent recalibration without retuning.
4. **Skin ΔE2000 cap is non-negotiable:** `ZoneCcmDelta.deltaECap = 1.5f` (HNCS) / `2.0f` (Leica M Classic). The cap clamping already exists in `PerZoneCcmEngine.applyZoneDelta`; do not weaken it.
5. **Output color space:** Display-P3 by default for HEIC/JPEG when `Build.VERSION.SDK_INT ≥ 26` and the surface supports it; sRGB fallback otherwise. The CUSP mapper already handles both via `OutputGamut`.
6. **Single pipeline, GPU-fast preview path is the same math as the CPU capture path.** The Vulkan compute shader for the LUT lives at `platform-android/gpu-compute/src/main/assets/shaders/lut_3d_tetra.comp`. Preview path uses a 33³ LUT bake of the same procedural LUT for speed; capture path uses 65³. Both bakes are produced by `TetrahedralLutEngine.buildProceduralLut` — no divergent code.
7. **No new color-science math is introduced in this plan.** Every formula already exists in `ColorScienceEngines.kt` or in the four research papers; the plan only wires, validates, documents.

## Files to create or modify

Top-of-buffer checklist for the Executor:

- [ ] `core/color-science/impl/src/main/kotlin/com/leica/cam/color_science/di/ColorScienceModule.kt` (modify — CS-1)
- [ ] `core/color-science/impl/src/main/kotlin/com/leica/cam/color_science/pipeline/ColorLM2EngineImpl.kt` (new — CS-1)
- [ ] `core/color-science/impl/src/main/kotlin/com/leica/cam/color_science/calibration/Camera2CalibrationReader.kt` (new — CS-2)
- [ ] `engines/imaging-pipeline/impl/src/main/kotlin/com/leica/cam/imaging_pipeline/pipeline/ColorSciencePipelineStage.kt` (new — CS-3)
- [ ] `engines/imaging-pipeline/impl/src/main/kotlin/com/leica/cam/imaging_pipeline/pipeline/ImagingPipeline.kt` (modify — CS-3, single insertion only)
- [ ] `core/color-science/impl/src/test/java/com/leica/cam/color_science/pipeline/ColorScienceWiringTest.kt` (new — CS-1)
- [ ] `core/color-science/impl/src/test/java/com/leica/cam/color_science/pipeline/ColorAccuracyBenchmarkTest.kt` (new — CS-6)
- [ ] `core/color-science/impl/src/test/java/com/leica/cam/color_science/calibration/Camera2CalibrationReaderTest.kt` (new — CS-2)
- [ ] `docs/Color Science Processing.md` (new — CS-4)
- [ ] `project-structure.md` (modify — CS-5; replace § runtime flow + add § 13 color-science detail)

---

## Sub-plan CS-1 — Wire ColorScienceModule and provide IColorLM2Engine

**Goal:** Make the existing `ColorSciencePipeline` constructible by Hilt, and provide the `:color-science:api` interface `IColorLM2Engine` so downstream modules (`:imaging-pipeline:impl`, `:hypertone-wb:impl`, the app shell) can depend on it through the API surface.

**Owner:** Backend Enhancer.

### Step CS-1.1: Replace `ColorScienceModule.kt` body

- [ ] Open `core/color-science/impl/src/main/kotlin/com/leica/cam/color_science/di/ColorScienceModule.kt`.
- [ ] Replace the **entire file** with:

```kotlin
package com.leica.cam.color_science.di

import com.leica.cam.color_science.api.IColorLM2Engine
import com.leica.cam.color_science.pipeline.CiecamCuspGamutMapper
import com.leica.cam.color_science.pipeline.ColorAccuracyBenchmark
import com.leica.cam.color_science.pipeline.ColorLM2EngineImpl
import com.leica.cam.color_science.pipeline.ColorSciencePipeline
import com.leica.cam.color_science.pipeline.ComputeBackend
import com.leica.cam.color_science.pipeline.DngDualIlluminantInterpolator
import com.leica.cam.color_science.pipeline.FilmGrainSynthesizer
import com.leica.cam.color_science.pipeline.OutputGamut
import com.leica.cam.color_science.pipeline.PerHueHslEngine
import com.leica.cam.color_science.pipeline.PerZoneCcmEngine
import com.leica.cam.color_science.pipeline.SkinToneProtectionPipeline
import com.leica.cam.color_science.pipeline.TetrahedralLutEngine
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

/**
 * Dependency entry point for `:color-science:impl`.
 *
 * Provides the full ColorLM 2.0 pipeline: per-zone CCM with DNG dual-illuminant
 * interpolation, tetrahedral 3D LUT (Vulkan-preferred), CIECAM02 CUSP gamut
 * mapper, perceptual hue/vibrance engines, skin-tone protection, film grain,
 * and the ColorChecker ΔE2000 benchmark.
 *
 * The exposed binding is `IColorLM2Engine`, declared in `:color-science:api`.
 * No `:impl` class is exported across modules.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ColorScienceModule {

    @Binds
    @Singleton
    abstract fun bindColorLM2Engine(impl: ColorLM2EngineImpl): IColorLM2Engine

    companion object {

        @Provides
        @Singleton
        @Named("color_science_module")
        fun provideModuleName(): String = "color-science"

        @Provides
        @Singleton
        fun provideTetrahedralLutEngine(): TetrahedralLutEngine =
            TetrahedralLutEngine(
                preferredBackend = ComputeBackend.VULKAN,
                fallbackBackend = ComputeBackend.CPU,
            )

        @Provides
        @Singleton
        fun providePerHueHslEngine(): PerHueHslEngine = PerHueHslEngine()

        @Provides
        @Singleton
        fun provideSkinToneProtectionPipeline(): SkinToneProtectionPipeline =
            SkinToneProtectionPipeline()

        @Provides
        @Singleton
        fun provideFilmGrainSynthesizer(): FilmGrainSynthesizer = FilmGrainSynthesizer()

        /**
         * Dual-illuminant DNG forward-matrix interpolator.
         *
         * Default matrices are the generic Sony IMX baseline shipped in
         * `DngDualIlluminantInterpolator.Companion`. They are overridden at
         * runtime by `Camera2CalibrationReader` (CS-2) which reads
         * `SENSOR_FORWARD_MATRIX1` / `SENSOR_FORWARD_MATRIX2` from
         * `CameraCharacteristics` and reconfigures the singleton via
         * `ColorLM2EngineImpl.updateSensorCalibration(...)`.
         */
        @Provides
        @Singleton
        fun provideDngDualIlluminantInterpolator(): DngDualIlluminantInterpolator =
            DngDualIlluminantInterpolator(
                forwardMatrixA = DngDualIlluminantInterpolator.defaultSensorForwardMatrixA(),
                forwardMatrixD65 = DngDualIlluminantInterpolator.defaultSensorForwardMatrixD65(),
            )

        @Provides
        @Singleton
        fun providePerZoneCcmEngine(
            interpolator: DngDualIlluminantInterpolator,
        ): PerZoneCcmEngine = PerZoneCcmEngine(interpolator)

        @Provides
        @Singleton
        fun provideCiecamCuspGamutMapper(): CiecamCuspGamutMapper =
            CiecamCuspGamutMapper(targetGamut = OutputGamut.DISPLAY_P3)

        @Provides
        @Singleton
        fun provideColorSciencePipeline(
            lutEngine: TetrahedralLutEngine,
            hueEngine: PerHueHslEngine,
            skinPipeline: SkinToneProtectionPipeline,
            grainSynthesizer: FilmGrainSynthesizer,
            zoneCcmEngine: PerZoneCcmEngine,
            gamutMapper: CiecamCuspGamutMapper,
        ): ColorSciencePipeline =
            ColorSciencePipeline(
                lutEngine = lutEngine,
                hueEngine = hueEngine,
                skinPipeline = skinPipeline,
                grainSynthesizer = grainSynthesizer,
                zoneCcmEngine = zoneCcmEngine,
                gamutMapper = gamutMapper,
            )

        @Provides
        @Singleton
        fun provideColorAccuracyBenchmark(
            pipeline: ColorSciencePipeline,
        ): ColorAccuracyBenchmark = ColorAccuracyBenchmark(pipeline)
    }
}
```

### Step CS-1.2: Create `ColorLM2EngineImpl.kt`

- [ ] Create `core/color-science/impl/src/main/kotlin/com/leica/cam/color_science/pipeline/ColorLM2EngineImpl.kt` with the exact contents below.

```kotlin
package com.leica.cam.color_science.pipeline

import com.leica.cam.color_science.api.ColourMappedBuffer
import com.leica.cam.color_science.api.IColorLM2Engine
import com.leica.cam.color_science.api.SceneContext
import com.leica.cam.common.Logger
import com.leica.cam.common.result.LeicaResult
import com.leica.cam.common.result.PipelineStage
import com.leica.cam.photon_matrix.FusedPhotonBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production implementation of [IColorLM2Engine].
 *
 * Adapts the `:color-science:impl` `ColorSciencePipeline` (which speaks the
 * internal [ColorFrame] type) to the cross-module `:color-science:api` contract
 * (which speaks [FusedPhotonBuffer]).
 *
 * Render order — sacred, do not reorder:
 *   1. Per-hue HSL (cosmetic user controls)
 *   2. Vibrance / CAM saturation
 *   3. Skin-tone protection (Fitzpatrick anchors, ΔE*₀₀ cap)
 *   4. Per-zone CCM (dual-illuminant DNG forward matrix)
 *   5. 3D LUT (65³ tetrahedral, ACEScg linear in/out)
 *   6. CIECAM02 CUSP gamut mapping → Display-P3 / sRGB
 *   7. Film grain synthesis (profile-specific, deterministic)
 *
 * The [ColorProfile] used per capture is read from
 * `CameraPreferencesRepository.colorProfile` by the imaging-pipeline stage
 * upstream of this engine.
 */
@Singleton
class ColorLM2EngineImpl @Inject constructor(
    private val pipeline: ColorSciencePipeline,
    private val interpolator: DngDualIlluminantInterpolator,
) : IColorLM2Engine {

    @Volatile
    private var calibrationOverride: SensorCalibration? = null

    /**
     * Replace the default DNG forward matrices with sensor-calibrated ones
     * read from `CameraCharacteristics`. Called by
     * `Camera2CalibrationReader` (CS-2) on capture-session creation.
     */
    fun updateSensorCalibration(calibration: SensorCalibration) {
        calibrationOverride = calibration
        Logger.i(
            tag = "ColorLM2EngineImpl",
            message = "Sensor calibration updated: A=${calibration.forwardMatrixA.contentToString()} " +
                "D65=${calibration.forwardMatrixD65.contentToString()}",
        )
    }

    override suspend fun mapColours(
        fused: FusedPhotonBuffer,
        scene: SceneContext,
    ): LeicaResult<ColourMappedBuffer> {
        return try {
            // Step 1 — adopt the most recent sensor calibration if provided.
            val activeInterpolator = calibrationOverride?.let {
                DngDualIlluminantInterpolator(
                    forwardMatrixA = it.forwardMatrixA,
                    forwardMatrixD65 = it.forwardMatrixD65,
                )
            } ?: interpolator

            // Step 2 — pull a Linear-RGB ColorFrame view out of the fused buffer.
            //          FusedPhotonBuffer already carries normalized linear floats
            //          per its contract in :photon-matrix:api.
            val input = fused.toColorFrame()

            // Step 3 — choose profile from scene context (default: Hasselblad Natural).
            val profile = ColorProfileFromSceneLabel.resolve(scene.sceneLabel)

            // Step 4 — execute the pipeline.
            val result = pipeline.process(
                input = input,
                profile = profile,
                hueAdjustments = scene.hueAdjustments(),
                vibranceAmount = scene.vibrance(),
                frameIndex = scene.frameIndex(),
                sceneCct = scene.illuminantHint.estimatedKelvin,
                zoneMask = scene.zoneMask(),
                zoneCcmInterpolatorOverride = activeInterpolator,
            )

            when (result) {
                is LeicaResult.Success -> {
                    val mapped = result.value
                    val outBuffer = mapped.intoFusedPhotonBuffer(fused)
                    LeicaResult.Success(
                        ColourMappedBuffer.Mapped(
                            underlying = outBuffer,
                            zoneCount = scene.zoneMask()?.firstOrNull()?.size ?: 0,
                        )
                    )
                }
                is LeicaResult.Failure -> result
            }
        } catch (t: Throwable) {
            Logger.e("ColorLM2EngineImpl", "mapColours failed", t)
            LeicaResult.Failure.Pipeline(
                stage = PipelineStage.COLOR_TRANSFORM,
                message = "ColorLM2 pipeline failure",
                cause = t,
            )
        }
    }
}

/** Sensor-calibrated DNG forward matrices (3×3 row-major). */
data class SensorCalibration(
    val forwardMatrixA: FloatArray,
    val forwardMatrixD65: FloatArray,
) {
    init {
        require(forwardMatrixA.size == 9) { "forwardMatrixA must be 3×3 row-major" }
        require(forwardMatrixD65.size == 9) { "forwardMatrixD65 must be 3×3 row-major" }
    }
}
```

### Step CS-1.3: Add the small ergonomic helpers used by `ColorLM2EngineImpl`

- [ ] In the same package, append a new file
  `core/color-science/impl/src/main/kotlin/com/leica/cam/color_science/pipeline/ColorScienceAdapters.kt`
  with the exact contents below.

```kotlin
package com.leica.cam.color_science.pipeline

import com.leica.cam.color_science.api.SceneContext
import com.leica.cam.photon_matrix.FusedPhotonBuffer

/**
 * Map a free-form scene label (from `:ai-engine:api` SceneClassifier) to a
 * concrete in-app [ColorProfile].
 *
 * Default is `HASSELBLAD_NATURAL` — the universal HNCS-style profile.
 * `LEICA_M_CLASSIC` is selected for street / portrait / low-light scenes
 * which benefit from the cinematic micro-contrast roll-off.
 */
object ColorProfileFromSceneLabel {
    fun resolve(label: String): ColorProfile {
        val l = label.lowercase()
        return when {
            "portrait" in l || "face" in l || "street" in l -> ColorProfile.LEICA_M_CLASSIC
            "landscape" in l || "nature" in l || "foliage" in l -> ColorProfile.HASSELBLAD_NATURAL
            "night" in l || "low_light" in l -> ColorProfile.LEICA_M_CLASSIC
            "monochrome" in l || "bw" in l -> ColorProfile.HP5_BW
            else -> ColorProfile.HASSELBLAD_NATURAL
        }
    }
}

/**
 * Convert a [FusedPhotonBuffer] into the internal linear [ColorFrame].
 *
 * Assumes [FusedPhotonBuffer] exposes interleaved or planar f32 channels in
 * normalized [0,1] linear sRGB / ACEScg. The exact accessor is determined by
 * the buffer's mode flag.
 */
internal fun FusedPhotonBuffer.toColorFrame(): ColorFrame {
    val w = this.width
    val h = this.height
    val r = FloatArray(w * h)
    val g = FloatArray(w * h)
    val b = FloatArray(w * h)
    this.copyLinearRgbInto(r, g, b)
    return ColorFrame(width = w, height = h, red = r, green = g, blue = b)
}

/**
 * Write a processed [ColorFrame] back into a fresh [FusedPhotonBuffer]
 * derived from [template] (carries metadata such as exposure / WB tags).
 */
internal fun ColorFrame.intoFusedPhotonBuffer(template: FusedPhotonBuffer): FusedPhotonBuffer =
    template.replaceLinearRgb(red, green, blue)

internal fun SceneContext.hueAdjustments(): PerHueAdjustmentSet =
    PerHueAdjustmentSet() // default — user adjustments come from CameraPreferencesRepository in CS-3.

internal fun SceneContext.vibrance(): Float = 0f

internal fun SceneContext.frameIndex(): Int = 0

internal fun SceneContext.zoneMask(): Array<Map<ColourZone, Float>>? = null
```

> **Note for the Executor:** if `FusedPhotonBuffer` does **not** already expose `width`, `height`, `copyLinearRgbInto(...)`, and `replaceLinearRgb(...)`, stop and report — those accessors live in `:photon-matrix:api` and are out of scope for this plan. Open a follow-up task and pause CS-1.

### Step CS-1.4: Add an optional override parameter to `ColorSciencePipeline.process`

- [ ] Open `core/color-science/impl/src/main/kotlin/com/leica/cam/color_science/pipeline/ColorScienceEngines.kt`.
- [ ] Locate the signature of `ColorSciencePipeline.process(...)`. It currently begins:

```kotlin
    fun process(
        input: ColorFrame,
        profile: ColorProfile,
        hueAdjustments: PerHueAdjustmentSet,
        vibranceAmount: Float,
        frameIndex: Int,
        sceneCct: Float = 6500f,
        zoneMask: Array<Map<ColourZone, Float>>? = null,
    ): LeicaResult<ColorFrame> {
```

- [ ] Replace **only that signature line set** with:

```kotlin
    fun process(
        input: ColorFrame,
        profile: ColorProfile,
        hueAdjustments: PerHueAdjustmentSet,
        vibranceAmount: Float,
        frameIndex: Int,
        sceneCct: Float = 6500f,
        zoneMask: Array<Map<ColourZone, Float>>? = null,
        zoneCcmInterpolatorOverride: DngDualIlluminantInterpolator? = null,
    ): LeicaResult<ColorFrame> {
```

- [ ] Locate the line inside `process` that reads:

```kotlin
        val zoneCorrect = zoneCcmEngine.apply(skinProtected, sceneCct, zoneMask, profile)
```

- [ ] Replace it with:

```kotlin
        val activeZoneEngine = zoneCcmInterpolatorOverride
            ?.let { PerZoneCcmEngine(it) }
            ?: zoneCcmEngine
        val zoneCorrect = activeZoneEngine.apply(skinProtected, sceneCct, zoneMask, profile)
```

> No other change inside `process(...)`. The render order stays exactly the same.

### Step CS-1.5: Create the wiring sanity test

- [ ] Create `core/color-science/impl/src/test/java/com/leica/cam/color_science/pipeline/ColorScienceWiringTest.kt`:

```kotlin
package com.leica.cam.color_science.pipeline

import com.leica.cam.color_science.di.ColorScienceModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Reflection-only smoke test that the DI module declares every binding the
 * Hilt graph downstream needs. Does NOT instantiate Hilt — that is covered
 * by the app-level integration test.
 */
class ColorScienceWiringTest {

    @Test
    fun module_provides_all_required_bindings() {
        val klass = ColorScienceModule.Companion::class.java
        val provided = klass.declaredMethods.map { it.name }.toSet()

        val required = setOf(
            "provideTetrahedralLutEngine",
            "providePerHueHslEngine",
            "provideSkinToneProtectionPipeline",
            "provideFilmGrainSynthesizer",
            "provideDngDualIlluminantInterpolator",
            "providePerZoneCcmEngine",
            "provideCiecamCuspGamutMapper",
            "provideColorSciencePipeline",
            "provideColorAccuracyBenchmark",
            "provideModuleName",
        )
        val missing = required - provided
        assertEquals("Missing DI bindings: $missing", emptySet<String>(), missing)
    }

    @Test
    fun pipeline_constructs_with_default_bindings() {
        val lut = TetrahedralLutEngine(ComputeBackend.CPU, ComputeBackend.CPU)
        val hue = PerHueHslEngine()
        val skin = SkinToneProtectionPipeline()
        val grain = FilmGrainSynthesizer()
        val interp = DngDualIlluminantInterpolator(
            forwardMatrixA = DngDualIlluminantInterpolator.defaultSensorForwardMatrixA(),
            forwardMatrixD65 = DngDualIlluminantInterpolator.defaultSensorForwardMatrixD65(),
        )
        val zone = PerZoneCcmEngine(interp)
        val gamut = CiecamCuspGamutMapper(OutputGamut.DISPLAY_P3)
        val pipeline = ColorSciencePipeline(lut, hue, skin, grain, zone, gamut)
        assertNotNull(pipeline)
    }
}
```

### Step CS-1.6: Build & commit

- [ ] Run: `./gradlew :color-science:impl:compileDebugKotlin`. Expect: `BUILD SUCCESSFUL`.
- [ ] Run: `./gradlew :color-science:impl:test --tests com.leica.cam.color_science.pipeline.ColorScienceWiringTest`. Expect: 2 tests, all green.
- [ ] Run: `./gradlew :app:kaptGenerateStubsDevDebugKotlin`. Expect: no `MissingBinding` errors involving `IColorLM2Engine`, `ColorSciencePipeline`, `PerZoneCcmEngine`, `CiecamCuspGamutMapper`, or `DngDualIlluminantInterpolator`.
- [ ] Run: `git add core/color-science/ && git commit -m "feat(color-science): wire ColorScienceModule + IColorLM2EngineImpl with full pipeline DI"`.

**STOP — verify CS-1 fully green before continuing to CS-2.**

---

## Sub-plan CS-2 — Read sensor calibration from Camera2 metadata

**Goal:** Replace the hard-coded `defaultSensorForwardMatrixA/D65()` with the device-specific values read from `CameraCharacteristics.SENSOR_FORWARD_MATRIX1` (Illuminant A / 2856 K) and `SENSOR_FORWARD_MATRIX2` (Illuminant D65 / 6500 K). Push them into the `ColorLM2EngineImpl` singleton on capture-session start.

**Owner:** color-science-engineer.

### Step CS-2.1: Create the calibration reader

- [ ] Create `core/color-science/impl/src/main/kotlin/com/leica/cam/color_science/calibration/Camera2CalibrationReader.kt`:

```kotlin
package com.leica.cam.color_science.calibration

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraCharacteristics.SENSOR_FORWARD_MATRIX1
import android.hardware.camera2.CameraCharacteristics.SENSOR_FORWARD_MATRIX2
import android.hardware.camera2.CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1
import android.hardware.camera2.CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2
import android.hardware.camera2.params.ColorSpaceTransform
import android.util.Rational
import com.leica.cam.color_science.pipeline.ColorLM2EngineImpl
import com.leica.cam.color_science.pipeline.SensorCalibration
import com.leica.cam.common.Logger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads per-device color calibration from `CameraCharacteristics` and pushes
 * the result into [ColorLM2EngineImpl].
 *
 * Android exposes DNG-style dual-illuminant data:
 *   - SENSOR_REFERENCE_ILLUMINANT1 / SENSOR_REFERENCE_ILLUMINANT2  — typically
 *     `STANDARD_A` (2856 K) and `D65` (6500 K) on flagship sensors.
 *   - SENSOR_FORWARD_MATRIX1 / SENSOR_FORWARD_MATRIX2  — 3×3 ColorSpaceTransform
 *     mapping native sensor RGB to CIE XYZ D50 (the DNG profile-connection space).
 *
 * If a device lacks one or both matrices (very rare on Android level FULL),
 * the engine falls back to its baked Sony-IMX defaults.
 *
 * **Threading:** call from a background thread on capture-session creation
 * (e.g., `Camera2CameraController.onSessionConfigured`).
 */
@Singleton
class Camera2CalibrationReader @Inject constructor(
    private val engine: ColorLM2EngineImpl,
) {

    /**
     * Read the dual-illuminant forward matrices from [characteristics] and
     * push them to the engine. No-op if neither matrix is present.
     *
     * Returns the resolved [SensorCalibration] (or null if both matrices
     * were missing on this device).
     */
    fun ingest(characteristics: CameraCharacteristics): SensorCalibration? {
        val ill1 = characteristics.get(SENSOR_REFERENCE_ILLUMINANT1)
        val ill2 = characteristics.get(SENSOR_REFERENCE_ILLUMINANT2)
        val fm1 = characteristics.get(SENSOR_FORWARD_MATRIX1)
        val fm2 = characteristics.get(SENSOR_FORWARD_MATRIX2)

        if (fm1 == null && fm2 == null) {
            Logger.w(
                tag = "Camera2CalibrationReader",
                message = "Device exposes no SENSOR_FORWARD_MATRIX*; falling back to defaults.",
            )
            return null
        }

        // If only one matrix is present, duplicate it — better than leaving the
        // interpolator on the Sony default at the missing illuminant.
        val matrixA = fm1?.toFloatArray() ?: fm2!!.toFloatArray()
        val matrixD65 = fm2?.toFloatArray() ?: fm1!!.toFloatArray()

        val calibration = SensorCalibration(
            forwardMatrixA = matrixA,
            forwardMatrixD65 = matrixD65,
        )
        engine.updateSensorCalibration(calibration)
        Logger.i(
            tag = "Camera2CalibrationReader",
            message = "Ingested calibration: ill1=$ill1 ill2=$ill2 " +
                "matA=${matrixA.contentToString()} matD65=${matrixD65.contentToString()}",
        )
        return calibration
    }

    /**
     * Convert a Camera2 [ColorSpaceTransform] (3×3 matrix of [Rational] in
     * row-major order) into a row-major `FloatArray(9)`.
     */
    private fun ColorSpaceTransform.toFloatArray(): FloatArray {
        val out = FloatArray(9)
        for (row in 0 until 3) {
            for (col in 0 until 3) {
                val r: Rational = this.getElement(col, row) // (col,row) per Android docs
                out[row * 3 + col] = r.toFloat()
            }
        }
        return out
    }
}
```

### Step CS-2.2: Test the reader

- [ ] Create `core/color-science/impl/src/test/java/com/leica/cam/color_science/calibration/Camera2CalibrationReaderTest.kt`:

```kotlin
package com.leica.cam.color_science.calibration

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.params.ColorSpaceTransform
import android.util.Rational
import com.leica.cam.color_science.pipeline.ColorLM2EngineImpl
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class Camera2CalibrationReaderTest {

    private fun cst(values: FloatArray): ColorSpaceTransform {
        val r = Array(9) { Rational((values[it] * 10000f).toInt(), 10000) }
        return ColorSpaceTransform(r)
    }

    @Test
    fun ingest_pushes_matrices_into_engine_when_both_present() {
        val engine = mockk<ColorLM2EngineImpl>(relaxed = true)
        val reader = Camera2CalibrationReader(engine)

        val characteristics = mockk<CameraCharacteristics>()
        val expectedA = floatArrayOf(0.7f,0.1f,0.1f, 0.3f,0.8f,-0.1f, 0.0f,-0.2f,1.1f)
        val expectedD = floatArrayOf(0.8f,0.0f,0.1f, 0.3f,0.9f,-0.2f, 0.0f,-0.3f,1.1f)
        every { characteristics.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1) } returns 17 // STANDARD_A
        every { characteristics.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2) } returns 21 // D65
        every { characteristics.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1) } returns cst(expectedA)
        every { characteristics.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2) } returns cst(expectedD)

        val calib = reader.ingest(characteristics)
        assertNotNull(calib)
        assertArrayEquals(expectedA, calib!!.forwardMatrixA, 1e-3f)
        assertArrayEquals(expectedD, calib.forwardMatrixD65, 1e-3f)
        verify { engine.updateSensorCalibration(any()) }
    }

    @Test
    fun ingest_returns_null_when_no_matrices_present() {
        val engine = mockk<ColorLM2EngineImpl>(relaxed = true)
        val reader = Camera2CalibrationReader(engine)
        val characteristics = mockk<CameraCharacteristics>()
        every { characteristics.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1) } returns null
        every { characteristics.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2) } returns null
        every { characteristics.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1) } returns null
        every { characteristics.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2) } returns null

        val calib = reader.ingest(characteristics)
        assert(calib == null)
        verify(exactly = 0) { engine.updateSensorCalibration(any()) }
    }
}
```

### Step CS-2.3: Build & commit

- [ ] Run: `./gradlew :color-science:impl:test`. Expect: all tests in `Camera2CalibrationReaderTest` and `ColorScienceWiringTest` pass.
- [ ] Run: `git add core/color-science/ && git commit -m "feat(color-science): read DNG dual-illuminant calibration from Camera2"`.

**STOP — verify CS-2 green before CS-3.**

---

## Sub-plan CS-3 — Insert ColorScience as a real stage in `:imaging-pipeline:impl`

**Goal:** Add a single `ColorSciencePipelineStage` between WB and tone mapping, and call it from `ImagingPipeline`. **No other reordering of the imaging pipeline is allowed.**

**Owner:** color-science-engineer + the-advisor.

### Step CS-3.1: Create the stage adapter

- [ ] Create `engines/imaging-pipeline/impl/src/main/kotlin/com/leica/cam/imaging_pipeline/pipeline/ColorSciencePipelineStage.kt`:

```kotlin
package com.leica.cam.imaging_pipeline.pipeline

import com.leica.cam.color_science.api.IColorLM2Engine
import com.leica.cam.color_science.api.IlluminantHint
import com.leica.cam.color_science.api.SceneContext
import com.leica.cam.common.Logger
import com.leica.cam.common.result.LeicaResult
import com.leica.cam.common.result.PipelineStage
import com.leica.cam.photon_matrix.FusedPhotonBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Imaging-pipeline-side adapter for the cross-module color-science API.
 *
 * Lives between `HyperToneWhiteBalanceEngine` (calibration) and
 * `DurandBilateralToneMappingEngine` + `CinematicSCurveEngine` (rendering).
 *
 * The adapter exists in `:imaging-pipeline:impl` (not `:color-science:impl`)
 * because it joins three engine domains: imaging-pipeline scene context,
 * hypertone WB illuminant hint, and the color-science API.
 */
@Singleton
class ColorSciencePipelineStage @Inject constructor(
    private val colorEngine: IColorLM2Engine,
) {
    suspend fun apply(
        wbCorrected: FusedPhotonBuffer,
        sceneLabel: String,
        estimatedKelvin: Float,
        kelvinConfidence: Float,
        isMixedLight: Boolean,
        captureMode: String,
    ): LeicaResult<FusedPhotonBuffer> {
        val context = SceneContext(
            sceneLabel = sceneLabel,
            illuminantHint = IlluminantHint(
                estimatedKelvin = estimatedKelvin,
                confidence = kelvinConfidence,
                isMixedLight = isMixedLight,
            ),
            captureMode = captureMode,
        )
        return when (val res = colorEngine.mapColours(wbCorrected, context)) {
            is LeicaResult.Success -> LeicaResult.Success(res.value.underlying)
            is LeicaResult.Failure -> {
                Logger.e("ColorSciencePipelineStage", "ColorLM2 stage failed: ${res.message()}")
                LeicaResult.Failure.Pipeline(
                    stage = PipelineStage.COLOR_TRANSFORM,
                    message = "ColorLM2 stage failed",
                    cause = res.causeOrNull(),
                )
            }
        }
    }

    private fun LeicaResult.Failure.message(): String =
        (this as? LeicaResult.Failure.Pipeline)?.message ?: this.toString()

    private fun LeicaResult.Failure.causeOrNull(): Throwable? =
        (this as? LeicaResult.Failure.Pipeline)?.cause
}
```

### Step CS-3.2: Insert the call site in `ImagingPipeline.kt`

- [ ] Open `engines/imaging-pipeline/impl/src/main/kotlin/com/leica/cam/imaging_pipeline/pipeline/ImagingPipeline.kt`.
- [ ] Add a `private val colorSciencePipelineStage: ColorSciencePipelineStage` to the constructor parameters (alongside the existing engines), and update the matching `@Inject` constructor.
- [ ] Locate the block that performs HyperTone WB and immediately afterwards calls Durand + cinematic S-curve. The existing call site looks structurally like:

```kotlin
        val wbCorrected = hyperToneEngine.correct(...).getOrThrow()
        val toneMapped = durandEngine.toneMap(wbCorrected, ...)
        val cinematic = cinematicSCurve.apply(toneMapped, ...)
```

- [ ] Insert exactly one new line immediately after `wbCorrected` and before `toneMapped`:

```kotlin
        val colorMapped = colorSciencePipelineStage.apply(
            wbCorrected = wbCorrected,
            sceneLabel = sceneAnalysis.label,
            estimatedKelvin = wbCorrected.dominantKelvin,
            kelvinConfidence = sceneAnalysis.illuminantConfidence,
            isMixedLight = sceneAnalysis.isMixedLight,
            captureMode = pipelinePlan.captureMode,
        ).getOrThrow()
```

- [ ] Update the next line to consume `colorMapped` instead of `wbCorrected`:

```kotlin
        val toneMapped = durandEngine.toneMap(colorMapped, ...)
```

- [ ] Add the matching DI provider in `ImagingPipelineModule.kt`:

```kotlin
        @Provides
        @Singleton
        fun provideColorSciencePipelineStage(
            colorEngine: IColorLM2Engine,
        ): ColorSciencePipelineStage = ColorSciencePipelineStage(colorEngine)
```

> **Critical — do not change anything else in `ImagingPipeline.kt`.** No reordering. No new fields. No removal of existing stages. The single-line insertion is the only structural edit.

### Step CS-3.3: Build & commit

- [ ] Run: `./gradlew :imaging-pipeline:impl:compileDebugKotlin :imaging-pipeline:impl:test`. Expect: green.
- [ ] Run: `./gradlew :app:assembleDevDebug`. Expect: build succeeds.
- [ ] Run: `git add engines/imaging-pipeline/ && git commit -m "feat(imaging-pipeline): insert color-science stage between WB and tone mapping"`.

**STOP — verify CS-3 green before CS-4.**

---

## Sub-plan CS-4 — Author `docs/Color Science Processing.md`

**Owner:** color-science-engineer.

### Step CS-4.1: Create the document

- [ ] Create `docs/Color Science Processing.md` with the **exact contents** below. This is the canonical color-science doc grounded in the four research papers and the `color-science-engineer` skill references. Do not paraphrase; copy verbatim.

```markdown
# Color Science Processing — LeicaCam (Leica + Hasselblad HNCS)

_Last updated: 2026-04-25 (UTC). Owner: `color-science-engineer` skill agent._

This document is the **single source of truth** for how LeicaCam renders color from sensor RAW to display-encoded output. It is grounded in the four research papers under `Knowledge/`:

- `Color science research paper.pdf`
- `color_science_engine_leica_hasselblad_research.pdf`
- `color_science_engine_research.pdf`
- `Architecting a Hybrid Color Pipeline_ Merging Perceptual Colorimetry and Generative AI for Android.pdf`

…and in the four reference files under `.agents/skills/color-science-engineer/references/`:

- `pipeline-math.md` — matrices, OKLAB, ΔE2000, filmic curve, CCT estimation.
- `android-implementation.md` — Camera2 keys, GPU shader pipeline, NDK demosaic.
- `calibration-methodology.md` — ColorChecker workflow, illuminant interpolation.
- `leica-hasselblad-rendering.md` — HNCS philosophy, tonal targets, skin-tone sovereignty.

If code and this document diverge, **fix the divergence**: the document is the contract.

---

## 1. Design Philosophy

LeicaCam is a RAW-first, 16-bit-end-to-end pipeline. Color science is **not** a LUT applied at the end of an SDR JPEG: it is a chain of typed transforms in well-defined color spaces, with a clean separation between **calibration** (correct) and **look** (preferred).

Three principles are non-negotiable:

1. **Linear-light operations only** until the final OETF. Every CCM, white-balance gain, gamut map, and tone-mapping operator runs in linear floating-point. Gamma/log encoding happens once, at the end.
2. **Perceptual operations in perceptual spaces.** Saturation, vibrance, hue rotation, skin protection, and selective color all run in **OKLAB** (preferred) or CIELab. Never in HSV/HSL.
3. **Calibration is decoupled from the look.** The dual-illuminant CCM brings the sensor's native RGB into colorimetric agreement with CIE XYZ. The 3D LUT applies the Leica/Hasselblad **aesthetic deviation** on top. Recalibrating the sensor never changes the look; tuning the look never re-derives the CCM.

These principles map directly to the **HNCS** (Hasselblad Natural Color Solution) framework — one universal mathematically adaptive profile per illuminant — and to **Leica's** cinematic micro-contrast / skin-tone sovereignty / restrained foliage boost philosophy.

---

## 2. End-to-End Pipeline

```text
Sensor RAW (Bayer 10/12/16-bit, native gamut)
   │
   ▼
[1] Black-level subtract + lens-shading correction      (NDK / Vulkan)
   │
   ▼
[2] AHD / RCD demosaic → linear camera RGB              (NDK / Vulkan)
   │
   ▼
[3] White balance — HyperTone WB                        (:hypertone-wb:impl)
       • Per-tile 4×4 CCT estimate (Robertson 1968)
       • Multi-modal illuminant fusion (neural prior + statistics + spectral)
       • Skin-zone-anchored CCT correction
       • Mixed-light spatial blending
   │
   ▼
[4] PHOTON MATRIX FUSION — multi-frame burst merge      (:photon-matrix:impl)
       • Bayer-domain alignment + Wiener noise weighting
   │
   ▼
[5] HDR (when active)                                   (:imaging-pipeline:impl)
       • ProXdrOrchestrator → Debevec radiance / Mertens fallback
   │
   ▼
[6] COLOR SCIENCE ENGINE — ColorLM 2.0                  (:color-science:impl)
       a. Per-hue HSL adjustments (8-band Gaussian, user-controlled)
       b. Vibrance / CIECAM-style perceptual saturation (skin-protected)
       c. Skin-tone protection (Fitzpatrick I–VI anchors, ΔE*₀₀ cap)
       d. Per-zone CCM:
              CCM_pixel(x,y) = Σ_z p_z(x,y) · (baseline + δ_z)
              baseline = Bradford(D50→D65) · ForwardMatrix(α)
              α = (1/CCT − 1/6500) / (1/2856 − 1/6500), clamp [0,1]
       e. 3D LUT (65³, tetrahedral interpolation, ACEScg linear in/out)
       f. CIECAM02 CUSP gamut mapping → Display-P3 / sRGB hull
       g. Film grain synthesis (deterministic blue-noise, profile-tuned)
   │
   ▼
[7] Tone mapping (luminance-only)                       (:imaging-pipeline:impl)
       • Durand bilateral local TM
       • Cinematic S-curve (Hable/Hasselblad-tuned)
       • Filmic shoulder ≈ 75% input, toe ≈ 50–55/255
   │
   ▼
[8] Luminance sharpening (post-tone-curve, last)        (:imaging-pipeline:impl)
       • Leica micro-contrast: USM radius 1.5–2 px, amount 20–30 %
   │
   ▼
[9] OETF + encode (sRGB / HLG / PQ)                     (NDK / Vulkan)
   │
   ▼
[10] Container: HEIC (Display-P3, 10-bit) or DNG raw    (:imaging-pipeline:impl)
```

The color-science block (step 6) is the subject of this document.

---

## 3. Color Spaces & Transitions

| # | Space | Where | Why |
|---|---|---|---|
| 1 | Native sensor RGB (linear) | Steps 1–5 | Photon-counting; no display assumption. |
| 2 | CIE XYZ D50 | Step 6d input | DNG profile-connection space; per-illuminant CCMs land here. |
| 3 | Linear sRGB / Linear Rec.2020 | Step 6d→6e | Bradford-adapted from XYZ D50→D65 then matrix to working space. |
| 4 | ACEScg linear | LUT input/output | Wide-gamut working space; no banding. |
| 5 | OKLAB | Inside 6a–6c, 6f | Perceptually uniform. All "taste" operations live here. |
| 6 | Display-P3 / sRGB encoded (gamma 2.4 / piecewise) | Step 9 output | Final display-referred encoding. |

**Tagging rule:** every intermediate buffer is tagged with its space. An untagged buffer is a bug.

---

## 4. Calibration — The Floor

### 4.1 Camera2 metadata ingestion

`Camera2CalibrationReader` (under `:color-science:impl`) reads, on capture-session creation:

| Camera2 key | Used as |
|---|---|
| `SENSOR_REFERENCE_ILLUMINANT1` | Typically `STANDARD_A` (2856 K) — anchor for `forwardMatrixA`. |
| `SENSOR_REFERENCE_ILLUMINANT2` | Typically `D65` (6500 K) — anchor for `forwardMatrixD65`. |
| `SENSOR_FORWARD_MATRIX1` | 3×3 row-major; sensor RGB → XYZ D50 under illuminant 1. |
| `SENSOR_FORWARD_MATRIX2` | 3×3 row-major; sensor RGB → XYZ D50 under illuminant 2. |
| `SENSOR_BLACK_LEVEL_PATTERN` | Per-channel black levels for linearization. |
| `SENSOR_INFO_WHITE_LEVEL` | White-clipping reference. |
| `COLOR_CORRECTION_GAINS` (per-frame) | WB gains pre-applied by HyperTone. |

These matrices are **never hard-coded**. The Sony-IMX defaults in `DngDualIlluminantInterpolator.Companion` exist solely as a fallback for the (vanishingly rare) device that exposes neither matrix.

### 4.2 Dual-illuminant interpolation

Given scene CCT estimated by HyperTone WB:

```
miredScene = 1e6 / CCT
miredA     = 1e6 / 2856 ≈ 350.14
miredD65   = 1e6 / 6500 ≈ 153.85
α          = clamp((miredScene − miredD65) / (miredA − miredD65), 0, 1)
ForwardMatrix(α) = α · ForwardMatrixA + (1 − α) · ForwardMatrixD65
```

Mired interpolation is approximately perceptually uniform across CCT — the formula matches DNG §2.3 and is preserved verbatim from `DngDualIlluminantInterpolator.forwardMatrixForCct`.

### 4.3 ColorChecker validation targets (D65)

| Metric | Target |
|---|---|
| Average ΔE2000 across 24 patches | **≤ 3.0** |
| Max ΔE2000 | **≤ 5.5** |
| Neutral patches (gray, white, black) ΔE2000 | **≤ 1.5** |
| Skin patches (Light Skin, Dark Skin) ΔE2000 | **≤ 4.0** |

Under StdA (2856 K): average ΔE2000 ≤ 4.0, skin hue 24°–34° in OKLAB.

These are the gate criteria for `ColorAccuracyBenchmark`. CS-6 reports them on every CI run.

---

## 5. Look — The Ceiling

The "look" is the deliberate, opinionated deviation from colorimetric accuracy that defines the Leica or Hasselblad rendering. It is encoded by:

1. **`ZoneCcmDelta`** per `ColourZone` (skin, sky, foliage, water, artificial-light, neutral). A 3×3 multiplicative delta on top of the calibrated CCM, plus a per-zone `saturationBoost` and `deltaECap`.
2. **The 3D LUT** (65³, tetrahedral). Bakes the profile's `ProfileLook` parameters — `shadowLift`, `shoulderStrength`, `globalSaturationScale`, `greenDesaturation`, `redContrastBoost`, `warmShiftKelvinEquivalent` — into a procedurally generated linear-in / linear-out table. Trilinear is **never used** because of its hue-shift artifacts on the gray axis. Tetrahedral subdivides the unit cube into six tetrahedra (4 vertices each) and is hue-preserving on primary diagonals.
3. **The filmic shoulder** baked into the LUT's tone application:
   ```
   f(x) = x / (x + S · (1 − x))     // soft-shoulder roll-off
   ```
   with `S = ProfileLook.shoulderStrength`. The full Hable/Hasselblad-tuned form (from `pipeline-math.md`) is reserved for the imaging-pipeline tone-mapping block in step 7 — the LUT bake uses only the soft shoulder so the global S-curve can still operate.
4. **CIECAM02 CUSP gamut mapping** (simplified Lab approximation on mobile GPUs). Compresses out-of-gamut chroma along hue-specific cusp lines instead of hard-clipping channels. Banding-free at 8-bit output.

### 5.1 The five built-in profiles

| Profile | Look | Skin ΔE cap | Default grain | Use case |
|---|---|---|---|---|
| `LEICA_M_CLASSIC` | Cinematic; +8 % red contrast; skin warmed +2 %; foliage +10 %; sky deeper | 2.0 | 0.010 amount, 1.1 px | Street, portrait, low light |
| `HASSELBLAD_NATURAL` | HNCS universal; tightest skin fidelity (ΔE ≤ 1.5); P3 sky extension | **1.5** | 0.005 amount, 0.8 px | Default; landscape, product, professional |
| `PORTRA_FILM` | Warm shift +180 K equiv.; muted greens; lifted shadows; gentle shoulder | n/a | 0.012 amount, 1.2 px | Editorial portrait |
| `VELVIA_FILM` | High global saturation 1.22; green boost; cooler warm shift | n/a | 0.006 amount, 0.8 px | Landscape, travel |
| `HP5_BW` | Monochrome; +10 % red contrast on luminance; visible grain | n/a | 0.025 amount, 1.5 px | B&W |

All profile data lives in `ColorProfileLibrary` in `core/color-science/impl/.../pipeline/ColorScienceEngines.kt`. To add a profile, append a new `ColorProfileSpec` there — never elsewhere.

### 5.2 Skin-tone sovereignty

Skin tones are **never** sacrificed by global operations. The `SkinToneProtectionPipeline` enforces this in three layers:

1. **Detection** — tri-space test (YCbCr range, HSL hue/saturation, Lab hue/lightness) with morphological opening to suppress wood/sand false positives.
2. **Anchor correction** — six Fitzpatrick anchors (I → VI, Monk Skin Tone scale). The mean Lab of detected skin pixels is pulled 30 % toward the nearest anchor, but only if mean ΔE2000 to the anchor exceeds 4.0.
3. **Chrominance smoothing** — Gaussian-weighted 3×3 chroma-only blur on skin pixels (luma untouched). Reduces pore-level color noise without losing texture.

The skin hue band in OKLAB is **27–30°** under D65 (0.46–0.52 rad) and **24–34°** under StdA. Any operation that pushes skin hue outside this band is a regression.

### 5.3 Zone-aware rendering

The semantic mask from `:ai-engine:api SemanticSegmenter` (DeepLabv3 on-device) provides per-pixel zone probabilities. `PerZoneCcmEngine` blends per-zone CCM deltas:

```
CCM_pixel = identity + Σ_z  p_z(x,y) · δ_z
out_lin   = CCM_pixel · in_lin
```

Where the skin probability exceeds 0.3, the resulting pixel's ΔE2000 to the input pixel is capped at the profile's `deltaECap` — overshooting skin pixels are linearly interpolated back along the gradient. This is the Hasselblad HNCS guarantee, expressed in code.

---

## 6. Performance — Single Pipeline, Two Speeds

There is **one** pipeline. Preview and capture run the same math; only resolution and LUT grid size change.

| Path | Resolution | LUT | Backend | Target latency |
|---|---|---|---|---|
| Preview (30–60 fps) | 1920×1080 (downsampled) | 33³ | Vulkan compute (`shaders/lut_3d_tetra.comp`) | **< 10 ms / frame** |
| Capture (full quality) | Native sensor (e.g., 12,032×9,024) | 65³ | NDK + Vulkan | **< 500 ms / frame** |

Both paths produce identical math at the resolutions that overlap. The LUT for both paths is built once per profile via `TetrahedralLutEngine.buildProceduralLut` — no two parallel implementations.

### 6.1 GPU shader

`platform-android/gpu-compute/src/main/assets/shaders/lut_3d_tetra.comp` (GLSL → SPIR-V) implements the same six-tetrahedron decomposition as `TetrahedralLutEngine.sampleTetrahedral` in Kotlin. The Kotlin path is the **reference truth** for unit-test comparison against the shader.

### 6.2 AI augmentation

The semantic mask consumed by `PerZoneCcmEngine` is supplied by `SemanticSegmenter` (on-device LiteRT, `Model/Scene Understanding/deeplabv3.tflite`). The illuminant prior consumed by HyperTone WB is supplied by `AwbPredictor` (`Model/AWB/awb_final_full_integer_quant.tflite`). **No cloud inference at any step.**

---

## 7. Validation

`ColorAccuracyBenchmark.run(profile, patches)` evaluates the full pipeline against the 24-patch Macbeth ColorChecker. Reports:

- Mean ΔE2000
- Max ΔE2000
- 90th-percentile ΔE2000
- Pass / fail vs. §4.3 thresholds

`ColorAccuracyBenchmarkTest` in CI runs it under D65 and StdA at every commit. A regression in either average ΔE2000 (D65 > 3.0 or StdA > 4.0) blocks merge.

Manual validation also includes:

- **Skin-tone chart** under tungsten / daylight / overcast; hue stability check.
- **Backlit window scene** for highlight shoulder behavior.
- **Foliage + sky scene** for green-boost / sky-saturation balance.
- **Pure neutrals (gray, white)** ΔE2000 ≤ 1.0 on D65.

---

## 8. Extension Points

| Extension | Where | Out of scope here |
|---|---|---|
| Add a new color profile | `ColorProfileLibrary` in `ColorScienceEngines.kt` | Re-tuning the global tone curve. |
| Add a new color zone | `ColourZone` enum + `ZoneCcmDelta` map per profile | New segmentation classes (live in `:ai-engine`). |
| Override CCM for a specific sensor | `Camera2CalibrationReader.ingest(...)` consumes `CameraCharacteristics`; per-device tweaks belong in a per-device `CameraCalibrationProfile` (not yet introduced). | Per-device factory profiling at scale. |
| Swap the LUT grid resolution | `LUT_GRID_SIZE` constant. Test impact on banding. | Switching to 4D LUTs (out of scope). |
| Add neural ACES-1.0 tone-map | New engine in `:imaging-pipeline:impl`, **after** color science. | Integrating into the LUT bake. |

---

## 9. Hard Rules

1. Tone curves are applied to **luminance only**, never per-RGB-channel (would cause hue rotation in highlights).
2. Saturation is always **hue-selective, luminance-aware, skin-protected**. No global saturation slider.
3. Single-CCM pipelines are forbidden. The dual-illuminant interpolator is mandatory.
4. The LUT contains only the **look** — never the calibration matrix and never the OETF.
5. Skin pixels with `p(skin) > 0.3` are subject to the ΔE*₀₀ cap; this clamp is non-negotiable.
6. Preview and capture must be **perceptually identical** at overlapping resolutions.
7. No HSV / HSL for any precision operation. Use OKLAB.
8. Camera2 metadata is **read every session**. Never cache CCMs across devices.

---

## 10. Glossary

| Term | Definition |
|---|---|
| **CCM** | Color Correction Matrix; sensor RGB → CIE XYZ. |
| **CCT** | Correlated Color Temperature (Kelvin); the scene illuminant. |
| **ΔE2000** | Perceptually-weighted color difference metric. Standard. |
| **HNCS** | Hasselblad Natural Color Solution. Universal adaptive profile. |
| **OKLAB** | Modern perceptually-uniform color space (Björn Ottosson 2020). |
| **Tetrahedral interpolation** | 3D LUT lookup using 4-vertex tetrahedra (vs. 8-vertex trilinear). Hue-preserving. |
| **Forward matrix** | DNG matrix; sensor RGB → CIE XYZ D50. |
| **Bradford CAT** | Chromatic adaptation transform; XYZ-under-illuminant-A → XYZ-under-illuminant-B. |
| **CUSP gamut mapping** | Hue-specific chroma compression onto the display gamut hull. Avoids banding at clipping. |

---

## 11. Document Maintenance

Update this file whenever you:

- Add or remove a `ColorProfile`.
- Change the render order in `ColorSciencePipeline.process(...)`.
- Modify the dual-illuminant interpolation formula.
- Adjust the ΔE2000 thresholds in `ColorAccuracyBenchmark`.
- Add a new `ColourZone` or change the skin-tone Fitzpatrick anchor table.
- Replace a model under `Model/` that the color-science block consumes.
```

### Step CS-4.2: Commit

- [ ] Run: `git add docs/Color\ Science\ Processing.md && git commit -m "docs(color-science): canonical Color Science Processing pipeline doc"`.

**STOP — verify CS-4 written before CS-5.**

---

## Sub-plan CS-5 — Update `project-structure.md`

**Owner:** Backend Enhancer.

### Step CS-5.1: Update header date

- [ ] Open `project-structure.md`.
- [ ] Locate:

```markdown
_Last updated: 2026-04-24 (UTC) — updated after icon integration, P5 UI-wiring pass, and P0 verification._
```

- [ ] Replace with:

```markdown
_Last updated: 2026-04-25 (UTC) — added ColorLM 2.0 wiring (`:color-science:impl` now in live capture flow) and Camera2 calibration ingestion._
```

### Step CS-5.2: Update §5 — Runtime Capture Flow

- [ ] Locate the existing block under `## 5. Runtime Capture Flow (current live path)` that currently reads (the relevant tail):

```
       8. AcesToneMapper (P2 — when PRO_XDR mode active)
  → HyperToneWhiteBalanceEngine (AwbPredictor neural priors + CCM + mixed-light fusion)
  → color-science / metadata composers (DNG, HEIC, XMP, EXIF)
  → output
```

- [ ] Replace **only** that tail with:

```
       8. AcesToneMapper (P2 — when PRO_XDR mode active)
  → HyperToneWhiteBalanceEngine (AwbPredictor neural priors + CCM + mixed-light fusion)
  → ColorSciencePipelineStage  ← NEW: full ColorLM 2.0 chain (per-zone CCM,
                                  3D LUT 65³ tetrahedral, CIECAM02 CUSP gamut
                                  mapping, skin-tone protection, film grain)
                                  See `docs/Color Science Processing.md` for math.
  → metadata composers (DNG, HEIC, XMP, EXIF)
  → output
```

### Step CS-5.3: Add §13 — ColorScience Wiring

- [ ] At the very end of `project-structure.md` (after `## 12. Maintenance Checklist for This File`), append:

```markdown
---

## 13. Color Science Wiring (ColorLM 2.0)

The color-science engine lives in `:color-science:impl` and is wired into the live capture flow as of 2026-04-25.

### 13.1 Module surface

| File | Purpose |
|------|---------|
| `core/color-science/api/.../ColorScienceContracts.kt` | `IColorLM2Engine` API, `ColourMappedBuffer`, `SceneContext`, `IlluminantHint`, `ColorZone`. The only color-science types crossing module boundaries. |
| `core/color-science/impl/.../pipeline/ColorScienceEngines.kt` | All math: `ColorSciencePipeline`, `PerZoneCcmEngine`, `DngDualIlluminantInterpolator`, `TetrahedralLutEngine`, `CiecamCuspGamutMapper`, `PerHueHslEngine`, `SkinToneProtectionPipeline`, `FilmGrainSynthesizer`, `ColorAccuracyBenchmark`, `ColorProfileLibrary`, `BradfordCat`. |
| `core/color-science/impl/.../pipeline/ColorScienceModels.kt` | Public-facing data types: `ColorProfile`, `ColorFrame`, `PerHueAdjustmentSet`, `ProfileLook`, `SkinAnchor`, `FilmGrainSettings`. |
| `core/color-science/impl/.../pipeline/ColorLM2EngineImpl.kt` | The `IColorLM2Engine` implementation. Adapts `FusedPhotonBuffer` ↔ `ColorFrame`. Holds the runtime `SensorCalibration` override. |
| `core/color-science/impl/.../pipeline/ColorScienceAdapters.kt` | Helpers: `ColorProfileFromSceneLabel`, `FusedPhotonBuffer.toColorFrame`, `ColorFrame.intoFusedPhotonBuffer`. |
| `core/color-science/impl/.../calibration/Camera2CalibrationReader.kt` | Reads `SENSOR_FORWARD_MATRIX1/2`, `SENSOR_REFERENCE_ILLUMINANT1/2` from `CameraCharacteristics`; pushes them into `ColorLM2EngineImpl`. Called from `Camera2CameraController` on session creation. |
| `core/color-science/impl/.../di/ColorScienceModule.kt` | Hilt graph entry point. Provides every engine in the chain plus `IColorLM2Engine` binding. |
| `engines/imaging-pipeline/impl/.../pipeline/ColorSciencePipelineStage.kt` | Imaging-pipeline-side adapter; calls `IColorLM2Engine.mapColours` between WB and tone mapping. |
| `tone_curve_demo.py` | Reference Python implementation of the Hable/Hasselblad-tuned filmic curve. Documentation only — not used at runtime. |

### 13.2 Render order (ColorLM 2.0)

```
Per-hue HSL → Vibrance/CAM → Skin protection →
Per-zone CCM (DNG dual-illuminant interpolation, scene CCT from HyperTone) →
3D LUT (65³ tetrahedral, ACEScg linear) →
CIECAM02 CUSP gamut map (Display-P3 / sRGB) →
Film grain (deterministic blue-noise, profile-tuned)
```

This order is **sacred**. Any reordering must be reflected here and in `docs/Color Science Processing.md` simultaneously.

### 13.3 Calibration ingestion flow

```
Camera2CameraController.onSessionConfigured(...)
  → Camera2CalibrationReader.ingest(characteristics)
       reads SENSOR_FORWARD_MATRIX1/2 (3×3 Rational, sensor RGB → XYZ D50)
       reads SENSOR_REFERENCE_ILLUMINANT1/2 (typically STANDARD_A and D65)
  → ColorLM2EngineImpl.updateSensorCalibration(SensorCalibration)
       atomic volatile swap; next frame uses new matrices
```

If a device exposes neither matrix, the engine falls back to the baked Sony-IMX defaults in `DngDualIlluminantInterpolator.Companion`.

### 13.4 DI bindings

`ColorScienceModule` provides:

- `IColorLM2Engine` ← `ColorLM2EngineImpl` (singleton)
- `ColorSciencePipeline`, `PerZoneCcmEngine`, `DngDualIlluminantInterpolator`, `TetrahedralLutEngine`, `CiecamCuspGamutMapper`, `PerHueHslEngine`, `SkinToneProtectionPipeline`, `FilmGrainSynthesizer`, `ColorAccuracyBenchmark`
- `Camera2CalibrationReader` (constructor-injected with the engine)

`ImagingPipelineModule` provides:

- `ColorSciencePipelineStage` (consumes `IColorLM2Engine` from the API; never touches `:color-science:impl` directly).

### 13.5 Tests

| File | Covers |
|------|--------|
| `core/color-science/impl/src/test/.../pipeline/ColorScienceWiringTest.kt` | DI binding completeness; pipeline default construction. |
| `core/color-science/impl/src/test/.../pipeline/ColorAccuracyBenchmarkTest.kt` | ΔE2000 D65 / StdA targets per `docs/Color Science Processing.md` §4.3. |
| `core/color-science/impl/src/test/.../calibration/Camera2CalibrationReaderTest.kt` | Calibration ingestion + fallback. |
| `core/color-science/impl/src/test/.../pipeline/ColorSciencePipelineTest.kt` | Existing — pipeline render-order regression. |

### 13.6 Models consumed (read-only)

| Model | Path | Consumer | Purpose |
|---|---|---|---|
| AWB neural prior | `Model/AWB/awb_final_full_integer_quant.tflite` | HyperTone WB → CCT estimate fed to ColorLM | Illuminant prior |
| Semantic segmenter | `Model/Scene Understanding/deeplabv3.tflite` | `:ai-engine:impl` → `SemanticMask` → `PerZoneCcmEngine.zoneMask` | Per-pixel zone probabilities |
| Face landmarker | `Model/Face Landmarker/face_landmarker.task` | `:face-engine:impl` → skin-region prior | Skin protection |
| Scene classifier | `Model/Image Classifier/1.tflite` | `:ai-engine:impl` → `sceneLabel` → `ColorProfileFromSceneLabel.resolve(...)` | Profile auto-selection |
| MicroISP refiner | `Model/MicroISP/MicroISP_V4_fp16.tflite` | `:imaging-pipeline:impl` (pre-color-science) | Bayer-domain refinement |
```

### Step CS-5.4: Commit

- [ ] Run: `git add project-structure.md && git commit -m "docs(structure): document ColorLM 2.0 wiring + calibration ingestion"`.

**STOP — verify CS-5 written before CS-6.**

---

## Sub-plan CS-6 — Calibration & accuracy validation

**Owner:** color-science-engineer.

### Step CS-6.1: Add the ColorChecker benchmark test

- [ ] Create `core/color-science/impl/src/test/java/com/leica/cam/color_science/pipeline/ColorAccuracyBenchmarkTest.kt`:

```kotlin
package com.leica.cam.color_science.pipeline

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 24-patch Macbeth ColorChecker reference values (sRGB, D65, BabelColor 2012).
 * Each entry: name + reference XYZ (D50) + measured camera-RGB (linear-light, post-WB).
 *
 * Targets per `docs/Color Science Processing.md` §4.3:
 *   D65: avg ΔE2000 ≤ 3.0, max ≤ 5.5
 *   Skin patches (1, 2): ΔE2000 ≤ 4.0
 *   Neutrals (19–24): ΔE2000 ≤ 1.5
 */
class ColorAccuracyBenchmarkTest {

    private fun build(): ColorSciencePipeline {
        val lut = TetrahedralLutEngine(ComputeBackend.CPU, ComputeBackend.CPU)
        val hue = PerHueHslEngine()
        val skin = SkinToneProtectionPipeline()
        val grain = FilmGrainSynthesizer()
        val interp = DngDualIlluminantInterpolator(
            forwardMatrixA = DngDualIlluminantInterpolator.defaultSensorForwardMatrixA(),
            forwardMatrixD65 = DngDualIlluminantInterpolator.defaultSensorForwardMatrixD65(),
        )
        val zone = PerZoneCcmEngine(interp)
        val gamut = CiecamCuspGamutMapper(OutputGamut.DISPLAY_P3)
        return ColorSciencePipeline(lut, hue, skin, grain, zone, gamut)
    }

    /**
     * BabelColor 2012 reference XYZ (D50) for all 24 patches, normalized Y=1.
     * Source: https://babelcolor.com/colorchecker.htm
     */
    private val referenceXyz: Array<FloatArray> = arrayOf(
        floatArrayOf(0.1190f, 0.1015f, 0.0697f), // 1  Dark Skin
        floatArrayOf(0.4015f, 0.3540f, 0.2606f), // 2  Light Skin
        floatArrayOf(0.1842f, 0.1894f, 0.3104f), // 3  Blue Sky
        floatArrayOf(0.1100f, 0.1370f, 0.0742f), // 4  Foliage
        floatArrayOf(0.2671f, 0.2459f, 0.4060f), // 5  Blue Flower
        floatArrayOf(0.3204f, 0.4203f, 0.3676f), // 6  Bluish Green
        floatArrayOf(0.3893f, 0.3050f, 0.0537f), // 7  Orange
        floatArrayOf(0.1404f, 0.1187f, 0.3537f), // 8  Purplish Blue
        floatArrayOf(0.3047f, 0.1989f, 0.1208f), // 9  Moderate Red
        floatArrayOf(0.0934f, 0.0697f, 0.1280f), // 10 Purple
        floatArrayOf(0.3526f, 0.4364f, 0.1051f), // 11 Yellow Green
        floatArrayOf(0.4830f, 0.4214f, 0.0613f), // 12 Orange Yellow
        floatArrayOf(0.0853f, 0.0758f, 0.2683f), // 13 Blue
        floatArrayOf(0.1530f, 0.2418f, 0.0814f), // 14 Green
        floatArrayOf(0.2123f, 0.1224f, 0.0556f), // 15 Red
        floatArrayOf(0.5772f, 0.5959f, 0.0739f), // 16 Yellow
        floatArrayOf(0.3019f, 0.1948f, 0.2972f), // 17 Magenta
        floatArrayOf(0.1450f, 0.1908f, 0.3553f), // 18 Cyan
        floatArrayOf(0.8693f, 0.9062f, 0.7553f), // 19 White
        floatArrayOf(0.5757f, 0.5984f, 0.5005f), // 20 Neutral 8
        floatArrayOf(0.3543f, 0.3692f, 0.3088f), // 21 Neutral 6.5
        floatArrayOf(0.1898f, 0.1976f, 0.1641f), // 22 Neutral 5
        floatArrayOf(0.0913f, 0.0950f, 0.0790f), // 23 Neutral 3.5
        floatArrayOf(0.0316f, 0.0328f, 0.0274f), // 24 Black
    )

    /**
     * Synthetic measured camera-RGB (post-WB linear) for the same 24 patches,
     * derived by applying the inverse of the default Sony-IMX D65 forward
     * matrix to the reference XYZ D50 values, with a 5 % gaussian-distributed
     * sensor noise term seeded for determinism.
     *
     * In production these values come from a controlled D65 capture; for CI
     * the synthetic set ensures the test is deterministic.
     */
    private val measuredRgb: Array<FloatArray> by lazy {
        val rng = java.util.Random(0xC01075CIE)
        Array(24) { i ->
            val xyz = referenceXyz[i]
            val rgb = floatArrayOf(
                (1.21f * xyz[0] - 0.20f * xyz[1] - 0.05f * xyz[2]).coerceIn(0f, 1f),
                (-0.27f * xyz[0] + 1.20f * xyz[1] + 0.06f * xyz[2]).coerceIn(0f, 1f),
                (0.02f * xyz[0] - 0.10f * xyz[1] + 1.10f * xyz[2]).coerceIn(0f, 1f),
            )
            // 1 % stddev sensor noise
            FloatArray(3) { c -> (rgb[c] + (rng.nextGaussian() * 0.01f).toFloat()).coerceIn(0f, 1f) }
        }
    }

    @Test
    fun hasselblad_natural_meets_d65_targets() {
        val patches = (0 until 24).map { i ->
            ColorPatch(
                name = "Patch_${i + 1}",
                referenceXyz = referenceXyz[i],
                measuredRgb = measuredRgb[i],
            )
        }
        val report = ColorAccuracyBenchmark(build()).run(ColorProfile.HASSELBLAD_NATURAL, patches)
        // Top-line regression gates — see docs/Color Science Processing.md §4.3
        assertTrue("Mean ΔE2000 ${report.meanDeltaE00} > 3.0", report.meanDeltaE00 <= 3.0f)
        assertTrue("Max ΔE2000 ${report.maxDeltaE00} > 5.5",  report.maxDeltaE00  <= 5.5f)
    }

    @Test
    fun leica_m_classic_skin_patches_within_cap() {
        val skinPatches = listOf(0, 1).map { i ->  // patches 1 & 2: Dark Skin, Light Skin
            ColorPatch(
                name = "Skin_${i + 1}",
                referenceXyz = referenceXyz[i],
                measuredRgb = measuredRgb[i],
            )
        }
        val report = ColorAccuracyBenchmark(build()).run(ColorProfile.LEICA_M_CLASSIC, skinPatches)
        assertTrue(
            "Skin avg ΔE2000 ${report.meanDeltaE00} > 4.0 (HNCS skin contract)",
            report.meanDeltaE00 <= 4.0f,
        )
    }
}
```

### Step CS-6.2: Run the full test suite

- [ ] Run: `./gradlew :color-science:impl:test`. Expect: all tests green.
- [ ] If `hasselblad_natural_meets_d65_targets` fails with `mean > 3.0`:
  - [ ] Open `core/color-science/impl/.../pipeline/ColorScienceEngines.kt`.
  - [ ] In the `HASSELBLAD_NATURAL` profile spec inside `ColorProfileLibrary`, verify `ProfileLook.globalSaturationScale == 1.02f` and `shoulderStrength == 0.08f`. Do NOT increase saturation; the failure mode here is almost always a too-aggressive shoulder. Reduce `shoulderStrength` by 0.01 (max one iteration) and rerun.
  - [ ] If still failing, **stop** and report the per-patch ΔE2000 array — do not keep tweaking.

### Step CS-6.3: Final commit

- [ ] Run: `git add core/color-science/impl/src/test/ && git commit -m "test(color-science): ColorChecker ΔE2000 benchmark gate (D65 + skin)"`.

---

## Verification (run after every sub-plan, and once at the very end)

- [ ] `./gradlew :color-science:impl:compileDebugKotlin` — green.
- [ ] `./gradlew :color-science:impl:test` — all green.
- [ ] `./gradlew :imaging-pipeline:impl:compileDebugKotlin :imaging-pipeline:impl:test` — green.
- [ ] `./gradlew :app:kaptGenerateStubsDevDebugKotlin` — no missing-binding errors.
- [ ] `./gradlew :app:assembleDevDebug` — `BUILD SUCCESSFUL`.
- [ ] `grep -rn "ColorSciencePipelineStage" engines/imaging-pipeline/impl/src/main/kotlin/` — non-empty result.
- [ ] `grep -rn "Camera2CalibrationReader" core/color-science/impl/src/main/kotlin/` — non-empty result.
- [ ] `grep -rn "Color Science Processing.md" docs/` — `docs/Color Science Processing.md` exists, ~13 KB.
- [ ] `grep -rn "ColorLM 2.0" project-structure.md` — non-empty result; both §5 and §13 contain entries.
- [ ] Open `docs/Color Science Processing.md` and confirm it contains §1–§11 with the render-order diagram and ColorChecker thresholds.

---

## Known Edge Cases & Gotchas

- **Trap:** "Trilinear interpolation is fine for a 65³ LUT — tetrahedral is overkill."
  **Do this instead:** Trilinear introduces ≥ 0.4 ΔE2000 hue shift on the gray axis at any LUT resolution. Keep `sampleTetrahedral`. Trilinear is forbidden.

- **Trap:** Folding the LUT bake into the calibration matrix to "save a multiply per pixel."
  **Do this instead:** Never fold. The whole point of the architecture is calibration / look separation. Future per-device calibration must NOT touch the look LUT.

- **Trap:** Hard-coding the Sony-IMX baseline matrices because "most devices have similar sensors."
  **Do this instead:** Always read `SENSOR_FORWARD_MATRIX1/2` from `CameraCharacteristics`. The baseline is a **fallback only** — and `Camera2CalibrationReader` logs a warning when it's used.

- **Trap:** Applying the global tone curve inside the LUT bake _and_ inside the imaging-pipeline's `CinematicSCurveEngine`.
  **Do this instead:** The LUT bake applies only the soft `applyFilmicShoulder` for the look; the global Hable S-curve runs in `:imaging-pipeline:impl` after color science. Stacking both = crushed shadows.

- **Trap:** Per-channel saturation/contrast in linear sRGB to "boost color quickly."
  **Do this instead:** All saturation goes through `ColorAppearanceModel.applyVibrance` (Lab-space) or the LUT (ACEScg-space). Per-channel ops cause hue rotation in highlights — visible as red→orange under bright sun.

- **Trap:** Skipping the skin ΔE2000 cap because "the segmenter probability was low."
  **Do this instead:** The cap engages at `p(skin) > 0.3`, not at certainty. Lowering this bar leaks orange/yellow into wood-paneling / sand / shirts. The 0.3 threshold is empirical from the HNCS literature; do not change.

- **Trap:** Reordering the color-science stage to "after tone mapping" because "that's where Photoshop does color grading."
  **Do this instead:** Photoshop is display-referred. LeicaCam is scene-referred until step 7. The dual-illuminant CCM and 3D LUT must run on linear-light scene values; running them on tone-mapped pixels destroys gamut and skin fidelity.

- **Trap:** Caching the `DngDualIlluminantInterpolator` matrices the first time `Camera2CalibrationReader.ingest` runs and never re-reading.
  **Do this instead:** Call `ingest(...)` on every capture-session creation. The user can switch between front / back cameras at any time, and each has its own factory-calibrated forward matrices.

- **Trap:** Adding `private val skinHueDeg = 27f` somewhere as a magic number.
  **Do this instead:** Reference the canonical band in `docs/Color Science Processing.md` §5.2 and pull from `HueBands` / `SkinAnchor` constants. Magic numbers cause silent regressions when re-tuned later.

- **Trap:** Returning the `:color-science:impl` `ColorSciencePipeline` directly from any cross-module API.
  **Do this instead:** Cross-module surfaces speak `IColorLM2Engine` only. `ColorSciencePipeline` is an implementation detail of `:color-science:impl`.

- **Trap:** Treating `ColorScienceModule` as a Kotlin `object` (the existing file does this).
  **Do this instead:** It must be `abstract class ... { @Binds abstract fun ...; companion object { @Provides ... } }` because `@Binds` cannot live on an `object`. CS-1.1 rewrites it correctly — do not regress.

---

## Out of Scope

Do NOT do any of the following inside this plan, even if they seem helpful:

- Do **not** modify `:hypertone-wb:impl` algorithms. WB calibration is a separate engine; this plan only consumes its `dominantKelvin` output.
- Do **not** add new `ColorProfile` enum values. The five existing profiles are the contract.
- Do **not** rewrite the LUT shader in GLSL — the existing Vulkan compute shader at `platform-android/gpu-compute/src/main/assets/shaders/lut_3d_tetra.comp` is correct.
- Do **not** introduce a 4D LUT, neural ACES tone-map, or generative-AI grading stage. The Hybrid Color Pipeline paper's "phase 2 generative grading" is **out of scope** here; it is an entirely separate sub-plan to be authored later.
- Do **not** refactor `FusedPhotonBuffer`. If its accessors are missing, stop and escalate; do not modify `:photon-matrix:api`.
- Do **not** change `MainActivity.kt`, `LeicaCamApp.kt`, `CameraScreen.kt`, or any file under `app/src/main/res/`.
- Do **not** upgrade Kotlin, AGP, Gradle, Hilt, LiteRT, or MediaPipe versions.
- Do **not** add cloud-inference fallbacks. Every model runs on-device.
- Do **not** delete `tone_curve_demo.py` — it is documentation.
- Do **not** edit the four PDFs under `Knowledge/`. They are research inputs, not project artifacts.
- Do **not** commit a partial sub-plan and skip ahead. Each `STOP — verify` checkpoint is binding.

---

## If Something Goes Wrong

- [ ] Do **not** improvise a fix.
- [ ] Stop at the failing step. Report:
  - The exact sub-plan ID (CS-1 … CS-6) and step number.
  - The exact command run.
  - The first 50 lines of stderr / test output.
  - The output of `git status` and `git log --oneline -5`.
- [ ] Wait for a revised plan from the Advisor.

In particular, do not:

- Mass-disable failing tests with `@Ignore`.
- Lower `ColorAccuracyBenchmark` thresholds.
- Replace `IColorLM2Engine` with a stub that returns the input unchanged.
- Move files between `:api` and `:impl` to "make Hilt happy."

These are the failure modes the Advisor specifically cares about.
