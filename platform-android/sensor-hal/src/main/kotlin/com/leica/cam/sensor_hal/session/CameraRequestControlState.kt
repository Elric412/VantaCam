package com.leica.cam.sensor_hal.session

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Mutable-at-the-edges capture-request state for the active camera session.
 *
 * ISO + shutter imply manual exposure. White balance is manual only when
 * [whiteBalanceKelvin] is non-null.
 */
data class CameraRequestControlState(
    val manualIso: Int? = null,
    val manualShutterUs: Long? = null,
    val exposureCompensationEv: Float = 0f,
    val whiteBalanceKelvin: Int? = null,
) {
    val usesManualExposure: Boolean
        get() = manualIso != null || manualShutterUs != null

    val usesManualWhiteBalance: Boolean
        get() = whiteBalanceKelvin != null

    fun withIso(iso: Int): CameraRequestControlState = copy(manualIso = iso)

    fun withShutterUs(shutterUs: Long): CameraRequestControlState = copy(manualShutterUs = shutterUs)

    fun withExposureCompensation(ev: Float): CameraRequestControlState = copy(exposureCompensationEv = ev)

    fun withWhiteBalance(kelvin: Int): CameraRequestControlState = copy(whiteBalanceKelvin = kelvin)

    fun resetExposure(): CameraRequestControlState = copy(
        manualIso = null,
        manualShutterUs = null,
        exposureCompensationEv = 0f,
    )

    fun resetWhiteBalance(): CameraRequestControlState = copy(whiteBalanceKelvin = null)

    fun exposureCompensationIndex(stepEv: Float, supportedRange: IntRange): Int {
        if (stepEv <= 0f) return 0
        return (exposureCompensationEv / stepEv).roundToInt()
            .coerceIn(supportedRange.first, supportedRange.last)
    }

    fun whiteBalanceGains(): WhiteBalanceGains? = whiteBalanceKelvin?.let(WhiteBalanceGains::fromKelvin)
}

/**
 * Simplified RGGB gain vector used to derive Camera2 manual white-balance gains.
 */
data class WhiteBalanceGains(
    val red: Float,
    val greenEven: Float = 1f,
    val greenOdd: Float = 1f,
    val blue: Float,
) {
    companion object {
        fun fromKelvin(kelvin: Int): WhiteBalanceGains {
            val boundedKelvin = kelvin.coerceIn(2_000, 12_000)
            val normalizedTemperature = boundedKelvin / 100.0

            val illuminantRed = if (normalizedTemperature <= 66.0) {
                255.0
            } else {
                329.698727446 * (normalizedTemperature - 60.0).pow(-0.1332047592)
            }

            val illuminantGreen = if (normalizedTemperature <= 66.0) {
                99.4708025861 * ln(normalizedTemperature) - 161.1195681661
            } else {
                288.1221695283 * (normalizedTemperature - 60.0).pow(-0.0755148492)
            }

            val illuminantBlue = when {
                normalizedTemperature >= 66.0 -> 255.0
                normalizedTemperature <= 19.0 -> 0.0
                else -> 138.5177312231 * ln(normalizedTemperature - 10.0) - 305.0447927307
            }

            val red = illuminantRed.coerceIn(1.0, 255.0)
            val green = illuminantGreen.coerceIn(1.0, 255.0)
            val blue = illuminantBlue.coerceIn(1.0, 255.0)

            return WhiteBalanceGains(
                red = (green / red).toFloat().coerceIn(0.1f, 8f),
                blue = (green / blue).toFloat().coerceIn(0.1f, 8f),
            )
        }
    }
}
