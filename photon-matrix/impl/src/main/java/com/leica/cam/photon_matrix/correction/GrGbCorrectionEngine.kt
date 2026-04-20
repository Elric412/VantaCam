package com.leica.cam.photon_matrix.correction

import com.leica.cam.sensor_hal.sensor.profiles.SensorProfile
import kotlin.math.abs

/**
 * Gr/Gb green-channel-split correction for OmniVision PureCel Plus-S sensors.
 *
 * OmniVision sensors (OV64B40, OV50D40, OV08D10, OV16A1Q) exhibit a measurable
 * difference between the Gr (green-red row) and Gb (green-blue row) pixel
 * responses. This manifests as fine-grained "maze" pattern noise visible in
 * uniformly lit regions.
 *
 * Samsung ISOCELL 2.0 sensors (S5KHM6) have negligible Gr/Gb split and SHOULD NOT
 * be corrected — applying this to ISOCELL sensors adds computational cost with
 * zero quality benefit.
 *
 * Algorithm:
 * 1. Compute mean(Gr) and mean(Gb) from the Bayer quad pattern.
 * 2. If |mean(Gr) − mean(Gb)| > threshold (sensor-specific), apply correction.
 * 3. Correction: equalise Gr and Gb to their average: Gr' = Gb' = (Gr + Gb) / 2
 *    applied per-pixel within the Bayer mosaic.
 *
 * This MUST be applied BEFORE demosaicing — after demosaicing, Gr/Gb split
 * is permanently baked into the green channel and cannot be corrected.
 *
 * References:
 * - OmniVision PureCel Plus-S datasheet: Gr/Gb imbalance specification
 * - Dimension 5 spec: "OV64B40 Gr/Gb split correction"
 */
class GrGbCorrectionEngine {

    /**
     * Result of Gr/Gb analysis.
     *
     * @param grMean     Mean value of green-red pixels
     * @param gbMean     Mean value of green-blue pixels
     * @param deltaDn    |mean(Gr) − mean(Gb)| in digital numbers (normalised)
     * @param correctionApplied Whether correction was applied (depends on sensor threshold)
     */
    data class GrGbAnalysis(
        val grMean: Float,
        val gbMean: Float,
        val deltaDn: Float,
        val correctionApplied: Boolean,
    )

    /**
     * Analyse and optionally correct Gr/Gb split in a packed Bayer frame.
     *
     * @param grPlane    Green-red pixel plane (quarter resolution)
     * @param gbPlane    Green-blue pixel plane (quarter resolution)
     * @param packedWidth  Width of packed Bayer plane (= raw_width / 2)
     * @param packedHeight Height of packed Bayer plane (= raw_height / 2)
     * @param sensorProfile Sensor profile for threshold lookup
     * @return Corrected (Gr, Gb) planes and analysis metadata
     */
    fun analyseAndCorrect(
        grPlane: FloatArray,
        gbPlane: FloatArray,
        packedWidth: Int,
        packedHeight: Int,
        sensorProfile: SensorProfile,
    ): Triple<FloatArray, FloatArray, GrGbAnalysis> {
        require(grPlane.size == packedWidth * packedHeight) {
            "Gr plane size must match packed dimensions"
        }
        require(gbPlane.size == packedWidth * packedHeight) {
            "Gb plane size must match packed dimensions"
        }

        // Step 1: Compute mean(Gr) and mean(Gb) — strided sampling for speed
        val sampleStride = 4 // Sample every 4th pixel for mean estimation
        var sumGr = 0.0; var sumGb = 0.0; var count = 0L
        for (i in grPlane.indices step sampleStride) {
            sumGr += grPlane[i]
            sumGb += gbPlane[i]
            count++
        }
        val grMean = (sumGr / count).toFloat()
        val gbMean = (sumGb / count).toFloat()
        val deltaDn = abs(grMean - gbMean)

        // Step 2: Check sensor-specific threshold
        val needsCorrection = sensorProfile.requiresGrGbCorrection(deltaDn)

        val analysis = GrGbAnalysis(
            grMean = grMean,
            gbMean = gbMean,
            deltaDn = deltaDn,
            correctionApplied = needsCorrection,
        )

        return if (!needsCorrection) {
            Triple(grPlane, gbPlane, analysis)
        } else {
            // Step 3: Equalise per-pixel — Gr' = Gb' = (Gr + Gb) / 2
            val correctedGr = FloatArray(grPlane.size)
            val correctedGb = FloatArray(gbPlane.size)
            for (i in grPlane.indices) {
                val avg = (grPlane[i] + gbPlane[i]) * 0.5f
                correctedGr[i] = avg
                correctedGb[i] = avg
            }
            Triple(correctedGr, correctedGb, analysis)
        }
    }

