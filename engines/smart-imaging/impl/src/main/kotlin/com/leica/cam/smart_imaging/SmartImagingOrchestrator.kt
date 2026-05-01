package com.leica.cam.smart_imaging

import com.leica.cam.ai_engine.api.IAiEngine
import com.leica.cam.ai_engine.api.SceneAnalysis
import com.leica.cam.bokeh_engine.api.IBokehEngine
import com.leica.cam.color_science.api.ColourMappedBuffer
import com.leica.cam.color_science.api.IColorLM2Engine
import com.leica.cam.common.logging.LeicaLogger
import com.leica.cam.common.result.LeicaResult
import com.leica.cam.common.result.PipelineStage
import com.leica.cam.depth_engine.api.DepthMap
import com.leica.cam.depth_engine.api.IDepthEngine
import com.leica.cam.face_engine.api.FaceAnalysis
import com.leica.cam.face_engine.api.IFaceEngine
import com.leica.cam.hardware.contracts.photon.PhotonBuffer
import com.leica.cam.hypertone_wb.api.IHyperToneWB2Engine
import com.leica.cam.hypertone_wb.api.IlluminantMap
import com.leica.cam.hypertone_wb.api.SkinZoneMap
import com.leica.cam.hypertone_wb.api.WbCorrectedBuffer
import com.leica.cam.motion_engine.api.IMotionEngine
import com.leica.cam.neural_isp.api.INeuralIspOrchestrator
import com.leica.cam.neural_isp.api.ThermalBudget
import com.leica.cam.neural_isp.api.TonedBuffer
import com.leica.cam.photon_matrix.FusedPhotonBuffer
import com.leica.cam.photon_matrix.IPhotonMatrixAssembler
import com.leica.cam.photon_matrix.IPhotonMatrixIngestor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmartImagingOrchestrator @Inject constructor(
    private val photonMatrix: IPhotonMatrixIngestor,
    private val motionEngine: IMotionEngine,
    private val fusionEngine: FusionLM2Engine,
    private val colourEngine: IColorLM2Engine,
    private val depthEngine: IDepthEngine,
    private val faceEngine: IFaceEngine,
    private val aiEngine: IAiEngine,
    private val wbEngine: IHyperToneWB2Engine,
    private val bokehEngine: IBokehEngine,
    private val toneEngine: ToneLM2Engine,
    private val neuralIsp: INeuralIspOrchestrator,
    private val assembler: IPhotonMatrixAssembler,
    private val governor: RuntimeGovernor,
    private val logger: LeicaLogger,
    private val io: CoroutineDispatcher,
) : ISmartImagingOrchestrator {

    override suspend fun processCapture(
        frames: List<PhotonBuffer>,
        plan: LumoCapturePlan,
    ): LeicaResult<LumoOutputPackage> = withContext(io) {
        val budget = when (val r = governor.checkBudget()) {
            is LeicaResult.Success -> r.value
            is LeicaResult.Failure -> return@withContext r
        }
        logger.info("LUMO", "LUMO capture: budget=$budget, frames=${frames.size}")

        val photon = when (val r = photonMatrix.ingest(frames)) {
            is LeicaResult.Success -> r.value
            is LeicaResult.Failure -> return@withContext r
        }
        val aligned = when (val r = motionEngine.align(photon, plan.motionConfig)) {
            is LeicaResult.Success -> r.value
            is LeicaResult.Failure -> return@withContext r
        }
        val fused = when (val r = fusionEngine.fuse(aligned, plan.fusionConfig)) {
            is LeicaResult.Success -> r.value
            is LeicaResult.Failure -> return@withContext r
        }

        // Parallel analysis stage
        data class ParallelResults(
            val colour: ColourMappedBuffer,
            val depth: DepthMap,
            val face: FaceAnalysis,
            val scene: SceneAnalysis,
        )

        val parallel: ParallelResults = coroutineScope {
            val cDeferred = async { colourEngine.mapColours(fused, plan.sceneContext) }
            val dDeferred = async { depthEngine.estimate(fused, plan.depthConfig) }
            val fDeferred = async { faceEngine.detect(fused) }
            val sDeferred = async { aiEngine.classifyAndScore(fused, plan.captureMode) }

            val cResult = cDeferred.await()
            val dResult = dDeferred.await()
            val fResult = fDeferred.await()
            val sResult = sDeferred.await()

            val colour = when (cResult) {
                is LeicaResult.Success -> cResult.value
                is LeicaResult.Failure -> return@coroutineScope cResult
            }
            val depth = when (dResult) {
                is LeicaResult.Success -> dResult.value
                is LeicaResult.Failure -> return@coroutineScope dResult
            }
            val face = when (fResult) {
                is LeicaResult.Success -> fResult.value
                is LeicaResult.Failure -> return@coroutineScope fResult
            }
            val scene = when (sResult) {
                is LeicaResult.Success -> sResult.value
                is LeicaResult.Failure -> return@coroutineScope sResult
            }

            LeicaResult.Success(ParallelResults(colour, depth, face, scene))
        }.let { result ->
            when (result) {
                is LeicaResult.Success -> result.value
                is LeicaResult.Failure -> return@withContext result
            }
        }

        // Serial WB + Bokeh stage
        val skinZones = SkinZoneMap(
            parallel.face.skinZones.width,
            parallel.face.skinZones.height,
            parallel.face.skinZones.mask,
        )
        val illuminantMap = IlluminantMap(
            tiles = emptyList(),
            dominantKelvin = parallel.scene.illuminantHint.estimatedKelvin,
        )

        val wb = when (val r = wbEngine.correct(parallel.colour, skinZones, illuminantMap)) {
            is LeicaResult.Success -> r.value
            is LeicaResult.Failure -> return@withContext r
        }
        val bokeh = when (val r = bokehEngine.compute(parallel.depth, parallel.face.subjectBoundary, plan.bokehConfig)) {
            is LeicaResult.Success -> r.value
            is LeicaResult.Failure -> return@withContext r
        }

        val toned = when (val r = toneEngine.render(wb, bokeh, parallel.scene, plan.toneConfig)) {
            is LeicaResult.Success -> r.value
            is LeicaResult.Failure -> return@withContext r
        }
        val enhanced = when (val r = neuralIsp.enhance(toned, budget)) {
            is LeicaResult.Success -> r.value
            is LeicaResult.Failure -> return@withContext r
        }

        when (val assembled = assembler.assemble(enhanced, plan.outputMode, IPhotonMatrixAssembler.OutputMetadata())) {
            is LeicaResult.Success -> {
                LeicaResult.Success(
                    LumoOutputPackage.Complete(
                        finalBuffer = assembled.value.finalBuffer,
                        bokehMask = (bokeh as? com.leica.cam.bokeh_engine.api.BokehResult.Rendered)?.bokehMask,
                        captureMetadata = LumoOutputPackage.CaptureMetadata(
                            iso = assembled.value.metadata.iso,
                            exposureTimeNs = assembled.value.metadata.exposureTimeNs,
                            focalLengthMm = assembled.value.metadata.focalLengthMm,
                            whiteBalanceKelvin = assembled.value.metadata.whiteBalanceKelvin,
                            timestampNs = assembled.value.metadata.timestampNs,
                        ),
                        outputMode = assembled.value.outputMode,
                        toneProfile = assembled.value.toneProfile,
                    ),
                )
            }
            is LeicaResult.Failure -> assembled
        }
    }
}

