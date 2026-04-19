package com.leica.cam.feature.gallery.ui

import com.leica.cam.common.result.LeicaResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class GalleryMetadataEnginePhase9Test {
    private val engine = GalleryMetadataEngine()

    @Test
    fun `build view state sorts by recency and computes facets`() {
        val older = sampleItem(id = "1", mode = "AUTO", lens = "Main", ts = Instant.parse("2026-01-01T00:00:00Z"))
        val newer = sampleItem(id = "2", mode = "PRO", lens = "Tele", ts = Instant.parse("2026-02-01T00:00:00Z"))

        val state = engine.buildViewState(listOf(older, newer))

        assertEquals("2", state.items.first().id)
        assertEquals(1, state.modeFacets["AUTO"])
        assertEquals(1, state.modeFacets["PRO"])
        assertEquals(1, state.lensFacets["Main"])
    }

    @Test
    fun `metadata panel formats capture details and location flag`() {
        val item = sampleItem(
            id = "abc",
            mode = "PRO",
            lens = "Main 23mm",
            ts = Instant.parse("2026-03-03T10:00:00Z"),
            lat = 40.7128,
            lng = -74.006,
        )

        val panel = engine.buildMetadataPanel(item)

        assertTrue(panel is LeicaResult.Success)
        val data = (panel as LeicaResult.Success).value
        assertEquals("PRO • Main 23mm", data.headline)
        assertTrue(data.hasLocation)
        assertTrue(data.captureDetails.any { it.first == "Shutter" })
    }

    private fun sampleItem(
        id: String,
        mode: String,
        lens: String,
        ts: Instant,
        lat: Double? = null,
        lng: Double? = null,
    ): GalleryItem {
        return GalleryItem(
            id = id,
            captureTimestampUtc = ts,
            width = 4080,
            height = 3072,
            mimeType = "image/jpeg",
            lensLabel = lens,
            modeLabel = mode,
            iso = 200,
            shutterUs = 8_000,
            whiteBalanceKelvin = 5600,
            exposureCompensationEv = -0.3f,
            gpsLatitude = lat,
            gpsLongitude = lng,
        )
    }
}
