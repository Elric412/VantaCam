package com.leica.cam.hypertone_wb.pipeline

/** Kelvin+tint to sensor color correction matrix conversion (phase 4.2). */
class KelvinToCcmConverter {
    private val d65Xyz = floatArrayOf(0.95047f, 1.0f, 1.08883f)

    // CAT16 adaptation transform matrices.
    private val cat16Forward = floatArrayOf(
        0.401288f, 0.650173f, -0.051461f,
        -0.250268f, 1.204414f, 0.045854f,
        -0.002079f, 0.048952f, 0.953127f,
    )

    private val cat16Inverse = ColorTemperatureMath.invert3x3(cat16Forward)

    /**
     * Builds the final matrix described in Implementation chapter 6.2:
     * `CCM_final = CCM_WB x CCM_sensor_to_XYZ`.
     */
    fun computeFinalCcm(
        cctKelvin: Float,
        tint: Float,
        sensorToXyz3x3: FloatArray,
    ): FloatArray {
        val (x, y) = ColorTemperatureMath.cctTintToXy(cctKelvin, tint)
        val sourceXyz = ColorTemperatureMath.xyToXyz(x, y)
        val wbMatrix = computeCat16Adaptation(sourceXyz, d65Xyz)
        return ColorTemperatureMath.multiply3x3(wbMatrix, sensorToXyz3x3)
    }

    private fun computeCat16Adaptation(sourceWhiteXyz: FloatArray, targetWhiteXyz: FloatArray): FloatArray {
        val sourceCone = multiply3x1(cat16Forward, sourceWhiteXyz)
        val targetCone = multiply3x1(cat16Forward, targetWhiteXyz)

        val scale = floatArrayOf(
            targetCone[0] / sourceCone[0].coerceAtLeast(1e-6f),
            targetCone[1] / sourceCone[1].coerceAtLeast(1e-6f),
            targetCone[2] / sourceCone[2].coerceAtLeast(1e-6f),
        )

        val diagonal = floatArrayOf(
            scale[0], 0f, 0f,
            0f, scale[1], 0f,
            0f, 0f, scale[2],
        )

        val scaled = ColorTemperatureMath.multiply3x3(diagonal, cat16Forward)
        return ColorTemperatureMath.multiply3x3(cat16Inverse, scaled)
    }

    private fun multiply3x1(matrix3x3: FloatArray, vector3: FloatArray): FloatArray {
        require(matrix3x3.size == 9) { "Matrix must be 3x3" }
        require(vector3.size == 3) { "Vector must be 3 elements" }
        return floatArrayOf(
            matrix3x3[0] * vector3[0] + matrix3x3[1] * vector3[1] + matrix3x3[2] * vector3[2],
            matrix3x3[3] * vector3[0] + matrix3x3[4] * vector3[1] + matrix3x3[5] * vector3[2],
            matrix3x3[6] * vector3[0] + matrix3x3[7] * vector3[1] + matrix3x3[8] * vector3[2],
        )
    }
}
