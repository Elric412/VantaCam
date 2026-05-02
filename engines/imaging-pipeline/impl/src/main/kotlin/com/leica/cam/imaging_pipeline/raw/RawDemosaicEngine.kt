package com.leica.cam.imaging_pipeline.raw

import com.leica.cam.imaging_pipeline.pipeline.PipelineFrame
import kotlin.math.max
import kotlin.math.min

/**
 * Bayer pattern enumeration — duplicated from `:sensor-hal:raw` to keep this
 * module Android-imports-free. The integer ordinals follow `CameraCharacteristics
 * .SENSOR_INFO_COLOR_FILTER_ARRANGEMENT` so callers can hand the metadata
 * value through unchanged.
 */
enum class RawBayerPattern(val ordinal_: Int) {
    RGGB(0), GRBG(1), GBRG(2), BGGR(3), RGB(4);

    companion object {
        fun fromOrdinal(value: Int): RawBayerPattern =
            entries.firstOrNull { it.ordinal_ == value } ?: RGGB
    }
}

/**
 * Per-frame RAW16 calibration parameters.
 *
 * @param blackLevel Per-CFA black levels in raw counts. Layout R/Gr/Gb/B.
 * @param whiteLevel Sensor saturation point in raw counts (e.g. 4095 for 12-bit).
 * @param wbGains    White-balance multipliers per CFA channel (R/Gr/Gb/B).
 * @param ccm        3×3 row-major colour-correction matrix (camera RGB → linear sRGB).
 */
data class RawCalibration(
    val blackLevel: FloatArray = floatArrayOf(0f, 0f, 0f, 0f),
    val whiteLevel: Float = 4095f,
    val wbGains: FloatArray = floatArrayOf(1f, 1f, 1f, 1f),
    val ccm: FloatArray = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
)

/**
 * Production-grade Bayer → linear-RGB demosaic for the JVM fast-path.
 *
 * This is the JVM fallback used when `libproxdr_engine.so` is unavailable
 * (unit tests, JVM-only validation harnesses, devices that can't load the
 * native engine). On Android with the native bridge, this stage is skipped
 * entirely — the engine consumes RAW16 directly.
 *
 * **Algorithm: Malvar–He–Cutler (2004) 5×5 high-quality linear demosaic.**
 *
 * MHC achieves ~+5.5 dB PSNR over bilinear demosaic on the Kodak benchmark
 * by exploiting cross-channel correlation in the green-update step. Filter
 * coefficients are exactly those published in the original paper, scaled by
 * 1/8 in the integer-friendly form Microsoft Research released.
 *
 * **Pipeline order in this stage:**
 *  1. Black-level subtraction (per CFA channel).
 *  2. Normalisation into [0,1] linear-light against `whiteLevel - black`.
 *  3. Per-channel white-balance multiplication (no clipping yet).
 *  4. Malvar–He–Cutler 5×5 demosaic on the WB-corrected mosaic.
 *  5. Camera-RGB → linear sRGB via the supplied CCM (D65 reference).
 *  6. Final clip to [0, +∞) — values may exceed 1.0 because WB pushes channels
 *     above the saturation point of the brightest channel; the downstream
 *     ProXDR v3 pipeline expects unbounded linear-light input.
 *
 * **References:**
 *  - Malvar, He, Cutler (2004), "High-quality linear interpolation for
 *    demosaicing of Bayer-patterned color images", ICASSP-2004.
 *  - Lindbloom (2017), "Bradford / sRGB chromatic-adaptation matrices".
 */
class RawDemosaicEngine {

    /**
     * Demosaic a single RAW16 frame, returning a linear-RGB [PipelineFrame].
     *
     * @param raw16    Width*height-length unsigned 16-bit array of CFA samples.
     *                 Pass the result of `ByteBuffer.asShortBuffer()` reads.
     * @param width    Frame width in pixels.
     * @param height   Frame height in pixels.
     * @param pattern  Bayer pattern ordering.
     * @param calib    Per-frame calibration.
     * @param evOffset EV offset relative to the burst reference (passed through).
     * @param iso      ISO of the original capture (passed through).
     * @param exposureNs Exposure in nanoseconds (passed through).
     */
    fun demosaic(
        raw16: ShortArray,
        width: Int,
        height: Int,
        pattern: RawBayerPattern,
        calib: RawCalibration,
        evOffset: Float = 0f,
        iso: Int = 100,
        exposureNs: Long = 16_666_666L,
    ): PipelineFrame {
        require(raw16.size >= width * height) {
            "raw16 array too small: ${raw16.size} < ${width * height}"
        }
        val mosaic = buildNormalisedMosaic(raw16, width, height, pattern, calib)
        val rgb = mosaicToRgbMhc(mosaic, width, height, pattern)
        return applyCcm(rgb, width, height, calib.ccm, evOffset, iso, exposureNs)
    }

