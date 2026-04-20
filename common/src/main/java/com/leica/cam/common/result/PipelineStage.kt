package com.leica.cam.common.result

/**
 * Exhaustive enumeration of pipeline stages for [LeicaResult.Failure.Pipeline].
 *
 * Every engine that can produce a [LeicaResult.Failure] MUST have a corresponding
 * entry here. Adding a new engine without adding its stage is a compile-time
 * omission that will be caught by exhaustive `when` expressions.
 */
enum class PipelineStage {
    SESSION,
    INGEST,
    ALIGNMENT,
    FUSION,
    COLOUR,
    DEPTH,
    FACE,
    WB,
    BOKEH,
    TONE,
    ISP,
    AI_ANALYSIS,
    METADATA,
    SMART_IMAGING,
    IMAGING_PIPELINE,
    GPU_COMPUTE,
    // ── LUMO 2.0 stages ──────────────────────────────────────────────
    DENOISE,
    SHARPEN,
    LENS_CORRECTION,
    MODEL_REGISTRY,
    SENSOR_DETECTION,
    SOC_DETECTION,
    AI_ENGINE,
}

