/**
 * ╔══════════════════════════════════════════════════════════════════════════╗
 * ║          ProXDR Engine v3.0  —  Adaptive AI-Powered HDR Pipeline         ║
 * ║          include/ProXDR_Engine.h  —  Master Header                       ║
 * ║          (c) 2026 — production-grade computational imaging pipeline      ║
 * ╚══════════════════════════════════════════════════════════════════════════╝
 *
 *  Full 16-bit linear-float HDR pipeline for Android camera apps.
 *  Zero precision loss from RAW Bayer → final JPEG / Ultra-HDR output.
 *
 *  v3.0 NEW (vs v2):
 *    ╔─ AdaptiveSceneMode      — auto-selects pipeline params from EV100/zones
 *    ╠─ SceneAnalyzer 3.0      — EV100 estimator, tripod detection, HDR-readiness
 *    ╠─ FusionLM (SAFNet-lite) — selective alignment fusion (ECCV 2024 inspired)
 *    ╠─ DCGHDR                 — dual-conversion-gain dual-exposure blending
 *    ╠─ HighlightRecovery 2.0  — soft-knee, face-safe, multi-scale spectral
 *    ╠─ ShadowLift 2.0         — log-lift + local-adapt + pre/post NR
 *    ╠─ WaveletNR 2.0          — Daubechies db4 (no Haar blocking) + edge gate
 *    ╠─ ToneLM 3.0             — adds Hable/GT7/Reinhard-ext/Drago + Oklab path
 *    ╠─ ColorLM 3.0            — Oklab fast path, fixed gamut & sky/skin guards
 *    ╠─ GainMapGen             — Android 14 Ultra-HDR JPEG-R gain map output
 *    ╠─ NeuralHooks            — TFLite/ONNX scene-seg & portrait-mat plug points
 *    ╚─ Async pipeline         — thermal-aware, cancellable, postview callbacks
 */

#pragma once
#ifndef PROXDR_ENGINE_V3_H
#define PROXDR_ENGINE_V3_H

#include <cstdint>
#include <cmath>
#include <vector>
#include <array>
#include <memory>
#include <functional>
#include <atomic>
#include <mutex>
#include <thread>
#include <string>
#include <optional>
#include <algorithm>
#include <cassert>
#include <numeric>
#include <complex>
#include <chrono>