class FusionLM2Engine {
    suspend fun fuse(
        aligned: com.leica.cam.motion_engine.api.AlignedBuffer,
        config: FusionConfig,
    ): LeicaResult<FusedPhotonBuffer> {
        if (aligned.frames.size < config.minFrames) {
            return LeicaResult.Failure.Pipeline(
                PipelineStage.FUSION,
                "Need at least ${config.minFrames} frames for fusion, got ${aligned.frames.size}",
            )
        }
        val firstFrame = aligned.frames.first()
        return LeicaResult.Success(
            FusedPhotonBuffer(
                underlying = firstFrame,
                fusionQuality = 1f,
                frameCount = aligned.frames.size,
                motionMagnitude = 0f,
            ),
        )
    }
}

class ToneLM2Engine {
    suspend fun render(
        wb: WbCorrectedBuffer,
        bokeh: com.leica.cam.bokeh_engine.api.BokehResult,
        scene: SceneAnalysis,
        config: ToneConfig,
    ): LeicaResult<TonedBuffer> {
        val underlying = when (wb) {
            is WbCorrectedBuffer.Corrected -> wb.underlying.underlying
        }
        return LeicaResult.Success(TonedBuffer.TonedImage(underlying, config.profile))
    }
}

class RuntimeGovernor {
    fun checkBudget(): LeicaResult<ThermalBudget> =
        LeicaResult.Success(
            ThermalBudget(
                tier = com.leica.cam.neural_isp.api.ThermalTier.FULL,
                gpuTemperatureCelsius = 45f,
                processingBudgetMs = 500L,
            ),
        )
}
