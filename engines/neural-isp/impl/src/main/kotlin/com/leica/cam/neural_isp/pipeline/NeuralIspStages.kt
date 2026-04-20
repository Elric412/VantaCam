package com.leica.cam.neural_isp.pipeline

import com.leica.cam.ai_engine.api.SceneType
import com.leica.cam.ai_engine.api.SegmentationMask
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

private const val STAGE0_TILE_SIZE = 256
private const val STAGE0_TILE_OVERLAP = 32
private const val STAGE1_TILE_SIZE = 512
private const val STAGE1_TILE_OVERLAP = 16
private const val EPSILON = 1e-6f

/** Stage 0 raw-to-raw denoising with sigma-conditioned bilateral-like smoothing. */
class RawDenoiseStage {
    fun run(raw: RawBayerFrame, noiseProfile: SensorNoiseProfile): RawBayerFrame {
        return RawBayerFrame(
            width = raw.width,
            height = raw.height,
            pattern = raw.pattern,
            r = denoisePlaneTiled(raw.r, raw.packedWidth, raw.packedHeight, noiseProfile),
            gEven = denoisePlaneTiled(raw.gEven, raw.packedWidth, raw.packedHeight, noiseProfile),
            gOdd = denoisePlaneTiled(raw.gOdd, raw.packedWidth, raw.packedHeight, noiseProfile),
            b = denoisePlaneTiled(raw.b, raw.packedWidth, raw.packedHeight, noiseProfile),
        )
    }

    private fun denoisePlaneTiled(
        source: FloatArray,
        width: Int,
        height: Int,
        noiseProfile: SensorNoiseProfile,
    ): FloatArray {
        val accum = FloatArray(source.size)
        val weight = FloatArray(source.size)

        val step = (STAGE0_TILE_SIZE - STAGE0_TILE_OVERLAP * 2).coerceAtLeast(16)
        var tileY = 0
        while (tileY < height) {
            val yStart = (tileY - STAGE0_TILE_OVERLAP).coerceAtLeast(0)
            val yEnd = (tileY + STAGE0_TILE_SIZE + STAGE0_TILE_OVERLAP).coerceAtMost(height)
            var tileX = 0
            while (tileX < width) {
                val xStart = (tileX - STAGE0_TILE_OVERLAP).coerceAtLeast(0)
                val xEnd = (tileX + STAGE0_TILE_SIZE + STAGE0_TILE_OVERLAP).coerceAtMost(width)
                processTile(source, accum, weight, width, height, xStart, xEnd, yStart, yEnd, noiseProfile)
                tileX += step
            }
            tileY += step
        }

        return FloatArray(source.size) { index ->
            if (weight[index] <= EPSILON) source[index] else (accum[index] / weight[index]).coerceIn(0f, 1f)
        }
    }

    private fun processTile(
        source: FloatArray,
        accum: FloatArray,
        weight: FloatArray,
        width: Int,
        height: Int,
        xStart: Int,
        xEnd: Int,
        yStart: Int,
        yEnd: Int,
        noiseProfile: SensorNoiseProfile,
    ) {
        for (y in yStart until yEnd) {
            for (x in xStart until xEnd) {
                val index = y * width + x
                val center = source[index]
                val sigma = sqrt((noiseProfile.a * center + noiseProfile.b).coerceAtLeast(EPSILON))
                var weightedValue = 0f
                var totalWeight = 0f

                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val sx = (x + dx).coerceIn(0, width - 1)
                        val sy = (y + dy).coerceIn(0, height - 1)
                        val sample = source[sy * width + sx]
                        val spatialWeight = if (dx == 0 && dy == 0) 1f else 0.7f
                        val rangeWeight = exp(-abs(sample - center) / (sigma + EPSILON))
                        val kernelWeight = spatialWeight * rangeWeight
                        weightedValue += sample * kernelWeight
                        totalWeight += kernelWeight
                    }
                }

                val denoised = if (totalWeight <= EPSILON) center else weightedValue / totalWeight
                val blendWeight = tileWindowWeight(x, y, xStart, xEnd, yStart, yEnd)
                accum[index] += denoised * blendWeight
                weight[index] += blendWeight
            }
        }
    }

    private fun tileWindowWeight(
        x: Int,
        y: Int,
        xStart: Int,
        xEnd: Int,
        yStart: Int,
        yEnd: Int,
    ): Float {
        val distanceLeft = (x - xStart).toFloat()
        val distanceRight = (xEnd - 1 - x).toFloat()
        val distanceTop = (y - yStart).toFloat()
        val distanceBottom = (yEnd - 1 - y).toFloat()
        val edgeDistance = minOf(distanceLeft, distanceRight, distanceTop, distanceBottom)
        if (edgeDistance >= STAGE0_TILE_OVERLAP) return 1f
        val normalized = (edgeDistance / STAGE0_TILE_OVERLAP).coerceIn(0f, 1f)
        return (0.5f - 0.5f * kotlin.math.cos(Math.PI.toFloat() * normalized)).coerceIn(0f, 1f)
    }
}

