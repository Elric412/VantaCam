package com.leica.cam.bokeh_engine.api

import com.leica.cam.common.result.LeicaResult
import com.leica.cam.depth_engine.api.DepthMap
import com.leica.cam.face_engine.api.SubjectBoundary

interface IBokehEngine {
    suspend fun compute(
        depth: DepthMap,
        boundary: SubjectBoundary,
        config: BokehConfig,
    ): LeicaResult<BokehResult>
}

sealed class BokehResult {
    data class Rendered(
        val bokehMask: BokehMask,
        val compositeBuffer: FloatArray,
    ) : BokehResult()
}

data class BokehMask(
    val width: Int,
    val height: Int,
    val foregroundAlpha: FloatArray,
)

data class BokehConfig(
    val apertureFStop: Float = 1.8f,
    val focalLengthMm: Float = 50f,
    val subjectDistanceHint: Float = 2.0f,
    val bladeCount: Int = 9,
    val bladeAngleDeg: Float = 0f,
)