    /**
     * Row-based fixed-pattern noise (FPN) correction.
     *
     * Applied to sensors where row FPN exceeds the threshold at the current ISO
     * (configured per-sensor in [SensorProfile.fpnCorrection.rowFpnIsoThreshold]).
     *
     * Algorithm: estimate per-row offset from the mean of optical-black pixels
     * or from the row median, then subtract from every pixel in that row.
     *
     * @param channel      Single Bayer channel (full resolution, one colour plane)
     * @param width        Channel width
     * @param height       Channel height
     * @param currentIso   Current capture ISO
     * @param sensorProfile Sensor profile for ISO threshold lookup
     * @return Corrected channel, or original if ISO is below threshold
     */
    fun correctRowFpn(
        channel: FloatArray,
        width: Int,
        height: Int,
        currentIso: Int,
        sensorProfile: SensorProfile,
    ): FloatArray {
        if (currentIso < sensorProfile.fpnCorrection.rowFpnIsoThreshold) {
            return channel
        }

        val corrected = channel.copyOf()
        // Estimate per-row bias from central 80% of each row (avoid vignetted edges)
        val startX = (width * 0.1f).toInt()
        val endX = (width * 0.9f).toInt()
        val sampleWidth = endX - startX

        for (y in 0 until height) {
            // Compute row mean
            var rowSum = 0.0
            for (x in startX until endX) {
                rowSum += channel[y * width + x]
            }
            val rowMean = (rowSum / sampleWidth).toFloat()

            // Compute global mean of all sampled rows (lazy: approximate from first row)
            // In production, precompute global mean from optical black region
            val globalMean = 0.5f // Placeholder — in production, use OB region

            val rowBias = rowMean - globalMean
            if (abs(rowBias) > 0.001f) {
                for (x in 0 until width) {
                    corrected[y * width + x] = (channel[y * width + x] - rowBias).coerceAtLeast(0f)
                }
            }
        }

        return corrected
    }

    /**
     * Column-based FPN correction for OV08D10 ultra-wide sensor.
     *
     * The OV08D10 exhibits pronounced column FPN due to its readout architecture.
     * This is applied only when [SensorProfile.fpnCorrection.columnFpnEnabled] is true.
     *
     * Same algorithm as row FPN but transposed.
     */
    fun correctColumnFpn(
        channel: FloatArray,
        width: Int,
        height: Int,
        sensorProfile: SensorProfile,
    ): FloatArray {
        if (!sensorProfile.fpnCorrection.columnFpnEnabled) return channel

        val corrected = channel.copyOf()
        val startY = (height * 0.1f).toInt()
        val endY = (height * 0.9f).toInt()
        val sampleHeight = endY - startY

        for (x in 0 until width) {
            var colSum = 0.0
            for (y in startY until endY) {
                colSum += channel[y * width + x]
            }
            val colMean = (colSum / sampleHeight).toFloat()
            val globalMean = 0.5f

            val colBias = colMean - globalMean
            if (abs(colBias) > 0.001f) {
                for (y in 0 until height) {
                    corrected[y * width + x] = (channel[y * width + x] - colBias).coerceAtLeast(0f)
                }
            }
        }

        return corrected
    }
}
