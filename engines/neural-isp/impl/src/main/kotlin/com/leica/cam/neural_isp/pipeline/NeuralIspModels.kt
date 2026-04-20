package com.leica.cam.neural_isp.pipeline

import com.leica.cam.ai_engine.api.SceneType
import com.leica.cam.ai_engine.api.SegmentationMask

/** RGGB Bayer mosaic pattern currently supported by the neural ISP models. */
enum class BayerPattern {
    RGGB,
}

/** Two-parameter sensor noise profile where noise(I) = sqrt(a * I + b). */
data class SensorNoiseProfile(
    val a: Float,
    val b: Float,
) {
    init {
        require(a >= 0f) { "Noise coefficient a must be non-negative." }
        require(b >= 0f) { "Noise coefficient b must be non-negative." }
    }
}

/** Raw Bayer frame split in four quarter-resolution planes for RGGB processing. */
data class RawBayerFrame(
    val width: Int,
    val height: Int,
    val pattern: BayerPattern,
    val r: FloatArray,
    val gEven: FloatArray,
    val gOdd: FloatArray,
    val b: FloatArray,
) {
    init {
        require(width > 1 && height > 1) { "Raw dimensions must be > 1." }
        require(width % 2 == 0 && height % 2 == 0) { "Raw dimensions must be even for RGGB packing." }
        val expected = (width / 2) * (height / 2)
        require(r.size == expected && gEven.size == expected && gOdd.size == expected && b.size == expected) {
            "All packed Bayer planes must match width/2 * height/2."
        }
    }

    val packedWidth: Int = width / 2
    val packedHeight: Int = height / 2
}

/** Linear RGB frame used across demosaicing and color/tone stages. */
data class RgbFrame(
    val width: Int,
    val height: Int,
    val red: FloatArray,
    val green: FloatArray,
    val blue: FloatArray,
) {
    init {
        val expected = width * height
        require(width > 0 && height > 0) { "RGB dimensions must be positive." }
        require(red.size == expected && green.size == expected && blue.size == expected) {
            "RGB channels must match width * height."
        }
    }

    val pixelCount: Int = width * height
}

/** Thermal state input from system power/thermal manager required for routing decisions. */
data class ThermalStatus(
    val gpuTemperatureCelsius: Float,
    val isThermalThrottled: Boolean,
)

/** Device tier and runtime budget controls for choosing neural vs traditional ISP. */
data class IspRoutingContext(
    val socModel: String,
    val thermal: ThermalStatus,
    val processingBudgetMs: Int,
    val isVideoMode: Boolean,
    val isFastModeEnabled: Boolean,
    val resolutionMegapixels: Float,
)

/** Inputs consumed by both neural and traditional ISP processors. */
data class ImagePipelineRequest(
    val raw: RawBayerFrame,
    val noiseProfile: SensorNoiseProfile,
    val sceneCctKelvin: Int,
    val sceneType: SceneType,
    val segmentationMask: SegmentationMask? = null,
    val routing: IspRoutingContext,
)

/** Pipeline output with metadata needed by callers and telemetry layers. */
data class ImagePipelineResult(
    val output: RgbFrame,
    val processor: IspProcessorType,
    val latencyMs: Long,
)

/** Concrete pipeline implementation type used for transparent routing logs. */
enum class IspProcessorType {
    NEURAL,
    TRADITIONAL,
}

/** Common processing contract shared by neural and traditional ISP stacks. */
fun interface ImagePipelineProcessor {
    fun process(request: ImagePipelineRequest): ImagePipelineResult
}
