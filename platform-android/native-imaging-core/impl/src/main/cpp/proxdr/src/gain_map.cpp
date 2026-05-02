/**
 * ╔══════════════════════════════════════════════════════════════════════════╗
 * ║  ProXDR v3  ·  src/gain_map.cpp                                         ║
 * ║  GainMapGen — Android 14 Ultra-HDR JPEG-R Gain Map Encoder              ║
 * ╠══════════════════════════════════════════════════════════════════════════╣
 * ║  Reference: developer.android.com/media/platform/hdr-image-format        ║
 * ║                                                                          ║
 * ║  Ultra-HDR is a JPEG container with two embedded images:                 ║
 * ║    1. Primary: SDR JPEG (the tone-mapped output)                         ║
 * ║    2. Secondary: gain map (greyscale JPEG, ~quarter-res)                 ║
 * ║                                                                          ║
 * ║  At display time:                                                        ║
 * ║    HDR_pixel = SDR_pixel × 2 ^ (gain_map × hdr_capacity_ev)              ║
 * ║                                                                          ║
 * ║  We compute the gain map from the difference between the LINEAR HDR     ║
 * ║  buffer and the gamma-decoded SDR buffer, log2'd and quantised.          ║
 * ║                                                                          ║
 * ║  Zone-aware mode:                                                        ║
 * ║    • Shadow zones get larger gains (more brightening on HDR display)     ║
 * ║    • Face zones get moderate gains (avoid harsh skin in HDR)             ║
 * ║    • Sky zones moderated (prevent oversaturation on HDR display)         ║
 * ║    • Highlight/clipped zones get near-zero gains (already at peak)       ║
 * ╚══════════════════════════════════════════════════════════════════════════╝
 */

#include "../include/ProXDR_Engine.h"

