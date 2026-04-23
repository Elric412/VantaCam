package com.leica.cam.sensor_hal.session

/** Picks the rear-facing primary lens by lowest numeric id, falling back to first. */
class DefaultCameraSelector : CameraSelector {
    override fun selectCameraId(cameraIds: List<String>): String {
        require(cameraIds.isNotEmpty()) { "No cameras available on device" }
        return cameraIds
            .mapNotNull { id -> id.toIntOrNull()?.let { id to it } }
            .minByOrNull { it.second }?.first
            ?: cameraIds.first()
    }
}