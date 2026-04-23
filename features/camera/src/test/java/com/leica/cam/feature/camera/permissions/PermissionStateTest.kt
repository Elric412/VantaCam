package com.leica.cam.feature.camera.permissions

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionStateTest {
    @Test
    fun `all granted returns AllGranted`() {
        val result = LeicaPermissionReducer.reduce(
            grants = mapOf(Manifest.permission.CAMERA to true, Manifest.permission.RECORD_AUDIO to true),
            rationales = emptyMap(),
        )
        assertEquals(LeicaPermissionState.AllGranted, result)
    }

    @Test
    fun `camera grant is sufficient even when microphone is denied`() {
        val result = LeicaPermissionReducer.reduce(
            grants = mapOf(Manifest.permission.CAMERA to true, Manifest.permission.RECORD_AUDIO to false),
            rationales = mapOf(Manifest.permission.RECORD_AUDIO to false),
        )
        assertEquals(LeicaPermissionState.AllGranted, result)
    }

    @Test
    fun `rationale visible returns NeedsRationale`() {
        val result = LeicaPermissionReducer.reduce(
            grants = mapOf(Manifest.permission.CAMERA to false, Manifest.permission.RECORD_AUDIO to true),
            rationales = mapOf(Manifest.permission.CAMERA to true),
        )
        assertTrue(result is LeicaPermissionState.NeedsRationale)
    }

    @Test
    fun `denied without rationale after ask returns PermanentlyDenied`() {
        val result = LeicaPermissionReducer.reduce(
            grants = mapOf(Manifest.permission.CAMERA to false, Manifest.permission.RECORD_AUDIO to true),
            rationales = mapOf(Manifest.permission.CAMERA to false),
        )
        assertTrue(result is LeicaPermissionState.PermanentlyDenied)
    }
}