namespace ProXDR {

// ─── sRGB linearise (gamma → linear) ────────────────────────────────────────
static inline f32 srgb_to_linear(f32 v) {
    v = std::max(0.f, v);
    return (v <= 0.04045f) ? v / 12.92f : std::pow((v + 0.055f) / 1.055f, 2.4f);
}

// ─── Resize float map (bilinear) ────────────────────────────────────────────
static ImageBuffer resize_bilinear(const ImageBuffer& src, int dW, int dH) {
    ImageBuffer dst(dW, dH, src.c);
    const f32 sx = static_cast<f32>(src.w) / dW;
    const f32 sy = static_cast<f32>(src.h) / dH;
    for (int y = 0; y < dH; ++y) {
        for (int x = 0; x < dW; ++x) {
            const f32 fx = (x + 0.5f) * sx - 0.5f;
            const f32 fy = (y + 0.5f) * sy - 0.5f;
            for (int c = 0; c < src.c; ++c)
                dst.at(x,y,c) = src.sample(fx, fy, c);
        }
    }
    return dst;
}

// ─── Gain map metadata (XMP packet for embedding) ───────────────────────────
struct GainMapMetadata {
    f32 gain_map_min   = 0.0f;
    f32 gain_map_max   = 3.0f;
    f32 gamma          = 1.0f;
    f32 offset_sdr     = 0.015625f;
    f32 offset_hdr     = 0.015625f;
    f32 hdr_capacity_min = 0.0f;
    f32 hdr_capacity_max = 3.0f;
    int base_renditional = 0;   // 0 = SDR base, 1 = HDR base
};

/**
 * Render the standard XMP packet that the Android UltraHDREncoder consumes.
 * In production the JPEG library writes this as an APP1 segment.
 */
std::string make_gainmap_xmp(const GainMapMetadata& m) {
    char buf[1024];
    std::snprintf(buf, sizeof(buf),
        "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">"
          "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
            "<rdf:Description rdf:about=\"\" xmlns:hdrgm=\"http://ns.adobe.com/hdr-gain-map/1.0/\""
              " hdrgm:Version=\"1.0\""
              " hdrgm:GainMapMin=\"%.6f\""
              " hdrgm:GainMapMax=\"%.6f\""
              " hdrgm:Gamma=\"%.6f\""
              " hdrgm:OffsetSDR=\"%.6f\""
              " hdrgm:OffsetHDR=\"%.6f\""
              " hdrgm:HDRCapacityMin=\"%.6f\""
              " hdrgm:HDRCapacityMax=\"%.6f\""
              " hdrgm:BaseRenditionIsHDR=\"%s\"/>"
          "</rdf:RDF>"
        "</x:xmpmeta>",
        m.gain_map_min, m.gain_map_max, m.gamma,
        m.offset_sdr, m.offset_hdr,
        m.hdr_capacity_min, m.hdr_capacity_max,
        (m.base_renditional == 1) ? "True" : "False");
    return std::string(buf);
}

// ─── Compute zone-aware gain weight ─────────────────────────────────────────
/**
 * Returns a multiplicative weight in [0..1] for the gain at this pixel.
 * Used to attenuate gains in face/sky/highlight zones.
 */
static inline f32 zone_gain_weight(const ZoneMap& zones, int x, int y) {
    const u8 lbl = zones.labels[y*zones.w + x];
    f32 w = 1.f;
    if (lbl & ZONE_FACE)      w *= 0.65f;     // gentler skin in HDR
    if (lbl & ZONE_SKY)       w *= 0.75f;     // avoid oversaturated sky
    if (lbl & ZONE_CLIPPED)   w *= 0.10f;     // already at peak — minimal lift
    if (lbl & ZONE_HIGHLIGHT) w *= 0.55f;     // moderate
    if (lbl & ZONE_SHADOW)    w *= 1.15f;     // give a little extra in shadows
    return std::clamp(w, 0.05f, 1.25f);
}

// ─── Top-level: build gain map from SDR + HDR ───────────────────────────────
/**
 * generate_gainmap()
 *
 * @param hdr_linear   The pre-tone-map linear HDR RGB (scene referred).
 * @param sdr_gamma    The final tone-mapped, gamma-encoded sRGB output.
 * @param zones        Zone map (full resolution) — used for zone-aware gains.
 * @param cfg          GainMap configuration.
 * @return             8-bit greyscale gain-map ImageBuffer (gainmap_w × gainmap_h)
 *                     plus metadata to write into the XMP packet.
 *
 * The 8-bit greyscale image stores: g_8bit = (gain - cfg.gain_map_min)
 *                                            / (cfg.gain_map_max - gain_map_min) × 255
 * where gain = log2((HDR + offset_hdr) / (SDR_lin + offset_sdr))
 */
struct GainMapResult {
    ImageBuffer       map;       // 8-bit (stored as 0..255 floats; encoder converts)
    GainMapMetadata   meta;
};

GainMapResult generate_gainmap(const ImageBuffer& hdr_linear,
                                 const ImageBuffer& sdr_gamma,
                                 const ZoneMap& zones,
                                 const GainMapCfg& cfg) {
    GainMapResult R;
    if (!cfg.generate || hdr_linear.w == 0 || sdr_gamma.w == 0) return R;
    // Match dimensions
    const int W = hdr_linear.w, H = hdr_linear.h;

    // Linearise SDR
    ImageBuffer sdr_linear(W, H, 3);
    for (int y = 0; y < H; ++y)
        for (int x = 0; x < W; ++x)
            for (int c = 0; c < 3; ++c)
                sdr_linear.at(x,y,c) = srgb_to_linear(sdr_gamma.at(x,y,c));

    // Per-pixel scalar gain (use luminance)
    ImageBuffer gain_full(W, H, 1);
    for (int y = 0; y < H; ++y) {
        for (int x = 0; x < W; ++x) {
            const f32 hdrL = 0.2126f*hdr_linear.at(x,y,0)
                            +0.7152f*hdr_linear.at(x,y,1)
                            +0.0722f*hdr_linear.at(x,y,2);
            const f32 sdrL = 0.2126f*sdr_linear.at(x,y,0)
                            +0.7152f*sdr_linear.at(x,y,1)
                            +0.0722f*sdr_linear.at(x,y,2);
            f32 g = std::log2((hdrL + cfg.offset_hdr) /
                               std::max(EPS, (sdrL + cfg.offset_sdr)));
            if (cfg.zone_aware) g *= zone_gain_weight(zones, x, y);
            g = std::clamp(g, 0.f, cfg.hdr_capacity_ev);
            gain_full.at(x,y) = g;
        }
    }

    // Downsample to gain-map resolution
    R.map = resize_bilinear(gain_full, cfg.gainmap_w, cfg.gainmap_h);

    // Quantise into [0..255] in-place
    const f32 scale = 255.f / std::max(EPS, cfg.hdr_capacity_ev);
    for (auto& v : R.map.data) v = std::clamp(v * scale, 0.f, 255.f);

    R.meta.gain_map_min     = 0.f;
    R.meta.gain_map_max     = cfg.hdr_capacity_ev;
    R.meta.gamma            = 1.f;
    R.meta.offset_sdr       = cfg.offset_sdr;
    R.meta.offset_hdr       = cfg.offset_hdr;
    R.meta.hdr_capacity_min = 0.f;
    R.meta.hdr_capacity_max = cfg.hdr_capacity_ev;
    return R;
}

} // namespace ProXDR