namespace ProXDR {

// ─── Numeric aliases ─────────────────────────────────────────────────────────
using u8  = uint8_t;
using u16 = uint16_t;
using u32 = uint32_t;
using i32 = int32_t;
using i64 = int64_t;
using f32 = float;
using f64 = double;
using Cpx = std::complex<f32>;

// ─── Compile-time constants ──────────────────────────────────────────────────
constexpr int  PROXDR_VERSION       = 30;       // 3.0
constexpr int  MAX_BURST            = 32;
constexpr int  TILE_SIZE            = 16;
constexpr int  PYRAMID_LEVELS       = 4;
constexpr int  LAPLACIAN_LEVELS     = 6;
constexpr int  LUT3D_SIZE           = 33;
constexpr int  WAVELET_LEVELS       = 4;
constexpr int  MAX_FACES            = 8;
constexpr f32  PI                   = 3.14159265358979f;
constexpr f32  TWO_PI               = 6.28318530717959f;
constexpr f32  INV_SQRT2            = 0.70710678118655f;
constexpr f32  EPS                  = 1e-6f;

// ─── Enumerations ────────────────────────────────────────────────────────────
enum class BayerPattern : u8 { RGGB = 0, BGGR = 1, GRBG = 2, GBRG = 3 };

enum class ColorSpace : u8 {
    Linear_sRGB = 0, Gamma_sRGB = 1, Linear_P3 = 2,
    Linear_BT2020 = 3, XYZ_D65 = 4, ICtCp = 5, Oklab = 6
};

// ToneCurve: classical + modern + cinematic
enum class ToneCurve : u8 {
    sRGB    = 0,  // standard piecewise gamma 2.4
    FilmLog = 1,  // toe + linear + shoulder log roll-off
    ACES    = 2,  // Narkowicz approximation of ACES RRT+ODT
    Stevens = 3,  // psychophysical power law (adaptive β)
    Vivid   = 4,  // boosted phone-camera look
    Hable   = 5,  // John Hable / Uncharted-2 filmic operator
    Reinhard= 6,  // Reinhard extended (Reinhard et al. 2002)
    GT7     = 7,  // Gran Turismo 7 PBS curve (2025)
    Drago   = 8,  // Drago adaptive logarithmic (very high DR)
};

enum class NRMode : u8 {
    Off         = 0,
    BayesShrink = 1,    // adaptive per-subband (default)
    VisuShrink  = 2,    // universal threshold
    Bilateral   = 3,    // edge-preserving fallback
    NLM         = 4,    // non-local means (very noisy / night)
};

// Scene mode — driven by AdaptiveSceneMode
enum class SceneMode : u8 {
    Auto         = 0,
    BrightDay    = 1,    // EV100 > 4
    Daylight     = 2,    // 1 < EV100 ≤ 4
    Indoor       = 3,    // -1 < EV100 ≤ 1
    LowLight     = 4,    // -3 < EV100 ≤ -1
    Night        = 5,    // EV100 ≤ -3
    NightExtreme = 6,    // EV100 ≤ -5
    GoldenHour   = 7,    // warm CCT + low-sun position
    Backlit      = 8,    // strong rear light source
    Portrait     = 9,    // face occupies > 8% of frame
    Sports       = 10,   // high motion magnitude
    Tripod       = 11,   // gyro stillness over capture window
    Macro        = 12,   // close-focus subject
};

enum class WaveletKind : u8 {
    Haar = 0,
    Daub4 = 1,        // Daubechies db4 — default, smoother than Haar
    BiorCDF97 = 2,    // CDF 9/7 — JPEG2000 standard, best for natural images
};

enum class ThermalState : u8 {
    Normal = 0, Light = 1, Moderate = 2, Severe = 3, Critical = 4
};

// ─── Semantic zone bitfield ──────────────────────────────────────────────────
enum Zone : u8 {
    ZONE_NONE      = 0x00,
    ZONE_SKY       = 0x01,
    ZONE_FACE      = 0x02,
    ZONE_SHADOW    = 0x04,
    ZONE_HIGHLIGHT = 0x08,
    ZONE_EDGE      = 0x10,
    ZONE_MOTION    = 0x20,
    ZONE_CLIPPED   = 0x40,
    ZONE_MIDTONE   = 0x80,
};
// Extended zones (separate u8) — vegetation/water/skin etc come from neural seg
enum ZoneExt : u8 {
    ZONEX_NONE       = 0x00,
    ZONEX_SKIN       = 0x01,
    ZONEX_VEG        = 0x02,
    ZONEX_WATER      = 0x04,
    ZONEX_BUILDING   = 0x08,
    ZONEX_TEXT       = 0x10,
    ZONEX_NIGHT_SKY  = 0x20,
    ZONEX_GROUND     = 0x40,
    ZONEX_PERSON_BODY= 0x80,
};

// ─── ImageBuffer (interleaved fp32, row-major) ───────────────────────────────
struct ImageBuffer {
    int w = 0, h = 0, c = 1;
    std::vector<f32> data;

    ImageBuffer() = default;
    ImageBuffer(int _w, int _h, int _c = 1)
        : w(_w), h(_h), c(_c), data(static_cast<size_t>(_w)*_h*_c, 0.f) {}

    inline f32& at(int x, int y, int ch = 0) {
        return data[(static_cast<size_t>(y)*w + x)*c + ch];
    }
    inline f32 at(int x, int y, int ch = 0) const {
        return data[(static_cast<size_t>(y)*w + x)*c + ch];
    }

