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
 * Reads per-device DNG dual-illuminant color calibration from [CameraCharacteristics]
 * and pushes the result into [ColorLM2EngineImpl].
 *
 * ## Why this is needed
 *
 * Android exposes per-device sensor color calibration via the DNG metadata model:
 *   - `SENSOR_REFERENCE_ILLUMINANT1/2` — the two illuminant anchors (typically
 *     `STANDARD_A` = 2856 K and `D65` = 6500 K on flagship sensors).
 *   - `SENSOR_FORWARD_MATRIX1/2` — 3×3 [ColorSpaceTransform] mapping the sensor's
 *     native RGB into CIE XYZ D50 (the DNG profile-connection space).
 *
 * These matrices are **factory-calibrated per device**. Using the correct matrices
 * instead of the generic Sony-IMX defaults gives ΔE2000 improvements of ~0.5–1.5
 * on the Macbeth ColorChecker D65 patches, and eliminates the warm/cool hue cast
 * that occurs when the sensor's spectral response deviates from the Sony baseline.
 *
 * ## Threading
 *
 * Call [ingest] from a background thread on capture-session creation.
 * The [ColorLM2EngineImpl.updateSensorCalibration] write is atomic (volatile).
 *
 * The Android recommendation is to call this once per **session** (not per frame),
 * because `CameraCharacteristics` is immutable for the lifetime of a camera device.
 * However, since the user may switch between front/back cameras during a session,
 * [ingest] must be called every time `onSessionConfigured` fires.
 *
 * ## Fallback behaviour
 *
 * If a device exposes neither `SENSOR_FORWARD_MATRIX1` nor `SENSOR_FORWARD_MATRIX2`,
 * [ingest] returns null and does **not** call [ColorLM2EngineImpl.updateSensorCalibration].
 * The engine falls back to the baked Sony-IMX defaults in
 * [com.leica.cam.color_science.pipeline.DngDualIlluminantInterpolator.Companion].
 *
 * If only one matrix is present, it is duplicated for both illuminants — this is
 * better than leaving the interpolator on the wrong default at the missing illuminant.
 *
 * @see com.leica.cam.color_science.pipeline.DngDualIlluminantInterpolator
 * @see com.leica.cam.color_science.pipeline.ColorLM2EngineImpl
 */
@Singleton
class Camera2CalibrationReader @Inject constructor(
    private val engine: ColorLM2EngineImpl,
) {

    /**
     * Read the dual-illuminant forward matrices from [characteristics] and push
     * them into [ColorLM2EngineImpl] via [ColorLM2EngineImpl.updateSensorCalibration].
     *
     * This method is a no-op if neither `SENSOR_FORWARD_MATRIX1` nor
     * `SENSOR_FORWARD_MATRIX2` is present — the engine retains its current
     * calibration (either a previous call's value or the Sony-IMX default).
     *
     * @param characteristics The [CameraCharacteristics] for the opened camera.
     * @return The resolved [SensorCalibration], or null if both matrices were missing.
     */
    fun ingest(characteristics: CameraCharacteristics): SensorCalibration? {
        val ill1 = characteristics.get(SENSOR_REFERENCE_ILLUMINANT1)
        val ill2 = characteristics.get(SENSOR_REFERENCE_ILLUMINANT2)
        val fm1: ColorSpaceTransform? = characteristics.get(SENSOR_FORWARD_MATRIX1)
        val fm2: ColorSpaceTransform? = characteristics.get(SENSOR_FORWARD_MATRIX2)

        if (fm1 == null && fm2 == null) {
            Logger.i(
                tag = TAG,
                message = "WARN: Device exposes no SENSOR_FORWARD_MATRIX* — " +
                    "falling back to Sony-IMX defaults. " +
                    "Camera accuracy may be reduced on this device.",
            )
            return null
        }

        // If only one matrix is present, duplicate it for the missing illuminant.
        // This is always better than using the wrong-device Sony-IMX default.
        val matrixA: FloatArray = fm1?.toFloatArray() ?: fm2!!.toFloatArray()
        val matrixD65: FloatArray = fm2?.toFloatArray() ?: fm1!!.toFloatArray()

        val calibration = SensorCalibration(
            forwardMatrixA = matrixA,
            forwardMatrixD65 = matrixD65,
        )
        engine.updateSensorCalibration(calibration)

        Logger.i(
            tag = TAG,
            message = buildString {
                append("Camera calibration ingested: ")
                append("ill1=$ill1 (${illuminantName(ill1?.toInt())}) ")
                append("ill2=$ill2 (${illuminantName(ill2?.toInt())}) ")
                if (fm1 == null) append("[FM1 missing — FM2 duplicated] ")
                if (fm2 == null) append("[FM2 missing — FM1 duplicated] ")
                append("matA=${matrixA.contentToString()} ")
                append("matD65=${matrixD65.contentToString()}")
            },
        )
        return calibration
    }

    /**
     * Convert a Camera2 [ColorSpaceTransform] (3×3 matrix of [Rational] values
     * in row-major order) into a row-major `FloatArray(9)`.
     *
     * **Android docs note:** [ColorSpaceTransform.getElement] is indexed as
     * `getElement(column, row)` — i.e. column-first. The output array uses
     * standard row-major convention: `out[row * 3 + col]`.
     *
     * @param this A [ColorSpaceTransform] from `CameraCharacteristics`.
     * @return A 9-element row-major float array representing the 3×3 matrix.
     */
    private fun ColorSpaceTransform.toFloatArray(): FloatArray {
        val out = FloatArray(9)
        for (row in 0 until 3) {
            for (col in 0 until 3) {
                // Android API: getElement(column, row) — note col-first ordering
                val rational: Rational = this.getElement(col, row)
                out[row * 3 + col] = rational.toFloat()
            }
        }
        return out
    }

    /** Human-readable name for a Camera2 illuminant integer constant. */
    private fun illuminantName(illuminant: Int?): String = when (illuminant) {
        null -> "null"
        1 -> "DAYLIGHT"
        2 -> "FLUORESCENT"
        3 -> "TUNGSTEN"
        4 -> "FLASH"
        9 -> "FINE_WEATHER"
        10 -> "CLOUDY_WEATHER"
        11 -> "SHADE"
        12 -> "DAYLIGHT_FLUORESCENT"
        13 -> "DAY_WHITE_FLUORESCENT"
        14 -> "COOL_WHITE_FLUORESCENT"
        15 -> "WHITE_FLUORESCENT"
        17 -> "STANDARD_A"  // 2856 K — most common for SENSOR_REFERENCE_ILLUMINANT1
        18 -> "STANDARD_B"
        19 -> "STANDARD_C"
        20 -> "D55"
        21 -> "D65"         // 6500 K — most common for SENSOR_REFERENCE_ILLUMINANT2
        22 -> "D75"
        23 -> "D50"
        24 -> "ISO_STUDIO_TUNGSTEN"
        255 -> "OTHER"
        else -> "UNKNOWN($illuminant)"
    }

    private companion object {
        private const val TAG = "Camera2CalibrationReader"
    }
}
