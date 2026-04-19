package com.leica.cam.ai_engine.impl.pipeline
import com.leica.cam.ai_engine.api.*
import com.leica.cam.depth_engine.api.*
import com.leica.cam.face_engine.api.*
import javax.inject.Inject

data class AiEngineOutput(
    val faces: List<FaceMeshResult>,
    val depth: DepthMap,
    val skinMask: FloatArray
)

class AiEngineOrchestrator @Inject constructor(
    private val faceEngine: FaceEngine,
    private val skinMapper: SkinZoneMapper,
    private val depthEngine: DepthEngine,
    private val edgeRefiner: EdgeRefinementEngine,
    private val segEngine: SegmentationEngine
) {
    fun process(frame: AiFrame): AiEngineOutput {
        val faces = faceEngine.infer(frame)
        val skin = skinMapper.map(frame, faces)
        val seg = segEngine.segment(frame)
        val depth = depthEngine.estimate(frame, seg)
        val refined = edgeRefiner.refine(frame, depth, seg)
        return AiEngineOutput(faces, refined, skin)
    }
}
