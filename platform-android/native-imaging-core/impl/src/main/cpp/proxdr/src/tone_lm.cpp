/**
 * ╔══════════════════════════════════════════════════════════════════════════╗
 * ║  ProXDR v3  ·  src/tone_lm.cpp                                          ║
 * ║  ToneLM 3.0 — Adaptive Tone Mapping with Oklab Luminance Preservation   ║
 * ╠══════════════════════════════════════════════════════════════════════════╣
 * ║  v3 changes vs v2:                                                       ║
 * ║   • Oklab L is used for tone-curve scaling (preserves hue, no shifts)    ║
 * ║   • New curves: Hable (Uncharted-2), GT7 (Polyphony PBS 2025), Drago,   ║
 * ║     Reinhard-extended                                                    ║
 * ║   • Fixed missing fields (film_toe/shoulder, vivid_strength → header)    ║
 * ║   • Mertens fusion now operates on REAL synthetic exposures from the    ║
 * ║     LINEAR HDR input (was: post-curve which made it a no-op)             ║
 * ║   • Scene-peak normalisation uses 99.5th percentile, never 100% max     ║
 * ║   • Sky contrast boost uses unsharp-mask (multiplicative + clamp)        ║
 * ║   • Local contrast halos suppressed near sky/highlight edges             ║
 * ╚══════════════════════════════════════════════════════════════════════════╝
 */

#include "../include/ProXDR_Engine.h"

