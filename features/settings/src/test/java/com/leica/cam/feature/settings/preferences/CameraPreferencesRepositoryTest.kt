package com.leica.cam.feature.settings.preferences

import com.leica.cam.hypertone_wb.api.UserAwbMode
import com.leica.cam.imaging_pipeline.api.UserHdrMode
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraPreferencesRepositoryTest {
    @Test
    fun preferencesDefaultsAreStable() {
        val prefs = CameraPreferences()
        assertEquals(GridStyle.OFF, prefs.grid.style)
        assertEquals(false, prefs.grid.showCenterMark)
        assertEquals(true, prefs.grid.showHorizonGuide)
        assertEquals(UserHdrMode.SMART, prefs.hdr.mode)
        assertEquals(UserAwbMode.ADVANCE, prefs.awb.mode)
    }

    @Test
    fun gridPreferencesCopySemantics() {
        val base = GridPreferences()
        val flipped = base.copy(style = GridStyle.GOLDEN_RATIO, showCenterMark = true)
        assertEquals(GridStyle.GOLDEN_RATIO, flipped.style)
        assertEquals(true, flipped.showCenterMark)
        assertEquals(true, flipped.showHorizonGuide)
    }
}
