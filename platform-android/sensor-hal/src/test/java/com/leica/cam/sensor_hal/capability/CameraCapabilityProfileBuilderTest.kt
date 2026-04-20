package com.leica.cam.sensor_hal.capability

import android.graphics.Rect
import android.util.Size
import android.util.SizeF
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraCapabilityProfileBuilderTest {
    @Test
    fun `builds profile for each physical camera`() {
        val source = CameraMetadataSource { cameraId ->
            when (cameraId) {
                "logical" -> sampleMetadata(setOf("wide", "ultra"))
                "wide" -> sampleMetadata(emptySet(), minimumFocusDistance = 0.1f)
                else -> sampleMetadata(emptySet(), minimumFocusDistance = 0.2f)
            }
        }

        val profiles = CameraCapabilityProfileBuilder(source).buildProfiles("logical")

        assertEquals(2, profiles.size)
        assertEquals(setOf("wide", "ultra"), profiles.map { it.physicalCameraId }.toSet())
    }

    private fun sampleMetadata(
        physicalIds: Set<String>,
        minimumFocusDistance: Float = 0.1f,
    ): CameraMetadata = CameraMetadata(
        physicalIds = physicalIds,
        focalLengths = floatArrayOf(24f),
        apertures = floatArrayOf(1.8f),
        minimumFocusDistance = minimumFocusDistance,
        physicalSensorSize = SizeF(5f, 3.8f),
        pixelArraySize = Size(4000, 3000),
        activeArraySize = Rect(0, 0, 4000, 3000),
        availableAberrationModes = intArrayOf(1, 2),
    )
}