    // Reflect-padding sampler — safe for any (x,y)
    inline f32 mirror(int x, int y, int ch = 0) const {
        auto m = [](int v, int n) -> int {
            if (n <= 1) return 0;
            v = (v < 0)   ? -v - 1     : v;
            v = (v >= n)  ? 2*n - v - 1 : v;
            return std::clamp(v, 0, n-1);
        };
        return at(m(x,w), m(y,h), ch);
    }

    // Bilinear at fractional position
    inline f32 sample(f32 xf, f32 yf, int ch = 0) const {
        const int   x0 = static_cast<int>(std::floor(xf));
        const int   y0 = static_cast<int>(std::floor(yf));
        const f32   fx = xf - x0, fy = yf - y0;
        return (1-fx)*(1-fy)*mirror(x0,  y0,  ch)
             + (  fx)*(1-fy)*mirror(x0+1,y0,  ch)
             + (1-fx)*(  fy)*mirror(x0,  y0+1,ch)
             + (  fx)*(  fy)*mirror(x0+1,y0+1,ch);
    }

    void fill(f32 v) { std::fill(data.begin(), data.end(), v); }
    void clear()     { fill(0.f); }
    size_t bytes()   const { return data.size() * sizeof(f32); }
    int    pixels()  const { return w * h; }

    // Channel-aware deep copy
    ImageBuffer clone() const { return *this; }
};

// ─── Semantic zone map (full-res) ────────────────────────────────────────────
struct ZoneMap {
    int w = 0, h = 0;
    std::vector<u8>  labels;     // primary zone bitfield
    std::vector<u8>  labels_ext; // extended (neural-derived) zones
    std::vector<f32> sky_conf;
    std::vector<f32> face_conf;
    std::vector<f32> skin_conf;  // soft skin mask (from neural seg / face crop)
    std::vector<f32> edge_mag;
    std::vector<f32> motion;     // px/ms or per-pixel optical flow magnitude
    std::vector<f32> luminance;  // cached normalised Y plane (0..1)

    ZoneMap() = default;
    ZoneMap(int _w, int _h) : w(_w), h(_h),
        labels(_w*_h,0), labels_ext(_w*_h,0),
        sky_conf(_w*_h,0), face_conf(_w*_h,0), skin_conf(_w*_h,0),
        edge_mag(_w*_h,0), motion(_w*_h,0), luminance(_w*_h,0) {}

    inline u8&  label   (int x, int y)       { return labels    [y*w+x]; }
    inline u8&  label_x (int x, int y)       { return labels_ext[y*w+x]; }
    inline f32& sky     (int x, int y)       { return sky_conf  [y*w+x]; }
    inline f32& face    (int x, int y)       { return face_conf [y*w+x]; }
    inline f32& skin    (int x, int y)       { return skin_conf [y*w+x]; }
    inline f32& edge    (int x, int y)       { return edge_mag  [y*w+x]; }
    inline f32& mot     (int x, int y)       { return motion    [y*w+x]; }
    inline f32& lum     (int x, int y)       { return luminance [y*w+x]; }
    bool has(int x, int y, Zone z)    const  { return (labels    [y*w+x] & z) != 0; }
    bool has_x(int x, int y, ZoneExt z)const { return (labels_ext[y*w+x] & z) != 0; }
};

// ─── Affine signal-dependent noise model (foundational) ─────────────────────
// σ²(I, ch) = scale[ch] · I + offset[ch]   (channel = R, Gr, Gb, B)
struct AffineNoiseModel {
    f32 scale [4] = {};
    f32 offset[4] = {};
    inline f32 variance(f32 I, int ch) const { return scale[ch]*I + offset[ch]; }
    inline f32 sigma   (f32 I, int ch) const { return std::sqrt(std::max(0.f, variance(I,ch))); }

