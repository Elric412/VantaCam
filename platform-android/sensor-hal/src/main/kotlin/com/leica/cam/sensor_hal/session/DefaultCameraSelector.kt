package com.leica.cam.sensor_hal.session

/**
 * Picks the first id provided by [CameraController.availableCameraIds].
 *
 * The concrete controller sorts ids by Camera2 metadata (rear-facing first), so
 * this selector must preserve that capability-aware order instead of assuming
 * that numeric id 0 is always the primary rear camera.
 */
class DefaultCameraSelector : CameraSelector {
    override fun selectCameraId(cameraIds: List<String>): String {
        require(cameraIds.isNotEmpty()) { "No cameras available on device" }
        return cameraIds.first()
    }
}