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
 * Adapts the `:color-science:impl` [ColorSciencePipeline] (which speaks the
 * internal [ColorFrame] type) to the cross-module `:color-science:api` contract
 * (which speaks [FusedPhotonBuffer]).
 *
 * **Render order — sacred, do NOT reorder:**
 * 1. Per-hue HSL (cosmetic user controls, 8-band Gaussian)
 * 2. Vibrance / CIECAM perceptual saturation (skin-protected)
 * 3. Skin-tone protection (Fitzpatrick I–VI anchors, ΔE*₀₀ cap)
 * 4. Per-zone CCM (dual-illuminant DNG forward matrix interpolation)
 * 5. 3D LUT (65³ tetrahedral, ACEScg linear in/out)
 * 6. CIECAM02 CUSP gamut mapping → Display-P3 / sRGB
 * 7. Film grain synthesis (profile-specific, deterministic)
 *
 * The [ColorProfile] used per capture is resolved from the scene label via
 * [ColorProfileFromSceneLabel.resolve], giving automatic profile selection based
 * on scene semantics (portrait → LEICA_M_CLASSIC, landscape → HASSELBLAD_NATURAL).
 *
 * **Calibration:** Default DNG forward matrices are the Sony-IMX fallback baked
 * into [DngDualIlluminantInterpolator.Companion]. Real device-specific matrices
 * are pushed via [updateSensorCalibration] on every capture-session creation by
 * `Camera2CalibrationReader` (CS-2).
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
     * read from `CameraCharacteristics`. Called by `Camera2CalibrationReader`
     * (CS-2) on capture-session creation.
     *
     * Thread-safe: the volatile assignment is atomic on JVM/ART; the next
     * call to [mapColours] will pick up the new calibration.
     */
    fun updateSensorCalibration(calibration: SensorCalibration) {
        calibrationOverride = calibration
        Logger.i(
            tag = TAG,
            message = "Sensor calibration updated: " +
                "A=${calibration.forwardMatrixA.contentToString()} " +
                "D65=${calibration.forwardMatrixD65.contentToString()}",
        )
    }

    /**
     * Execute the full ColorLM 2.0 pipeline on [fused].
     *
     * Pipeline position in the imaging flow:
     *   - Inputs: post-WB linear sensor RGB in [fused]
     *   - Outputs: [ColourMappedBuffer.Mapped] carrying Display-P3 linear RGB
     *   - Must run BEFORE the filmic tone curve (Durand + Hable S-curve)
     *
     * @param fused   The linear-light RGB buffer produced by HyperTone WB.
     * @param scene   Scene metadata: label, illuminant CCT, capture mode.
     */
    override suspend fun mapColours(
        fused: FusedPhotonBuffer,
        scene: SceneContext,
    ): LeicaResult<ColourMappedBuffer> {
        return try {
            // Step 1 — adopt the most recent sensor calibration if available.
            val activeInterpolator = calibrationOverride?.let { cal ->
                DngDualIlluminantInterpolator(
                    forwardMatrixA = cal.forwardMatrixA,
                    forwardMatrixD65 = cal.forwardMatrixD65,
                )
            } ?: interpolator

            // Step 2 — extract linear-RGB ColorFrame from the fused buffer.
            val input = fused.toColorFrame()

            // Step 3 — resolve color profile from the scene semantic label.
            val profile = ColorProfileFromSceneLabel.resolve(scene.sceneLabel)

            // Step 4 — run the pipeline with the active interpolator override.
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
                    val mappedFrame = result.value
                    val outBuffer = mappedFrame.intoFusedPhotonBuffer(fused)
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
            Logger.e(TAG, "mapColours failed with unhandled exception", throwable = t)
            LeicaResult.Failure.Pipeline(
                stage = PipelineStage.COLOR_TRANSFORM,
                message = "ColorLM2 pipeline failure: ${t.message}",
                cause = t,
            )
        }
    }

    private companion object {
        private const val TAG = "ColorLM2EngineImpl"
    }
}

/**
 * Sensor-calibrated DNG forward matrices (3×3 row-major, sensor RGB → XYZ D50).
 *
 * Populated by `Camera2CalibrationReader.ingest(CameraCharacteristics)` on every
 * capture-session creation. Pushed into [ColorLM2EngineImpl] via
 * [ColorLM2EngineImpl.updateSensorCalibration].
 *
 * The init block enforces the 3×3 layout contract — if either array is wrong
 * length, the bug is caught immediately at the push site rather than silently
 * producing wrong colour.
 */
data class SensorCalibration(
    /** DNG ForwardMatrix1 — sensor RGB → CIE XYZ D50 under Illuminant A (2856 K). */
    val forwardMatrixA: FloatArray,
    /** DNG ForwardMatrix2 — sensor RGB → CIE XYZ D50 under Illuminant D65 (6500 K). */
    val forwardMatrixD65: FloatArray,
) {
    init {
        require(forwardMatrixA.size == 9) {
            "forwardMatrixA must be 3×3 row-major (9 elements), got ${forwardMatrixA.size}"
        }
        require(forwardMatrixD65.size == 9) {
            "forwardMatrixD65 must be 3×3 row-major (9 elements), got ${forwardMatrixD65.size}"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SensorCalibration
        return forwardMatrixA.contentEquals(other.forwardMatrixA) &&
            forwardMatrixD65.contentEquals(other.forwardMatrixD65)
    }

    override fun hashCode(): Int {
        var result = forwardMatrixA.contentHashCode()
        result = 31 * result + forwardMatrixD65.contentHashCode()
        return result
    }
}
