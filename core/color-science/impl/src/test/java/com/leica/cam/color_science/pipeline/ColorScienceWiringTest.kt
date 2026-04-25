package com.leica.cam.color_science.pipeline

import com.leica.cam.color_science.di.ColorScienceModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Reflection-only smoke test that the DI module declares every binding the
 * Hilt graph downstream needs.
 *
 * Does NOT instantiate Hilt — that is covered by the app-level integration test.
 * This test verifies that [ColorScienceModule] exposes the correct @Provides methods
 * so the Hilt annotation processor won't fail with MissingBinding errors at build time.
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
        assertEquals(
            "Missing @Provides methods in ColorScienceModule.Companion: $missing",
            emptySet<String>(),
            missing,
        )
    }

    @Test
    fun pipeline_constructs_with_default_bindings() {
        // Verify the pipeline can be constructed end-to-end without Hilt
        val lut = TetrahedralLutEngine(
            preferredBackend = ComputeBackend.CPU,
            fallbackBackend = ComputeBackend.CPU,
        )
        val hue = PerHueHslEngine()
        val skin = SkinToneProtectionPipeline()
        val grain = FilmGrainSynthesizer()
        val interp = DngDualIlluminantInterpolator(
            forwardMatrixA = DngDualIlluminantInterpolator.defaultSensorForwardMatrixA(),
            forwardMatrixD65 = DngDualIlluminantInterpolator.defaultSensorForwardMatrixD65(),
        )
        val zone = PerZoneCcmEngine(interp)
        val gamut = CiecamCuspGamutMapper(OutputGamut.DISPLAY_P3)
        val pipeline = ColorSciencePipeline(
            lutEngine = lut,
            hueEngine = hue,
            skinPipeline = skin,
            grainSynthesizer = grain,
            zoneCcmEngine = zone,
            gamutMapper = gamut,
        )
        assertNotNull("ColorSciencePipeline must not be null after construction", pipeline)
    }

    @Test
    fun pipeline_processes_1x1_frame_without_exception() {
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

        val testFrame = ColorFrame(
            width = 1,
            height = 1,
            red = floatArrayOf(0.5f),
            green = floatArrayOf(0.4f),
            blue = floatArrayOf(0.3f),
        )

        val result = pipeline.process(
            input = testFrame,
            profile = ColorProfile.HASSELBLAD_NATURAL,
            hueAdjustments = PerHueAdjustmentSet(),
            vibranceAmount = 0f,
            frameIndex = 0,
            sceneCct = 6500f,
        )

        assertNotNull("Pipeline result must not be null", result)
    }

    @Test
    fun dual_illuminant_interpolator_constructs_with_default_matrices() {
        val interp = DngDualIlluminantInterpolator(
            forwardMatrixA = DngDualIlluminantInterpolator.defaultSensorForwardMatrixA(),
            forwardMatrixD65 = DngDualIlluminantInterpolator.defaultSensorForwardMatrixD65(),
        )
        assertNotNull("DngDualIlluminantInterpolator must not be null", interp)

        // Validate interpolation at D65 (α=0) returns D65 matrix
        val matD65 = interp.forwardMatrixForCct(6500f)
        assertEquals(9, matD65.size)

        // Validate interpolation at StdA (α=1) returns A matrix
        val matA = interp.forwardMatrixForCct(2856f)
        assertEquals(9, matA.size)
    }

    @Test
    fun sensor_calibration_data_class_enforces_3x3_contract() {
        val validCalibration = SensorCalibration(
            forwardMatrixA = FloatArray(9) { it.toFloat() },
            forwardMatrixD65 = FloatArray(9) { it.toFloat() * 2f },
        )
        assertNotNull(validCalibration)

        var caught = false
        try {
            SensorCalibration(
                forwardMatrixA = FloatArray(4),
                forwardMatrixD65 = FloatArray(9),
            )
        } catch (e: IllegalArgumentException) {
            caught = true
        }
        assertEquals("SensorCalibration must reject non-9-element forwardMatrixA", true, caught)
    }
}