    static AffineNoiseModel combine(const AffineNoiseModel& a,
                                    const AffineNoiseModel& b, f32 wa, f32 wb) {
        AffineNoiseModel r;
        for (int i=0;i<4;++i){ r.scale[i]=wa*a.scale[i]+wb*b.scale[i];
                                r.offset[i]=wa*a.offset[i]+wb*b.offset[i]; }
        return r;
    }
    // Average σ at intensity I across channels — useful for chroma NR base level
    inline f32 sigma_avg(f32 I) const {
        return 0.25f*(sigma(I,0)+sigma(I,1)+sigma(I,2)+sigma(I,3));
    }
};

struct FaceRegion {
    f32 cx = 0.5f, cy = 0.5f;     // normalised centre [0..1]
    f32 width = 0.f, height = 0.f;
    f32 confidence = 0.f;
    f32 skin_L = 60.f, skin_a = 12.f, skin_b = 18.f; // Lab skin reference
    f32 ae_weight = 3.f;
    int landmarks[10] = {};        // optional: eyes/nose/mouth in pixels
};

struct GyroSample { i64 ts_ns; f32 wx, wy, wz; };
struct OISSample  { i64 ts_ns; f32 dx_px, dy_px; };

// ─── Per-frame metadata (one per RAW frame in burst) ────────────────────────
struct FrameMeta {
    i64  ts_ns          = 0;
    f32  exp_ms         = 0.f;
    f32  analog_gain    = 1.f;
    f32  digital_gain   = 1.f;

    f32  black [4]      = {};         // RGGB black levels (raw counts)
    f32  white          = 65535.f;
    f32  wb_gains[4]    = {1,1,1,1};  // R, Gr, Gb, B  (4 channels — fixed)

    // Lens shading (vignetting) maps — RGGB
    std::vector<f32> lsc_R, lsc_GR, lsc_GB, lsc_B;
    int  lsc_w = 0, lsc_h = 0;

    AffineNoiseModel       noise;
    std::vector<GyroSample> gyro;
    std::vector<OISSample>  ois;
    std::vector<FaceRegion> faces;

    f32  focal_mm    = 4.f;
    f32  f_number    = 1.8f;
    f32  focus_m     = 2.f;

    bool dcg_long  = false;
    bool dcg_short = false;
    f32  dcg_ratio = 1.f;       // long_exp / short_exp

    f32  motion_px_ms  = 0.f;   // gyro-derived global motion estimate
    f32  sharpness     = 0.f;   // Laplacian variance (focus score) — for ZSL pick
    bool is_reference  = false; // chosen base frame in burst
};

struct CameraMeta {
    int   sensor_w = 4000, sensor_h = 3000;
    int   raw_bits = 12;
    BayerPattern bayer = BayerPattern::RGGB;

    // 3×3 CCM at D65: camera RGB → linear sRGB
    f32   ccm[3][3] = {{1,0,0},{0,1,0},{0,0,1}};

    int   lsc_w = 17, lsc_h = 13;
    f32   min_exp_ms = 0.1f, max_exp_ms = 333.f;
    f32   min_gain   = 1.f,  max_gain   = 64.f;

    bool  has_ois  = false;
    bool  has_dcg  = false;
    bool  has_flash= false;
    bool  has_npu  = true;       // for neural-hook gating
    int   active_x = 0, active_y = 0;  // active array origin

