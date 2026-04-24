package com.leica.cam.feature.settings.preferences

import com.leica.cam.hypertone_wb.api.UserAwbMode
import com.leica.cam.imaging_pipeline.api.UserHdrMode

/**
 * User-facing camera preference state. Plain, immutable data persisted by
 * [SharedPreferencesCameraStore] and observed via [CameraPreferencesRepository].
 *
 * Default values preserve the current UX until the user changes a setting.
 */
data class CameraPreferences(
    val grid: GridPreferences = GridPreferences(),
    val hdr: HdrPreferences = HdrPreferences(),
    val awb: AwbPreferences = AwbPreferences(),
    val flashMode: FlashMode = FlashMode.OFF,
    val currentZoom: Float = 1f,
    val cameraFacing: CameraFacing = CameraFacing.BACK,
)

/** HDR override persisted from the settings surface. */
data class HdrPreferences(
    val mode: UserHdrMode = UserHdrMode.SMART,
)

/** AWB override persisted from the settings surface. */
data class AwbPreferences(
    val mode: UserAwbMode = UserAwbMode.ADVANCE,
)

/** Composition aid overlay preferences for the live viewfinder. */
data class GridPreferences(
    val style: GridStyle = GridStyle.OFF,
    val showCenterMark: Boolean = false,
    val showHorizonGuide: Boolean = true,
)

enum class GridStyle {
    OFF,
    RULE_OF_THIRDS,
    GOLDEN_RATIO,
}

enum class FlashMode {
    OFF,
    ON,
    AUTO,
    ;

    fun next(): FlashMode = when (this) {
        OFF -> ON
        ON -> AUTO
        AUTO -> OFF
    }
}

enum class CameraFacing {
    BACK,
    FRONT,
    ;

    fun toggled(): CameraFacing = if (this == BACK) FRONT else BACK
}
