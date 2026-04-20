package com.leica.cam.smart_imaging

import com.leica.cam.ai_engine.api.IAiEngine
import com.leica.cam.bokeh_engine.api.IBokehEngine
import com.leica.cam.color_science.api.ColourMappedBuffer
import com.leica.cam.color_science.api.IColorLM2Engine
import com.leica.cam.color_science.api.SceneContext
import com.leica.cam.common.logging.LeicaLogger
import com.leica.cam.common.result.LeicaResult
import com.leica.cam.common.result.PipelineStage
import com.leica.cam.common.types.NonEmptyList
import com.leica.cam.depth_engine.api.DepthMap
import com.leica.cam.depth_engine.api.IDepthEngine
import com.leica.cam.face_engine.api.FaceAnalysis
import com.leica.cam.face_engine.api.IFaceEngine
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
import com.leica.cam.photon_matrix.PhotonBuffer
import com.leica.cam.photon_matrix.ProXdrOutputMode
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
        frames: NonEmptyList<Any>,
        plan: LumoCapturePlan,
    ): LeicaResult<LumoOutputPackage> = withContext(io) {
        // ── 1. Thermal gate ──────────────────────────────────────────────
        val budget = governor.checkBudget().getOrElse { return@withContext it }
        logger.info("LUMO", "LUMO capture: budget=$budget, frames=${frames.size}")

        // ── 2. Ingest → align → fuse (serial data dependency) ────────────
        val photon = photonMatrix.ingest(frames).getOrElse { return@withContext it }
        val aligned = motionEngine.align(photon, plan.motionConfig).getOrElse { return@withContext it }
        val fused = fusionEngine.fuse(aligned, plan.fusionConfig).getOrElse { return@withContext it }

        // ── 3. Parallel dispatch — colour, depth, face, scene ─────────────
        data class ParallelResults(
            val colour: ColourMappedBuffer,
            val depth: DepthMap,
            val face: FaceAnalysis,
            val scene: com.leica.cam.ai_engine.api.SceneAnalysis,
        )

        val parallel = coroutineScope {
            val cDeferred = async { colourEngine.mapColours(fused, plan.sceneContext) }
            val dDeferred = async { depthEngine.estimate(fused, plan.depthConfig) }
            val fDeferred = async { faceEngine.detect(fused) }
            val sDeferred = async { aiEngine.classifyAndScore(fused, plan.captureMode) }

            ParallelResults(
                colour = cDeferred.await().getOrElse { return@coroutineScope it },
                depth = dDeferred.await().getOrElse { return@coroutineScope it },
                face = fDeferred.await().getOrElse { return@coroutineScope it },
                scene = sDeferred.await().getOrElse { return@coroutineScope it },
            )
        }.let {
            when (it) {
                is LeicaResult.Success -> it.value
                is LeicaResult.Failure -> return@withContext it
                else -> return@withContext LeicaResult.Failure.Pipeline(
                    PipelineStage.SMART_IMAGING, "Parallel dispatch failed"
                )
            }
        }

        // ── 4. WB + Bokeh (parallel — both depend only on parallel results)
        val (wb, bokeh) = coroutineScope {
            val skinZones = SkinZoneMap(
                parallel.face.skinZones.width,
                parallel.face.skinZones.height,
                parallel.face.skinZones.mask,
            )
            val illuminantMap = IlluminantMap(
                tiles = emptyList(),
                dominantKelvin = parallel.scene.illuminantHint.estimatedKelvin,
            )
            val wbDeferred = async { wbEngine.correct(parallel.colour, skinZones, illuminantMap) }
            val bokehDeferred = async { bokehEngine.compute(parallel.depth, parallel.face.subjectBoundary, plan.bokehConfig) }

            wbDeferred.await().getOrElse { return@coroutineScope it } to
                bokehDeferred.await().getOrElse { return@coroutineScope it }
        }

        // ── 5. Tone → Neural ISP → Assemble (serial data dependency) ──────
        val toned = toneEngine.render(wb, bokeh, parallel.scene, plan.toneConfig).getOrElse { return@withContext it }
        val enhanced = neuralIsp.enhance(toned, budget).getOrElse { return@withContext it }
        return@withContext assembler.assemble(enhanced, plan.outputMode, IPhotonMatrixAssembler.OutputMetadata())
    }
}

class FusionLM2Engine {
    suspend fun fuse(
        aligned: com.leica.cam.motion_engine.api.AlignedBuffer,
        config: FusionConfig,
    ): LeicaResult<FusedPhotonBuffer> {
        // In production: per-pixel weighted average, confidence-weighted blending,
        // NEON SIMD via native bridge, minimum 2 frames for FusedPhotonBuffer.
        if (aligned.frames.size < config.minFrames) {
            return LeicaResult.Failure.Pipeline(
                PipelineStage.FUSION,
                "Need at least ${config.minFrames} frames for fusion, got ${aligned.frames.size}",
            )
        }
        // Stub: return fused buffer from first frame
        val firstFrame = aligned.frames.first()
        if (firstFrame is FusedPhotonBuffer) return LeicaResult.Success(firstFrame)
        return LeicaResult.Failure.Pipeline(PipelineStage.FUSION, "Fusion not yet fully implemented")
    }
}

class ToneLM2Engine {
    suspend fun render(
        wb: WbCorrectedBuffer,
        bokeh: com.leica.cam.bokeh_engine.api.BokehResult,
        scene: com.leica.cam.ai_engine.api.SceneAnalysis,
        config: ToneConfig,
    ): LeicaResult<TonedBuffer> {
        // In production: cinematic tone mapping via GPU compute shader tone_map.comp
        // Zone-aware: sky roll-off, skin S-curve, shadow lift
        val underlying = when (wb) {
            is WbCorrectedBuffer.Corrected -> wb.underlying
        }
        return LeicaResult.Success(TonedBuffer.TonedImage(underlying, config.profile))
    }
}

class RuntimeGovernor {
    fun checkBudget(): LeicaResult<ThermalBudget> =
        LeicaResult.Success(ThermalBudget(
            tier = com.leica.cam.neural_isp.api.ThermalTier.FULL,
            gpuTemperatureCelsius = 45f,
            processingBudgetMs = 500L,
        ))
}
