package com.leica.cam.camera_core.api

import com.leica.cam.common.result.LeicaResult

interface ICameraSessionManager {
    suspend fun open(): LeicaResult<Unit>
    suspend fun configure(surfaces: List<Any>): LeicaResult<CaptureSessionContract>
    suspend fun close(): LeicaResult<Unit>
}

data class CaptureSessionContract(
    val sessionId: String,
    val capabilities: CameraCapabilityProfile,
)

sealed class CameraCapabilityProfile {
    data class Full(val maxBurst: Int, val supportsRaw: Boolean) : CameraCapabilityProfile()
    data class Limited(val maxBurst: Int) : CameraCapabilityProfile()
    data object Legacy : CameraCapabilityProfile()

    companion object {
        fun default(): CameraCapabilityProfile = Full(maxBurst = 8, supportsRaw = true)
    }
}

enum class CaptureMode {
    AUTO,
    PORTRAIT,
    LANDSCAPE,
    NIGHT,
    MACRO,
    VIDEO,
}

data class CaptureConfig(
    val mode: CaptureMode,
    val iso: Int = 0,
    val exposureTimeNs: Long = 0L,
    val focusDistanceDiopters: Float = 0f,
)

enum class AfState {
    INACTIVE,
    SCANNING,
    FOCUSED,
    NOT_FOCUSED,
}

data class MeteringResult(
    val targetIso: Int,
    val targetExposureNs: Long,
    val stable: Boolean,
)

data class AfResult(
    val state: AfState,
    val distanceDiopters: Float,
)
