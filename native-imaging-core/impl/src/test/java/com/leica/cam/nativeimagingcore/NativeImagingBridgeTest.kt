package com.leica.cam.nativeimagingcore

import com.leica.cam.common.result.LeicaResult
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ShortBuffer

class NativeImagingBridgeTest {

    @Test
    fun allocatePhotonBuffer_rejectsInvalidDimensionsBeforeJNI() {
        val bridge = NativeImagingBridge()

        val result = bridge.allocatePhotonBuffer(width = 0, height = 4, channels = 3)

        assertTrue(result is LeicaResult.Failure)
    }

    @Test
    fun fillPhotonChannel_rejectsClosedHandle() {
        val bridge = NativeImagingBridge()
        val handle = NativePhotonBufferHandle(handle = 0L, width = 2, height = 2, channels = 3)
        val plane = NativePhotonPlane(channel = 0, data = ShortBuffer.allocate(4))

        val result = bridge.fillPhotonChannel(handle, plane)

        assertTrue(result is LeicaResult.Failure)
    }

    @Test
    fun fillPhotonChannel_rejectsNonDirectShortBuffer() {
        val bridge = NativeImagingBridge()
        val handle = NativePhotonBufferHandle(handle = 1L, width = 2, height = 2, channels = 3)
        val plane = NativePhotonPlane(channel = 0, data = ShortBuffer.allocate(4))

        val result = bridge.fillPhotonChannel(handle, plane)

        assertTrue(result is LeicaResult.Failure)
    }
}
