/**
 * ╔══════════════════════════════════════════════════════════════════════════╗
 * ║  ProXDR v3  ·  src/adaptive_scene.cpp                                   ║
 * ║  AdaptiveSceneMode — Auto Configuration from Scene Analysis             ║
 * ╠══════════════════════════════════════════════════════════════════════════╣
 * ║  Takes the SceneReport from scene_analyzer.cpp and rewrites the cfg     ║
 * ║  for optimal results. Replaces hand-tuning with data-driven choices.    ║
 * ║                                                                          ║
 * ║  Decision matrix (full table from skill / our experiments):              ║
 * ║                                                                          ║
 * ║   Scene            | NR    | Lift | Curve     | Sat  | LC   | Frames    ║
 * ║   ─────────────────┼───────┼──────┼───────────┼──────┼──────┼────────   ║
 * ║   BrightDay        | 0.7   | 0.08 | Stevens   | 1.15 | 0.25 | 4         ║
 * ║   Daylight         | 1.0   | 0.18 | Stevens   | 1.10 | 0.35 | 6         ║
 * ║   Indoor           | 1.1   | 0.22 | Hable     | 1.10 | 0.35 | 6         ║
 * ║   GoldenHour       | 1.0   | 0.25 | FilmLog   | 1.20 | 0.30 | 6         ║
 * ║   Backlit          | 1.0   | 0.35 | Hable     | 1.10 | 0.30 | 8         ║
 * ║   Portrait         | 0.85  | 0.18 | Hable     | 1.05 | 0.20 | 5         ║
 * ║   LowLight         | 1.3   | 0.30 | Stevens   | 1.05 | 0.25 | 8         ║
 * ║   Night            | 1.5   | 0.35 | Stevens   | 1.05 | 0.25 | 8 (bin 4) ║
 * ║   NightExtreme     | 1.7   | 0.40 | Stevens β=0.45 | 1.0 | 0.20 | 8(bin8)║
 * ║   Tripod           | 1.4   | 0.30 | Stevens   | 1.05 | 0.30 | 12        ║
 * ║   Sports           | 0.8   | 0.15 | Hable     | 1.10 | 0.40 | 3 (no bin)║
 * ╚══════════════════════════════════════════════════════════════════════════╝
 */

#include "../include/ProXDR_Engine.h"

