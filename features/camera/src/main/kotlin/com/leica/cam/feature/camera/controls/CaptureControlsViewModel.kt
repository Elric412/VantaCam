package com.leica.cam.feature.camera.controls

import androidx.lifecycle.ViewModel
import com.leica.cam.feature.camera.ui.ProModeController
import com.leica.cam.sensor_hal.session.Camera2CameraController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/**
 * Holds the current manual-capture state (ISO/shutter/EV/WB) for the camera HUD
 * and forwards every change to the active [Camera2CameraController].
 *
 * All value lists here MUST match what the hardware can actually accept on the
 * target device (per the sensor profiles in `:sensor-hal`). For this first pass
 * we use safe, conservative ranges covering 99% of available sensors.
 */
data class CaptureControlsUiState(
    val iso: Int = 200,
    val shutterUs: Long = 4_000L,            // 1/250
    val exposureEv: Float = 0f,
    val whiteBalanceKelvin: Int = 5500,
    val isAuto: Boolean = true,
) {
    val shutterLabel: String get() = formatShutter(shutterUs)
    val evLabel: String get() = (if (exposureEv >= 0f) "+" else "") + "%.1f".format(exposureEv)
    val wbLabel: String get() = if (isAuto) "AUTO" else "${whiteBalanceKelvin}K"

    private fun formatShutter(us: Long): String {
        if (us >= 1_000_000L) return "${(us / 1_000_000.0).let { "%.1f".format(it) }}s"
        val denom = (1_000_000.0 / us).toInt().coerceAtLeast(1)
        return "1/$denom"
    }
}

@HiltViewModel
class CaptureControlsViewModel @Inject constructor(
    private val controller: Camera2CameraController,
    private val proModeController: ProModeController,
) : ViewModel() {

    private val _state = MutableStateFlow(CaptureControlsUiState())
    val state: StateFlow<CaptureControlsUiState> = _state.asStateFlow()

    val isoOptions: List<Int> = listOf(50, 100, 200, 400, 800, 1600, 3200, 6400)
    val shutterUsOptions: List<Long> = listOf(
        30_000_000L, 15_000_000L, 8_000_000L, 4_000_000L, 2_000_000L, 1_000_000L,
        500_000L, 250_000L, 125_000L, 62_500L, 31_250L, 15_625L, 8_000L, 4_000L,
        2_000L, 1_000L, 500L, 250L, 125L,
    )
    val evOptions: List<Float> = (-20..20).map { it * 0.5f / 2f }.distinct()
    val wbOptions: List<Int> = listOf(2500, 3000, 3500, 4000, 4500, 5000, 5500, 6000, 6500, 7500, 9000)

    fun setIso(iso: Int) {
        val request = proModeController.buildManualRequest(
            iso = iso,
            shutterUs = _state.value.shutterUs,
            whiteBalanceKelvin = _state.value.whiteBalanceKelvin,
            focusDistanceNorm = 0.5f,
            exposureCompensationEv = _state.value.exposureEv,
        )
        controller.setManualExposure(request.iso, request.shutterUs)
        _state.update { it.copy(iso = request.iso, shutterUs = request.shutterUs, isAuto = false) }
    }

    fun setShutter(us: Long) {
        val request = proModeController.buildManualRequest(
            iso = _state.value.iso,
            shutterUs = us,
            whiteBalanceKelvin = _state.value.whiteBalanceKelvin,
            focusDistanceNorm = 0.5f,
            exposureCompensationEv = _state.value.exposureEv,
        )
        controller.setManualExposure(request.iso, request.shutterUs)
        _state.update { it.copy(iso = request.iso, shutterUs = request.shutterUs, isAuto = false) }
    }

    fun setExposureEv(ev: Float) {
        val clamped = ev.coerceIn(-5f, 5f)
        controller.setExposureCompensationEv(clamped)
        _state.update { it.copy(exposureEv = clamped) }
    }

    fun setWhiteBalance(kelvin: Int) {
        val clamped = kelvin.coerceIn(2000, 12000)
        controller.setWhiteBalanceKelvin(clamped)
        _state.update { it.copy(whiteBalanceKelvin = clamped, isAuto = false) }
    }

    fun resetToAuto() {
        controller.resetToAuto()
        _state.update { CaptureControlsUiState() }
    }
}