/** Stage 1 learned demosaicing surrogate with edge-aware interpolation in packed Bayer domain. */
class LearnedDemosaicStage {
    fun run(raw: RawBayerFrame): RgbFrame {
        val outR = FloatArray(raw.width * raw.height)
        val outG = FloatArray(raw.width * raw.height)
        val outB = FloatArray(raw.width * raw.height)

        processInTiles(raw, outR, outG, outB)
        return RgbFrame(raw.width, raw.height, outR, outG, outB)
    }

    private fun processInTiles(raw: RawBayerFrame, outR: FloatArray, outG: FloatArray, outB: FloatArray) {
        val step = (STAGE1_TILE_SIZE - STAGE1_TILE_OVERLAP * 2).coerceAtLeast(32)
        var tileY = 0
        while (tileY < raw.height) {
            val yStart = (tileY - STAGE1_TILE_OVERLAP).coerceAtLeast(0)
            val yEnd = (tileY + STAGE1_TILE_SIZE + STAGE1_TILE_OVERLAP).coerceAtMost(raw.height)
            var tileX = 0
            while (tileX < raw.width) {
                val xStart = (tileX - STAGE1_TILE_OVERLAP).coerceAtLeast(0)
                val xEnd = (tileX + STAGE1_TILE_SIZE + STAGE1_TILE_OVERLAP).coerceAtMost(raw.width)
                demosaicTile(raw, outR, outG, outB, xStart, xEnd, yStart, yEnd)
                tileX += step
            }
            tileY += step
        }
    }

    private fun demosaicTile(
        raw: RawBayerFrame,
        outR: FloatArray,
        outG: FloatArray,
        outB: FloatArray,
        xStart: Int,
        xEnd: Int,
        yStart: Int,
        yEnd: Int,
    ) {
        for (y in yStart until yEnd) {
            for (x in xStart until xEnd) {
                val index = y * raw.width + x
                outR[index] = samplePlane(raw.r, raw.packedWidth, raw.packedHeight, x * 0.5f, y * 0.5f)
                outG[index] = 0.5f * (
                    samplePlane(raw.gEven, raw.packedWidth, raw.packedHeight, x * 0.5f, y * 0.5f) +
                        samplePlane(raw.gOdd, raw.packedWidth, raw.packedHeight, x * 0.5f, y * 0.5f)
                    )
                outB[index] = samplePlane(raw.b, raw.packedWidth, raw.packedHeight, x * 0.5f, y * 0.5f)
            }
        }
    }

    private fun samplePlane(source: FloatArray, width: Int, height: Int, x: Float, y: Float): Float {
        val cx = x.coerceIn(0f, (width - 1).toFloat())
        val cy = y.coerceIn(0f, (height - 1).toFloat())
        val x0 = cx.toInt()
        val y0 = cy.toInt()
        val x1 = (x0 + 1).coerceAtMost(width - 1)
        val y1 = (y0 + 1).coerceAtMost(height - 1)
        val wx = cx - x0
        val wy = cy - y0

        val top = source[y0 * width + x0] + (source[y0 * width + x1] - source[y0 * width + x0]) * wx
        val bottom = source[y1 * width + x0] + (source[y1 * width + x1] - source[y1 * width + x0]) * wx
        return (top + (bottom - top) * wy).coerceIn(0f, 1f)
    }
}

