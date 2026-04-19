package com.leica.cam.nativeimagingcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeGovernorTest {
    private val governor = RuntimeGovernor()

    @Test
    fun `classifyTier returns flagship tier for high-end profile`() {
        val tier = governor.classifyTier(
            DeviceProfile(
                socFamily = "Snapdragon",
                gpuFamily = "Adreno",
                vulkanLevel = 1,
                bigCoreCount = 4,
                totalRamGb = 12,
                hasNnapiOrDsp = true,
            ),
        )

        assertEquals(DeviceTier.TIER_A, tier)
    }

    @Test
    fun `adapt downgrades tier and burst under pressure`() {
        val decision = governor.adapt(
            currentTier = DeviceTier.TIER_A,
            telemetry = RuntimeTelemetry(
                p95PreviewLatencyMs = 24.0,
                p95CaptureLatencyMs = 260.0,
                nativeHeapBytes = 1024L * 1024L * 1024L,
                thermalLevel = 8,
            ),
            burstLength = 9,
        )

        assertEquals(DeviceTier.TIER_B, decision.nextTier)
        assertEquals(8, decision.nextBurstLength)
        assertTrue(decision.disableExpensiveRefinements)
    }

    @Test
    fun `state machine transitions only along contract`() {
        assertTrue(SessionState.CREATED.canTransitionTo(SessionState.WARM))
        assertTrue(SessionState.WARM.canTransitionTo(SessionState.RUNNING))
        assertTrue(SessionState.RUNNING.canTransitionTo(SessionState.DRAINING))
        assertTrue(SessionState.DRAINING.canTransitionTo(SessionState.CLOSED))
        assertTrue(!SessionState.CLOSED.canTransitionTo(SessionState.CREATED))
    }
}
