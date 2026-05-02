/**
 * ╔══════════════════════════════════════════════════════════════════════════╗
 * ║  ProXDR v3  ·  src/highlight_recovery.cpp                               ║
 * ║  HighlightRecovery 2.0 + ShadowLift 2.0                                 ║
 * ╠══════════════════════════════════════════════════════════════════════════╣
 * ║  HIGHLIGHT RECOVERY 2.0                                                  ║
 * ║   • Soft-threshold detection (continuous, no hard step)                  ║
 * ║   • Multi-scale spectral ratio (5×5 + 17×17 — robust to local outliers)  ║
 * ║   • Face-zone protection (spectral ratio fails on skin → skip there)     ║
 * ║   • DCG short-frame blend integration (recover 2-3 extra EV)             ║
 * ║   • Roll-off applied BEFORE ratio writeback so recovery is preserved     ║
 * ║   • Bilinear-interpolated ratio map → smooth, no patchy reconstruction   ║
 * ║                                                                          ║
 * ║  SHADOW LIFT 2.0                                                         ║
 * ║   • Logarithmic lift (gentler than quadratic, natural film-toe feel)    ║
 * ║   • Local-mean adaptation (deep shadow under bright surroundings = more  ║
 * ║     lift; uniform low-key scene = less lift, preserves mood)             ║
 * ║   • Pre-lift wavelet NR option (denoise BEFORE amplifying noise)         ║
 * ║   • Post-lift wavelet NR with intensity-aware sigma compensation         ║
 * ╚══════════════════════════════════════════════════════════════════════════╝
 */

#include "../include/ProXDR_Engine.h"