/** Stage 2 color and tone network approximation conditioned on CCT + scene class. */
class ColorToneStage {
    fun run(frame: RgbFrame, sceneCctKelvin: Int, sceneType: SceneType): RgbFrame {
        val red = frame.red.copyOf()
        val green = frame.green.copyOf()
        val blue = frame.blue.copyOf()
        val cct = sceneCctKelvin.coerceIn(1800, 12_000)

        val warmBias = ((6_500 - cct).toFloat() / 6_500f).coerceIn(-0.7f, 0.7f)
        val coolBias = ((cct - 6_500).toFloat() / 6_500f).coerceIn(-0.7f, 0.7f)
        val sceneContrast = sceneContrast(sceneType)
        val sceneSaturation = sceneSaturation(sceneType)

        for (index in 0 until frame.pixelCount) {
            var r = red[index] * (1f + coolBias * 0.08f)
            var g = green[index] * (1f + (sceneContrast - 1f) * 0.03f)
            var b = blue[index] * (1f + warmBias * 0.08f)

            val luminance = (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceIn(0f, 1f)
            r = applyContrast(r, luminance, sceneContrast)
            g = applyContrast(g, luminance, sceneContrast)
            b = applyContrast(b, luminance, sceneContrast)

            val gray = (r + g + b) / 3f
            red[index] = applyGamma((gray + (r - gray) * sceneSaturation).coerceIn(0f, 1f))
            green[index] = applyGamma((gray + (g - gray) * sceneSaturation).coerceIn(0f, 1f))
            blue[index] = applyGamma((gray + (b - gray) * sceneSaturation).coerceIn(0f, 1f))
        }

        return RgbFrame(frame.width, frame.height, red, green, blue)
    }

    private fun sceneContrast(scene: SceneType): Float = when (scene) {
        SceneType.NIGHT -> 1.12f
        SceneType.LANDSCAPE -> 1.1f
        SceneType.DOCUMENT -> 1.15f
        SceneType.BACKLIT -> 1.08f
        else -> 1.04f
    }

    private fun sceneSaturation(scene: SceneType): Float = when (scene) {
        SceneType.PORTRAIT -> 1.02f
        SceneType.FOOD -> 1.12f
        SceneType.LANDSCAPE -> 1.1f
        SceneType.NIGHT -> 0.95f
        else -> 1f
    }

    private fun applyContrast(channel: Float, luminance: Float, contrast: Float): Float {
        return (luminance + (channel - luminance) * contrast).coerceIn(0f, 1f)
    }

    private fun applyGamma(value: Float): Float = value.pow(1f / 2.2f)
}

/** Stage 3 semantic detail enhancement with class-aware local contrast gains. */
class SemanticEnhancementStage {
    fun run(frame: RgbFrame, segmentationMask: SegmentationMask?): RgbFrame {
        if (segmentationMask == null || segmentationMask.labels.size != frame.pixelCount) {
            return frame
        }

        val outR = frame.red.copyOf()
        val outG = frame.green.copyOf()
        val outB = frame.blue.copyOf()

        for (index in 0 until frame.pixelCount) {
            val strength = classStrength(segmentationMask.labels[index], segmentationMask.confidence[index])
            if (strength <= 0f) continue
            val x = index % frame.width
            val y = index / frame.width

            val localR = localMean(frame.red, frame.width, frame.height, x, y)
            val localG = localMean(frame.green, frame.width, frame.height, x, y)
            val localB = localMean(frame.blue, frame.width, frame.height, x, y)

            outR[index] = (frame.red[index] + (frame.red[index] - localR) * strength).coerceIn(0f, 1f)
            outG[index] = (frame.green[index] + (frame.green[index] - localG) * strength).coerceIn(0f, 1f)
            outB[index] = (frame.blue[index] + (frame.blue[index] - localB) * strength).coerceIn(0f, 1f)
        }

        return RgbFrame(frame.width, frame.height, outR, outG, outB)
    }

    private fun classStrength(label: Int, confidence: Float): Float {
        val base = when (label) {
            0 -> 0.10f // background
            1 -> 0.22f // foreground/subject
            2 -> 0.06f // sky
            else -> 0.12f
        }
        return (base * confidence.coerceIn(0f, 1f)).coerceIn(0f, 0.28f)
    }

    private fun localMean(source: FloatArray, width: Int, height: Int, x: Int, y: Int): Float {
        var sum = 0f
        var count = 0
        for (dy in -1..1) {
            for (dx in -1..1) {
                val sx = (x + dx).coerceIn(0, width - 1)
                val sy = (y + dy).coerceIn(0, height - 1)
                sum += source[sy * width + sx]
                count += 1
            }
        }
        return (sum / count).coerceIn(0f, 1f)
    }
}
