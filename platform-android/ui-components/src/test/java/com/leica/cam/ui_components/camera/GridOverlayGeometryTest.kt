package com.leica.cam.ui_components.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class GridOverlayGeometryTest {
    @Test
    fun ruleOfThirdsFractionsAreCanonical() {
        val first = 1f / 3f
        val second = 2f / 3f
        assertEquals(0.3333333f, first, 1e-6f)
        assertEquals(0.6666667f, second, 1e-6f)
    }

    @Test
    fun goldenRatioFractionsAreConjugatePair() {
        val phi = 0.618f
        val phiConjugate = 0.382f
        assertEquals(1f, phi + phiConjugate, 1e-3f)
    }

    @Test
    fun compositionOverlayDefaultsDisableGrid() {
        val overlay = CompositionOverlay()
        assertEquals(ViewfinderGridStyle.OFF, overlay.gridStyle)
    }
}
