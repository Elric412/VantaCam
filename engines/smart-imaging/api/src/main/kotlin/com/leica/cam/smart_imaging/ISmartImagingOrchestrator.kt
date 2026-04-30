package com.leica.cam.smart_imaging

import com.leica.cam.common.result.LeicaResult
import com.leica.cam.hardware.contracts.photon.PhotonBuffer
import com.leica.cam.photon_matrix.ProXdrOutputMode
import java.io.Closeable

interface ISmartImagingOrchestrator {
    suspend fun processCapture(
        frames: List<PhotonBuffer>,
        plan: LumoCapturePlan,
    ): LeicaResult<LumoOutputPackage>
}

data class LumoCapturePlan(
    val fusionConfig: FusionConfig = FusionConfig(),
    val depthConfig: com.leica.cam.depth_engine.api.DepthConfig = com.leica.cam.depth_engine.api.DepthConfig(),
    val bokehConfig: com.leica.cam.bokeh_engine.api.BokehConfig = com.leica.cam.bokeh_engine.api.BokehConfig(),
    val toneConfig: ToneConfig = ToneConfig(),
    val motionConfig: com.leica.cam.motion_engine.api.MotionConfig = com.leica.cam.motion_engine.api.MotionConfig(),
    val sceneContext: com.leica.cam.color_science.api.SceneContext = com.leica.cam.color_science.api.SceneContext(
        sceneLabel = "auto",
        illuminantHint = com.leica.cam.color_science.api.IlluminantHint(6500f, 0.5f, false),
    ),
    val captureMode: com.leica.cam.ai_engine.api.CaptureMode = com.leica.cam.ai_engine.api.CaptureMode.AUTO,
    val outputMode: ProXdrOutputMode = ProXdrOutputMode.Heic,
)

data class FusionConfig(
    val minFrames: Int = 2,
    val qualityThreshold: Float = 0.7f,
    val motionTolerance: Float = 16f,
)

data class ToneConfig(
    val profile: String = "leica_authentic",
    val shadowLift: Float = 0.04f,
    val highlightRollOff: Float = 0.12f,
)

sealed class LumoOutputPackage : Closeable {
    data class Complete(
        val finalBuffer: PhotonBuffer,
        val bokehMask: com.leica.cam.bokeh_engine.api.BokehMask?,
        val captureMetadata: CaptureMetadata,
        val outputMode: ProXdrOutputMode,
        val toneProfile: String,
    ) : LumoOutputPackage() {
        override fun close() {
        }
    }

    data class CaptureMetadata(
        val iso: Int,
        val exposureTimeNs: Long,
        val focalLengthMm: Float,
        val whiteBalanceKelvin: Float,
        val timestampNs: Long,
    )
}
