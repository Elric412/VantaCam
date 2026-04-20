package com.leica.cam.motion_engine.api

import com.leica.cam.common.result.LeicaResult
import com.leica.cam.photon_matrix.PhotonBuffer

interface IMotionEngine {
    suspend fun align(photon: PhotonBuffer, config: MotionConfig): LeicaResult<AlignedBuffer>
}

data class AlignedBuffer(
    val frames: List<PhotonBuffer>,
    val alignmentTransforms: List<AlignmentTransform>,
    val referenceIndex: Int = 0,
)

data class AlignmentTransform(
    val tx: Float,
    val ty: Float,
    val confidence: Float,
)

data class MotionConfig(
    val maxDisplacement: Float = 32f,
    val pyramidLevels: Int = 4,
    val searchRadius: Int = 4,
    val deblurEnabled: Boolean = false,
)

data class MotionEstimate(
    val magnitude: Float,
    val directionDeg: Float,
    val isSignificant: Boolean,
)
