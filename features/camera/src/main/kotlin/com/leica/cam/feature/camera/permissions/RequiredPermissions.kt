package com.leica.cam.feature.camera.permissions

import android.Manifest
import android.os.Build

/**
 * Canonical list of runtime permissions the camera experience requires.
 *
 * The camera screen blocks only on camera access. Microphone, media access, and
 * location are requested contextually by the flows that need them.
 */
object RequiredPermissions {
    val mustHave: List<String> = listOf(
        Manifest.permission.CAMERA,
    )

    val niceToHave: List<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
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
