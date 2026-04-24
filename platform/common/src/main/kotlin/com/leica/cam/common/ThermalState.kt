package com.leica.cam.common

/**
 * Canonical thermal state mapped from Android PowerManager thermal-status ordinals.
 */
enum class ThermalState(val androidOrdinal: Int) {
    NONE(0),
    LIGHT(1),
    MODERATE(2),
    SEVERE(3),
    CRITICAL(4),
    EMERGENCY(5),
    SHUTDOWN(6),
    ;

    companion object {
        fun fromOrdinal(ordinal: Int): ThermalState =
            entries.firstOrNull { it.androidOrdinal == ordinal } ?: NONE

        val MULTI_FRAME_CUTOFF: ThermalState = SEVERE
        val FRAME_DROP_CUTOFF: ThermalState = CRITICAL
    }
}
