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

    // ── Capture orchestration stages ─────────────────────────────────
    CAPTURE_ORCHESTRATION,
    ZSL_BUFFER,
    AUTOFOCUS,
    METERING,
    ISP_ROUTING,
    COLOR_TRANSFORM,
    PERCEPTUAL_TONE,
    FILM_GRAIN,
    LUT_3D,
    SKIN_TONE,
    OUTPUT_ENCODING,

    // ── Advanced capture pipeline stages ──────────────────────────────
    HDR_STRATEGY,
    HDR_MERGE,
    DEHAZE,
    CLARITY,
    SHOT_QUALITY,
    PORTRAIT_MODE,
    PROCESSING_BUDGET,
    PER_HUE_HSL,
    CAM16_COLOR,
}

