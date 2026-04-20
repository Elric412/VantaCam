package com.leica.cam.imaging_pipeline.api

import com.leica.cam.common.types.NonEmptyList
import com.leica.cam.smart_imaging.LumoCapturePlan
import com.leica.cam.smart_imaging.LumoOutputPackage
import kotlinx.coroutines.flow.Flow

interface IImagingPipeline {
    fun processCapture(
        frames: NonEmptyList<Any>,
        plan: LumoCapturePlan,
    ): Flow<PipelineStageEvent>
}

sealed interface PipelineStageEvent {
    data object Ingesting : PipelineStageEvent
    data object Fusing : PipelineStageEvent
    data object Analysing : PipelineStageEvent
    data object Colouring : PipelineStageEvent
    data object Toning : PipelineStageEvent
    data object Enhancing : PipelineStageEvent
    data class Completed(val pkg: LumoOutputPackage) : PipelineStageEvent
    data class Failed(val error: com.leica.cam.common.result.LeicaResult.Failure) : PipelineStageEvent
}
