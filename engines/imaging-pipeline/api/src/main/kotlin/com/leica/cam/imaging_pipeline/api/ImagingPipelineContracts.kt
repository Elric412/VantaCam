package com.leica.cam.imaging_pipeline.api

import com.leica.cam.hardware.contracts.photon.PhotonBuffer
import kotlinx.coroutines.flow.Flow

interface IImagingPipeline {
    fun processCapture(
        frames: List<PhotonBuffer>,
        plan: PipelinePlan,
    ): Flow<PipelineStageEvent>
}

data class PipelinePlan(
    val captureMode: String = "auto",
    val outputMode: String = "heic",
)

sealed interface PipelineStageEvent {
    data object Ingesting : PipelineStageEvent
    data object Fusing : PipelineStageEvent
    data object Analysing : PipelineStageEvent
    data object Colouring : PipelineStageEvent
    data object Toning : PipelineStageEvent
    data object Enhancing : PipelineStageEvent
    data class Completed(val pkg: PipelineOutputPackage) : PipelineStageEvent
    data class Failed(val error: com.leica.cam.common.result.LeicaResult.Failure) : PipelineStageEvent
}

data class PipelineOutputPackage(
    val metadata: Map<String, String> = emptyMap(),
)
