package com.leica.cam.feature.settings.ui

import com.leica.cam.feature.settings.preferences.CameraPreferences
import com.leica.cam.feature.settings.preferences.GridStyle
import com.leica.cam.hypertone_wb.api.UserAwbMode
import com.leica.cam.imaging_pipeline.api.UserHdrMode

/**
 * Pure settings catalog builder: `(preferences, mutate) -> sections`.
 */
object SettingsCatalog {
    fun build(
        preferences: CameraPreferences,
        mutate: ((CameraPreferences) -> CameraPreferences) -> Unit,
    ): List<SettingsSection> = listOf(
        compositionSection(preferences, mutate),
        hdrSection(preferences, mutate),
        whiteBalanceSection(preferences, mutate),
        captureSection(),
        storageSection(),
        proSection(),
        aboutSection(),
    )

    private fun compositionSection(
        preferences: CameraPreferences,
        mutate: ((CameraPreferences) -> CameraPreferences) -> Unit,
    ): SettingsSection {
        return SettingsSection(
            id = "composition",
            title = "Composition",
            rows = listOf(
                SettingsRow.Choice(
                    id = "grid.style",
                    title = "Gridlines",
                    options = GridStyle.entries,
                    selected = preferences.grid.style,
                    label = { style ->
                        when (style) {
                            GridStyle.OFF -> "Off"
                            GridStyle.RULE_OF_THIRDS -> "Rule of thirds"
                            GridStyle.GOLDEN_RATIO -> "Golden ratio"
                        }
                    },
                    onSelect = { next ->
                        mutate { current -> current.copy(grid = current.grid.copy(style = next)) }
                    },
                ),
                SettingsRow.Toggle(
                    id = "grid.center_mark",
                    title = "Centre mark",
                    checked = preferences.grid.showCenterMark,
                    onToggle = { enabled ->
                        mutate { current ->
                            current.copy(grid = current.grid.copy(showCenterMark = enabled))
                        }
                    },
                ),
                SettingsRow.Toggle(
                    id = "grid.horizon_guide",
                    title = "Straighten (horizon guide)",
                    checked = preferences.grid.showHorizonGuide,
                    onToggle = { enabled ->
                        mutate { current ->
                            current.copy(grid = current.grid.copy(showHorizonGuide = enabled))
                        }
                    },
                ),
            ),
        )
    }

    private fun hdrSection(
        preferences: CameraPreferences,
        mutate: ((CameraPreferences) -> CameraPreferences) -> Unit,
    ): SettingsSection {
        return SettingsSection(
            id = "hdr",
            title = "HDR Control",
            rows = listOf(
                SettingsRow.Choice(
                    id = "hdr.mode",
                    title = "HDR mode",
                    options = UserHdrMode.entries,
                    selected = preferences.hdr.mode,
                    label = { mode ->
                        when (mode) {
                            UserHdrMode.OFF -> "Off"
                            UserHdrMode.ON -> "On"
                            UserHdrMode.SMART -> "Smart"
                            UserHdrMode.PRO_XDR -> "ProXDR"
                        }
                    },
                    onSelect = { next ->
                        mutate { current -> current.copy(hdr = current.hdr.copy(mode = next)) }
                    },
                ),
            ),
        )
    }

    private fun whiteBalanceSection(
        preferences: CameraPreferences,
        mutate: ((CameraPreferences) -> CameraPreferences) -> Unit,
    ): SettingsSection {
        return SettingsSection(
            id = "awb",
            title = "Auto White Balance",
            rows = listOf(
                SettingsRow.Choice(
                    id = "awb.mode",
                    title = "AWB mode",
                    options = UserAwbMode.entries,
                    selected = preferences.awb.mode,
                    label = { mode ->
                        when (mode) {
                            UserAwbMode.NORMAL -> "Normal"
                            UserAwbMode.ADVANCE -> "Advance"
                        }
                    },
                    onSelect = { next ->
                        mutate { current -> current.copy(awb = current.awb.copy(mode = next)) }
                    },
                ),
            ),
        )
    }

    private fun captureSection(): SettingsSection {
        return SettingsSection(
            id = "capture",
            title = "Capture",
            rows = listOf(
                SettingsRow.Info("capture.format", "Format", "RAW + JPEG"),
                SettingsRow.Info("capture.quality", "JPEG quality", "Max (100)"),
                SettingsRow.Info("capture.raw", "RAW profile", "DNG 1.6 (16-bit)"),
                SettingsRow.Info("capture.burst", "Burst depth", "Auto (≤ 8)"),
                SettingsRow.Info("capture.shutter_sound", "Shutter sound", "Leica M Type 240"),
            ),
        )
    }

    private fun storageSection(): SettingsSection {
        return SettingsSection(
            id = "storage",
            title = "Storage",
            rows = listOf(
                SettingsRow.Info("storage.location", "Location", "SD card"),
                SettingsRow.Info("storage.folder", "Folder", "DCIM/LeicaCam"),
                SettingsRow.Info("storage.naming", "File naming", "L1000_####"),
            ),
        )
    }

    private fun proSection(): SettingsSection {
        return SettingsSection(
            id = "pro",
            title = "Pro Controls",
            rows = listOf(
                SettingsRow.Info("pro.iso_range", "ISO range", "50 – 6400"),
                SettingsRow.Info("pro.shutter_range", "Shutter range", "1/8000 – 30 s"),
                SettingsRow.Info("pro.wb_kelvin", "Manual WB", "2000 – 12000 K"),
                SettingsRow.Info("pro.focus_peaking", "Focus peaking", "On"),
                SettingsRow.Info("pro.zebras", "Exposure zebras", "95 IRE"),
                SettingsRow.Info("pro.histogram", "Histogram", "RGB + Luma"),
            ),
        )
    }

    private fun aboutSection(): SettingsSection {
        return SettingsSection(
            id = "about",
            title = "About",
            rows = listOf(
                SettingsRow.Info("about.version", "Firmware version", "1.2.0"),
                SettingsRow.Info("about.lumo", "LUMO platform", "2.0"),
            ),
        )
    }
}
