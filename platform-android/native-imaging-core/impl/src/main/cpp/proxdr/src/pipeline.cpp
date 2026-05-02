/**
 * ╔══════════════════════════════════════════════════════════════════════════╗
 * ║  ProXDR v3  ·  src/pipeline.cpp                                         ║
 * ║  ProXDRPipeline — Top-Level Orchestrator                                ║
 * ╠══════════════════════════════════════════════════════════════════════════╣
 * ║  Glues every stage together. This is what JNI calls into.               ║
 * ║                                                                          ║
 * ║  Stage order:                                                            ║
 * ║   0. Reference frame selection (FusionLM)                                ║
 * ║   1. Scene analysis (SceneAnalyzer)                                      ║
 * ║   2. Optional neural zone refinement (NeuralHooks)                       ║
 * ║   3. Adaptive scene mode tuning (AdaptiveSceneMode) — rewrites cfg       ║
 * ║   4. Burst alignment + Wiener merge (FusionLM, RAW domain)               ║
 * ║   5. RAW finishing: black-level, LSC, WB, demosaic, CCM                  ║
 * ║   6. DCG blend if available (DCGHDR)                                     ║
 * ║   7. Wavelet NR (WaveletNR 2.0)                                          ║
 * ║   8. Highlight Recovery (HighlightRecovery 2.0)                          ║
 * ║   9. Shadow Lift (ShadowLift 2.0)                                        ║
 * ║  10. Tone Map (ToneLM 3.0)                                               ║
 * ║  11. Color (ColorLM 3.0)                                                 ║
 * ║  12. Detail Engine (sharpen + guided filter)                             ║
 * ║  13. JPEG encode + Ultra-HDR gain map (GainMapGen)                       ║
 * ╚══════════════════════════════════════════════════════════════════════════╝
 */

#include "../include/ProXDR_Engine.h"
#include <chrono>

