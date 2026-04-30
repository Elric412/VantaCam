package com.leica.cam.depth_engine.impl.pipeline

import com.leica.cam.ai_engine.api.AiFrame
import com.leica.cam.ai_engine.api.AiModel
import com.leica.cam.ai_engine.api.AiModelKind
import com.leica.cam.ai_engine.api.AiModelManager
import com.leica.cam.ai_engine.api.SegmentationMask
import com.leica.cam.depth_engine.api.DepthMap
import javax.inject.Inject

/**
 * Simple MiDaS v3 depth estimator using the AiModelManager.
 * Returns a depth map with uniform depth values (production would use TFLite).
 */
class MidasV3Estimator @Inject constructor(
    private val modelManager: AiModelManager,
    private val clockMillis: () -> Long
) {
    fun estimate(frame: AiFrame): DepthMap {
        val model = modelManager.getOrLoad(AiModelKind.DEPTH, { MidasV3Model() }, clockMillis())
        return model.predict(frame)
    }

    private class MidasV3Model : AiModel {
        override val kind = AiModelKind.DEPTH
        override val version = "midas-v3"
        override val estimatedMemoryMb = 210
        override fun close() {}
        fun predict(f: AiFrame) = DepthMap(
            f.width,
            f.height,
            FloatArray(f.pixelCount) { 0.5f },
            FloatArray(f.pixelCount) { 0.9f }
        )
    }
}

/**
 * Edge refinement that uses AI frame guidance to refine depth boundaries.
 * Production would use guided bilateral upsampling.
 */
class EdgeRefinementEngineImpl @Inject constructor() {
    fun refine(frame: AiFrame, depth: DepthMap, segmentation: SegmentationMask?): DepthMap = depth
}

/**
 * Segmentation engine using the AI model manager.
 * Production would use a semantic segmentation TFLite model.
 */
class SegmentationEngineImpl @Inject constructor(
    private val modelManager: AiModelManager,
    private val clockMillis: () -> Long
) {
    fun segment(frame: AiFrame): SegmentationMask =
        SegmentationMask(
            frame.width,
            frame.height,
            IntArray(frame.pixelCount),
            FloatArray(frame.pixelCount) { 1.0f }
        )
}