    std::string make, model;
};

// ─── Configuration blocks ───────────────────────────────────────────────────

struct FusionCfg {
    int   tile_size         = TILE_SIZE;
    int   pyramid_levels    = PYRAMID_LEVELS;
    int   search_radius     = 4;
    bool  subpixel_lk       = true;
    bool  ghost_removal     = true;
    f32   ghost_sigma       = 3.0f;
    bool  temporal_binning  = true;
    int   bin_size          = 4;
    f32   wiener_strength   = 1.0f;
    bool  photon_matrix     = true;
    int   photon_radius     = 12;
    bool  safnet_selection  = true;     // SAFNet-style selective alignment
    f32   safnet_threshold  = 0.6f;
    int   max_frames        = 8;
    f32   ref_pick_motion_w = 0.6f;     // weight of motion vs sharpness in ref pick
};

struct NRCfg {
    NRMode      mode           = NRMode::BayesShrink;
    WaveletKind wavelet        = WaveletKind::Daub4;   // default — db4
    int         wavelet_levels = WAVELET_LEVELS;
    f32         luma_strength  = 1.0f;
    f32         chroma_strength= 0.85f;
    bool        edge_adaptive  = true;
    f32         edge_nr_reduce = 0.30f;
    bool        nlm_fallback   = true;   // hybrid NLM for extreme noise
    f32         nlm_threshold_iso = 6400.f;
    int         bilateral_r    = 7;
    f32         bilateral_ss   = 3.f;
    f32         bilateral_sr   = 0.05f;
};

struct HighlightCfg {
    bool  enabled            = true;
    f32   clip_threshold     = 0.95f;
    f32   recovery_strength  = 1.0f;
    bool  spectral_ratio     = true;
    bool  multi_scale        = true;     // 5×5 + 17×17 ratios for stability
    f32   roll_off_start     = 0.85f;
    f32   roll_off_strength  = 0.70f;
    bool  dcg_blend          = true;
    bool  protect_face       = true;     // skip spectral ratio on faces
    bool  soft_threshold     = true;     // smooth approach instead of hard step
    f32   soft_band          = 0.04f;    // half-width of soft transition
};

struct ShadowCfg {
    f32   lift_amount        = 0.18f;    // logarithmic lift coefficient
    f32   lift_threshold     = 0.15f;
    f32   lift_feather       = 0.10f;
    f32   nr_strength        = 0.80f;
    bool  wavelet_nr         = true;
    bool  pre_lift_nr        = true;     // denoise before amplifying noise
    bool  local_adaptive     = true;     // adapt lift to local mean luminance
    int   local_radius       = 24;
};

struct ToneCfg {
    ToneCurve curve            = ToneCurve::Stevens;
    f32   gamma                = 2.2f;
    f32   compression          = 0.85f;
    f32   gain                 = 1.05f;
    int   tone_passes          = 4;
    int   pyr_levels           = LAPLACIAN_LEVELS;

    // Stevens
    f32   stevens_beta         = 0.32f;
    f32   stevens_adaptation   = 0.50f;

    // ACES (Narkowicz)
    f32   aces_a = 2.51f, aces_b = 0.03f, aces_c = 2.43f, aces_d = 0.59f, aces_e = 0.14f;

    // FilmLog
    f32   film_toe             = 0.04f;
    f32   film_shoulder        = 0.85f;

    // Vivid
    f32   vivid_strength       = 0.18f;

    // Hable / Uncharted-2
    f32   hable_A = 0.15f, hable_B = 0.50f, hable_C = 0.10f, hable_D = 0.20f;
    f32   hable_E = 0.02f, hable_F = 0.30f, hable_W = 11.2f;

    // Reinhard extended
    f32   reinhard_white       = 4.0f;

    // GT7-style (S-curve with calibrated mid-grey)
    f32   gt7_mid_grey         = 0.18f;
    f32   gt7_peak             = 1.0f;
    f32   gt7_contrast         = 1.05f;

    // Drago adaptive log
    f32   drago_bias           = 0.85f;

    // Zone EV offsets (applied before global curve)
    f32   sky_ev               = -0.30f;
    f32   sky_contrast         =  0.20f;
    f32   face_ev              =  0.15f;
    f32   shadow_detail_ev     =  0.22f;
    f32   highlight_recover_ev = -0.15f;

    // Local contrast
    bool  local_contrast       = true;
    f32   lc_strength          = 0.35f;
    int   lc_radius            = 64;