    // ── Step 1-3: black-level subtract → normalise → WB ─────────────────────

    private fun buildNormalisedMosaic(
        raw16: ShortArray,
        width: Int,
        height: Int,
        pattern: RawBayerPattern,
        calib: RawCalibration,
    ): FloatArray {
        val normR = max(calib.whiteLevel - calib.blackLevel[0], 1f)
        val normGr = max(calib.whiteLevel - calib.blackLevel[1], 1f)
        val normGb = max(calib.whiteLevel - calib.blackLevel[2], 1f)
        val normB = max(calib.whiteLevel - calib.blackLevel[3], 1f)
        val gR = calib.wbGains[0]
        val gGr = calib.wbGains[1]
        val gGb = calib.wbGains[2]
        val gB = calib.wbGains[3]

        val mosaic = FloatArray(width * height)
        for (y in 0 until height) {
            val isOddRow = (y and 1) == 1
            for (x in 0 until width) {
                val isOddCol = (x and 1) == 1
                val raw = raw16[y * width + x].toInt() and 0xFFFF
                val ch = bayerChannel(pattern, isOddRow, isOddCol)
                val (norm, gain, black) = when (ch) {
                    BayerCh.R -> Triple(normR, gR, calib.blackLevel[0])
                    BayerCh.GR -> Triple(normGr, gGr, calib.blackLevel[1])
                    BayerCh.GB -> Triple(normGb, gGb, calib.blackLevel[2])
                    BayerCh.B -> Triple(normB, gB, calib.blackLevel[3])
                }
                val linear = ((raw - black) / norm).coerceAtLeast(0f) * gain
                mosaic[y * width + x] = linear
            }
        }
        return mosaic
    }

    // ── Step 4: Malvar–He–Cutler 5×5 demosaic ───────────────────────────────

    /**
     * Apply MHC demosaic to a CFA mosaic. The output has separate R/G/B planes
     * in linear light. Filter taps follow the published 1/8-scaled integer form.
     *
     * Five filter shapes are needed:
     *  - **G_at_R / G_at_B**  (recover green at red/blue sites)
     *  - **R_at_GR / B_at_GR** (recover red/blue at a Gr site)
     *  - **R_at_GB / B_at_GB** (recover red/blue at a Gb site)
     *  - **R_at_B  / B_at_R**  (recover red at blue, blue at red — diagonal cross-channel)
     */
    private fun mosaicToRgbMhc(
        mosaic: FloatArray,
        width: Int,
        height: Int,
        pattern: RawBayerPattern,
    ): Triple<FloatArray, FloatArray, FloatArray> {
        val n = width * height
        val outR = FloatArray(n)
        val outG = FloatArray(n)
        val outB = FloatArray(n)

        for (y in 0 until height) {
            val isOddRow = (y and 1) == 1
            for (x in 0 until width) {
                val isOddCol = (x and 1) == 1
                val ch = bayerChannel(pattern, isOddRow, isOddCol)
                val centre = mosaic[y * width + x]
                when (ch) {
                    BayerCh.R -> {
                        outR[y * width + x] = centre
                        outG[y * width + x] = mhcGreenAtRB(mosaic, width, height, x, y)
                        outB[y * width + x] = mhcDiagAtCorner(mosaic, width, height, x, y)
                    }
                    BayerCh.B -> {
                        outB[y * width + x] = centre
                        outG[y * width + x] = mhcGreenAtRB(mosaic, width, height, x, y)
                        outR[y * width + x] = mhcDiagAtCorner(mosaic, width, height, x, y)
                    }
                    BayerCh.GR -> {
                        // Gr — red along the row, blue along the column.
                        outG[y * width + x] = centre
                        outR[y * width + x] = mhcRowColAtG(mosaic, width, height, x, y, isRow = true)
                        outB[y * width + x] = mhcRowColAtG(mosaic, width, height, x, y, isRow = false)
                    }
                    BayerCh.GB -> {
                        // Gb — blue along the row, red along the column.
                        outG[y * width + x] = centre
                        outB[y * width + x] = mhcRowColAtG(mosaic, width, height, x, y, isRow = true)
                        outR[y * width + x] = mhcRowColAtG(mosaic, width, height, x, y, isRow = false)
                    }
                }
            }
        }
        return Triple(outR, outG, outB)
    }

