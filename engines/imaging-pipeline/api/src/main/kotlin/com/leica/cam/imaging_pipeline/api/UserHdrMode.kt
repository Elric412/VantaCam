package com.leica.cam.imaging_pipeline.api

/**
 * User-facing HDR capture preference.
 *
 * - [OFF]: single-frame capture, bypass HDR merging.
 * - [ON]: prefer HDR processing even for same-exposure bursts.
 * - [SMART]: keep the current automatic behaviour.
 * - [PRO_XDR]: prefer the full Debevec radiance path (legacy v2 algorithm).
 * - [PRO_XDR_V3]: route to the upgraded ProXDR v3 adaptive AI HDR engine —
 *   SAFNet-lite Wiener fusion + spectral-ratio highlight recovery + log-lift
 *   shadow restoration + Mertens-on-linear-HDR fusion + Oklab tone curves.
 *   Requires `libproxdr_engine.so` for full quality; falls back gracefully
 *   to a Kotlin RGB fast path that preserves the same algorithmic shape.
 */
enum class UserHdrMode {
    OFF,
    ON,
    SMART,
    PRO_XDR,
    PRO_XDR_V3,
}