namespace ProXDR {

// ─── Oklab conversions (Björn Ottosson, 2020) ───────────────────────────────
// Oklab is perceptually uniform, hue-linear, and 100× cheaper than CIE Lab.
// Reference: https://bottosson.github.io/posts/oklab/
static inline void linear_srgb_to_oklab(f32 r, f32 g, f32 b,
                                         f32& L, f32& a, f32& bb) {
    f32 l = 0.4122214708f*r + 0.5363325363f*g + 0.0514459929f*b;
    f32 m = 0.2119034982f*r + 0.6806995451f*g + 0.1073969566f*b;
    f32 s = 0.0883024619f*r + 0.2817188376f*g + 0.6299787005f*b;
    l = std::cbrt(std::max(0.f, l));
    m = std::cbrt(std::max(0.f, m));
    s = std::cbrt(std::max(0.f, s));
    L  = 0.2104542553f*l + 0.7936177850f*m - 0.0040720468f*s;
    a  = 1.9779984951f*l - 2.4285922050f*m + 0.4505937099f*s;
    bb = 0.0259040371f*l + 0.7827717662f*m - 0.8086757660f*s;
}
static inline void oklab_to_linear_srgb(f32 L, f32 a, f32 bb,
                                         f32& r, f32& g, f32& b) {
    f32 l = L + 0.3963377774f*a + 0.2158037573f*bb;
    f32 m = L - 0.1055613458f*a - 0.0638541728f*bb;
    f32 s = L - 0.0894841775f*a - 1.2914855480f*bb;
    l = l*l*l; m = m*m*m; s = s*s*s;
    r =  4.0767416621f*l - 3.3077115913f*m + 0.2309699292f*s;
    g = -1.2684380046f*l + 2.6097574011f*m - 0.3413193965f*s;
    b = -0.0041960863f*l - 0.7034186147f*m + 1.7076147010f*s;
}

// ─── Gaussian / Laplacian pyramid utilities ─────────────────────────────────
static ImageBuffer gaussian_downsample(const ImageBuffer& src) {
    const int dW = src.w/2, dH = src.h/2;
    constexpr f32 K[5] = { 0.0625f, 0.25f, 0.375f, 0.25f, 0.0625f };
    ImageBuffer tmp(src.w, dH, src.c), dst(dW, dH, src.c);
    for (int c = 0; c < src.c; ++c) {
        for (int y = 0; y < dH; ++y)
            for (int x = 0; x < src.w; ++x) {
                f32 s = 0.f;
                for (int k=-2; k<=2; ++k) s += K[k+2]*src.mirror(x, 2*y+k, c);
                tmp.at(x,y,c) = s;
            }
        for (int y = 0; y < dH; ++y)
            for (int x = 0; x < dW; ++x) {
                f32 s = 0.f;
                for (int k=-2; k<=2; ++k) s += K[k+2]*tmp.mirror(2*x+k, y, c);
                dst.at(x,y,c) = s;
            }
    }
    return dst;
}

static ImageBuffer gaussian_upsample(const ImageBuffer& src, int tW, int tH) {
    // Bilinear upsample (simple, fast); production can use Burt-Adelson 5-tap.
    ImageBuffer dst(tW, tH, src.c);
    const f32 sx = 0.5f, sy = 0.5f; // because target is 2x source
    for (int y = 0; y < tH; ++y)
        for (int x = 0; x < tW; ++x)
            for (int c = 0; c < src.c; ++c)
                dst.at(x,y,c) = src.sample(x*sx - 0.25f, y*sy - 0.25f, c);
    return dst;
}

static std::vector<ImageBuffer> build_gauss_pyr(const ImageBuffer& img, int levels) {
    std::vector<ImageBuffer> G; G.reserve(levels);
    G.push_back(img);
    for (int l = 1; l < levels; ++l) G.push_back(gaussian_downsample(G.back()));
    return G;
}
static std::vector<ImageBuffer> build_laplacian_pyr(const std::vector<ImageBuffer>& G) {
    const int L = static_cast<int>(G.size());
    std::vector<ImageBuffer> Lp(L);
    Lp[L-1] = G[L-1];
    for (int l = L-2; l >= 0; --l) {
        auto up = gaussian_upsample(G[l+1], G[l].w, G[l].h);
        Lp[l] = ImageBuffer(G[l].w, G[l].h, G[l].c);
        for (size_t i = 0; i < G[l].data.size(); ++i)
            Lp[l].data[i] = G[l].data[i] - up.data[i];
    }
    return Lp;
}
static ImageBuffer reconstruct_laplacian(const std::vector<ImageBuffer>& Lp) {
    ImageBuffer rec = Lp.back();
    for (int l = static_cast<int>(Lp.size())-2; l >= 0; --l) {
        auto up = gaussian_upsample(rec, Lp[l].w, Lp[l].h);
        rec = ImageBuffer(up.w, up.h, up.c);
        for (size_t i = 0; i < up.data.size(); ++i)
            rec.data[i] = up.data[i] + Lp[l].data[i];
    }
    return rec;
}

// ─── Tone curves ────────────────────────────────────────────────────────────

// sRGB piecewise gamma (display encoding)
static inline f32 apply_srgb_gamma(f32 v) {
    v = std::max(0.f, v);
    return (v <= 0.0031308f) ? 12.92f*v : 1.055f*std::pow(v, 1.f/2.4f) - 0.055f;
}

// Stevens psychophysical power law
static inline f32 apply_stevens(f32 v, f32 beta, f32 L_adapt) {
    v = std::max(EPS, v);
    L_adapt = std::max(1e-4f, L_adapt);
    return std::pow(v / L_adapt, beta) * L_adapt;
}

// ACES (Narkowicz approximation)
static inline f32 apply_aces(f32 x, const ToneCfg& c) {
    return std::clamp((x*(c.aces_a*x + c.aces_b)) / (x*(c.aces_c*x + c.aces_d) + c.aces_e),
                      0.f, 1.f);
}

// FilmLog (toe-linear-shoulder)
static inline f32 apply_filmlog(f32 v, const ToneCfg& c) {
    const f32 toe = c.film_toe, shoulder = c.film_shoulder;
    if (v < toe)        return (v/toe)*(v/toe)*toe*0.5f;
    else if (v < shoulder) return v;
    else {
        const f32 s = (v - shoulder) / std::max(EPS, 1.f - shoulder);
        return shoulder + (1.f - shoulder)*(1.f - std::exp(-3.f * s));
    }
}

// Vivid (S-curve sin boost)
static inline f32 apply_vivid(f32 v, f32 str) {
    return std::clamp(v + str*0.5f*std::sin(PI*v), 0.f, 1.f);
}

// Hable / Uncharted-2 filmic operator
//   uncharted2(x) = ((x*(A*x + C*B) + D*E) / (x*(A*x + B) + D*F)) - E/F
static inline f32 hable_partial(f32 x, const ToneCfg& c) {
    const f32 A=c.hable_A, B=c.hable_B, C=c.hable_C, D=c.hable_D, E=c.hable_E, F=c.hable_F;
    return ((x*(A*x + C*B) + D*E) / (x*(A*x + B) + D*F)) - E/F;
}
static inline f32 apply_hable(f32 x, const ToneCfg& c) {
    const f32 numer = hable_partial(x*c.hable_W*0.0f + x, c); // exposure pass-through
    const f32 denom = hable_partial(c.hable_W, c);
    return std::clamp(numer / std::max(EPS, denom), 0.f, 1.f);
}

// Reinhard extended:  L_d = L * (1 + L/L_white²) / (1 + L)
static inline f32 apply_reinhard(f32 L, f32 L_white) {
    const f32 num = L * (1.f + L / (L_white*L_white));
    return std::clamp(num / (1.f + L), 0.f, 1.f);
}

// GT7-style filmic curve (Gran Turismo 7 / Polyphony 2025 approx).
// Smooth S-curve calibrated around mid-grey with controllable peak and contrast.
//   y(x) = peak * f((x/mid)^contrast / (1 + (x/mid)^contrast))   piecewise blended
static inline f32 apply_gt7(f32 x, const ToneCfg& c) {
    const f32 mid = std::max(EPS, c.gt7_mid_grey);
    const f32 r   = std::pow(std::max(EPS, x/mid), c.gt7_contrast);
    const f32 base = r / (1.f + r);
    // Calibrate so that mid-grey (x = mid) maps to base = 0.5
    return std::clamp(c.gt7_peak * (base * 2.f - 0.5f) + 0.18f, 0.f, 1.f);
}

// Drago adaptive logarithmic (Drago et al. 2003)
//   L_d = (log(L+1) / log(Lmax+1)) * (1 / log10(2 + 8 * (L/Lmax)^(log(b)/log(0.5))))
static inline f32 apply_drago(f32 L, f32 Lmax, f32 bias) {
    const f32 b = std::log(std::max(EPS, bias));
    const f32 r = std::pow(std::max(EPS, L/std::max(EPS, Lmax)), b/std::log(0.5f));
    const f32 denom = std::log10(2.f + 8.f * r) * std::log(Lmax + 1.f);
    return std::clamp(std::log(L + 1.f) / std::max(EPS, denom), 0.f, 1.f);
}

static f32 apply_tone_curve(f32 v, const ToneCfg& cfg, f32 scene_peak) {
    switch (cfg.curve) {
        case ToneCurve::sRGB:     return apply_srgb_gamma(v);
        case ToneCurve::Stevens:  return std::clamp(apply_stevens(v, cfg.stevens_beta,
                                                    cfg.stevens_adaptation*scene_peak), 0.f, 1.f);
        case ToneCurve::ACES:     return apply_aces(v, cfg);
        case ToneCurve::FilmLog:  return std::clamp(apply_filmlog(v, cfg), 0.f, 1.f);
        case ToneCurve::Vivid:    return apply_vivid(v, cfg.vivid_strength);
        case ToneCurve::Hable:    return apply_hable(v, cfg);
        case ToneCurve::Reinhard: return apply_reinhard(v, cfg.reinhard_white);
        case ToneCurve::GT7:      return apply_gt7(v, cfg);
        case ToneCurve::Drago:    return apply_drago(v, std::max(1.f, scene_peak), cfg.drago_bias);
        default:                   return apply_srgb_gamma(v);
    }
}

// ─── Mertens exposure-fusion weights (per-pixel) ────────────────────────────
static inline f32 weight_exposure(f32 L) {
    const f32 t = L - 0.5f;
    return std::exp(-12.5f * t*t);
}
static inline f32 weight_saturation(f32 r, f32 g, f32 b) {
    const f32 m = (r+g+b)/3.f;
    const f32 dr=r-m, dg=g-m, db=b-m;
    return std::sqrt((dr*dr+dg*dg+db*db)/3.f);
}
static ImageBuffer compute_laplacian_contrast(const ImageBuffer& img) {
    const int W = img.w, H = img.h;
    ImageBuffer out(W, H, 1);
    auto luma = [&](int x, int y) {
        return 0.2126f*img.mirror(x,y,0) + 0.7152f*img.mirror(x,y,1) + 0.0722f*img.mirror(x,y,2);
    };
    for (int y = 1; y < H-1; ++y) {
        for (int x = 1; x < W-1; ++x) {
            const f32 L = luma(x,y);
            out.at(x,y) = std::abs(4.f*L - luma(x-1,y) - luma(x+1,y) - luma(x,y-1) - luma(x,y+1));
        }
    }
    return out;
}

// ─── Zone-specific EV adjustment (multiplicative — preserves hue) ───────────
static void apply_zone_adjustments(ImageBuffer& rgb, const ZoneMap& zones, const ToneCfg& cfg) {
    const int W = rgb.w, H = rgb.h;
    for (int y = 0; y < H; ++y) {
        for (int x = 0; x < W; ++x) {
            f32 ev = 0.f;
            const u8 lbl = zones.labels[y*W+x];
            if (lbl & ZONE_SKY)                                ev += cfg.sky_ev;
            if (lbl & ZONE_FACE)                               ev += cfg.face_ev;
            if ((lbl & ZONE_SHADOW) && !(lbl & ZONE_FACE))     ev += cfg.shadow_detail_ev;
            if (lbl & ZONE_HIGHLIGHT)                          ev += cfg.highlight_recover_ev;
            if (ev == 0.f) continue;
            const f32 scale = std::pow(2.f, ev);
            rgb.at(x,y,0) = std::max(0.f, rgb.at(x,y,0)*scale);
            rgb.at(x,y,1) = std::max(0.f, rgb.at(x,y,1)*scale);
            rgb.at(x,y,2) = std::max(0.f, rgb.at(x,y,2)*scale);
        }
    }
}

// ─── Local contrast (CLAHE-inspired unsharp on Y, halo-suppressed) ──────────
static void enhance_local_contrast(ImageBuffer& rgb, const ZoneMap& zones, const ToneCfg& cfg) {
    if (!cfg.local_contrast || cfg.lc_strength <= 0.f) return;
    const int W = rgb.w, H = rgb.h;
    const int r = std::min(cfg.lc_radius, std::min(W,H)/4);
    if (r < 2) return;

    ImageBuffer Y(W, H, 1);
    for (int y = 0; y < H; ++y)
        for (int x = 0; x < W; ++x)
            Y.at(x,y) = 0.2126f*rgb.at(x,y,0)+0.7152f*rgb.at(x,y,1)+0.0722f*rgb.at(x,y,2);

    // Box-blurred Y via integral image
    std::vector<f64> ii((W+1)*(H+1), 0.0);
    for (int y = 0; y < H; ++y)
        for (int x = 0; x < W; ++x)
            ii[(y+1)*(W+1)+(x+1)] = Y.at(x,y) + ii[y*(W+1)+(x+1)] + ii[(y+1)*(W+1)+x] - ii[y*(W+1)+x];

    ImageBuffer Yb(W, H, 1);
    for (int y = 0; y < H; ++y) {
        const int y0 = std::max(0,y-r), y1 = std::min(H-1,y+r);
        for (int x = 0; x < W; ++x) {
            const int x0 = std::max(0,x-r), x1 = std::min(W-1,x+r);
            const int n = (x1-x0+1)*(y1-y0+1);
            const f64 s = ii[(y1+1)*(W+1)+(x1+1)] - ii[y0*(W+1)+(x1+1)]
                         - ii[(y1+1)*(W+1)+x0]    + ii[y0*(W+1)+x0];
            Yb.at(x,y) = static_cast<f32>(s / n);
        }
    }

    for (int y = 0; y < H; ++y) {
        for (int x = 0; x < W; ++x) {
            if (zones.has(x,y, ZONE_CLIPPED)) continue;
            const f32 hf = Y.at(x,y) - Yb.at(x,y);
            f32 s = cfg.lc_strength;
            // Halo suppression near sky / highlight edges
            if (zones.has(x,y, ZONE_SKY))       s *= 0.40f;
            if (zones.has(x,y, ZONE_HIGHLIGHT)) s *= 0.30f;
            if (zones.has(x,y, ZONE_FACE))      s *= 0.55f;  // gentler on faces

            for (int c = 0; c < 3; ++c) {
                const f32 v = rgb.at(x,y,c);
                rgb.at(x,y,c) = std::clamp(v + s * hf * v, 0.f, 1.5f);
            }
        }
    }
}

// ─── Sky contrast boost (multiplicative unsharp, clamped) ───────────────────
static void sky_contrast_boost(ImageBuffer& rgb, const ZoneMap& zones, f32 strength) {
    if (strength <= 0.f) return;
    const int W = rgb.w, H = rgb.h;
    for (int y = 1; y < H-1; ++y) {
        for (int x = 1; x < W-1; ++x) {
            if (!zones.has(x,y, ZONE_SKY)) continue;
            const f32 sky_w = zones.sky_conf[y*W+x];
            if (sky_w < 0.3f) continue;
            const f32 L = 0.2126f*rgb.at(x,y,0)+0.7152f*rgb.at(x,y,1)+0.0722f*rgb.at(x,y,2);
            auto luma = [&](int px,int py){ return 0.2126f*rgb.mirror(px,py,0)+0.7152f*rgb.mirror(px,py,1)+0.0722f*rgb.mirror(px,py,2); };
            const f32 lap = 4*L - luma(x-1,y) - luma(x+1,y) - luma(x,y-1) - luma(x,y+1);
            const f32 boost = 1.f + sky_w * strength * lap;
            for (int c = 0; c < 3; ++c)
                rgb.at(x,y,c) = std::clamp(rgb.at(x,y,c) * boost, 0.f, 1.f);
        }
    }
}

// ─── Apply tone curve with Oklab L preservation ─────────────────────────────
/**
 * The trick: classical "scale RGB by (L_out / L_in)" causes hue shift
 * (because Y is not perceptually uniform). Modern engines (incl. Apple/Google)
 * apply the curve to perceptual L (Oklab L), then reconstruct RGB while
 * preserving (a, b). This keeps hue stable through the curve.
 */
static void apply_tone_curve_oklab(ImageBuffer& rgb, const ToneCfg& cfg, f32 scene_peak) {
    const int W = rgb.w, H = rgb.h;
    for (int y = 0; y < H; ++y) {
        for (int x = 0; x < W; ++x) {
            f32 r = rgb.at(x,y,0), g = rgb.at(x,y,1), b = rgb.at(x,y,2);
            f32 L, a, bb;
            linear_srgb_to_oklab(r, g, b, L, a, bb);
            // Map Oklab L (which is perceptual) through the chosen curve
            const f32 L_lin = std::max(0.f, L*L*L);   // approximate luminance proxy
            const f32 L_new_lin = apply_tone_curve(L_lin, cfg, scene_peak);
            const f32 L_new = std::cbrt(std::max(0.f, L_new_lin));
            // Scale chroma so saturation stays roughly constant in perceived sense
            const f32 chroma_scale = (L > EPS) ? (L_new / L) : 1.f;
            const f32 cs = std::clamp(chroma_scale, 0.5f, 1.5f);
            f32 r2, g2, b2;
            oklab_to_linear_srgb(L_new, a*cs, bb*cs, r2, g2, b2);
            rgb.at(x,y,0) = std::max(0.f, r2);
            rgb.at(x,y,1) = std::max(0.f, g2);
            rgb.at(x,y,2) = std::max(0.f, b2);
        }
    }
}

// Classic Y-scale path (kept for compatibility / faster preview)
static void apply_tone_curve_yscale(ImageBuffer& rgb, const ToneCfg& cfg, f32 scene_peak) {
    const int W = rgb.w, H = rgb.h;
    for (int y = 0; y < H; ++y) {
        for (int x = 0; x < W; ++x) {
            const f32 Lin = 0.2126f*rgb.at(x,y,0)+0.7152f*rgb.at(x,y,1)+0.0722f*rgb.at(x,y,2);
            if (Lin < EPS) continue;
            const f32 Lout = apply_tone_curve(Lin, cfg, scene_peak);
            const f32 s = Lout / Lin;
            rgb.at(x,y,0) = std::max(0.f, rgb.at(x,y,0)*s);
            rgb.at(x,y,1) = std::max(0.f, rgb.at(x,y,1)*s);
            rgb.at(x,y,2) = std::max(0.f, rgb.at(x,y,2)*s);
        }
    }
}

// ─── Mertens exposure fusion (proper — synthesises real gain steps) ─────────
/**
 * Build N synthetic exposures from the linear HDR input by gain stepping,
 * each with Mertens weights (well-exposedness × saturation × contrast),
 * then fuse via Laplacian pyramid blending.
 *
 * Critical: the synthetic gain stepping happens on the LINEAR HDR data
 * (before any tone curve). Otherwise weights collapse and fusion is a no-op.
 */
static ImageBuffer mertens_fusion(const ImageBuffer& linear_hdr, const ToneCfg& cfg) {
    const int W = linear_hdr.w, H = linear_hdr.h;
    const int N = std::max(2, cfg.tone_passes);
    const f32 ev_step = cfg.compression / N;

    std::vector<ImageBuffer> exposures(N), weights(N);

    for (int i = 0; i < N; ++i) {
        const f32 ev_offset = -0.5f * cfg.compression + i * ev_step;
        const f32 gain      = cfg.gain * std::pow(2.f, ev_offset);
        exposures[i] = ImageBuffer(W, H, 3);
        weights[i]   = ImageBuffer(W, H, 1);
        for (int y = 0; y < H; ++y) {
            for (int x = 0; x < W; ++x) {
                const f32 r = std::clamp(linear_hdr.at(x,y,0)*gain, 0.f, 1.f);
                const f32 g = std::clamp(linear_hdr.at(x,y,1)*gain, 0.f, 1.f);
                const f32 b = std::clamp(linear_hdr.at(x,y,2)*gain, 0.f, 1.f);
                exposures[i].at(x,y,0) = r;
                exposures[i].at(x,y,1) = g;
                exposures[i].at(x,y,2) = b;
                const f32 L = 0.2126f*r + 0.7152f*g + 0.0722f*b;
                const f32 we = weight_exposure(L);
                const f32 ws = weight_saturation(r,g,b) + 0.1f;
                weights[i].at(x,y) = we * ws;
            }
        }
        // Add Laplacian-contrast component
        auto lc = compute_laplacian_contrast(exposures[i]);
        for (size_t k = 0; k < weights[i].data.size(); ++k)
            weights[i].data[k] *= (lc.data[k] + 0.001f);
    }

    // Normalise weights pixel-wise
    for (int y = 0; y < H; ++y) {
        for (int x = 0; x < W; ++x) {
            f32 sum = 0.f;
            for (int i = 0; i < N; ++i) sum += weights[i].at(x,y);
            sum = std::max(EPS, sum);
            for (int i = 0; i < N; ++i) weights[i].at(x,y) /= sum;
        }
    }

    // Pyramid fusion
    const int L = std::min(cfg.pyr_levels, LAPLACIAN_LEVELS);
    auto first_pyr = build_gauss_pyr(exposures[0], L+1);

    std::vector<ImageBuffer> result_pyr(L+1);
    for (int l = 0; l <= L; ++l)
        result_pyr[l] = ImageBuffer(first_pyr[l].w, first_pyr[l].h, 3);

    for (int i = 0; i < N; ++i) {
        auto Ep = build_gauss_pyr(exposures[i], L+1);
        auto El = build_laplacian_pyr(Ep);
        auto Wp = build_gauss_pyr(weights[i],   L+1);
        for (int l = 0; l <= L; ++l) {
            const int lh = result_pyr[l].h, lw = result_pyr[l].w;
            for (int y = 0; y < lh; ++y) {
                for (int x = 0; x < lw; ++x) {
                    const f32 w = Wp[l].at(x,y);
                    for (int c = 0; c < 3; ++c)
                        result_pyr[l].at(x,y,c) += w * El[l].at(x,y,c);
                }
            }
        }
    }
    auto out = reconstruct_laplacian(result_pyr);
    // Clamp gently — fused output can dip below 0 due to Laplacian residuals
    for (auto& v : out.data) v = std::max(0.f, v);
    return out;
}

// ─── Top-level ToneLM 3.0 ───────────────────────────────────────────────────
/**
 * tone_map()
 *
 * @param rgb_linear  Linear HDR RGB scene [0..∞)
 * @param zones       Semantic zone map
 * @param cfg         Tone configuration
 * @return            Tone-mapped LINEAR sRGB in [0..1] (ColorLM applies gamma)
 *
 * NOTE: returned image is still LINEAR. ColorLM 3.0 is responsible for the
 * final gamma encoding (so it can do colour ops in linear and gamma in one place).
 */
ImageBuffer tone_map(const ImageBuffer& rgb_linear,
                      const ZoneMap& zones,
                      const ToneCfg& cfg) {
    const int W = rgb_linear.w, H = rgb_linear.h;
    ImageBuffer work = rgb_linear;

    // 1. Zone-specific EV bias (before global curve)
    apply_zone_adjustments(work, zones, cfg);

    // 2. Scene peak: 99.5th percentile of luminance (avoids blown specular outliers)
    {
        std::vector<f32> ls; ls.reserve(W*H);
        for (int y = 0; y < H; ++y)
            for (int x = 0; x < W; ++x)
                ls.push_back(0.2126f*work.at(x,y,0)+0.7152f*work.at(x,y,1)+0.0722f*work.at(x,y,2));
        const int idx = static_cast<int>(0.995f * ls.size());
        std::nth_element(ls.begin(), ls.begin()+idx, ls.end());
        const f32 peak = std::max(0.05f, ls[idx]);
        // Soft-normalise (don't crush values > peak; allow modest over-1 input)
        for (auto& v : work.data) v /= peak;
    }

    // 3. Mertens fusion on the normalised linear data
    ImageBuffer fused = mertens_fusion(work, cfg);

    // 4. Apply tone curve — Oklab path (default) preserves hue much better
    if (cfg.use_oklab_lightness) apply_tone_curve_oklab (fused, cfg, 1.f);
    else                          apply_tone_curve_yscale(fused, cfg, 1.f);

    // 5. Sky contrast boost
    sky_contrast_boost(fused, zones, cfg.sky_contrast);

    // 6. Local contrast enhancement
    enhance_local_contrast(fused, zones, cfg);

    // 7. Final clamp to [0..1] linear
    for (auto& v : fused.data) v = std::clamp(v, 0.f, 1.f);
    return fused;
}

} // namespace ProXDR
