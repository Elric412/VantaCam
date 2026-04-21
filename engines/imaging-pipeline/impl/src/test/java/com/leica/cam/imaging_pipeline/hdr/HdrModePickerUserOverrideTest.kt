package com.leica.cam.imaging_pipeline.hdr

import com.leica.cam.imaging_pipeline.api.UserHdrMode
import com.leica.cam.imaging_pipeline.pipeline.HdrMergeMode
import org.junit.Assert.assertEquals
import org.junit.Test

class HdrModePickerUserOverrideTest {
    private val wideBracket = HdrFrameSetMetadata(
        evSpread = 2.0f,
        allFramesClipped = false,
        rawPathUnavailable = false,
        thermalSevere = false,
    )
    private val sameExposure = wideBracket.copy(evSpread = 0.1f)
    private val clipped = wideBracket.copy(allFramesClipped = true)
    private val thermalSevere = wideBracket.copy(thermalSevere = true)

    @Test
    fun offForcesSingleFrame() {
        assertEquals(
            HdrMergeMode.SINGLE_FRAME,
            HdrModePicker.pickWithUserOverride(wideBracket, UserHdrMode.OFF),
        )
    }

    @Test
    fun onForcesWienerBurstForSameExposure() {
        assertEquals(
            HdrMergeMode.WIENER_BURST,
            HdrModePicker.pickWithUserOverride(sameExposure, UserHdrMode.ON),
        )
    }

    @Test
    fun onWithRawUnavailableFallsBackToMertens() {
        val noRaw = wideBracket.copy(rawPathUnavailable = true)
        assertEquals(
            HdrMergeMode.MERTENS_FUSION,
            HdrModePicker.pickWithUserOverride(noRaw, UserHdrMode.ON),
        )
    }

    @Test
    fun smartMatchesAutomaticPicker() {
        assertEquals(
            HdrModePicker.pick(wideBracket),
            HdrModePicker.pickWithUserOverride(wideBracket, UserHdrMode.SMART),
        )
        assertEquals(
            HdrModePicker.pick(sameExposure),
            HdrModePicker.pickWithUserOverride(sameExposure, UserHdrMode.SMART),
        )
        assertEquals(
            HdrModePicker.pick(clipped),
            HdrModePicker.pickWithUserOverride(clipped, UserHdrMode.SMART),
        )
    }

    @Test
    fun proXdrForcesDebevecWhenBracketIsWide() {
        assertEquals(
            HdrMergeMode.DEBEVEC_LINEAR,
            HdrModePicker.pickWithUserOverride(wideBracket, UserHdrMode.PRO_XDR),
        )
    }

    @Test
    fun proXdrDegradesGracefullyWhenClipped() {
        assertEquals(
            HdrMergeMode.MERTENS_FUSION,
            HdrModePicker.pickWithUserOverride(clipped, UserHdrMode.PRO_XDR),
        )
    }

    @Test
    fun thermalSevereOverridesEverything() {
        for (mode in UserHdrMode.entries) {
            assertEquals(
                HdrMergeMode.SINGLE_FRAME,
                HdrModePicker.pickWithUserOverride(thermalSevere, mode),
            )
        }
    }
}
