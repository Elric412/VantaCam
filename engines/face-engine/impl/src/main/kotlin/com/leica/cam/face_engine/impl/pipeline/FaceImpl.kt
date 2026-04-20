package com.leica.cam.face_engine.impl.pipeline
import com.leica.cam.ai_engine.api.*
import com.leica.cam.face_engine.api.*
import javax.inject.Inject

class FaceMeshEngineImpl @Inject constructor(
    private val modelManager: AiModelManager,
    private val clockMillis: () -> Long
) : FaceEngine {
    override fun infer(frame: AiFrame): List<FaceMeshResult> {
        val model = modelManager.getOrLoad(AiModelKind.FACE_MESH, { FaceMesh468Model() }, clockMillis())
        return model.predict(frame)
    }
    private class FaceMesh468Model : AiModel {
        override val kind = AiModelKind.FACE_MESH
        override val version = "facemesh-468"
        override val estimatedMemoryMb = 96
        override fun close() {}
        fun predict(f: AiFrame): List<FaceMeshResult> {
            val lms = List(468) { FaceLandmark(0.5f, 0.5f, 0.5f) }
            return listOf(FaceMeshResult(1L, lms))
        }
    }
}

class SkinZoneMapperImpl @Inject constructor() : SkinZoneMapper {
    override fun map(frame: AiFrame, faces: List<FaceMeshResult>): FloatArray = FloatArray(frame.pixelCount)
}