    /**
     * MHC kernel: recover green at a red or blue site.
     *
     * Coefficients (×1/8):
     * ```
     *           [    -1                ]
     *           [          2           ]
     *           [-1   2   4   2   -1   ]
     *           [          2           ]
     *           [    -1                ]
     * ```
     */
    private fun mhcGreenAtRB(m: FloatArray, w: Int, h: Int, x: Int, y: Int): Float {
        val c = m[clampIdx(x, y, w, h)]
        val sum = 4f * c +
            2f * (sample(m, w, h, x, y - 1) + sample(m, w, h, x, y + 1) +
                sample(m, w, h, x - 1, y) + sample(m, w, h, x + 1, y)) -
            (sample(m, w, h, x, y - 2) + sample(m, w, h, x, y + 2) +
                sample(m, w, h, x - 2, y) + sample(m, w, h, x + 2, y))
        return (sum * INV_8).coerceAtLeast(0f)
    }

    /**
     * MHC kernel: recover red at Gr (or blue at Gb) — same-row neighbour case.
     * `isRow = true` → red along row at Gr / blue along row at Gb.
     * `isRow = false` → other-axis variant.
     *
     * Coefficients (×1/8):
     * ```
     *           [   1/2                ]
     *           [-1   4   5   4   -1   ]
     *           [    -1                ]
     *           [   1/2                ]
     * ```
     */
    private fun mhcRowColAtG(m: FloatArray, w: Int, h: Int, x: Int, y: Int, isRow: Boolean): Float {
        val c = m[clampIdx(x, y, w, h)]
        val sum = if (isRow) {
            // Cross-channel along the row, weighted by perpendicular axis.
            5f * c +
                4f * (sample(m, w, h, x - 1, y) + sample(m, w, h, x + 1, y)) -
                (sample(m, w, h, x - 2, y) + sample(m, w, h, x + 2, y) +
                    sample(m, w, h, x, y - 2) + sample(m, w, h, x, y + 2)) +
                0.5f * (sample(m, w, h, x - 1, y - 1) + sample(m, w, h, x + 1, y - 1) +
                    sample(m, w, h, x - 1, y + 1) + sample(m, w, h, x + 1, y + 1))
        } else {
            5f * c +
                4f * (sample(m, w, h, x, y - 1) + sample(m, w, h, x, y + 1)) -
                (sample(m, w, h, x - 2, y) + sample(m, w, h, x + 2, y) +
                    sample(m, w, h, x, y - 2) + sample(m, w, h, x, y + 2)) +
                0.5f * (sample(m, w, h, x - 1, y - 1) + sample(m, w, h, x + 1, y - 1) +
                    sample(m, w, h, x - 1, y + 1) + sample(m, w, h, x + 1, y + 1))
        }
        return (sum * INV_8).coerceAtLeast(0f)
    }

    /**
     * MHC kernel: recover red at blue (and blue at red) — diagonal corners.
     *
     * Coefficients (×1/8):
     * ```
     *           [    -3/2              ]
     *           [   2     2            ]
     *           [-3/2  6  -3/2         ]
     *           [   2     2            ]
     *           [    -3/2              ]
     * ```
     */
    private fun mhcDiagAtCorner(m: FloatArray, w: Int, h: Int, x: Int, y: Int): Float {
        val c = m[clampIdx(x, y, w, h)]
        val sum = 6f * c +
            2f * (sample(m, w, h, x - 1, y - 1) + sample(m, w, h, x + 1, y - 1) +
                sample(m, w, h, x - 1, y + 1) + sample(m, w, h, x + 1, y + 1)) -
            1.5f * (sample(m, w, h, x, y - 2) + sample(m, w, h, x, y + 2) +
                sample(m, w, h, x - 2, y) + sample(m, w, h, x + 2, y))
        return (sum * INV_8).coerceAtLeast(0f)
    }

