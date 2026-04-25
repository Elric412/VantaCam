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
 * The exposed binding is [IColorLM2Engine], declared in `:color-science:api`.
 * No `:impl` class is exported across modules.
 *
 * **Render order (sacred — never reorder):**
 * ```
 * Per-hue HSL → Vibrance/CAM → Skin protection →
 * Per-zone CCM (DNG dual-illuminant interpolation, scene CCT from HyperTone) →
 * 3D LUT (65³ tetrahedral, ACEScg linear in/out) →
 * CIECAM02 CUSP gamut map (Display-P3 / sRGB) →
 * Film grain (deterministic blue-noise, profile-tuned)
 * ```
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ColorScienceModule {

    /**
     * Bind [ColorLM2EngineImpl] as the production [IColorLM2Engine].
     * Singleton — the pipeline is stateless per-frame; calibration override is
     * held as a volatile field inside [ColorLM2EngineImpl].
     */
    @Binds
    @Singleton
    abstract fun bindColorLM2Engine(impl: ColorLM2EngineImpl): IColorLM2Engine

    companion object {

        @Provides
        @Singleton
        @Named("color_science_module")
        fun provideModuleName(): String = "color-science"

        /**
         * Tetrahedral LUT engine.
         *
         * Prefers the Vulkan compute backend (`shaders/lut_3d_tetra.comp`) for
         * preview frames; falls back to CPU when Vulkan is unavailable.
         * Both backends produce identical mathematical results — the Kotlin CPU
         * path is the reference truth for shader validation.
         */
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
         * [DngDualIlluminantInterpolator.Companion]. They are overridden at
         * runtime by `Camera2CalibrationReader` (CS-2) which reads
         * `SENSOR_FORWARD_MATRIX1` / `SENSOR_FORWARD_MATRIX2` from
         * `CameraCharacteristics` and reconfigures the singleton via
         * [ColorLM2EngineImpl.updateSensorCalibration].
         *
         * The fallback is only reached on devices that expose neither matrix —
         * extremely rare on Android `FULL` capability devices.
         */
        @Provides
        @Singleton
        fun provideDngDualIlluminantInterpolator(): DngDualIlluminantInterpolator =
            DngDualIlluminantInterpolator(
                forwardMatrixA = DngDualIlluminantInterpolator.defaultSensorForwardMatrixA(),
                forwardMatrixD65 = DngDualIlluminantInterpolator.defaultSensorForwardMatrixD65(),
            )

        /**
         * Per-zone CCM engine.
         *
         * Receives the [DngDualIlluminantInterpolator] singleton as its baseline.
         * At runtime, [ColorLM2EngineImpl.mapColours] can supply a per-call
         * override interpolator built from the device-specific calibration.
         */
        @Provides
        @Singleton
        fun providePerZoneCcmEngine(
            interpolator: DngDualIlluminantInterpolator,
        ): PerZoneCcmEngine = PerZoneCcmEngine(interpolator)

        /**
         * CIECAM02 CUSP gamut mapper.
         *
         * Targets Display-P3 by default for HEIC/JPEG when the device surface
         * supports it (SDK ≥ 26). The mapper handles sRGB fallback internally
         * via the [OutputGamut] parameter.
         */
        @Provides
        @Singleton
        fun provideCiecamCuspGamutMapper(): CiecamCuspGamutMapper =
            CiecamCuspGamutMapper(targetGamut = OutputGamut.DISPLAY_P3)

        /**
         * Main color-science pipeline.
         *
         * Constructor receives all sub-engines; this is the only place where
         * the render order is encoded — see [ColorSciencePipeline.process].
         */
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

        /**
         * ColorChecker ΔE2000 accuracy benchmark.
         *
         * Targets per `docs/Color Science Processing.md` §4.3:
         *   D65: mean ΔE2000 ≤ 3.0, max ≤ 5.5
         *   Skin patches: ΔE2000 ≤ 4.0
         */
        @Provides
        @Singleton
        fun provideColorAccuracyBenchmark(
            pipeline: ColorSciencePipeline,
        ): ColorAccuracyBenchmark = ColorAccuracyBenchmark(pipeline)
    }
}
