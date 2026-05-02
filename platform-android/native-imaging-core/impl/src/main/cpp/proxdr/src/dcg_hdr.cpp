/**
 * ╔══════════════════════════════════════════════════════════════════════════╗
 * ║  ProXDR v3  ·  src/dcg_hdr.cpp                                          ║
 * ║  DCGHDR — Dual-Conversion-Gain HDR Blending                             ║
 * ╠══════════════════════════════════════════════════════════════════════════╣
 * ║  On modern CMOS sensors (Sony IMX800/989, Samsung HP3, Omnivision OV50H),║
 * ║  each pixel can be read out in two modes simultaneously:                 ║
 * ║    • HCG (high conversion gain)  — low noise, good for shadows           ║
 * ║    • LCG (low conversion gain)   — high full-well, good for highlights   ║
 * ║                                                                          ║
 * ║  Or alternately, the camera can capture a long+short exposure pair       ║
 * ║  (bracketed DCG) very fast — this is what Camera2 typically exposes.     ║
 * ║                                                                          ║
 * ║  This module blends the two streams in post-demosaic linear RGB:         ║
 * ║                                                                          ║
 * ║    long_exp  → shadow + midtone detail (clipped highlights ignored)      ║
 * ║    short_exp → highlight detail (gained up by dcg_ratio to match scale) ║
 * ║                                                                          ║
 * ║  Smooth cosine blend with optional flicker correction.                   ║
 * ║  Recovers 2-3 EV of highlight headroom on sensors that support it.       ║
 * ╚══════════════════════════════════════════════════════════════════════════╝
 */

#include "../include/ProXDR_Engine.h"

namespace ProXDR {

// ─── Smooth cosine blend factor ─────────────────────────────────────────────
/**
 * Returns a smooth weight in [0..1]:
 *   1 when v < blend_start  → use long exposure
 *   0 when v > blend_end    → use short exposure
 *   raised-cosine in between.
 */
static inline f32 dcg_long_weight(f32 v, f32 blend_start, f32 blend_end) {
    if (v <= blend_start) return 1.f;
    if (v >= blend_end)   return 0.f;
    const f32 t = (v - blend_start) / std::max(EPS, blend_end - blend_start);
    return 0.5f * (1.f + std::cos(t * PI));
}

// ─── Flicker correction ─────────────────────────────────────────────────────
/**
 * Indoor lighting (50/60 Hz fluorescent / LED) flickers — short exposures
 * may catch a different point in the AC cycle than long exposures, causing
 * intensity mismatch. We compensate by matching the per-channel mean of the
 * unclipped overlap region between the two frames.
 *
 * Returns a 3-element gain vector to apply to the SHORT frame.
 */
static std::array<f32, 3> compute_flicker_gain(const ImageBuffer& long_rgb,
                                                  const ImageBuffer& short_rgb,
                                                  f32 dcg_ratio,
                                                  f32 unclipped_thr = 0.85f) {
    std::array<f32, 3> gain = {1.f, 1.f, 1.f};
    const int W = long_rgb.w, H = long_rgb.h;
    if (short_rgb.w != W || short_rgb.h != H) return gain;

    for (int c = 0; c < 3; ++c) {
        f64 sum_l = 0.0, sum_s = 0.0;
        i64 n = 0;
        for (int y = 0; y < H; ++y) {
            for (int x = 0; x < W; ++x) {
                const f32 vl = long_rgb .at(x,y,c);
                const f32 vs = short_rgb.at(x,y,c) * dcg_ratio;
                if (vl < unclipped_thr && vs > 0.05f && vs < 1.5f) {
                    sum_l += vl; sum_s += vs; ++n;
                }
            }
        }
        if (n > 1024) {
            const f32 ml = static_cast<f32>(sum_l / n);
            const f32 ms = static_cast<f32>(sum_s / n);
            gain[c] = std::clamp(ml / std::max(1e-3f, ms), 0.85f, 1.15f);
        }
    }
    return gain;
}

// ─── Top-level DCG blend ────────────────────────────────────────────────────
/**
 * dcg_blend()
 *
 * @param long_rgb   Long-exposure linear RGB (in-place output).
 * @param short_rgb  Short-exposure linear RGB at the SAME scale (will be
 *                   gained up by dcg_ratio internally).
 * @param zones      Zone map (used for per-zone weighting).
 * @param cfg        DCG configuration.
 * @return           Recovered EV (estimate of additional highlight headroom).
 */
f32 dcg_blend(ImageBuffer& long_rgb,
               const ImageBuffer& short_rgb,
               const ZoneMap& zones,
               const DCGCfg& cfg) {
    if (!cfg.enabled) return 0.f;
    const int W = long_rgb.w, H = long_rgb.h;
    if (short_rgb.w != W || short_rgb.h != H) return 0.f;

    // Optional flicker correction
    std::array<f32, 3> flick = {1.f, 1.f, 1.f};
    if (cfg.flicker_correct)
        flick = compute_flicker_gain(long_rgb, short_rgb, cfg.dcg_ratio);

    f32 max_short_seen = 0.f;
    f32 max_recovered_long = 0.f;

    for (int y = 0; y < H; ++y) {
        for (int x = 0; x < W; ++x) {
            const f32 r_l = long_rgb.at(x,y,0);
            const f32 g_l = long_rgb.at(x,y,1);
            const f32 b_l = long_rgb.at(x,y,2);

            // Compute scene-referred short values (gained up to long scale)
            const f32 r_s = short_rgb.at(x,y,0) * cfg.dcg_ratio * flick[0];
            const f32 g_s = short_rgb.at(x,y,1) * cfg.dcg_ratio * flick[1];
            const f32 b_s = short_rgb.at(x,y,2) * cfg.dcg_ratio * flick[2];

            // Use luminance as the blend driver (avoids per-channel hue wobble)
            const f32 L_l = 0.2126f*r_l + 0.7152f*g_l + 0.0722f*b_l;
            const f32 wL  = dcg_long_weight(L_l, cfg.blend_start, cfg.blend_end);

            const f32 r_o = wL * r_l + (1.f - wL) * r_s;
            const f32 g_o = wL * g_l + (1.f - wL) * g_s;
            const f32 b_o = wL * b_l + (1.f - wL) * b_s;

            long_rgb.at(x,y,0) = std::max(0.f, r_o);
            long_rgb.at(x,y,1) = std::max(0.f, g_o);
            long_rgb.at(x,y,2) = std::max(0.f, b_o);

            const f32 max_s = std::max({r_s, g_s, b_s});
            const f32 max_l = std::max({r_l, g_l, b_l});
            if (max_s > max_short_seen)        max_short_seen     = max_s;
            if (max_l > 0.95f && max_s < 1.f && max_s > max_recovered_long)
                max_recovered_long = max_s;
        }
    }

    // Estimated EV recovered = log2(max_short_recovered / 1.0)
    const f32 recovered = std::clamp(std::log2(std::max(1.f, max_short_seen)), 0.f, cfg.recover_stops);
    return recovered;
}

} // namespace ProXDR
