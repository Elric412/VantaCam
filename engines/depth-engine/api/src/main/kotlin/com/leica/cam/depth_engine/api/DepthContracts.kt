package com.leica.cam.depth_engine.api

import com.leica.cam.common.result.LeicaResult
import com.leica.cam.photon_matrix.FusedPhotonBuffer

interface IDepthEngine {
    suspend fun estimate(fused: FusedPhotonBuffer, config: DepthConfig): LeicaResult<DepthMap>
}

data class DepthMap(
    val width: Int,
    val height: Int,
    val depth: FloatArray,
    val confidence: FloatArray,
) {
    init {
        require(width > 0 && height > 0) { "DepthMap dimensions must be positive" }
        require(depth.size == width * height) { "Depth map size mismatch" }
        require(confidence.size == width * height) { "Confidence map size mismatch" }
    }
}

data class DepthConfig(
    val fusionMode: DepthFusionMode = DepthFusionMode.TOF_PRIMARY,
    val kalmanProcessNoise: Float = 0.01f,
    val kalmanMeasurementNoise: Float = 0.05f,
    val minConfidence: Float = 0.4f,
)

enum class DepthFusionMode {
    TOF_PRIMARY,
    MONOCULAR_ONLY,
    KALMAN_FUSED,
}

data class DepthEstimate(
    val depthMap: DepthMap,
    val method: DepthMethod,
    val latencyMs: Long,
)

enum class DepthMethod {
    TOF_HARDWARE,
    MONOCULAR_AI,
    KALMAN_FUSED,
}