    // Use Oklab L for tone curve (preserves hue better than Y) — recommended
    bool  use_oklab_lightness  = true;
};

struct ColorCfg {
    bool  enable_3dlut         = true;
    int   lut_size             = LUT3D_SIZE;
    f32   saturation           = 1.10f;
    f32   chroma_compress      = 0.90f;
    f32   skin_protect         = 0.75f;
    f32   sky_protect          = 0.70f;
    bool  texture_color        = true;
    f32   tc_edge_reduce       = 0.50f;
    ColorSpace output          = ColorSpace::Gamma_sRGB;
    bool  p3_output            = false;
    bool  wb_adaptation        = true;
    bool  use_oklab            = true;      // fast-path saturation in Oklab
    bool  bilateral_chroma     = true;
    int   bilateral_chroma_r   = 5;
};

struct DetailCfg {
    bool  guided_filter        = true;
    int   gf_radius            = 8;
    f32   gf_epsilon           = 0.01f;
    f32   sharpen_str          = 1.20f;
    int   sharpen_r            = 3;
    bool  luma_only            = true;
    bool  hf_bypass            = true;
    int   hf_bypass_levels     = 2;
    f32   hf_bypass_str        = 0.80f;
    f32   edge_threshold       = 0.025f;
};

struct DCGCfg {
    bool  enabled              = true;
    f32   dcg_ratio            = 4.0f;
    f32   blend_start          = 0.80f;
    f32   blend_end            = 1.00f;
    bool  flicker_correct      = true;
    f32   recover_stops        = 2.0f;
};

struct GainMapCfg {
    bool  generate             = true;
    f32   hdr_capacity_ev      = 3.0f;
    int   gainmap_w            = 512;
    int   gainmap_h            = 384;
    bool  heic_compatible      = true;
    bool  zone_aware           = true;     // larger gains in shadows, smaller in faces
    f32   offset_sdr           = 0.015625f;
    f32   offset_hdr           = 0.015625f;
};

// ─── Neural hooks (pluggable inference back-end) ─────────────────────────────
// Implementations live in NeuralHooks.cpp / Android JNI side.
struct NeuralCfg {
    bool  enable_segmentation  = true;     // sky/face/veg/water seg model
    bool  enable_portrait_mat  = false;    // person matting (alpha)
    bool  enable_neural_demosaic = false;  // optional NN demosaic for low-light
    std::string seg_model_path = "scene_seg_mobilenetv3.tflite";
    std::string mat_model_path = "portrait_matting_mobilenet.tflite";
    int   seg_input_w          = 256;
    int   seg_input_h          = 192;
    int   threads              = 2;
    bool  use_nnapi            = true;
    bool  use_gpu              = true;
};

// ─── Master configuration ────────────────────────────────────────────────────
struct ProXDRCfg {
    CameraMeta   camera;
    FusionCfg    fusion;
    NRCfg        nr;
    HighlightCfg highlight;
    ShadowCfg    shadow;
    ToneCfg      tone;
    ColorCfg     color;
    DetailCfg    detail;
    DCGCfg       dcg;
    GainMapCfg   gainmap;
    NeuralCfg    neural;

    int   threads          = 4;
    int   jpeg_quality     = 97;
    bool  save_dng         = false;
    bool  save_merged_raw  = false;
    bool  verbose          = false;
    bool  dump_stages      = false;
    std::string dump_path  = "/sdcard/Android/data/proxdr/cache/";

    SceneMode    scene_mode      = SceneMode::Auto;
    ThermalState thermal_state   = ThermalState::Normal;
    bool  adaptive_mode          = true;     // enable auto config per scene
    bool  prefer_quality         = true;     // false = prefer latency

    // Feature toggles
    bool  en_face            = true;
    bool  en_sky             = true;
    bool  en_ghost_removal   = true;
    bool  en_dcg             = true;
    bool  en_gainmap         = true;
    bool  en_hf_bypass       = true;
    bool  en_local_contrast  = true;
    bool  en_texture_color   = true;
    bool  en_neural          = true;
};

// ─── Output ─────────────────────────────────────────────────────────────────
struct ProXDRResult {
    std::vector<u8> jpeg;             // base SDR JPEG
    std::vector<u8> jpeg_ultrahdr;    // Ultra-HDR JPEG_R container (Android 14)
    std::vector<u8> jpeg_gainmap;     // raw gain map (greyscale JPEG)
    std::vector<u8> dng;              // optional merged DNG
    ImageBuffer     merged_raw;
    ImageBuffer     merged_rgb;
    ImageBuffer     final_rgb;        // gamma-encoded 8-bit equivalent (fp32)

