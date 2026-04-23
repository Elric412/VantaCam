package com.leica.cam.imaging_pipeline.pipeline

enum class SemanticZone(val tonePriority: Float) {
    FACE(1.00f),
    PERSON(0.85f),
    SUBJECT(0.70f),
    MIDGROUND(0.50f),
    BACKGROUND(0.30f),
    SKY(0.15f),
    UNKNOWN(0.40f),
}

data class SemanticMask(
    val width: Int,
    val height: Int,
    val zones: Array<SemanticZone>,
)
