package com.leica.cam.hypertone_wb.api

/**
 * User-facing auto white-balance preference.
 *
 * - [NORMAL]: use the AI dominant illuminant uniformly across all tiles.
 * - [ADVANCE]: keep the existing hardware + AI fusion path.
 */
enum class UserAwbMode {
    NORMAL,
    ADVANCE,
}
