package com.leica.cam.hypertone_wb.pipeline

import kotlin.math.abs

class IlluminantEstimators(
    val global: IlluminantPredictor,
    val local: IlluminantPredictor,
    val semantic: IlluminantPredictor,
)

/**
 * Simple heuristic IlluminantPredictor that returns a fixed CCT offset from a base.
 * Used as a lightweight placeholder for production ML-based predictors.
 */
class HeuristicIlluminantPredictor(
    private val cctOffsetKelvin: Float,
    private val tintOffset: Float,
    private val baseConfidence: Float,
) : IlluminantPredictor {
    override fun predict(frame: RgbFrame, sceneContext: SceneContext?): NeuralIlluminantPrediction {
        return NeuralIlluminantPrediction(
            cctKelvin = (6500f + cctOffsetKelvin).coerceIn(1667f, 25000f),
            tint = tintOffset,
            confidence = baseConfidence,
        )
    }
}


