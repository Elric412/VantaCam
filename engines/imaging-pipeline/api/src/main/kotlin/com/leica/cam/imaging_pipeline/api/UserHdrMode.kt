package com.leica.cam.imaging_pipeline.api

/**
 * User-facing HDR capture preference.
 *
 * - [OFF]: single-frame capture, bypass HDR merging.
 * - [ON]: prefer HDR processing even for same-exposure bursts.
 * - [SMART]: keep the current automatic behaviour.
 * - [PRO_XDR]: prefer the full Debevec radiance path when the frame set allows it.
 */
enum class UserHdrMode {
    OFF,
    ON,
    SMART,
    PRO_XDR,
}
