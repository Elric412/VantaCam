package com.leica.cam.face_engine.api

import com.leica.cam.common.result.LeicaResult
import com.leica.cam.photon_matrix.FusedPhotonBuffer

interface IFaceEngine {
    suspend fun detect(fused: FusedPhotonBuffer): LeicaResult<FaceAnalysis>
}

data class FaceAnalysis(
    val meshResults: List<FaceMeshResult>,
    val skinZones: SkinZoneMap,
    val subjectBoundary: SubjectBoundary,
    val expressions: List<ExpressionState>,
)

data class FaceMeshResult(
    val trackId: Long?,
    val landmarks: List<FaceLandmark>,
)

data class FaceLandmark(
    val x: Float,
    val y: Float,
    val z: Float,
)

data class SkinZoneMap(
    val width: Int,
    val height: Int,
    val mask: FloatArray,
) {
    init {
        require(width > 0 && height > 0) { "SkinZoneMap dimensions must be positive" }
        require(mask.size == width * height) { "SkinZoneMap size mismatch" }
    }
}

data class SubjectBoundary(
    val xMin: Float,
    val yMin: Float,
    val xMax: Float,
    val yMax: Float,
    val confidence: Float,
)

enum class ExpressionState {
    NEUTRAL,
    SMILE,
    BLINK,
    FROWN,
    SURPRISE,
    OTHER,
}
