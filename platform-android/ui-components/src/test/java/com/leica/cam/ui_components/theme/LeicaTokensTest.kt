package com.leica.cam.ui_components.theme

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class LeicaTokensTest {
    @Test
    fun `test default token values`() {
        val spacing = LeicaSpacing()
        val motion = LeicaMotion()
        val elevation = LeicaElevation()

        assertEquals(16.dp, spacing.l)
        assertEquals(220, motion.standard)
        assertEquals(3.dp, elevation.level2)
    }
}