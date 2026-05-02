/**
 * ╔══════════════════════════════════════════════════════════════════════════╗
 * ║  ProXDR v3  ·  src/scene_analyzer.cpp                                   ║
 * ║  SceneAnalyzer 3.0 — EV100, Tripod, HDR-readiness, Zone Map             ║
 * ╠══════════════════════════════════════════════════════════════════════════╣
 * ║  v3 changes vs v2:                                                       ║
 * ║   • EV100 estimator from exposure / ISO / aperture                       ║
 * ║   • Histogram-based percentiles (O(N) instead of O(N log N))             ║
 * ║   • Tripod / static-scene detection from gyro angular velocity           ║
 * ║   • HDR-readiness score (decides when HDR is even worth doing)           ║
 * ║   • Skin-Lab profile extraction from face crops                          ║
 * ║   • Soft (continuous) sky confidence — no hard threshold                 ║
 * ║   • Neural-hook merge: TFLite seg masks blended into rule-based zones    ║
 * ║   • Full-resolution Sobel (was: half-res then upscaled)                  ║
 * ║   • Per-pixel scalar motion field hook (Farneback-lite, optional)        ║
 * ╚══════════════════════════════════════════════════════════════════════════╝
 */

#include "../include/ProXDR_Engine.h"

namespace ProXDR {

// ─── Colour conversion helpers ──────────────────────────────────────────────
static inline void rgb_to_xyz(f32 r, f32 g, f32 b, f32& X, f32& Y, f32& Z) {
    X = 0.4124564f*r + 0.3575761f*g + 0.1804375f*b;
    Y = 0.2126729f*r + 0.7151522f*g + 0.0721750f*b;
    Z = 0.0193339f*r + 0.1191920f*g + 0.9503041f*b;
}
static inline void xyz_to_lab(f32 X, f32 Y, f32 Z, f32& L, f32& a, f32& b) {
    constexpr f32 Xn = 0.95047f, Yn = 1.f, Zn = 1.08883f;
    auto f = [](f32 t) { return (t > 0.008856f) ? std::cbrt(t) : (7.787f*t + 16.f/116.f); };
    const f32 fx = f(X/Xn), fy = f(Y/Yn), fz = f(Z/Zn);
    L = 116.f*fy - 16.f;
    a = 500.f*(fx - fy);
    b = 200.f*(fy - fz);
}
static inline void rgb_to_lab(f32 r, f32 g, f32 b_in, f32& L, f32& a, f32& bv) {
    f32 X,Y,Z; rgb_to_xyz(r,g,b_in,X,Y,Z); xyz_to_lab(X,Y,Z,L,a,bv);
}

// ─── EV100 estimator (skill formula) ─────────────────────────────────────────
/**
 * EV100 = log2(N²) - log2(t) - log2(S/100)
 *   N = aperture (f-number)
 *   t = exposure time (s)
 *   S = ISO sensitivity
 *
 * Higher EV100 = brighter scene. Typical values:
 *   > +5  bright outdoor sun
 *     0   well-lit indoor (overcast)
 *   < -2  low-light
 *   < -5  night
 */
f32 EstimateEV100(const FrameMeta& m) {
    const f32 N = std::max(0.5f, m.f_number);
    const f32 t_s = std::max(1e-6f, m.exp_ms * 1e-3f);
    const f32 iso = std::max(25.f,  m.analog_gain * 100.f * std::max(1.f, m.digital_gain));
    return std::log2(N*N) - std::log2(t_s) - std::log2(iso/100.f);
}

// ─── Bayer → half-res luminance (greens only) ───────────────────────────────
static ImageBuffer bayer_to_luma(const ImageBuffer& raw, const FrameMeta& m,
                                  BayerPattern bp) {
    const int W = raw.w, H = raw.h;
    const int dW = W/2, dH = H/2;
    ImageBuffer luma(dW, dH, 1);
    const f32 wl = std::max(1.f, m.white - 0.25f*(m.black[0]+m.black[1]+m.black[2]+m.black[3]));

    for (int y = 0; y < dH; ++y) {
        for (int x = 0; x < dW; ++x) {
            // For RGGB: green positions are (2x+1, 2y) and (2x, 2y+1)
            f32 g0, g1;
            switch (bp) {
                case BayerPattern::RGGB:
                    g0 = raw.at(2*x+1, 2*y  ) - m.black[1];
                    g1 = raw.at(2*x  , 2*y+1) - m.black[2]; break;
                case BayerPattern::BGGR:
                    g0 = raw.at(2*x+1, 2*y  ) - m.black[1];
                    g1 = raw.at(2*x  , 2*y+1) - m.black[2]; break;
                case BayerPattern::GRBG:
                    g0 = raw.at(2*x  , 2*y  ) - m.black[0];
                    g1 = raw.at(2*x+1, 2*y+1) - m.black[3]; break;
                case BayerPattern::GBRG: default:
                    g0 = raw.at(2*x  , 2*y  ) - m.black[0];
                    g1 = raw.at(2*x+1, 2*y+1) - m.black[3]; break;
            }
            luma.at(x,y) = std::clamp(0.5f*(g0+g1)/wl, 0.f, 1.f);
        }
    }
    return luma;
}

// ─── Bayer → half-res RGB (crude) for color analysis ────────────────────────
static ImageBuffer bayer_to_rgb_half(const ImageBuffer& raw, const FrameMeta& m,
                                      const CameraMeta& cam) {
    const int dW = raw.w/2, dH = raw.h/2;
    ImageBuffer rgb(dW, dH, 3);
    const f32 wr = std::max(1.f, m.white - m.black[0]);

    for (int y = 0; y < dH; ++y) {
        for (int x = 0; x < dW; ++x) {
            f32 R  = (raw.at(2*x  , 2*y  ) - m.black[0]) / wr;
            f32 Gr = (raw.at(2*x+1, 2*y  ) - m.black[1]) / wr;
            f32 Gb = (raw.at(2*x  , 2*y+1) - m.black[2]) / wr;
            f32 B  = (raw.at(2*x+1, 2*y+1) - m.black[3]) / wr;
            f32 G  = 0.5f*(Gr+Gb);

            R *= m.wb_gains[0]; G *= 0.5f*(m.wb_gains[1]+m.wb_gains[2]); B *= m.wb_gains[3];

            const f32 r = std::max(0.f, cam.ccm[0][0]*R + cam.ccm[0][1]*G + cam.ccm[0][2]*B);
            const f32 g = std::max(0.f, cam.ccm[1][0]*R + cam.ccm[1][1]*G + cam.ccm[1][2]*B);
            const f32 b = std::max(0.f, cam.ccm[2][0]*R + cam.ccm[2][1]*G + cam.ccm[2][2]*B);

            rgb.at(x,y,0) = std::min(r, 4.f);
            rgb.at(x,y,1) = std::min(g, 4.f);
            rgb.at(x,y,2) = std::min(b, 4.f);
        }
    }
    return rgb;
}

// ─── Histogram-based percentile (O(N), no malloc-of-size-N) ─────────────────
/**
 * Reservoir-free approximate percentile over normalised-[0..1] data.
 * Builds 1024-bin histogram, integrates to find the cumulative fraction.
 */
static f32 percentile_hist(const std::vector<f32>& data, f32 p) {
    constexpr int B = 1024;
    std::array<i32, B> hist{};
    hist.fill(0);
    for (f32 v : data) {
        int idx = static_cast<int>(std::clamp(v, 0.f, 1.f) * (B-1));
        ++hist[idx];
    }
    const i64 N = static_cast<i64>(data.size());
    const i64 target = static_cast<i64>(p * N);
    i64 cum = 0;
    for (int i = 0; i < B; ++i) {
        cum += hist[i];
        if (cum >= target) return static_cast<f32>(i) / (B-1);
    }
    return 1.f;
}

// ─── Soft sky confidence (continuous, in [0..1]) ────────────────────────────
/**
 * Combines:
 *   • Lab colour membership in blue-sky / overcast / sunset gamut
 *   • Vertical-position prior (top-of-frame more likely sky)
 *   • Vertical-gradient smoothness (sky is ~flat in luminance)
 * Falls off smoothly — no hard >50% cutoff.
 */
static void detect_sky_soft(const ImageBuffer& rgb_half,
                             ZoneMap& zones,
                             int full_w, int full_h) {
    const int dW = rgb_half.w, dH = rgb_half.h;

    // Compute Lab + vertical gradient on half-res
    ImageBuffer lab(dW, dH, 3), grad(dW, dH, 1);
    for (int y = 0; y < dH; ++y) {
        for (int x = 0; x < dW; ++x) {
            f32 L,a,b;
            rgb_to_lab(rgb_half.at(x,y,0), rgb_half.at(x,y,1), rgb_half.at(x,y,2), L,a,b);
            lab.at(x,y,0) = L; lab.at(x,y,1) = a; lab.at(x,y,2) = b;
        }
    }
    for (int y = 1; y < dH-1; ++y)
        for (int x = 0; x < dW; ++x)
            grad.at(x,y) = std::abs(lab.at(x,y+1,0) - lab.at(x,y-1,0));

    auto soft_in_range = [](f32 v, f32 lo, f32 hi, f32 feather) -> f32 {
        if (v < lo - feather || v > hi + feather) return 0.f;
        if (v >= lo && v <= hi) return 1.f;
        if (v < lo) return std::max(0.f, (v - (lo - feather)) / feather);
        return std::max(0.f, ((hi + feather) - v) / feather);
    };

    for (int y = 0; y < dH; ++y) {
        for (int x = 0; x < dW; ++x) {
            const f32 L = lab.at(x,y,0);
            const f32 a = lab.at(x,y,1);
            const f32 b = lab.at(x,y,2);
            const f32 gL = grad.at(x,std::clamp(y,1,dH-2));

            // Blue-day, overcast, sunset all share L > 40 ; varies in a/b.
            const f32 c_L  = soft_in_range(L, 40.f, 100.f, 8.f);
            const f32 c_a  = soft_in_range(a, -18.f, 12.f, 6.f);
            const f32 c_b  = soft_in_range(b, -40.f, 25.f, 10.f);
            const f32 colour_conf = c_L * c_a * c_b;

            const f32 y_frac = static_cast<f32>(y) / std::max(1, dH);
            const f32 pos_prior = std::max(0.f, 1.f - 1.4f * y_frac);

            const f32 grad_score = std::max(0.f, 1.f - gL/8.f);

            const f32 conf = std::clamp(
                0.55f*colour_conf*pos_prior + 0.30f*grad_score*colour_conf + 0.15f*colour_conf,
                0.f, 1.f);

            // Upscale ×2 to full res
            for (int dy = 0; dy < 2; ++dy)
            for (int dx = 0; dx < 2; ++dx) {
                const int fx = 2*x+dx, fy = 2*y+dy;
                if (fx < full_w && fy < full_h) {
                    zones.sky(fx,fy) = conf;
                    if (conf > 0.45f) zones.label(fx,fy) |= ZONE_SKY;
                }
            }
        }
    }
}

// ─── Face zones with skin-Lab extraction ────────────────────────────────────
static void detect_faces(const ImageBuffer& rgb_full,
                          std::vector<FaceRegion>& faces,   // mutable: writes skin_Lab
                          ZoneMap& zones) {
    const int W = zones.w, H = zones.h;
    for (auto& f : faces) {
        const int cx = static_cast<int>(f.cx * W);
        const int cy = static_cast<int>(f.cy * H);
        const int fw = static_cast<int>(f.width  * W);
        const int fh = static_cast<int>(f.height * H);

        const int x0 = std::max(0, cx - fw/2);
        const int x1 = std::min(W-1, cx + fw/2);
        const int y0 = std::max(0, cy - fh/2);
        const int y1 = std::min(H-1, cy + fh/2);

        const f32 sig_x = std::max(1.f, fw/2.f);
        const f32 sig_y = std::max(1.f, fh/2.f);

        // Lab histogram peak inside face bounding box → skin reference
        f64 sumL=0, suma=0, sumb=0; i64 nlab=0;
        for (int y = y0; y <= y1; ++y) {
            for (int x = x0; x <= x1; ++x) {
                const f32 dx = (x-cx)/(sig_x);
                const f32 dy = (y-cy)/(sig_y);
                const f32 conf = f.confidence * std::exp(-0.5f * (dx*dx + dy*dy));
                if (conf > zones.face(x,y)) zones.face(x,y) = conf;
                if (conf > 0.4f) zones.label(x,y) |= ZONE_FACE;
                if (conf > 0.65f && rgb_full.c >= 3) {
                    f32 L,a,b;
                    rgb_to_lab(rgb_full.at(x,y,0), rgb_full.at(x,y,1), rgb_full.at(x,y,2), L,a,b);
                    if (L > 25.f && L < 90.f) { sumL+=L; suma+=a; sumb+=b; ++nlab; }
                }
            }
        }
        if (nlab > 100) {
            f.skin_L = static_cast<f32>(sumL/nlab);
            f.skin_a = static_cast<f32>(suma/nlab);
            f.skin_b = static_cast<f32>(sumb/nlab);
        }
    }
}

// ─── Luminance classification with histogram percentiles ────────────────────
struct LumStats {
    f32 shadow_thr   = 0.10f;
    f32 highlight_thr= 0.85f;
    f32 clip_thr     = 0.95f;
    f32 mean         = 0.5f;
    f32 p99          = 1.f;
    f32 p1           = 0.f;
    f32 dynamic_range_ev = 4.f;  // log2(p99 / max(p1,floor))
};

static LumStats classify_luminance(const ImageBuffer& luma_full,
                                    ZoneMap& zones,
                                    f32 shadow_pct = 0.10f,
                                    f32 highlight_pct = 0.85f) {
    LumStats st;
    const int W = luma_full.w, H = luma_full.h;
    const auto& v = luma_full.data;

    // Mean
    f64 m = 0; for (f32 x : v) m += x; st.mean = static_cast<f32>(m / std::max<size_t>(1, v.size()));

    // Histogram percentiles (single pass)
    st.shadow_thr    = percentile_hist(v, shadow_pct);
    st.highlight_thr = percentile_hist(v, highlight_pct);
    st.p1            = percentile_hist(v, 0.01f);
    st.p99           = percentile_hist(v, 0.99f);
    const f32 noise_floor = std::max(1e-3f, st.p1);
    st.dynamic_range_ev = std::log2(std::max(1.f, st.p99) / noise_floor);

    for (int y = 0; y < H; ++y) {
        for (int x = 0; x < W; ++x) {
            const f32 L = luma_full.at(x,y);
            zones.lum(x,y) = L;
            u8& lbl = zones.label(x,y);
            if (L >= st.clip_thr)            lbl |= ZONE_CLIPPED;
            if (L >= st.highlight_thr)       lbl |= ZONE_HIGHLIGHT;
            else if (L <= st.shadow_thr)     lbl |= ZONE_SHADOW;
            else                              lbl |= ZONE_MIDTONE;
        }
    }
    return st;
}

// ─── Sobel edge detection (full resolution) ─────────────────────────────────
static void detect_edges(const ImageBuffer& luma, ZoneMap& zones, f32 thr = 0.025f) {
    const int W = luma.w, H = luma.h;
    for (int y = 1; y < H-1; ++y) {
        for (int x = 1; x < W-1; ++x) {
            const f32 Gx =
                -luma.at(x-1,y-1) + luma.at(x+1,y-1)
              -2*luma.at(x-1,y  ) + 2*luma.at(x+1,y  )
              -  luma.at(x-1,y+1) + luma.at(x+1,y+1);
            const f32 Gy =
                -luma.at(x-1,y-1) - 2*luma.at(x,y-1) - luma.at(x+1,y-1)
                +luma.at(x-1,y+1) + 2*luma.at(x,y+1) + luma.at(x+1,y+1);
            const f32 mag = std::sqrt(Gx*Gx + Gy*Gy) * INV_SQRT2;
            zones.edge(x,y) = mag;
            if (mag > thr) zones.label(x,y) |= ZONE_EDGE;
        }
    }
}

// ─── Tripod / motion classification from gyro ───────────────────────────────
/**
 * Returns true if the device was effectively stationary during capture.
 * Checks gyro angular-velocity magnitude over the burst window.
 *
 *   stationary if max|ω| < 0.02 rad/s for 95% of samples
 *
 * Tripod mode unlocks longer exposures and more frames in night mode.
 */
static bool is_tripod(const std::vector<FrameMeta>& metas) {
    if (metas.empty()) return false;
    int total = 0, still = 0;
    for (const auto& m : metas) {
        for (const auto& g : m.gyro) {
            const f32 mag = std::sqrt(g.wx*g.wx + g.wy*g.wy + g.wz*g.wz);
            ++total;
            if (mag < 0.02f) ++still;
        }
    }
    if (total < 8) return false; // not enough samples to decide
    return (static_cast<f32>(still) / total) > 0.95f;
}

// ─── HDR-readiness score (0..1) ─────────────────────────────────────────────
/**
 * Decides whether HDR processing is even worthwhile for this frame.
 * High score (> 0.6) ⇒ scene benefits from HDR; low (< 0.3) ⇒ skip HDR
 * and just process the base frame faster.
 *
 * Inputs:
 *   • dynamic range in EV (from luminance percentiles)
 *   • clipped fraction (highlights blown — recovery helps)
 *   • shadow fraction at the noise floor (lift helps)
 *   • motion magnitude (high motion ⇒ HDR risky)
 */
static f32 hdr_readiness_score(const LumStats& st, f32 motion_px_ms,
                                f32 clipped_frac, f32 shadow_frac) {
    const f32 dr_score   = std::clamp((st.dynamic_range_ev - 5.5f) / 5.0f, 0.f, 1.f);
    const f32 clip_score = std::clamp(clipped_frac * 12.f, 0.f, 1.f);
    const f32 shad_score = std::clamp(shadow_frac * 4.f,   0.f, 1.f);
    const f32 mot_pen    = std::clamp(motion_px_ms / 0.5f, 0.f, 1.f);

    return std::clamp(0.45f*dr_score + 0.30f*clip_score + 0.25f*shad_score - 0.30f*mot_pen,
                       0.f, 1.f);
}

// ─── Motion zone (from gyro and per-pixel optical flow if available) ────────
static void detect_motion(const FrameMeta& meta, ZoneMap& zones,
                            const ImageBuffer* flow_mag /*nullptr ok*/) {
    const f32 thr = 0.10f;
    const int W = zones.w, H = zones.h;

    if (flow_mag && flow_mag->w == W && flow_mag->h == H) {
        for (int y = 0; y < H; ++y) {
            for (int x = 0; x < W; ++x) {
                const f32 m = flow_mag->at(x,y);
                zones.mot(x,y) = m;
                if (m > thr) zones.label(x,y) |= ZONE_MOTION;
            }
        }
    } else if (meta.motion_px_ms > thr) {
        for (int i = 0; i < W*H; ++i) {
            zones.motion[i]  = meta.motion_px_ms;
            zones.labels[i] |= ZONE_MOTION;
        }
    }
}

// ─── Neural segmentation merge (called by pipeline if available) ────────────
/**
 * Blends a soft per-class confidence map (from TFLite segmentation network)
 * into the rule-based zone map. This is a no-op if neural is disabled.
 *
 * seg_conf: HxWxK float32 tensor at any resolution (typically 256×192)
 *   class indices: 0=background, 1=sky, 2=face, 3=veg, 4=water, 5=building
 *
 * The actual TFLite call lives in NeuralHooks.cpp; this function just merges.
 */
void apply_neural_zones(ZoneMap& zones, const ImageBuffer& seg_conf,
                         const int* class_map, int nclasses) {
    const int W = zones.w, H = zones.h;
    if (seg_conf.w == 0 || seg_conf.h == 0 || nclasses < 1) return;

    const f32 sx = static_cast<f32>(seg_conf.w) / W;
    const f32 sy = static_cast<f32>(seg_conf.h) / H;

    for (int y = 0; y < H; ++y) {
        for (int x = 0; x < W; ++x) {
            const f32 fx = (x + 0.5f) * sx - 0.5f;
            const f32 fy = (y + 0.5f) * sy - 0.5f;

            for (int k = 0; k < nclasses; ++k) {
                const f32 conf = seg_conf.sample(fx, fy, k);
                if (conf < 0.3f) continue;

                switch (class_map[k]) {
                    case 1: // sky
                        zones.sky(x,y)   = std::max(zones.sky(x,y), conf);
                        if (conf > 0.5f) zones.label(x,y) |= ZONE_SKY;
                        break;
                    case 2: // face
                        zones.face(x,y)  = std::max(zones.face(x,y), conf);
                        if (conf > 0.5f) zones.label(x,y) |= ZONE_FACE;
                        zones.label_x(x,y) |= ZONEX_SKIN;
                        break;
                    case 3: zones.label_x(x,y) |= ZONEX_VEG; break;
                    case 4: zones.label_x(x,y) |= ZONEX_WATER; break;
                    case 5: zones.label_x(x,y) |= ZONEX_BUILDING; break;
                    default: break;
                }
            }
        }
    }
}

// ─── Top-level analyzer entry point ─────────────────────────────────────────
struct SceneReport {
    ZoneMap   zones;
    LumStats  lum;
    f32       ev100              = 0.f;
    f32       hdr_readiness      = 0.f;
    f32       clipped_fraction   = 0.f;
    f32       shadow_fraction    = 0.f;
    f32       sky_fraction       = 0.f;
    bool      tripod             = false;
    bool      backlit            = false;
    SceneMode auto_mode          = SceneMode::Auto;
    int       faces_found        = 0;
    f32       cct_estimate_K     = 5500.f;  // crude WB-derived correlated colour temp
};

/**
 * analyze_scene()
 *
 * Single-call scene intelligence — produces the full ZoneMap plus a SceneReport
 * that AdaptiveSceneMode uses to auto-tune the rest of the pipeline.
 *
 * Order of operations matters:
 *   1. Estimate EV100 + tripod from metadata
 *   2. Bayer → half-res luma + RGB
 *   3. Histogram-based shadow/highlight/clipped classification
 *   4. Sky soft-confidence map
 *   5. Face zones + skin-Lab profile extraction
 *   6. Sobel edges (full resolution for correctness)
 *   7. Motion map (gyro or optical-flow)
 *   8. (Optional) Neural-segmentation merge — done by caller
 *   9. Backlit detection (rim light heuristic)
 *  10. HDR-readiness score → scene auto-mode classification
 */
SceneReport analyze_scene(const ImageBuffer& raw_ref,
                            const std::vector<FrameMeta>& metas,
                            const FrameMeta& ref_meta,
                            const CameraMeta& cam,
                            const ImageBuffer* optional_flow_mag = nullptr) {
    const int W = raw_ref.w, H = raw_ref.h;
    SceneReport rep;
    rep.zones = ZoneMap(W, H);

    // 1. EV100, tripod
    rep.ev100  = EstimateEV100(ref_meta);
    rep.tripod = is_tripod(metas);

    // 2. Half-res luma + RGB
    ImageBuffer luma_half = bayer_to_luma(raw_ref, ref_meta, cam.bayer);
    ImageBuffer rgb_half  = bayer_to_rgb_half(raw_ref, ref_meta, cam);

    // Upscale luma to full res (NN — only used for zone boundaries)
    ImageBuffer luma_full(W, H, 1);
    for (int y = 0; y < H; ++y)
        for (int x = 0; x < W; ++x)
            luma_full.at(x,y) = luma_half.mirror(x/2, y/2);

    // 3. Luminance classification
    rep.lum = classify_luminance(luma_full, rep.zones);

    // Crude RGB upscaled for face skin extraction (NN — fine for sampling)
    ImageBuffer rgb_full(W, H, 3);
    for (int y = 0; y < H; ++y)
        for (int x = 0; x < W; ++x) {
            rgb_full.at(x,y,0) = rgb_half.mirror(x/2, y/2, 0);
            rgb_full.at(x,y,1) = rgb_half.mirror(x/2, y/2, 1);
            rgb_full.at(x,y,2) = rgb_half.mirror(x/2, y/2, 2);
        }

    // 4. Sky
    detect_sky_soft(rgb_half, rep.zones, W, H);

    // 5. Faces (writes skin_Lab back into ref_meta-ish — we copy a working list)
    auto faces = ref_meta.faces;
    if (!faces.empty()) {
        detect_faces(rgb_full, faces, rep.zones);
        rep.faces_found = static_cast<int>(faces.size());
    }

    // 6. Edges (full-res Sobel)
    detect_edges(luma_full, rep.zones);

    // 7. Motion
    detect_motion(ref_meta, rep.zones, optional_flow_mag);

    // 8. Neural seg merge happens externally.

    // 9. Stats: clipped/shadow/sky fractions
    i64 n_clip = 0, n_shad = 0, n_sky = 0;
    for (int i = 0; i < W*H; ++i) {
        if (rep.zones.labels[i] & ZONE_CLIPPED) ++n_clip;
        if (rep.zones.labels[i] & ZONE_SHADOW)  ++n_shad;
        if (rep.zones.labels[i] & ZONE_SKY)     ++n_sky;
    }
    const f32 inv_total = 1.f / std::max(1, W*H);
    rep.clipped_fraction = n_clip * inv_total;
    rep.shadow_fraction  = n_shad * inv_total;
    rep.sky_fraction     = n_sky  * inv_total;

    // Backlit detection: faces are dark while sky/highlights bright
    if (!faces.empty()) {
        f32 face_lum_mean = 0.f; int n = 0;
        for (const auto& f : faces) {
            const int cx = static_cast<int>(f.cx*W), cy = static_cast<int>(f.cy*H);
            const int r  = static_cast<int>(0.25f*f.width*W);
            for (int dy=-r; dy<=r; ++dy)
                for (int dx=-r; dx<=r; ++dx) {
                    const int xx = std::clamp(cx+dx, 0, W-1);
                    const int yy = std::clamp(cy+dy, 0, H-1);
                    face_lum_mean += rep.zones.lum(xx,yy); ++n;
                }
        }
        if (n > 0) face_lum_mean /= n;
        rep.backlit = (face_lum_mean < 0.20f) && (rep.lum.p99 > 0.8f);
    }

    // 10. HDR-readiness + auto scene-mode
    rep.hdr_readiness = hdr_readiness_score(rep.lum, ref_meta.motion_px_ms,
                                             rep.clipped_fraction,
                                             rep.shadow_fraction);

    // CCT estimate from WB gains: warmer light → R/B gain ratio shifts
    const f32 r_g = ref_meta.wb_gains[0] / std::max(1e-3f, 0.5f*(ref_meta.wb_gains[1]+ref_meta.wb_gains[2]));
    const f32 b_g = ref_meta.wb_gains[3] / std::max(1e-3f, 0.5f*(ref_meta.wb_gains[1]+ref_meta.wb_gains[2]));
    rep.cct_estimate_K = std::clamp(6500.f - 2000.f*(r_g - b_g), 2200.f, 9500.f);

    // Auto scene-mode decision tree (pipeline.cpp's AdaptiveSceneMode owns the
    // full table; here we provide a compact first-pass guess so the analyzer is
    // self-contained for callers that don't run the adaptive layer).
    if      (rep.ev100 >  4.0f)  rep.auto_mode = SceneMode::BrightDay;
    else if (rep.ev100 >  1.0f)  rep.auto_mode = SceneMode::Daylight;
    else if (rep.ev100 > -1.0f)  rep.auto_mode = SceneMode::Indoor;
    else if (rep.ev100 > -3.0f)  rep.auto_mode = SceneMode::LowLight;
    else if (rep.ev100 > -5.0f)  rep.auto_mode = SceneMode::Night;
    else                          rep.auto_mode = SceneMode::NightExtreme;

    if (rep.tripod && rep.ev100 < 0.f) rep.auto_mode = SceneMode::Tripod;
    if (ref_meta.motion_px_ms > 0.6f)  rep.auto_mode = SceneMode::Sports;
    if (rep.faces_found > 0 && (faces[0].width * faces[0].height) > 0.08f)
        rep.auto_mode = SceneMode::Portrait;
    if (rep.backlit) rep.auto_mode = SceneMode::Backlit;
    if (rep.cct_estimate_K < 4000.f && rep.ev100 < 3.f) rep.auto_mode = SceneMode::GoldenHour;

    return rep;
}

} // namespace ProXDR
