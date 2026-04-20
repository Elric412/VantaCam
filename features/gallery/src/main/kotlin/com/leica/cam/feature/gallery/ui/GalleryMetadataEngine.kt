package com.leica.cam.feature.gallery.ui

import com.leica.cam.common.result.LeicaResult
import com.leica.cam.common.result.PipelineStage
import java.time.Instant

/** One media item visible in the gallery timeline. */
data class GalleryItem(
    val id: String,
    val captureTimestampUtc: Instant,
    val width: Int,
    val height: Int,
    val mimeType: String,
    val lensLabel: String,
    val modeLabel: String,
    val iso: Int,
    val shutterUs: Long,
    val whiteBalanceKelvin: Int,
    val exposureCompensationEv: Float,
    val gpsLatitude: Double? = null,
    val gpsLongitude: Double? = null,
)

/** Human-friendly metadata block shown by the phase 9 metadata viewer. */
data class MetadataPanel(
    val headline: String,
    val captureDetails: List<Pair<String, String>>,
    val technicalDetails: List<Pair<String, String>>,
    val hasLocation: Boolean,
)

/** Aggregate gallery view model for timeline + filter chips. */
data class GalleryViewState(
    val items: List<GalleryItem>,
    val modeFacets: Map<String, Int>,
    val lensFacets: Map<String, Int>,
)

/**
 * Gallery and metadata domain engine for phase 9.
 *
 * All formatting output is deterministic for stable snapshot/UI testing.
 */
class GalleryMetadataEngine {
    fun buildViewState(items: List<GalleryItem>): GalleryViewState {
        val sorted = items.sortedByDescending { it.captureTimestampUtc }
        val modeFacets = sorted.groupingBy { it.modeLabel }.eachCount().toSortedMap()
        val lensFacets = sorted.groupingBy { it.lensLabel }.eachCount().toSortedMap()
        return GalleryViewState(sorted, modeFacets, lensFacets)
    }

    fun buildMetadataPanel(item: GalleryItem): LeicaResult<MetadataPanel> {
        if (item.width <= 0 || item.height <= 0) {
            return LeicaResult.Failure.Pipeline(PipelineStage.METADATA, "Invalid media dimensions")
        }
        if (item.iso <= 0 || item.shutterUs <= 0L || item.whiteBalanceKelvin <= 0) {
            return LeicaResult.Failure.Pipeline(PipelineStage.METADATA, "Invalid exposure metadata")
        }

        val megapixels = item.width.toLong() * item.height.toLong() / 1_000_000.0
        val hasLocation = item.gpsLatitude != null && item.gpsLongitude != null
        return LeicaResult.Success(
            MetadataPanel(
                headline = "${item.modeLabel} • ${item.lensLabel}",
                captureDetails = listOf(
                    "Resolution" to "${item.width}×${item.height} (${String.format("%.1f MP", megapixels)})",
                    "ISO" to item.iso.toString(),
                    "Shutter" to formatShutter(item.shutterUs),
                    "WB" to "${item.whiteBalanceKelvin}K",
                    "EV" to String.format("%+.1f", item.exposureCompensationEv),
                ),
                technicalDetails = listOf(
                    "MIME" to item.mimeType,
                    "Captured" to item.captureTimestampUtc.toString(),
                    "Asset ID" to item.id,
                ),
                hasLocation = hasLocation,
            ),
        )
    }

    fun filterByMode(items: List<GalleryItem>, modeLabel: String): List<GalleryItem> {
        return items.filter { it.modeLabel == modeLabel }
    }

    fun filterByLens(items: List<GalleryItem>, lensLabel: String): List<GalleryItem> {
        return items.filter { it.lensLabel == lensLabel }
    }

    private fun formatShutter(shutterUs: Long): String {
        if (shutterUs >= 1_000_000L) {
            return String.format("%.2fs", shutterUs / 1_000_000f)
        }
        val denominator = (1_000_000f / shutterUs.toFloat()).toInt().coerceAtLeast(1)
        return "1/$denominator"
    }
}
