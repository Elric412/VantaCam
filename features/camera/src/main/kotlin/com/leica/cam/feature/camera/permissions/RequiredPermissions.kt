package com.leica.cam.feature.camera.permissions

import android.Manifest
import android.os.Build

/**
 * Canonical list of runtime permissions the camera experience needs.
 *
 * The app asks for every permission used by first-run camera features up front
 * (camera, video/audio capture, gallery/media reads, legacy save access, and
 * notifications for long-running captures). Only camera access blocks the
 * viewfinder so users who deny a non-photo permission do not get stuck on a
 * black/blank preview.
 */
object RequiredPermissions {
    val requiredForViewfinder: List<String> = listOf(
        Manifest.permission.CAMERA,
    )

    val startupPrompt: List<String> = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.READ_MEDIA_IMAGES)
            add(Manifest.permission.READ_MEDIA_VIDEO)
            add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            @Suppress("DEPRECATION")
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(Manifest.permission.ACCESS_MEDIA_LOCATION)
        }

        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            @Suppress("DEPRECATION")
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }.distinct()

    val optional: List<String> = emptyList()

    val mustHave: List<String> = requiredForViewfinder
    val niceToHave: List<String> = startupPrompt.filterNot { it in requiredForViewfinder }
    val all: List<String> = startupPrompt
}
