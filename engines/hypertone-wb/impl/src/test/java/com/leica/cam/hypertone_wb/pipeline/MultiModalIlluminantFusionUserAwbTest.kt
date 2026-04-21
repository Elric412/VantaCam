package com.leica.cam.hypertone_wb.pipeline

import com.leica.cam.hypertone_wb.api.IlluminantClass
import com.leica.cam.hypertone_wb.api.IlluminantMap
import com.leica.cam.hypertone_wb.api.TileCTEstimate
import com.leica.cam.hypertone_wb.api.UserAwbMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiModalIlluminantFusionUserAwbTest {
    private val fusion = MultiModalIlluminantFusion()

    private fun sixteenTilesAt(kelvin: Float, confidence: Float = 0.6f): List<TileCTEstimate> =
        List(16) { index ->
            TileCTEstimate(
                tileRow = index / 4,
                tileCol = index % 4,
                kelvin = kelvin,
                confidence = confidence,
                illuminantClass = IlluminantClass.MIXED,
            )
        }

    @Test
    fun advanceMatchesLegacyFusion() {
        val hardware = sixteenTilesAt(kelvin = 3200f, confidence = 0.9f)
        val map = IlluminantMap(tiles = emptyList(), dominantKelvin = 5500f)

        val legacy = fusion.fuseWithHardware(hardware, map)
        val advance = fusion.fuseForMode(UserAwbMode.ADVANCE, hardware, map)

        assertEquals(legacy.tiles.size, advance.tiles.size)
        legacy.tiles.zip(advance.tiles).forEach { (expected, actual) ->
            assertEquals(expected.kelvin, actual.kelvin, 1e-3f)
            assertEquals(expected.confidence, actual.confidence, 1e-3f)
        }
    }

    @Test
    fun normalPinsEveryTileToAiDominantKelvin() {
        val hardware = sixteenTilesAt(kelvin = 2800f, confidence = 0.4f)
        val map = IlluminantMap(tiles = emptyList(), dominantKelvin = 6500f)

        val result = fusion.fuseForMode(UserAwbMode.NORMAL, hardware, map)

        assertEquals(16, result.tiles.size)
        result.tiles.forEach { tile ->
            assertEquals(6500f, tile.kelvin, 1e-3f)
            assertEquals(1f, tile.confidence, 1e-3f)
        }
    }

    @Test
    fun normalDoesNotBlendMixedLight() {
        val mixed = sixteenTilesAt(3000f).take(8) + sixteenTilesAt(6500f).takeLast(8)
        val map = IlluminantMap(tiles = emptyList(), dominantKelvin = 5200f)

        val result = fusion.fuseForMode(UserAwbMode.NORMAL, mixed, map)

        assertTrue(result.tiles.all { it.kelvin == 5200f })
    }
}
