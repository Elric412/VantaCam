package com.leica.cam.hypertone_wb.pipeline

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

/** Color temperature and chromatic adaptation utilities for HyperTone WB. */
object ColorTemperatureMath {
    private const val MIN_CCT = 2000f
    private const val MAX_CCT = 12000f

    fun clampCct(cctKelvin: Float): Float = cctKelvin.coerceIn(MIN_CCT, MAX_CCT)

    /** Approximate conversion from CCT to CIE xy (McCamy/Hernandez-Andres segmented approximation). */
    fun cctToXy(cctKelvin: Float): Pair<Float, Float> {
        val t = clampCct(cctKelvin)
        val x = if (t <= 4000f) {
            -0.2661239e9f / t.pow(3) - 0.2343589e6f / t.pow(2) + 0.8776956e3f / t + 0.179910f
        } else {
            -3.0258469e9f / t.pow(3) + 2.1070379e6f / t.pow(2) + 0.2226347e3f / t + 0.240390f
        }

        val y = when {
            t < 2222f -> -1.1063814f * x * x * x - 1.34811020f * x * x + 2.18555832f * x - 0.20219683f
            t < 4000f -> -0.9549476f * x * x * x - 1.37418593f * x * x + 2.09137015f * x - 0.16748867f
            else -> 3.0817580f * x * x * x - 5.87338670f * x * x + 3.75112997f * x - 0.37001483f
        }
        return x to y
    }

    fun xyToUv(x: Float, y: Float): Pair<Float, Float> {
        val denominator = (-2f * x) + (12f * y) + 3f
        if (abs(denominator) < 1e-6f) return 0f to 0f
        val u = 4f * x / denominator
        val v = 9f * y / denominator
        return u to v
    }

    fun xyToXyz(x: Float, y: Float): FloatArray {
        val safeY = max(y, 1e-6f)
        val xComp = x / safeY
        val yComp = 1f
        val zComp = (1f - x - y) / safeY
        return floatArrayOf(xComp, yComp, zComp)
    }

    /**
     * Converts CCT+tint to target xy where tint shifts in uv-space perpendicular approximation.
     * Positive tint shifts toward green as requested in Implementation.md.
     */
    fun cctTintToXy(cctKelvin: Float, tint: Float): Pair<Float, Float> {
        val (x, y) = cctToXy(cctKelvin)
        val (u, v) = xyToUv(x, y)
        val shiftedV = v + tint * 0.0025f
        return uvToXy(u, shiftedV)
    }

    fun uvToXy(u: Float, v: Float): Pair<Float, Float> {
        val denominator = (6f * u) - (16f * v) + 12f
        if (abs(denominator) < 1e-6f) {
            return 0.3127f to 0.3290f
        }
        val x = 9f * u / denominator
        val y = 4f * v / denominator
        return x to y
    }

    fun multiply3x3(a: FloatArray, b: FloatArray): FloatArray {
        require(a.size == 9 && b.size == 9) { "Both matrices must be 3x3" }
        val out = FloatArray(9)
        for (row in 0..2) {
            for (col in 0..2) {
                out[row * 3 + col] =
                    a[row * 3] * b[col] +
                    a[row * 3 + 1] * b[3 + col] +
                    a[row * 3 + 2] * b[6 + col]
            }
        }
        return out
    }

    fun invert3x3(input: FloatArray): FloatArray {
        require(input.size == 9) { "Matrix must be 3x3" }
        val a = input[0]
        val b = input[1]
        val c = input[2]
        val d = input[3]
        val e = input[4]
        val f = input[5]
        val g = input[6]
        val h = input[7]
        val i = input[8]

        val cofactor00 = e * i - f * h
        val cofactor01 = -(d * i - f * g)
        val cofactor02 = d * h - e * g
        val cofactor10 = -(b * i - c * h)
        val cofactor11 = a * i - c * g
        val cofactor12 = -(a * h - b * g)
        val cofactor20 = b * f - c * e
        val cofactor21 = -(a * f - c * d)
        val cofactor22 = a * e - b * d

        val determinant = a * cofactor00 + b * cofactor01 + c * cofactor02
        require(abs(determinant) > 1e-9f) { "Matrix is singular" }
        val invDet = 1f / determinant

        return floatArrayOf(
            cofactor00 * invDet,
            cofactor10 * invDet,
            cofactor20 * invDet,
            cofactor01 * invDet,
            cofactor11 * invDet,
            cofactor21 * invDet,
            cofactor02 * invDet,
            cofactor12 * invDet,
            cofactor22 * invDet,
        )
    }
}
