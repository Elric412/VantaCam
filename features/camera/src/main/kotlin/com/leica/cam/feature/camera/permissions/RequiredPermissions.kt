package com.leica.cam.feature.camera.permissions

import android.Manifest
import android.os.Build

/**
 * Canonical list of runtime permissions the camera experience requires.
 * Split into "must-have" (camera, audio for video) and "nice-to-have"
 * (location, media). Only must-have blocks the UI.
 */
object RequiredPermissions {
    val mustHave: List<String> = listOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
    )

    val niceToHave: List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.READ_MEDIA_IMAGES)
            add(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            @Suppress("DEPRECATION")
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        add(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    val all: List<String> = mustHave + niceToHave
}