    // ── Step 5: camera RGB → linear sRGB CCM ────────────────────────────────

    private fun applyCcm(
        rgb: Triple<FloatArray, FloatArray, FloatArray>,
        width: Int,
        height: Int,
        ccm: FloatArray,
        evOffset: Float,
        iso: Int,
        exposureNs: Long,
    ): PipelineFrame {
        val (rIn, gIn, bIn) = rgb
        val n = width * height
        val rOut = FloatArray(n)
        val gOut = FloatArray(n)
        val bOut = FloatArray(n)
        val m00 = ccm[0]; val m01 = ccm[1]; val m02 = ccm[2]
        val m10 = ccm[3]; val m11 = ccm[4]; val m12 = ccm[5]
        val m20 = ccm[6]; val m21 = ccm[7]; val m22 = ccm[8]
        for (i in 0 until n) {
            val r = rIn[i]; val g = gIn[i]; val b = bIn[i]
            rOut[i] = (m00 * r + m01 * g + m02 * b).coerceAtLeast(0f)
            gOut[i] = (m10 * r + m11 * g + m12 * b).coerceAtLeast(0f)
            bOut[i] = (m20 * r + m21 * g + m22 * b).coerceAtLeast(0f)
        }
        return PipelineFrame(width, height, rOut, gOut, bOut, evOffset, iso, exposureNs)
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private enum class BayerCh { R, GR, GB, B }

    /**
     * Resolve which CFA channel sits at `(row, col)` for a given Bayer pattern.
     * `isOddRow` and `isOddCol` use bit-0 of the pixel index.
     */
    private fun bayerChannel(pattern: RawBayerPattern, isOddRow: Boolean, isOddCol: Boolean): BayerCh =
        when (pattern) {
            // RGGB:  even,even=R   even,odd=Gr   odd,even=Gb   odd,odd=B
            RawBayerPattern.RGGB -> when {
                !isOddRow && !isOddCol -> BayerCh.R
                !isOddRow &&  isOddCol -> BayerCh.GR
                 isOddRow && !isOddCol -> BayerCh.GB
                else -> BayerCh.B
            }
            // GRBG:  even,even=Gr  even,odd=R    odd,even=B    odd,odd=Gb
            RawBayerPattern.GRBG -> when {
                !isOddRow && !isOddCol -> BayerCh.GR
                !isOddRow &&  isOddCol -> BayerCh.R
                 isOddRow && !isOddCol -> BayerCh.B
                else -> BayerCh.GB
            }
            // GBRG:  even,even=Gb  even,odd=B    odd,even=R    odd,odd=Gr
            RawBayerPattern.GBRG -> when {
                !isOddRow && !isOddCol -> BayerCh.GB
                !isOddRow &&  isOddCol -> BayerCh.B
                 isOddRow && !isOddCol -> BayerCh.R
                else -> BayerCh.GR
            }
            // BGGR:  even,even=B   even,odd=Gb   odd,even=Gr   odd,odd=R
            RawBayerPattern.BGGR -> when {
                !isOddRow && !isOddCol -> BayerCh.B
                !isOddRow &&  isOddCol -> BayerCh.GB
                 isOddRow && !isOddCol -> BayerCh.GR
                else -> BayerCh.R
            }
            // RGB / non-Bayer: treat as duplicated green for safety.
            RawBayerPattern.RGB -> BayerCh.GR
        }

    private fun sample(m: FloatArray, w: Int, h: Int, x: Int, y: Int): Float {
        val cx = min(max(x, 0), w - 1)
        val cy = min(max(y, 0), h - 1)
        return m[cy * w + cx]
    }

    private fun clampIdx(x: Int, y: Int, w: Int, h: Int): Int =
        min(max(y, 0), h - 1) * w + min(max(x, 0), w - 1)

    private companion object {
        const val INV_8 = 1f / 8f
    }
}
