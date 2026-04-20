package com.leica.cam.depth_engine.impl.pipeline
import com.leica.cam.ai_engine.api.*
import com.leica.cam.depth_engine.api.*
import javax.inject.Inject

class MidasV3Estimator @Inject constructor(
    private val modelManager: AiModelManager,
    private val clockMillis: () -> Long
) : DepthEngine {
    override fun estimate(frame: AiFrame, segmentation: SegmentationMask?): DepthMap {
        val model = modelManager.getOrLoad(AiModelKind.DEPTH, { MidasV3Model() }, clockMillis())
        return model.predict(frame)
    }
    private class MidasV3Model : AiModel {
        override val kind = AiModelKind.DEPTH
        override val version = "midas-v3"
        override val estimatedMemoryMb = 210
        override fun close() {}
        fun predict(f: AiFrame) = DepthMap(f.width, f.height, FloatArray(f.pixelCount) { 0.5f }, 0.9f)
    }
}

class EdgeRefinementEngineImpl @Inject constructor() : EdgeRefinementEngine {
    override fun refine(frame: AiFrame, depth: DepthMap, segmentation: SegmentationMask?): DepthMap = depth
}

class SegmentationEngineImpl @Inject constructor(
    private val modelManager: AiModelManager,
    private val clockMillis: () -> Long
) : SegmentationEngine {
    override fun segment(frame: AiFrame) = SegmentationMask(frame.width, frame.height, IntArray(frame.pixelCount), FloatArray(frame.pixelCount) { 1.0f })
}