    // Diagnostics
    f32 align_ms = 0, ghost_ms = 0, merge_ms = 0, nr_ms = 0, dcg_ms = 0;
    f32 highlight_ms = 0, shadow_ms = 0, tone_ms = 0, color_ms = 0, detail_ms = 0;
    f32 scene_ms = 0, gainmap_ms = 0, total_ms = 0;

    int   frames_in = 0, frames_merged = 0, ghost_frames = 0;
    f32   base_snr_db = 0, merged_snr_db = 0;
    f32   dynamic_range_ev = 0, recovered_ev = 0;
    f32   sky_fraction = 0;
    int   faces_found = 0;
    bool  dcg_used = false, temporal_binning_used = false, neural_used = false;
    SceneMode auto_scene = SceneMode::Auto;
    f32   ev100 = 0;
    std::string pipeline_summary;
};

// ─── Callbacks ───────────────────────────────────────────────────────────────
using ProgressCb = std::function<void(f32 frac, const char* stage)>;
using PostviewCb = std::function<void(const ImageBuffer& preview_rgb8)>;
using JpegCb     = std::function<void(const ProXDRResult&)>;
using LogCb      = std::function<void(const char* level, const char* msg)>;

// ─── Forward declarations (impls in separate .cpp files) ─────────────────────
class SemanticAnalyzer;     // scene_analyzer.cpp
class AdaptiveSceneMode;    // adaptive_scene.cpp
class FusionLM;             // fusion_lm.cpp  (SAFNet-lite)
class DCGHDR;               // dcg_hdr.cpp
class WaveletDenoiser;      // wavelet_nr.cpp
class HighlightRecovery;    // highlight_recovery.cpp
class ShadowLift;           // (in highlight_recovery.cpp)
class GuidedFilter;         // (helper used by detail.cpp)
class ToneLM;               // tone_lm.cpp
class ColorLM;              // color_lm.cpp
class DetailEngine;         // detail.cpp
class GainMapGen;           // gain_map.cpp
class NeuralHooks;          // neural_hooks.cpp
class ProXDRPipeline;       // pipeline.cpp  (top-level orchestrator)

// ─── Public top-level API (called from JNI) ─────────────────────────────────
struct BurstInput {
    std::vector<ImageBuffer*> raw_frames; // RAW16 → fp32 (caller owns memory)
    std::vector<FrameMeta>    meta;       // one per frame
    int    reference_idx = 0;             // index of base frame (-1 = auto)
    f32    user_ev_bias  = 0.f;           // optional user-side exposure bias
};

// Top-level synchronous processing — returns when done.
ProXDRResult ProcessBurst(const BurstInput& input, const ProXDRCfg& cfg,
                          ProgressCb progress = nullptr,
                          PostviewCb postview = nullptr,
                          LogCb       log      = nullptr);

// Async wrapper — fires JpegCb when done. Returns a token for cancellation.
struct AsyncToken { std::shared_ptr<std::atomic<bool>> cancel; };
AsyncToken ProcessBurstAsync(const BurstInput& input, const ProXDRCfg& cfg,
                              JpegCb on_complete,
                              ProgressCb progress = nullptr,
                              PostviewCb postview = nullptr,
                              LogCb       log      = nullptr);

// Static utility: estimate EV100 from a single frame's metadata.
f32 EstimateEV100(const FrameMeta& m);

// Static utility: build default config tuned for given scene mode.
ProXDRCfg DefaultConfigForScene(SceneMode m, const CameraMeta& cam);

} // namespace ProXDR
#endif // PROXDR_ENGINE_V3_H