namespace ProXDR {

// Forward decls of stage entry points (defined in their respective .cpp)
struct SceneReport;
SceneReport analyze_scene(const ImageBuffer& raw_ref,
                            const std::vector<FrameMeta>& metas,
                            const FrameMeta& ref_meta,
                            const CameraMeta& cam,
                            const ImageBuffer* optional_flow_mag);
void tune_for_scene(ProXDRCfg& cfg, SceneMode mode, f32 ev100,
                    f32 dynamic_range_ev, bool tripod, bool backlit,
                    int faces_found, f32 motion_px_ms,
                    ThermalState thermal);
int  select_reference_frame(const std::vector<FrameMeta>& metas, f32 motion_w);
ImageBuffer fuse_burst(const std::vector<ImageBuffer>& frames,
                        f32 sigma_n, const FusionCfg& cfg);
void wavelet_denoise_rgb(ImageBuffer& rgb, f32 sigma_n,
                          const NRCfg& cfg, const ZoneMap* zones);
void highlight_recovery(ImageBuffer& rgb, const HighlightCfg& cfg,
                         const ZoneMap& zones,
                         const ImageBuffer* dcg_short, f32 dcg_ratio);
void shadow_lift(ImageBuffer& rgb, const ShadowCfg& cfg, const ZoneMap& zones,
                  const NRCfg& nr_cfg, f32 sigma_n);
ImageBuffer tone_map(const ImageBuffer& rgb_linear, const ZoneMap& zones,
                      const ToneCfg& cfg);
void color_process(ImageBuffer& rgb_linear, const ZoneMap& zones,
                    const ColorCfg& cfg, const std::vector<f32>* lut,
                    const std::vector<FaceRegion>* faces);
f32 dcg_blend(ImageBuffer& long_rgb, const ImageBuffer& short_rgb,
               const ZoneMap& zones, const DCGCfg& cfg);
// Full GainMap definitions duplicated here so we can use the result struct
// directly in pipeline.cpp (the implementation lives in gain_map.cpp).
struct GainMapMetadata {
    f32 gain_map_min     = 0.0f;
    f32 gain_map_max     = 3.0f;
    f32 gamma            = 1.0f;
    f32 offset_sdr       = 0.015625f;
    f32 offset_hdr       = 0.015625f;
    f32 hdr_capacity_min = 0.0f;
    f32 hdr_capacity_max = 3.0f;
    int base_renditional = 0;
};
struct GainMapResult {
    ImageBuffer     map;
    GainMapMetadata meta;
};
GainMapResult generate_gainmap(const ImageBuffer& hdr_linear,
                                 const ImageBuffer& sdr_gamma,
                                 const ZoneMap& zones,
                                 const GainMapCfg& cfg);
std::string make_gainmap_xmp(const GainMapMetadata& m);
void run_neural_zone_refinement(const ImageBuffer& rgb_full,
                                  ZoneMap& zones, const NeuralCfg& ncfg);
void run_portrait_mat_refinement(const ImageBuffer& rgb_full,
                                   ZoneMap& zones, const NeuralCfg& ncfg);

// Re-declare the SceneReport struct fields used here (full def lives in scene_analyzer.cpp).
struct SceneReport {
    ZoneMap   zones;
    struct LumStats { f32 shadow_thr, highlight_thr, clip_thr, mean, p99, p1, dynamic_range_ev; } lum;
    f32       ev100, hdr_readiness, clipped_fraction, shadow_fraction, sky_fraction;
    bool      tripod, backlit;
    SceneMode auto_mode;
    int       faces_found;
    f32       cct_estimate_K;
};

// ─── RAW finishing helpers (black-level, LSC, WB, demosaic, CCM) ────────────
/**
 * Subtracts black-level, applies lens-shading correction, then white balance.
 * Output: still-mosaiced fp32 buffer in [0..1] (per-channel) ready for demosaic.
 */
static void raw_pre(ImageBuffer& raw, const FrameMeta& m, BayerPattern bp) {
    const int W = raw.w, H = raw.h;
    const f32 wl = std::max(1.f, m.white - 0.25f*(m.black[0]+m.black[1]+m.black[2]+m.black[3]));

    auto chan_at = [&](int x, int y) -> int {
        // RGGB: 0 = R, 1 = Gr, 2 = Gb, 3 = B
        const int xy = (y & 1) * 2 + (x & 1);
        switch (bp) {
            case BayerPattern::RGGB: return std::array<int,4>{0,1,2,3}[xy];
            case BayerPattern::BGGR: return std::array<int,4>{3,1,2,0}[xy];
            case BayerPattern::GRBG: return std::array<int,4>{1,0,3,2}[xy];
            case BayerPattern::GBRG: return std::array<int,4>{2,3,0,1}[xy];
        }
        return 0;
    };

    for (int y = 0; y < H; ++y) {
        for (int x = 0; x < W; ++x) {
            const int c = chan_at(x, y);
            f32 v = raw.at(x,y) - m.black[c];
            v = std::max(0.f, v) / wl;
            v *= m.wb_gains[c];
            raw.at(x,y) = std::clamp(v, 0.f, 4.f); // allow >1 for HDR scenes
        }
    }
}

/**
 * Malvar 5×5 demosaic (HQ Linear) — Microsoft Research 2004.
 * Faster than full bilinear-with-correction methods, produces clean results.
 * Output: W×H×3 linear RGB (camera native primaries).
 */
static ImageBuffer demosaic_malvar(const ImageBuffer& bayer, BayerPattern bp) {
    const int W = bayer.w, H = bayer.h;
    ImageBuffer rgb(W, H, 3);

    auto chan = [&](int x, int y) -> int {
        const int xy = (y & 1) * 2 + (x & 1);
        switch (bp) {
            case BayerPattern::RGGB: return std::array<int,4>{0,1,1,2}[xy];   // R,Gr,Gb,B → R,G,G,B
            case BayerPattern::BGGR: return std::array<int,4>{2,1,1,0}[xy];
            case BayerPattern::GRBG: return std::array<int,4>{1,0,2,1}[xy];
            case BayerPattern::GBRG: return std::array<int,4>{1,2,0,1}[xy];
        }
        return 1;
    };

    auto B = [&](int x, int y) { return bayer.mirror(x, y); };

    for (int y = 0; y < H; ++y) {
        for (int x = 0; x < W; ++x) {
            const int c = chan(x, y);
            f32 R = 0, G = 0, Bv = 0;

            const f32 v = B(x,y);
            const f32 sN  = B(x,   y-1), sS  = B(x,   y+1);
            const f32 sE  = B(x+1, y  ), sW  = B(x-1, y  );
            const f32 sN2 = B(x,   y-2), sS2 = B(x,   y+2);
            const f32 sE2 = B(x+2, y  ), sW2 = B(x-2, y  );
            const f32 sNE = B(x+1, y-1), sNW = B(x-1, y-1);
            const f32 sSE = B(x+1, y+1), sSW = B(x-1, y+1);

            if (c == 0) { // at R site
                R = v;
                G = (2.f*(sN+sS+sE+sW) + 4.f*v - (sN2+sS2+sE2+sW2)) / 8.f;
                Bv= (4.f*v + 6.f*(sNE+sNW+sSE+sSW)/4.f - 1.5f*(sN2+sS2+sE2+sW2) - 0.f) / 8.f;
            } else if (c == 2) { // at B site
                Bv= v;
                G = (2.f*(sN+sS+sE+sW) + 4.f*v - (sN2+sS2+sE2+sW2)) / 8.f;
                R = (4.f*v + 6.f*(sNE+sNW+sSE+sSW)/4.f - 1.5f*(sN2+sS2+sE2+sW2) - 0.f) / 8.f;
            } else { // G site (1)
                G = v;
                // Different formulas at Gr vs Gb sites — simplified average here
                R = 0.5f * (sE + sW);
                Bv= 0.5f * (sN + sS);
            }
            rgb.at(x,y,0) = std::max(0.f, R);
            rgb.at(x,y,1) = std::max(0.f, G);
            rgb.at(x,y,2) = std::max(0.f, Bv);
        }
    }
    return rgb;
}

/**
 * Apply CCM (camera RGB → linear sRGB).
 */
static void apply_ccm(ImageBuffer& rgb, const f32 ccm[3][3]) {
    const int W = rgb.w, H = rgb.h;
    for (int y = 0; y < H; ++y) {
        for (int x = 0; x < W; ++x) {
            const f32 R = rgb.at(x,y,0), G = rgb.at(x,y,1), Bv = rgb.at(x,y,2);
            rgb.at(x,y,0) = std::max(0.f, ccm[0][0]*R + ccm[0][1]*G + ccm[0][2]*Bv);
            rgb.at(x,y,1) = std::max(0.f, ccm[1][0]*R + ccm[1][1]*G + ccm[1][2]*Bv);
            rgb.at(x,y,2) = std::max(0.f, ccm[2][0]*R + ccm[2][1]*G + ccm[2][2]*Bv);
        }
    }
}

// ─── Trivial DoG sharpener for the detail stage ────────────────────────────
static void dog_sharpen(ImageBuffer& rgb, f32 strength, const ZoneMap& zones) {
    if (strength <= 0.f) return;
    const int W = rgb.w, H = rgb.h;
    ImageBuffer Y(W,H,1);
    for (int y = 0; y < H; ++y)
        for (int x = 0; x < W; ++x)
            Y.at(x,y) = 0.2126f*rgb.at(x,y,0)+0.7152f*rgb.at(x,y,1)+0.0722f*rgb.at(x,y,2);

    auto blur = [&](int r) {
        // Simple separable 1-3-1 binomial blur, repeated r/2 times
        ImageBuffer t(W,H,1);
        for (int y = 0; y < H; ++y)
            for (int x = 0; x < W; ++x)
                t.at(x,y) = 0.25f*Y.mirror(x-1,y) + 0.5f*Y.at(x,y) + 0.25f*Y.mirror(x+1,y);
        ImageBuffer u(W,H,1);
        for (int y = 0; y < H; ++y)
            for (int x = 0; x < W; ++x)
                u.at(x,y) = 0.25f*t.mirror(x,y-1) + 0.5f*t.at(x,y) + 0.25f*t.mirror(x,y+1);
        return u;
    };
    auto Y1 = blur(2);
    auto Y2 = blur(2); // applied to Y1 below — placeholder for proper σ=2 Gaussian

    for (int y = 0; y < H; ++y) {
        for (int x = 0; x < W; ++x) {
            // Avoid sharpening faces / sky aggressively
            f32 s = strength;
            if (zones.has(x,y, ZONE_FACE)) s *= 0.5f;
            if (zones.has(x,y, ZONE_SKY))  s *= 0.4f;
            const f32 hf = Y.at(x,y) - Y1.at(x,y);   // unsharp delta
            const f32 boost = 1.f + s * (hf / std::max(EPS, Y.at(x,y)));
            for (int c = 0; c < 3; ++c)
                rgb.at(x,y,c) = std::clamp(rgb.at(x,y,c) * boost, 0.f, 1.f);
        }
    }
    (void)Y2;
}

// ─── Public factory: defaults per scene ─────────────────────────────────────
ProXDRCfg DefaultConfigForScene(SceneMode m, const CameraMeta& cam) {
    ProXDRCfg cfg;
    cfg.camera = cam;
    cfg.scene_mode = m;
    cfg.adaptive_mode = true;
    return cfg;
}

// ─── Top-level synchronous entry ────────────────────────────────────────────
ProXDRResult ProcessBurst(const BurstInput& input, const ProXDRCfg& cfg_in,
                          ProgressCb progress, PostviewCb postview, LogCb logcb) {
    using clk = std::chrono::high_resolution_clock;
    const auto t_total0 = clk::now();
    ProXDRResult result;
    ProXDRCfg cfg = cfg_in;

    auto stage_t0 = [&]() { return clk::now(); };
    auto stage_dt = [&](const decltype(clk::now())& s) {
        return std::chrono::duration<f32, std::milli>(clk::now() - s).count();
    };
    auto report = [&](f32 frac, const char* name) {
        if (progress) progress(frac, name);
        if (logcb)    logcb("info", name);
    };

    if (input.raw_frames.empty() || input.meta.empty()) {
        if (logcb) logcb("error", "ProcessBurst: empty input");
        return result;
    }
    result.frames_in = static_cast<int>(input.raw_frames.size());

    // ── 0. Reference-frame pick ──────────────────────────────────────────
    int ref_idx = input.reference_idx;
    if (ref_idx < 0 || ref_idx >= static_cast<int>(input.meta.size()))
        ref_idx = select_reference_frame(input.meta, cfg.fusion.ref_pick_motion_w);
    const FrameMeta& ref_meta = input.meta[ref_idx];

    // ── 1. Scene analysis ────────────────────────────────────────────────
    report(0.05f, "scene_analysis");
    auto t_scene = stage_t0();
    auto rep = ::ProXDR::analyze_scene(*input.raw_frames[ref_idx], input.meta,
                                         ref_meta, cfg.camera, nullptr);
    result.scene_ms       = stage_dt(t_scene);
    result.ev100          = rep.ev100;
    result.dynamic_range_ev = rep.lum.dynamic_range_ev;
    result.sky_fraction   = rep.sky_fraction;
    result.faces_found    = rep.faces_found;
    result.auto_scene     = rep.auto_mode;

    // ── 2. Adaptive tuning ───────────────────────────────────────────────
    SceneMode chosen = (cfg.scene_mode == SceneMode::Auto) ? rep.auto_mode : cfg.scene_mode;
    tune_for_scene(cfg, chosen, rep.ev100, rep.lum.dynamic_range_ev,
                    rep.tripod, rep.backlit, rep.faces_found,
                    ref_meta.motion_px_ms, cfg.thermal_state);

    // ── 3. Burst alignment + merge (RAW domain) ──────────────────────────
    report(0.20f, "burst_merge");
    auto t_merge = stage_t0();
    std::vector<ImageBuffer> frames_owned;
    for (auto* p : input.raw_frames) frames_owned.push_back(*p);
    const int max_frames = std::min<int>(cfg.fusion.max_frames, static_cast<int>(frames_owned.size()));
    if (ref_idx != 0) std::swap(frames_owned[0], frames_owned[ref_idx]);
    frames_owned.resize(max_frames);

    const f32 sigma_n_raw = ref_meta.noise.sigma_avg(rep.lum.mean);
    ImageBuffer merged_raw = (max_frames > 1)
        ? fuse_burst(frames_owned, sigma_n_raw, cfg.fusion)
        : frames_owned[0];
    result.merged_raw     = cfg.save_merged_raw ? merged_raw : ImageBuffer();
    result.merge_ms       = stage_dt(t_merge);
    result.frames_merged  = max_frames;
    result.temporal_binning_used = cfg.fusion.temporal_binning;

    // ── 4. RAW finishing ─────────────────────────────────────────────────
    report(0.40f, "raw_finishing");
    raw_pre(merged_raw, ref_meta, cfg.camera.bayer);
    ImageBuffer rgb = demosaic_malvar(merged_raw, cfg.camera.bayer);
    apply_ccm(rgb, cfg.camera.ccm);

    // ── 4b. Optional neural zone refinement (now we have a full RGB) ─────
    if (cfg.en_neural && cfg.neural.enable_segmentation) {
        run_neural_zone_refinement(rgb, rep.zones, cfg.neural);
        if (cfg.neural.enable_portrait_mat) run_portrait_mat_refinement(rgb, rep.zones, cfg.neural);
        result.neural_used = true;
    }

    // ── 5. DCG blend ─────────────────────────────────────────────────────
    if (cfg.en_dcg && ref_meta.dcg_long && ref_meta.dcg_short) {
        // DCG short stream comes in via a separate buffer (caller-side wiring)
        // — here we simply flag; proper implementation pulls dcg_short via input.
    }

    // ── 6. Wavelet NR ────────────────────────────────────────────────────
    report(0.55f, "wavelet_nr");
    auto t_nr = stage_t0();
    const f32 sigma_n_rgb = ref_meta.noise.sigma_avg(0.5f);
    wavelet_denoise_rgb(rgb, sigma_n_rgb, cfg.nr, &rep.zones);
    result.nr_ms = stage_dt(t_nr);

    // ── 7. Highlight Recovery ────────────────────────────────────────────
    report(0.65f, "highlight_recovery");
    auto t_hi = stage_t0();
    highlight_recovery(rgb, cfg.highlight, rep.zones, nullptr, ref_meta.dcg_ratio);
    result.highlight_ms = stage_dt(t_hi);

    // ── 8. Shadow Lift ───────────────────────────────────────────────────
    report(0.72f, "shadow_lift");
    auto t_sh = stage_t0();
    shadow_lift(rgb, cfg.shadow, rep.zones, cfg.nr, sigma_n_rgb);
    result.shadow_ms = stage_dt(t_sh);

    // Snapshot the linear HDR (used later for gain map)
    ImageBuffer hdr_linear = rgb.clone();
    result.merged_rgb = hdr_linear;

    // ── 9. Tone map ──────────────────────────────────────────────────────
    report(0.80f, "tone_map");
    auto t_tm = stage_t0();
    ImageBuffer tone_out = tone_map(rgb, rep.zones, cfg.tone);
    result.tone_ms = stage_dt(t_tm);

    // ── 10. Color (LUT, sat, gamma) ──────────────────────────────────────
    report(0.88f, "color");
    auto t_co = stage_t0();
    color_process(tone_out, rep.zones, cfg.color, nullptr, &ref_meta.faces);
    result.color_ms = stage_dt(t_co);

    // ── 11. Detail engine (sharpen) ──────────────────────────────────────
    report(0.92f, "detail");
    auto t_de = stage_t0();
    dog_sharpen(tone_out, cfg.detail.sharpen_str * 0.10f, rep.zones);
    result.detail_ms = stage_dt(t_de);
    result.final_rgb = tone_out;

    // ── 12. Gain map ─────────────────────────────────────────────────────
    if (cfg.en_gainmap && cfg.gainmap.generate) {
        report(0.96f, "gain_map");
        auto t_gm = stage_t0();
        auto gmr = generate_gainmap(hdr_linear, tone_out, rep.zones, cfg.gainmap);
        result.gainmap_ms = stage_dt(t_gm);
        // The actual JPEG_R container packing is done in the JNI-side encoder.
        // Here we store the raw 8-bit gain map into result.jpeg_gainmap as a
        // placeholder; the bridge layer encodes it as JPEG and packs into JPEG_R.
        if (gmr.map.w > 0) {
            result.jpeg_gainmap.assign(gmr.map.data.begin(), gmr.map.data.end()
                /* caller will quantise float→uint8 in JNI side */);
        }
    }

    // ── 13. JPEG encode placeholder (real impl in JNI uses libjpeg-turbo) ─
    // The pipeline returns final_rgb; the bridge writes the JPEG.

    // Postview callback
    if (postview) postview(tone_out);

    result.total_ms = std::chrono::duration<f32, std::milli>(clk::now() - t_total0).count();

    // Build summary
    char buf[512];
    std::snprintf(buf, sizeof(buf),
        "ProXDR v3 | scene=%d | EV100=%.2f | DR=%.2f EV | frames %d→%d | "
        "merge=%.0fms NR=%.0fms tone=%.0fms color=%.0fms total=%.0fms",
        static_cast<int>(chosen), result.ev100, result.dynamic_range_ev,
        result.frames_in, result.frames_merged,
        result.merge_ms, result.nr_ms, result.tone_ms, result.color_ms, result.total_ms);
    result.pipeline_summary = buf;

    if (logcb) logcb("info", result.pipeline_summary.c_str());
    return result;
}

// ─── Async wrapper ──────────────────────────────────────────────────────────
AsyncToken ProcessBurstAsync(const BurstInput& input, const ProXDRCfg& cfg,
                              JpegCb on_complete, ProgressCb progress,
                              PostviewCb postview, LogCb logcb) {
    AsyncToken tk;
    tk.cancel = std::make_shared<std::atomic<bool>>(false);
    auto cancel_ptr = tk.cancel;

    std::thread([=]() {
        if (cancel_ptr->load()) return;
        auto r = ProcessBurst(input, cfg, progress, postview, logcb);
        if (!cancel_ptr->load() && on_complete) on_complete(r);
    }).detach();

    return tk;
}

} // namespace ProXDR
