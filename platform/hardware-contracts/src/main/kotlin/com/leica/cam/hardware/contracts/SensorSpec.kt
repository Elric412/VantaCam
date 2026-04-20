package com.leica.cam.hardware.contracts

data class SensorSpec(
    val id: String,
    val widthPx: Int,
    val heightPx: Int,
    val bitDepth: Int,
)

/** Hardware contract for the True Colour Camera sensor. */
interface TrueColourHardwareSensor {
    fun readFullGrid(): List<TrueColourRawReading>

    fun getConfidence(): Float
}

/** Raw reading from the True Colour hardware sensor. */
data class TrueColourRawReading(
    val row: Int,
    val col: Int,
    val kelvin: Float,
    val lux: Float,
    val confidence: Float,
)
