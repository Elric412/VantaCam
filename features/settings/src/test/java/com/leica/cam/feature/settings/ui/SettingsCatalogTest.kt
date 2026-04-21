package com.leica.cam.feature.settings.ui

import com.leica.cam.feature.settings.preferences.CameraPreferences
import com.leica.cam.feature.settings.preferences.GridStyle
import com.leica.cam.hypertone_wb.api.UserAwbMode
import com.leica.cam.imaging_pipeline.api.UserHdrMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsCatalogTest {
    @Test
    fun catalogContainsAllExpectedSections() {
        val sections = SettingsCatalog.build(CameraPreferences()) { _ -> }
        val ids = sections.map { it.id }

        assertEquals(
            listOf("composition", "hdr", "awb", "capture", "storage", "pro", "about"),
            ids,
        )
    }

    @Test
    fun gridStyleChoiceSurfacesAllOptions() {
        val sections = SettingsCatalog.build(CameraPreferences()) { _ -> }
        val composition = sections.first { it.id == "composition" }
        val gridRow = composition.rows.first { it.id == "grid.style" } as SettingsRow.Choice<*>

        assertEquals(GridStyle.entries.toList(), gridRow.options)
        assertEquals(GridStyle.OFF, gridRow.selected)
    }

    @Test
    fun hdrChoiceSurfacesAllFourModes() {
        val sections = SettingsCatalog.build(CameraPreferences()) { _ -> }
        val hdr = sections.first { it.id == "hdr" }
        val row = hdr.rows.first { it.id == "hdr.mode" } as SettingsRow.Choice<*>

        assertEquals(UserHdrMode.entries.toList(), row.options)
        assertEquals(UserHdrMode.SMART, row.selected)
    }

    @Test
    fun awbChoiceSurfacesBothModes() {
        val sections = SettingsCatalog.build(CameraPreferences()) { _ -> }
        val awb = sections.first { it.id == "awb" }
        val row = awb.rows.first { it.id == "awb.mode" } as SettingsRow.Choice<*>

        assertEquals(UserAwbMode.entries.toList(), row.options)
        assertEquals(UserAwbMode.ADVANCE, row.selected)
    }

    @Test
    fun mutateCallbackRoutesGridSelection() {
        var captured: CameraPreferences? = null
        val sections = SettingsCatalog.build(CameraPreferences()) { update ->
            captured = update(CameraPreferences())
        }
        val composition = sections.first { it.id == "composition" }
        @Suppress("UNCHECKED_CAST")
        val gridRow = composition.rows.first { it.id == "grid.style" } as SettingsRow.Choice<GridStyle>

        gridRow.onSelect(GridStyle.GOLDEN_RATIO)

        val updated = captured
        assertNotNull(updated)
        assertEquals(GridStyle.GOLDEN_RATIO, updated?.grid?.style)
    }

    @Test
    fun centerMarkToggleIsIndependent() {
        var captured: CameraPreferences? = null
        val sections = SettingsCatalog.build(CameraPreferences()) { update ->
            captured = update(CameraPreferences())
        }
        val toggle = sections.first { it.id == "composition" }
            .rows.first { it.id == "grid.center_mark" } as SettingsRow.Toggle

        toggle.onToggle(true)

        assertTrue(captured?.grid?.showCenterMark == true)
    }
}