namespace ProXDR {

struct SceneReport;  // forward decl from scene_analyzer.cpp

/**
 * tune_for_scene()
 *
 * Mutates the cfg in-place based on detected scene mode + EV100 + zones.
 *
 * Caller must have run analyze_scene() first and set cfg.scene_mode (or leave
 * Auto and pass auto_mode from the SceneReport).
 *
 * Thermal awareness:
 *   - ThermalState >= Moderate ⇒ reduce frame count, switch to faster wavelet
 *   - ThermalState >= Severe   ⇒ disable Mertens fusion entirely, force Y-scale
 */
void tune_for_scene(ProXDRCfg& cfg, SceneMode mode, f32 ev100,
                    f32 dynamic_range_ev, bool tripod, bool backlit,
                    int faces_found, f32 motion_px_ms,
                    ThermalState thermal = ThermalState::Normal) {
    if (!cfg.adaptive_mode) return;

    cfg.scene_mode = mode;

    // Defaults serve as the "Daylight" baseline; we override per scene.
    auto reset = [&]() {
        cfg.nr.luma_strength       = 1.0f;
        cfg.nr.chroma_strength     = 0.85f;
        cfg.shadow.lift_amount     = 0.18f;
        cfg.tone.curve             = ToneCurve::Stevens;
        cfg.tone.stevens_beta      = 0.32f;
        cfg.color.saturation       = 1.10f;
        cfg.tone.lc_strength       = 0.35f;
        cfg.fusion.max_frames      = 6;
        cfg.fusion.temporal_binning= false;
        cfg.fusion.bin_size        = 1;
        cfg.fusion.ghost_sigma     = 3.0f;
        cfg.tone.sky_ev            = -0.30f;
        cfg.tone.shadow_detail_ev  =  0.22f;
        cfg.detail.sharpen_str     = 1.20f;
        cfg.color.skin_protect     = 0.75f;
        cfg.tone.use_oklab_lightness = true;
        cfg.tone.compression       = 0.85f;
        cfg.tone.gain              = 1.05f;
    };
    reset();

    switch (mode) {
        case SceneMode::BrightDay:
            cfg.shadow.lift_amount   = 0.08f;
            cfg.tone.compression     = 0.80f;
            cfg.tone.sky_ev          = -0.40f;
            cfg.nr.luma_strength     = 0.70f;
            cfg.color.saturation     = 1.15f;
            cfg.fusion.max_frames    = 4;
            break;

        case SceneMode::Daylight:
            // defaults already good
            break;

        case SceneMode::Indoor:
            cfg.tone.curve           = ToneCurve::Hable;
            cfg.shadow.lift_amount   = 0.22f;
            break;

        case SceneMode::GoldenHour:
            cfg.tone.curve           = ToneCurve::FilmLog;
            cfg.tone.sky_ev          = -0.20f;
            cfg.color.saturation     = 1.20f;
            cfg.color.chroma_compress= 0.85f;
            cfg.shadow.lift_amount   = 0.25f;
            cfg.highlight.roll_off_start = 0.80f;
            break;

        case SceneMode::Backlit:
            cfg.tone.curve           = ToneCurve::Hable;
            cfg.shadow.lift_amount   = 0.35f;
            cfg.tone.face_ev         = 0.30f;       // pull faces up out of shadow
            cfg.tone.sky_ev          = -0.45f;       // pull bright background down
            cfg.fusion.max_frames    = 8;
            break;

        case SceneMode::Portrait:
            cfg.tone.curve           = ToneCurve::Hable;
            cfg.tone.face_ev         = 0.20f;
            cfg.tone.lc_strength     = 0.20f;        // less local contrast on faces
            cfg.color.skin_protect   = 0.60f;
            cfg.color.saturation     = 1.05f;
            cfg.detail.sharpen_str   = 0.90f;        // gentler sharpening
            cfg.fusion.ghost_sigma   = 2.5f;
            cfg.fusion.max_frames    = 5;
            break;

        case SceneMode::LowLight:
            cfg.nr.luma_strength     = 1.30f;
            cfg.nr.chroma_strength   = 1.10f;
            cfg.shadow.lift_amount   = 0.30f;
            cfg.shadow.nr_strength   = 0.85f;
            cfg.tone.gain            = 1.10f;
            cfg.fusion.max_frames    = 8;
            cfg.fusion.temporal_binning = true;
            cfg.fusion.bin_size      = 2;
            break;

        case SceneMode::Night:
            cfg.nr.luma_strength     = 1.50f;
            cfg.nr.chroma_strength   = 1.20f;
            cfg.nr.edge_nr_reduce    = 0.20f;
            cfg.shadow.lift_amount   = 0.35f;
            cfg.shadow.nr_strength   = 0.90f;
            cfg.tone.stevens_beta    = 0.40f;        // mesopic shift
            cfg.tone.gain            = 1.15f;
            cfg.detail.sharpen_str   = 0.80f;
            cfg.fusion.max_frames    = 8;
            cfg.fusion.temporal_binning = true;
            cfg.fusion.bin_size      = 4;
            cfg.color.saturation     = 1.05f;
            break;

        case SceneMode::NightExtreme:
            cfg.nr.luma_strength     = 1.70f;
            cfg.nr.chroma_strength   = 1.40f;
            cfg.nr.nlm_fallback      = true;
            cfg.shadow.lift_amount   = 0.40f;
            cfg.tone.stevens_beta    = 0.45f;        // scotopic
            cfg.tone.gain            = 1.20f;
            cfg.fusion.max_frames    = 8;
            cfg.fusion.temporal_binning = true;
            cfg.fusion.bin_size      = 8;
            cfg.color.saturation     = 1.00f;
            break;

        case SceneMode::Tripod:
            // Use night-mode-quality settings but with longer effective TET
            cfg.nr.luma_strength     = 1.40f;
            cfg.shadow.lift_amount   = 0.30f;
            cfg.tone.lc_strength     = 0.30f;
            cfg.fusion.max_frames    = 12;
            cfg.fusion.temporal_binning = true;
            cfg.fusion.bin_size      = 4;
            break;

        case SceneMode::Sports:
            cfg.fusion.max_frames    = 3;
            cfg.fusion.temporal_binning = false;
            cfg.fusion.bin_size      = 1;
            cfg.fusion.ghost_sigma   = 2.0f;
            cfg.fusion.wiener_strength = 0.80f;
            cfg.tone.lc_strength     = 0.40f;
            cfg.detail.sharpen_str   = 1.50f;
            cfg.nr.luma_strength     = 0.80f;
            cfg.tone.curve           = ToneCurve::Hable;
            break;

        default: break;
    }

    // EV100 fine-tuning (overrides per-scene defaults if needed)
    if (ev100 > 6.0f) {
        // Very bright — almost certain highlight clipping
        cfg.highlight.recovery_strength = 1.0f;
        cfg.highlight.roll_off_start    = 0.78f;
    } else if (ev100 < -4.0f) {
        // Very dark — bump NR aggressively
        cfg.nr.luma_strength   = std::max(cfg.nr.luma_strength,   1.6f);
        cfg.nr.chroma_strength = std::max(cfg.nr.chroma_strength, 1.3f);
    }

    // Scene-DR awareness — if DR > 12 EV, switch to a curve with more shoulder
    if (dynamic_range_ev > 12.f) {
        cfg.tone.curve        = ToneCurve::Hable;
        cfg.tone.compression  = 1.00f;
        cfg.tone.sky_ev       = std::min(cfg.tone.sky_ev, -0.40f);
        cfg.shadow.lift_amount= std::max(cfg.shadow.lift_amount, 0.30f);
    }
    // Very low DR — reduce all the work, scene doesn't need HDR
    if (dynamic_range_ev < 5.f && faces_found == 0) {
        cfg.fusion.max_frames = std::min(cfg.fusion.max_frames, 4);
        cfg.tone.lc_strength  = std::min(cfg.tone.lc_strength, 0.20f);
    }

    // Tripod overrides — extra frames OK, gyro is still
    if (tripod) {
        cfg.fusion.max_frames = std::max(cfg.fusion.max_frames, 8);
        cfg.fusion.ghost_sigma = std::min(cfg.fusion.ghost_sigma, 2.5f);
    }

    // High motion — tighten ghosting, fewer frames
    if (motion_px_ms > 0.4f) {
        cfg.fusion.max_frames = std::min(cfg.fusion.max_frames, 4);
        cfg.fusion.ghost_sigma = 2.0f;
    }

    // Backlit hint — extra shadow lift even if mode wasn't classified Backlit
    if (backlit && mode != SceneMode::Backlit) {
        cfg.shadow.lift_amount = std::max(cfg.shadow.lift_amount, 0.30f);
        cfg.tone.face_ev       = std::max(cfg.tone.face_ev, 0.20f);
    }

    // ── Thermal-aware downscaling ────────────────────────────────────────
    switch (thermal) {
        case ThermalState::Light:
            cfg.fusion.max_frames = std::min(cfg.fusion.max_frames, 6);
            cfg.tone.tone_passes  = std::min(cfg.tone.tone_passes, 3);
            break;
        case ThermalState::Moderate:
            cfg.fusion.max_frames = std::min(cfg.fusion.max_frames, 4);
            cfg.tone.tone_passes  = 2;
            cfg.nr.wavelet_levels = std::min(cfg.nr.wavelet_levels, 3);
            cfg.tone.use_oklab_lightness = false;   // faster Y-scale path
            break;
        case ThermalState::Severe:
            cfg.fusion.max_frames = 2;
            cfg.tone.tone_passes  = 1;
            cfg.nr.wavelet_levels = 2;
            cfg.tone.use_oklab_lightness = false;
            cfg.detail.guided_filter = false;
            cfg.color.bilateral_chroma = false;
            break;
        case ThermalState::Critical:
            // Bypass HDR — base frame plus minimal NR only
            cfg.fusion.max_frames = 1;
            cfg.fusion.safnet_selection = false;
            cfg.tone.local_contrast = false;
            cfg.color.bilateral_chroma = false;
            cfg.detail.guided_filter = false;
            cfg.gainmap.generate = false;
            break;
        default: break;
    }
}

} // namespace ProXDR