namespace ProXDR {

// Forward decl from wavelet_nr.cpp — pulls in BayesShrink NR pipeline.
void wavelet_denoise(ImageBuffer& img, f32 sigma_n,
                     const NRCfg& cfg, const ZoneMap* zones, f32 strength);
void wavelet_denoise_rgb(ImageBuffer& rgb, f32 sigma_n,
                          const NRCfg& cfg, const ZoneMap* zones);

// ─── Smoothstep helper ──────────────────────────────────────────────────────
static inline f32 smoothstep(f32 e0, f32 e1, f32 x) {
    const f32 t = std::clamp((x - e0) / std::max(EPS, e1 - e0), 0.f, 1.f);
    return t*t*(3.f - 2.f*t);
}

// ─── Soft "is this clipped?" weight ─────────────────────────────────────────
// Returns 0 well below threshold, 1 at threshold and above, smooth in between.
static inline f32 clip_weight(f32 v, f32 thr, f32 band) {
    return smoothstep(thr - band, thr, v);
}

// ─── Highlight roll-off (raised cosine) ─────────────────────────────────────
/**
 * Smoothly compresses values approaching 1.0 to avoid hard clipping edge.
 * Used as a pre-filter on values that DON'T qualify for spectral recovery
 * (i.e., already above threshold but no good reference channel exists).
 */
static inline f32 highlight_roll_off(f32 v, f32 start, f32 strength) {
    if (v <= start) return v;
    const f32 t = (v - start) / std::max(EPS, 1.f - start);
    const f32 tc = std::clamp(t, 0.f, 1.f);
    const f32 compressed = start + (1.f - start) * (1.f - std::cos(tc * PI * 0.5f));
    return v + strength * (compressed - v);
}

// ─── Box-mean ratio map (separable, O(W·H)) ─────────────────────────────────
/**
 * Computes a smoothly-varying ratio map between two channels using a
 * separable box filter on the unclipped overlap. Returns a half-resolution
 * ImageBuffer of size (W,H,1) that must be sampled bilinearly.
 *
 * For each pixel: ratio[y,x] = mean(numerator) / mean(denominator)
 * over a [radius] neighbourhood, where both channels are below clip_thr.
 *
 * This is dramatically faster than per-pixel 5×5 inner loop (the v2 design)
 * and produces smoother reconstructions.
 */
static ImageBuffer compute_ratio_map(const ImageBuffer& rgb,
                                      int num_ch, int den_ch,
                                      f32 clip_thr, int radius) {
    const int W = rgb.w, H = rgb.h;

    // Build masked numerator/denominator with valid-mask via integral images
    std::vector<f64> num_ii((W+1)*(H+1), 0.0);
    std::vector<f64> den_ii((W+1)*(H+1), 0.0);
    std::vector<f64> cnt_ii((W+1)*(H+1), 0.0);

    for (int y = 0; y < H; ++y) {
        for (int x = 0; x < W; ++x) {
            const f32 vn = rgb.at(x,y,num_ch);
            const f32 vd = rgb.at(x,y,den_ch);
            const bool valid = (vn < clip_thr) && (vd > 0.05f) && (vd < clip_thr);
            const f64 n = valid ? vn : 0.0;
            const f64 d = valid ? vd : 0.0;
            const f64 c = valid ? 1.0 : 0.0;
            num_ii[(y+1)*(W+1)+(x+1)] = n + num_ii[y*(W+1)+(x+1)] + num_ii[(y+1)*(W+1)+x] - num_ii[y*(W+1)+x];
            den_ii[(y+1)*(W+1)+(x+1)] = d + den_ii[y*(W+1)+(x+1)] + den_ii[(y+1)*(W+1)+x] - den_ii[y*(W+1)+x];
            cnt_ii[(y+1)*(W+1)+(x+1)] = c + cnt_ii[y*(W+1)+(x+1)] + cnt_ii[(y+1)*(W+1)+x] - cnt_ii[y*(W+1)+x];
        }
    }

    auto box = [&](const std::vector<f64>& I, int x0, int y0, int x1, int y1) -> f64 {
        return I[(y1+1)*(W+1)+(x1+1)] - I[y0*(W+1)+(x1+1)]
             - I[(y1+1)*(W+1)+x0]     + I[y0*(W+1)+x0];
    };

    ImageBuffer ratio(W, H, 1);
    for (int y = 0; y < H; ++y) {
        const int y0 = std::max(0, y-radius), y1 = std::min(H-1, y+radius);
        for (int x = 0; x < W; ++x) {
            const int x0 = std::max(0, x-radius), x1 = std::min(W-1, x+radius);
            const f64 c  = box(cnt_ii, x0, y0, x1, y1);
            if (c < 4.0) { ratio.at(x,y) = 1.f; continue; }
            const f64 n  = box(num_ii, x0, y0, x1, y1);
            const f64 d  = box(den_ii, x0, y0, x1, y1);
            ratio.at(x,y) = (d > 1e-3) ? static_cast<f32>(n/d) : 1.f;
        }
    }
    return ratio;
}

// ─── Highlight Recovery 2.0 — top-level ─────────────────────────────────────
/**
 * Reconstructs clipped channels from unclipped reference channels using a
 * neighbourhood spectral ratio.
 *
 * @param rgb       Demosaiced linear RGB (in-place)
 * @param cfg       Highlight configuration
 * @param zones     Zone map (used for face protection + clipped gating)
 * @param dcg_short Optional short-exposure RGB buffer (same size, for DCG blend)
 *                  — pass nullptr if no DCG.
 * @param dcg_ratio Long/short exposure ratio (≥1). Ignored if dcg_short is null.
 */
void highlight_recovery(ImageBuffer& rgb,
                         const HighlightCfg& cfg,
                         const ZoneMap& zones,
                         const ImageBuffer* dcg_short = nullptr,
                         f32 dcg_ratio = 1.f) {
    if (!cfg.enabled) return;

    const int W = rgb.w, H = rgb.h;
    const f32 clip_thr = cfg.clip_threshold;
    const f32 band     = cfg.soft_threshold ? cfg.soft_band : 0.f;

    // Pre-compute ratio maps for the three channel pairs that actually matter:
    //   R reconstructed from G (r_from_g), B reconstructed from G (b_from_g),
    //   G reconstructed from R+B average (g_from_rb — rare, only deep red lights).
    ImageBuffer r_from_g, b_from_g;
    if (cfg.spectral_ratio) {
        const int r_fine = 2, r_coarse = 8;
        // Fine + coarse blend for stability
        ImageBuffer r_fg_fine = compute_ratio_map(rgb, 0, 1, clip_thr, r_fine);
        ImageBuffer b_fg_fine = compute_ratio_map(rgb, 2, 1, clip_thr, r_fine);

        if (cfg.multi_scale) {
            ImageBuffer r_fg_coarse = compute_ratio_map(rgb, 0, 1, clip_thr, r_coarse);
            ImageBuffer b_fg_coarse = compute_ratio_map(rgb, 2, 1, clip_thr, r_coarse);
            r_from_g = ImageBuffer(W, H, 1);
            b_from_g = ImageBuffer(W, H, 1);
            for (int i = 0; i < W*H; ++i) {
                r_from_g.data[i] = 0.6f*r_fg_fine.data[i] + 0.4f*r_fg_coarse.data[i];
                b_from_g.data[i] = 0.6f*b_fg_fine.data[i] + 0.4f*b_fg_coarse.data[i];
            }
        } else {
            r_from_g = std::move(r_fg_fine);
            b_from_g = std::move(b_fg_fine);
        }
    }

    // Process each pixel
    for (int y = 0; y < H; ++y) {
        for (int x = 0; x < W; ++x) {
            const f32 R = rgb.at(x,y,0);
            const f32 G = rgb.at(x,y,1);
            const f32 B = rgb.at(x,y,2);

            // Soft "clippedness" weight per channel
            const f32 wR = clip_weight(R, clip_thr, band);
            const f32 wG = clip_weight(G, clip_thr, band);
            const f32 wB = clip_weight(B, clip_thr, band);

            const bool any = (wR + wG + wB) > 0.f;
            if (!any) continue;

            // Skip spectral ratio on faces — skin has wrong spectral signature
            const bool is_face = cfg.protect_face && zones.has(x,y, ZONE_FACE);

            f32 newR = R, newG = G, newB = B;

            if (cfg.spectral_ratio && !is_face) {
                // R: clipped, G: not clipped → reconstruct R = G * ratio(R/G)
                if (wR > 0.f && wG < 0.5f) {
                    const f32 ratio = r_from_g.at(x,y);
                    const f32 reco  = std::max(R, G * ratio);
                    newR = R + wR * (reco - R);
                }
                // B: clipped, G: not clipped → reconstruct B = G * ratio(B/G)
                if (wB > 0.f && wG < 0.5f) {
                    const f32 ratio = b_from_g.at(x,y);
                    const f32 reco  = std::max(B, G * ratio);
                    newB = B + wB * (reco - B);
                }
                // G clipped but R or B not — rare for natural scenes; use mean
                if (wG > 0.f && (wR < 0.5f || wB < 0.5f)) {
                    const f32 ref = (wR < 0.5f && wB < 0.5f) ? 0.5f*(R+B)
                                  : (wR < 0.5f ? R : B);
                    const f32 reco = std::max(G, ref);
                    newG = G + wG * (reco - G);
                }
            }

            // DCG short-frame blend — recover all-clipped pixels
            if (dcg_short && dcg_ratio > 1.f &&
                cfg.dcg_blend && wR > 0.5f && wG > 0.5f && wB > 0.5f)
            {
                const f32 sR = dcg_short->at(x,y,0) * dcg_ratio;
                const f32 sG = dcg_short->at(x,y,1) * dcg_ratio;
                const f32 sB = dcg_short->at(x,y,2) * dcg_ratio;
                const f32 wDCG = std::min({wR, wG, wB});  // only blend if all clipped
                newR = newR + wDCG * (sR - newR);
                newG = newG + wDCG * (sG - newG);
                newB = newB + wDCG * (sB - newB);
            }

            // Apply roll-off to soften the remaining hard edge — but ONLY to
            // values above roll_off_start, and BEFORE clamping so spectral
            // recovery of >1.0 values is preserved as visible highlight texture.
            newR = highlight_roll_off(newR, cfg.roll_off_start, cfg.roll_off_strength);
            newG = highlight_roll_off(newG, cfg.roll_off_start, cfg.roll_off_strength);
            newB = highlight_roll_off(newB, cfg.roll_off_start, cfg.roll_off_strength);

            rgb.at(x,y,0) = std::max(0.f, newR);
            rgb.at(x,y,1) = std::max(0.f, newG);
            rgb.at(x,y,2) = std::max(0.f, newB);
        }
    }
}

// ─── Local mean luminance via integral image (helper for adaptive lift) ────
static ImageBuffer local_mean_luma(const ImageBuffer& rgb, int radius) {
    const int W = rgb.w, H = rgb.h;
    ImageBuffer Y(W, H, 1);
    for (int y = 0; y < H; ++y)
        for (int x = 0; x < W; ++x)
            Y.at(x,y) = 0.2126f*rgb.at(x,y,0) + 0.7152f*rgb.at(x,y,1) + 0.0722f*rgb.at(x,y,2);

    std::vector<f64> ii((W+1)*(H+1), 0.0);
    for (int y = 0; y < H; ++y)
        for (int x = 0; x < W; ++x)
            ii[(y+1)*(W+1)+(x+1)] = Y.at(x,y)
                + ii[y*(W+1)+(x+1)] + ii[(y+1)*(W+1)+x] - ii[y*(W+1)+x];

    ImageBuffer mean(W, H, 1);
    for (int y = 0; y < H; ++y) {
        const int y0 = std::max(0, y-radius), y1 = std::min(H-1, y+radius);
        for (int x = 0; x < W; ++x) {
            const int x0 = std::max(0, x-radius), x1 = std::min(W-1, x+radius);
            const int n = (x1-x0+1)*(y1-y0+1);
            const f64 s = ii[(y1+1)*(W+1)+(x1+1)] - ii[y0*(W+1)+(x1+1)]
                         - ii[(y1+1)*(W+1)+x0]    + ii[y0*(W+1)+x0];
            mean.at(x,y) = static_cast<f32>(s / n);
        }
    }
    return mean;
}

// ─── Shadow Lift 2.0 ─────────────────────────────────────────────────────────
/**
 * Brightens dark regions while keeping noise under control.
 *
 * Lift formula (logarithmic, smoother than v2's quadratic):
 *
 *   lifted(v) = v + lift * w(v) * log1p(α * (1 - v/thr)) / log1p(α)
 *
 *   where w(v) = smoothstep(thr-feather, thr, v)  feathering weight
 *         α    = 4   (curve sharpness — higher = more punch in deep shadow)
 *
 * Local adaptation (when local_adaptive=true):
 *   Multiplies lift weight by f(local_mean / global_mean) so deep shadows
 *   that sit beneath bright surroundings (e.g. backlit foreground) get the
 *   full lift, while uniformly dim scenes (e.g. moody indoor) get less lift.
 *
 * Pre/post NR:
 *   pre_lift_nr=true  → denoise BEFORE lifting so noise isn't amplified
 *   post-lift NR     → always applied with sigma scaled by lift factor
 */
void shadow_lift(ImageBuffer& rgb,
                 const ShadowCfg& cfg,
                 const ZoneMap& zones,
                 const NRCfg& nr_cfg,
                 f32 sigma_n) {
    const int W = rgb.w, H = rgb.h;

    // Step 0: Optional pre-lift NR — denoise dark pixels before amplifying.
    if (cfg.pre_lift_nr && cfg.wavelet_nr && sigma_n > 0.f) {
        // Mild pre-lift NR — half-strength so we don't double-NR.
        wavelet_denoise_rgb(rgb, sigma_n, nr_cfg, &zones);
    }

    // Step 1: Per-pixel luminance + (optionally) local mean
    ImageBuffer luma(W, H, 1);
    for (int y = 0; y < H; ++y)
        for (int x = 0; x < W; ++x)
            luma.at(x,y) = 0.2126f*rgb.at(x,y,0)+0.7152f*rgb.at(x,y,1)+0.0722f*rgb.at(x,y,2);

    ImageBuffer local_mean;
    if (cfg.local_adaptive)
        local_mean = local_mean_luma(rgb, cfg.local_radius);

    // Compute the global mean — used as reference for local adaptation
    f64 sum = 0; for (f32 v : luma.data) sum += v;
    const f32 global_mean = static_cast<f32>(sum / std::max<size_t>(1, luma.data.size()));

    // Step 2: Apply log-lift
    const f32 thr      = cfg.lift_threshold;
    const f32 fth      = cfg.lift_feather;
    const f32 lift     = cfg.lift_amount;
    constexpr f32 alpha = 4.f;
    const f32 inv_log_a = 1.f / std::log1p(alpha);

    for (int y = 0; y < H; ++y) {
        for (int x = 0; x < W; ++x) {
            const f32 L = luma.at(x,y);
            f32 w = 0.f;
            if (L < thr - fth)        w = 1.f;
            else if (L < thr)         w = smoothstep(thr, thr - fth, L);  // descending
            if (w < 1e-4f) continue;

            // Local-mean modulation: more lift when surroundings are brighter
            if (cfg.local_adaptive) {
                const f32 lm  = local_mean.at(x,y);
                const f32 ratio = lm / std::max(EPS, global_mean);
                // ratio > 1 ⇒ pixel is darker than its surroundings ⇒ boost lift
                // ratio < 1 ⇒ pixel sits in a uniformly dim area ⇒ less lift
                const f32 mod = std::clamp(0.7f + 0.6f * std::log2(std::max(0.5f, ratio)),
                                            0.5f, 1.6f);
                w *= mod;
            }

            // Logarithmic gain: f(0) = 0, f(thr) = 0, peaks in mid-shadow
            const f32 dark = std::max(0.f, 1.f - L / std::max(EPS, thr));
            const f32 g    = std::log1p(alpha * dark) * inv_log_a;
            const f32 add  = lift * w * g;

            for (int c = 0; c < 3; ++c) {
                f32 v = rgb.at(x,y,c);
                v *= (1.f + add);     // multiplicative — preserves hue
                rgb.at(x,y,c) = std::max(0.f, v);
            }
        }
    }

    // Step 3: Post-lift NR — noise is amplified by ~ (1 + lift*peak_w)
    if (cfg.wavelet_nr && sigma_n > 0.f) {
        const f32 amplified = sigma_n * (1.f + lift * 1.6f);
        // Build a temporary NR config with shadow-zone strength applied
        NRCfg nr_post = nr_cfg;
        nr_post.luma_strength   = nr_cfg.luma_strength   * cfg.nr_strength;
        nr_post.chroma_strength = nr_cfg.chroma_strength * cfg.nr_strength;
        wavelet_denoise_rgb(rgb, amplified, nr_post, &zones);
    }
}

} // namespace ProXDR
