package com.leica.cam.feature.settings.preferences

import android.content.Context
import android.content.SharedPreferences
import com.leica.cam.hypertone_wb.api.UserAwbMode
import com.leica.cam.imaging_pipeline.api.UserHdrMode
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper over a single `SharedPreferences` file. The UI talks only to
 * [CameraPreferencesRepository].
 */
@Singleton
class SharedPreferencesCameraStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): CameraPreferences {
        val style = runCatching {
            GridStyle.valueOf(
                prefs.getString(KEY_GRID_STYLE, GridStyle.OFF.name) ?: GridStyle.OFF.name,
            )
        }.getOrDefault(GridStyle.OFF)
        val hdrMode = runCatching {
            UserHdrMode.valueOf(
                prefs.getString(KEY_HDR_MODE, UserHdrMode.SMART.name) ?: UserHdrMode.SMART.name,
            )
        }.getOrDefault(UserHdrMode.SMART)
        val awbMode = runCatching {
            UserAwbMode.valueOf(
                prefs.getString(KEY_AWB_MODE, UserAwbMode.ADVANCE.name) ?: UserAwbMode.ADVANCE.name,
            )
        }.getOrDefault(UserAwbMode.ADVANCE)
        val flashMode = runCatching {
            FlashMode.valueOf(
                prefs.getString(KEY_FLASH_MODE, FlashMode.OFF.name) ?: FlashMode.OFF.name,
            )
        }.getOrDefault(FlashMode.OFF)
        val cameraFacing = runCatching {
            CameraFacing.valueOf(
                prefs.getString(KEY_CAMERA_FACING, CameraFacing.BACK.name) ?: CameraFacing.BACK.name,
            )
        }.getOrDefault(CameraFacing.BACK)
        return CameraPreferences(
            grid = GridPreferences(
                style = style,
                showCenterMark = prefs.getBoolean(KEY_CENTER_MARK, false),
                showHorizonGuide = prefs.getBoolean(KEY_HORIZON_GUIDE, true),
            ),
            hdr = HdrPreferences(mode = hdrMode),
            awb = AwbPreferences(mode = awbMode),
            flashMode = flashMode,
            currentZoom = prefs.getFloat(KEY_CURRENT_ZOOM, 1f),
            cameraFacing = cameraFacing,
        )
    }

    fun save(preferences: CameraPreferences) {
        prefs.edit()
            .putString(KEY_GRID_STYLE, preferences.grid.style.name)
            .putBoolean(KEY_CENTER_MARK, preferences.grid.showCenterMark)
            .putBoolean(KEY_HORIZON_GUIDE, preferences.grid.showHorizonGuide)
            .putString(KEY_HDR_MODE, preferences.hdr.mode.name)
            .putString(KEY_AWB_MODE, preferences.awb.mode.name)
            .putString(KEY_FLASH_MODE, preferences.flashMode.name)
            .putFloat(KEY_CURRENT_ZOOM, preferences.currentZoom)
            .putString(KEY_CAMERA_FACING, preferences.cameraFacing.name)
            .commit()
    }

    private companion object {
        const val PREFS_NAME = "com.leica.cam.camera_preferences"
        const val KEY_GRID_STYLE = "grid.style"
        const val KEY_CENTER_MARK = "grid.center_mark"
        const val KEY_HORIZON_GUIDE = "grid.horizon_guide"
        const val KEY_HDR_MODE = "hdr.mode"
        const val KEY_AWB_MODE = "awb.mode"
        const val KEY_FLASH_MODE = "flash.mode"
        const val KEY_CURRENT_ZOOM = "zoom.current"
        const val KEY_CAMERA_FACING = "camera.facing"
    }
}
