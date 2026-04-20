package com.leica.cam.camera_core.impl.isp

import android.os.Build
import java.util.Locale

/**
 * Detects the active SoC family so the app can select ISP tuning at runtime.
 */
class ChipsetDetector {

    fun detect(): ChipsetFamily {
        val hardware = Build.HARDWARE.orEmpty().lowercase(Locale.US)
        val boardPlatform = readSystemProperty("ro.board.platform")
        val chipName = readSystemProperty("ro.chipname")

        val fingerprint = listOf(hardware, boardPlatform, chipName)
            .joinToString(separator = " ")
            .lowercase(Locale.US)

        return when {
            fingerprint.contains("qcom") ||
                fingerprint.contains("msm") ||
                fingerprint.contains("sm") -> ChipsetFamily.Qualcomm

            fingerprint.contains("mt") ||
                fingerprint.contains("mediatek") ||
                fingerprint.contains("dimensity") -> ChipsetFamily.MediaTek

            fingerprint.contains("exynos") ||
                fingerprint.contains("s5e") -> ChipsetFamily.Exynos

            else -> ChipsetFamily.Generic
        }
    }

    private fun readSystemProperty(propertyName: String): String {
        return try {
            val systemPropertiesClass = Class.forName("android.os.SystemProperties")
            val getMethod = systemPropertiesClass.getMethod("get", String::class.java)
            (getMethod.invoke(null, propertyName) as? String).orEmpty()
        } catch (_: Throwable) {
            ""
        }
    }
}

enum class ChipsetFamily {
    Qualcomm,
    MediaTek,
    Exynos,
    Generic,
}
