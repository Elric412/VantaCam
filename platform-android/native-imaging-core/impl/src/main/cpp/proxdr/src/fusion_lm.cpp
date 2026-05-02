/**
 * ╔══════════════════════════════════════════════════════════════════════════╗
 * ║  ProXDR v3  ·  src/fusion_lm.cpp                                        ║
 * ║  FusionLM — SAFNet-lite Multi-Frame RAW Burst Merge                     ║
 * ╠══════════════════════════════════════════════════════════════════════════╣
 * ║  Inspired by:                                                            ║
 * ║   • SAFNet (Kong et al., ECCV 2024) — selective alignment fusion         ║
 * ║   • HDR+ burst photography (Hasinoff et al., SIGGRAPH 2016) — Wiener     ║
 * ║                                                                          ║
 * ║  Pipeline (all in RAW Bayer or post-demosaic linear RGB):                ║
 * ║   1. Reference frame pick — best sharpness × least motion                ║
 * ║   2. Hierarchical tile alignment (Gaussian pyramid, L1, 16×16 tiles)     ║
 * ║   3. Optional Lucas-Kanade subpixel refinement                           ║
 * ║   4. Ghost detection — Wiener residual > ghost_sigma × σ_n               ║
 * ║   5. Per-tile DFT-domain Wiener merge:                                   ║
 * ║        w(u,v) = σ²_n / (σ²_n + |A(u,v) - B(u,v)|²)                       ║
 * ║      multiplied by selective alignment confidence (SAFNet-lite mask)     ║
 * ║   6. Photon-matrix confidence: sqrt(P) / sqrt(P + σ²_read)               ║
 * ║   7. Raised-cosine 50%-overlap reconstruction                            ║
 * ║                                                                          ║
 * ║  This file deliberately uses a CPU-friendly DFT (Cooley–Tukey radix-2)   ║
 * ║  with tile size = 16. For mobile production, a FFT library (e.g. KFR or  ║
 * ║  Eigen FFT) is faster and recommended.                                   ║
 * ╚══════════════════════════════════════════════════════════════════════════╝
 */

#include "../include/ProXDR_Engine.h"

namespace ProXDR {

// ─── Reference-frame selection ──────────────────────────────────────────────
/**
 * Picks the best base frame from a burst by combining:
 *   • Sharpness  (higher is better) — Laplacian variance from FrameMeta.sharpness
 *   • Motion     (lower is better) — gyro-derived motion_px_ms
 * Score = sharpness * (1 - motion_weight * normalized_motion)
 */
int select_reference_frame(const std::vector<FrameMeta>& metas, f32 motion_w = 0.6f) {
    if (metas.empty()) return -1;
    f32 max_motion = 1e-3f;
    for (const auto& m : metas) max_motion = std::max(max_motion, m.motion_px_ms);

    int best = 0;
    f32 best_score = -1.f;
    for (int i = 0; i < static_cast<int>(metas.size()); ++i) {
        const f32 norm_mot = metas[i].motion_px_ms / max_motion;
        const f32 score    = metas[i].sharpness * (1.f - motion_w * norm_mot);
        if (score > best_score) { best_score = score; best = i; }
    }
    return best;
}

// ─── Gaussian pyramid (luma only — used for alignment) ──────────────────────
static ImageBuffer gauss_down_luma(const ImageBuffer& Y) {
    const int dW = Y.w/2, dH = Y.h/2;
    ImageBuffer dst(dW, dH, 1);
    constexpr f32 K[5] = {0.0625f, 0.25f, 0.375f, 0.25f, 0.0625f};
    for (int y = 0; y < dH; ++y) {
        for (int x = 0; x < dW; ++x) {
            f32 s = 0.f;
            for (int j = -2; j <= 2; ++j)
                for (int i = -2; i <= 2; ++i)
                    s += K[i+2]*K[j+2] * Y.mirror(2*x+i, 2*y+j);
            dst.at(x,y) = s;
        }
    }
    return dst;
}

// ─── Tile L1 distance ───────────────────────────────────────────────────────
static inline f32 tile_l1(const ImageBuffer& A, int ax, int ay,
                            const ImageBuffer& B, int bx, int by, int T) {
    f32 sum = 0.f;
    for (int dy = 0; dy < T; ++dy)
        for (int dx = 0; dx < T; ++dx)
            sum += std::abs(A.mirror(ax+dx, ay+dy) - B.mirror(bx+dx, by+dy));
    return sum / (T*T);
}

// ─── Hierarchical tile alignment ────────────────────────────────────────────
/**
 * For each TxT tile in the reference frame, finds the best matching offset
 * in the alternate frame using L1 distance and a Gaussian pyramid for speed.
 *
 * Returns a flow field of size (W/T × H/T × 2) — (dx, dy) per tile.
 */
struct TileFlow {
    int tw = 0, th = 0;        // tile-grid dims
    std::vector<i32> dx, dy;   // offsets per tile
    inline i32&  ox(int i, int j) { return dx[j*tw + i]; }
    inline i32&  oy(int i, int j) { return dy[j*tw + i]; }
};

static TileFlow align_tiles(const ImageBuffer& ref_Y, const ImageBuffer& alt_Y,
                             int T, int pyr_levels, int search_radius) {
    // Build pyramids
    std::vector<ImageBuffer> pyrR{ref_Y}, pyrA{alt_Y};
    for (int l = 1; l < pyr_levels; ++l) {
        pyrR.push_back(gauss_down_luma(pyrR.back()));
        pyrA.push_back(gauss_down_luma(pyrA.back()));
    }

    // Coarse-to-fine tile search
    const int tw = ref_Y.w / T, th = ref_Y.h / T;
    TileFlow flow; flow.tw = tw; flow.th = th;
    flow.dx.assign(tw*th, 0); flow.dy.assign(tw*th, 0);

    // Coarsest level — full search in ±search_radius
    for (int l = pyr_levels-1; l >= 0; --l) {
        const ImageBuffer& R = pyrR[l];
        const ImageBuffer& A = pyrA[l];
        const int Tl = T >> l;
        const int twl = R.w / std::max(1, Tl);
        const int thl = R.h / std::max(1, Tl);
        for (int j = 0; j < thl; ++j) {
            for (int i = 0; i < twl; ++i) {
                // Map this tile to its index in flow
                const int fi = std::min(tw-1, i << l);
                const int fj = std::min(th-1, j << l);

                const int seed_dx = flow.ox(fi, fj) >> l;
                const int seed_dy = flow.oy(fi, fj) >> l;

                f32 best = 1e9f;
                int bdx = seed_dx, bdy = seed_dy;
                for (int dy = seed_dy-search_radius; dy <= seed_dy+search_radius; ++dy) {
                    for (int dx = seed_dx-search_radius; dx <= seed_dx+search_radius; ++dx) {
                        const f32 d = tile_l1(R, i*Tl, j*Tl, A, i*Tl + dx, j*Tl + dy, Tl);
                        if (d < best) { best = d; bdx = dx; bdy = dy; }
                    }
                }
                // Propagate to ALL fine-level tiles within this coarse tile
                for (int jj = 0; jj < (1<<l) && fj+jj < th; ++jj)
                    for (int ii = 0; ii < (1<<l) && fi+ii < tw; ++ii) {
                        flow.ox(fi+ii, fj+jj) = bdx << l;
                        flow.oy(fi+ii, fj+jj) = bdy << l;
                    }
            }
        }
    }
    return flow;
}

// ─── Lucas-Kanade subpixel refinement ───────────────────────────────────────
/**
 * Refines integer-pixel tile alignment to subpixel precision by minimising
 * the L2 residual locally using one Newton step on the gradient of the warp.
 */
static void refine_lk(const ImageBuffer& R, const ImageBuffer& A,
                       int T, TileFlow& flow,
                       std::vector<f32>& subdx, std::vector<f32>& subdy) {
    subdx.assign(flow.tw*flow.th, 0.f);
    subdy.assign(flow.tw*flow.th, 0.f);
    for (int j = 0; j < flow.th; ++j) {
        for (int i = 0; i < flow.tw; ++i) {
            const int dx0 = flow.ox(i,j), dy0 = flow.oy(i,j);
            f32 Ixx = 0, Iyy = 0, Ixy = 0, Ixt = 0, Iyt = 0;
            for (int dy = 0; dy < T; ++dy) {
                for (int dx = 0; dx < T; ++dx) {
                    const int rx = i*T + dx, ry = j*T + dy;
                    const int ax = rx + dx0, ay = ry + dy0;
                    const f32 Ix = 0.5f*(A.mirror(ax+1,ay) - A.mirror(ax-1,ay));
                    const f32 Iy = 0.5f*(A.mirror(ax,ay+1) - A.mirror(ax,ay-1));
                    const f32 It = A.mirror(ax,ay) - R.mirror(rx,ry);
                    Ixx += Ix*Ix; Iyy += Iy*Iy; Ixy += Ix*Iy;
                    Ixt += Ix*It; Iyt += Iy*It;
                }
            }
            const f32 det = Ixx*Iyy - Ixy*Ixy;
            if (std::abs(det) < 1e-3f) continue;
            const f32 sx = -(Iyy*Ixt - Ixy*Iyt) / det;
            const f32 sy = -(Ixx*Iyt - Ixy*Ixt) / det;
            // Clamp to sane subpixel range
            subdx[j*flow.tw+i] = std::clamp(sx, -1.f, 1.f);
            subdy[j*flow.tw+i] = std::clamp(sy, -1.f, 1.f);
        }
    }
}

// ─── Cooley-Tukey radix-2 DFT (in-place complex) ────────────────────────────
static void dft_1d(std::vector<Cpx>& a, bool inverse) {
    const int N = static_cast<int>(a.size());
    // Bit reversal
    for (int i = 1, j = 0; i < N; ++i) {
        int bit = N >> 1;
        for (; j & bit; bit >>= 1) j ^= bit;
        j ^= bit;
        if (i < j) std::swap(a[i], a[j]);
    }
    for (int len = 2; len <= N; len <<= 1) {
        const f32 ang = (inverse ? 2.f : -2.f) * PI / len;
        const Cpx wlen(std::cos(ang), std::sin(ang));
        for (int i = 0; i < N; i += len) {
            Cpx w(1.f, 0.f);
            for (int j = 0; j < len/2; ++j) {
                const Cpx u = a[i+j];
                const Cpx v = a[i+j+len/2] * w;
                a[i+j]         = u + v;
                a[i+j+len/2]   = u - v;
                w *= wlen;
            }
        }
    }
    if (inverse) for (auto& x : a) x /= static_cast<f32>(N);
}

static void dft_2d(std::vector<Cpx>& a, int W, int H, bool inverse) {
    std::vector<Cpx> row(W), col(H);
    for (int y = 0; y < H; ++y) {
        for (int x = 0; x < W; ++x) row[x] = a[y*W+x];
        dft_1d(row, inverse);
        for (int x = 0; x < W; ++x) a[y*W+x] = row[x];
    }
    for (int x = 0; x < W; ++x) {
        for (int y = 0; y < H; ++y) col[y] = a[y*W+x];
        dft_1d(col, inverse);
        for (int y = 0; y < H; ++y) a[y*W+x] = col[y];
    }
}

// ─── Per-tile DFT-domain Wiener merge ───────────────────────────────────────
/**
 * For one T×T tile pair (reference, alternate-warped), compute:
 *
 *   A(u,v) = DFT(ref tile)
 *   B(u,v) = DFT(alt tile, warped)
 *
 *   diff(u,v) = |A(u,v) - B(u,v)|²
 *   w(u,v)    = σ²_n / (σ²_n + diff(u,v))
 *               × ghost_weight     (SAFNet-lite spatial-confidence mask)
 *               × photon_confidence
 *
 *   merged(u,v) = A(u,v) + w(u,v) * (B(u,v) - A(u,v))
 *
 * Returns the merged-tile spatial domain (real part of IDFT).
 */
static void wiener_merge_tile(const f32* ref, const f32* alt,
                                int T, f32 sigma2_n, f32 ghost_w, f32 photon_w,
                                f32 wiener_str, f32* out) {
    const int N = T*T;
    std::vector<Cpx> A(N), B(N);
    for (int i = 0; i < N; ++i) { A[i] = Cpx(ref[i], 0.f); B[i] = Cpx(alt[i], 0.f); }
    dft_2d(A, T, T, false);
    dft_2d(B, T, T, false);

    const f32 conf = ghost_w * photon_w * wiener_str;
    for (int i = 0; i < N; ++i) {
        const Cpx d = A[i] - B[i];
        const f32 d2 = d.real()*d.real() + d.imag()*d.imag();
        const f32 w  = std::clamp(conf * sigma2_n / (sigma2_n + d2 + EPS), 0.f, 1.f);
        A[i] = A[i] + (B[i] - A[i]) * w;
    }
    dft_2d(A, T, T, true);
    for (int i = 0; i < N; ++i) out[i] = A[i].real();
}

// ─── Photon-matrix confidence (Hasinoff et al. 2016) ────────────────────────
static f32 photon_confidence(f32 P_avg, f32 sigma_read) {
    constexpr f32 max_SNR = 63.f;  // ≈ sqrt(4000 e-) for typical sensor full-well
    const f32 snr = P_avg / std::max(EPS, std::sqrt(P_avg + sigma_read*sigma_read));
    return std::min(1.f, snr / max_SNR);
}

// ─── SAFNet-lite ghost / motion mask (per-tile) ─────────────────────────────
/**
 * SAFNet's full deep model isn't practical to ship in a 12kB header pipeline
 * without a TFLite hookup. This is a hand-crafted approximation that captures
 * the essential idea: select where alignment is reliable using the residual
 * after warping vs. expected noise level.
 *
 * confidence ∈ [0..1] :  1 = clean alignment (use frame), 0 = ghost (skip)
 *
 *   r = mean_abs_diff(ref_tile, warped_alt_tile)
 *   z = r / (ghost_sigma * σ_noise)
 *   conf = 1 - smoothstep(0.5, 1.5, z)
 */
static f32 safnet_lite_confidence(const f32* ref, const f32* alt, int T,
                                    f32 sigma_n, f32 ghost_sigma) {
    const int N = T*T;
    f32 sum = 0.f;
    for (int i = 0; i < N; ++i) sum += std::abs(ref[i] - alt[i]);
    const f32 r = sum / N;
    const f32 z = r / std::max(EPS, ghost_sigma * sigma_n);
    auto smooth = [](f32 e0, f32 e1, f32 x) {
        const f32 t = std::clamp((x-e0)/std::max(EPS,e1-e0), 0.f, 1.f);
        return t*t*(3.f - 2.f*t);
    };
    return std::clamp(1.f - smooth(0.5f, 1.5f, z), 0.f, 1.f);
}

// ─── Top-level FusionLM merge ───────────────────────────────────────────────
/**
 * fuse_burst()
 *
 * Merges a burst of luminance buffers (already aligned or not) into a single
 * merged buffer using SAFNet-lite + Wiener. For RAW Bayer, run on each
 * channel pair (R, Gr, Gb, B) independently.
 *
 * @param frames     Pre-aligned luma buffers (frames[0] is reference)
 * @param sigma_n    Noise std at the average frame intensity (per-channel)
 * @param cfg        Fusion config
 * @return           Merged buffer (same size as frames[0])
 */
ImageBuffer fuse_burst(const std::vector<ImageBuffer>& frames,
                        f32 sigma_n, const FusionCfg& cfg) {
    if (frames.empty()) return ImageBuffer();
    const ImageBuffer& ref = frames[0];
    const int W = ref.w, H = ref.h;
    const int T = cfg.tile_size;
    const int stride = T / 2;       // 50% overlap raised cosine

    // Output accumulators
    std::vector<f32> acc(static_cast<size_t>(W)*H, 0.f);
    std::vector<f32> wsum(static_cast<size_t>(W)*H, 0.f);

    // Raised cosine window
    std::vector<f32> win(T*T);
    for (int j = 0; j < T; ++j)
        for (int i = 0; i < T; ++i)
            win[j*T+i] = 0.25f
                * (1.f - std::cos(2.f*PI*i/(T-1)))
                * (1.f - std::cos(2.f*PI*j/(T-1)));

    std::vector<f32> ref_tile(T*T), alt_tile(T*T), merged_tile(T*T);
    const f32 sigma2_n = sigma_n * sigma_n;

    for (int ty = 0; ty + T <= H; ty += stride) {
        for (int tx = 0; tx + T <= W; tx += stride) {
            // Load reference tile
            for (int j = 0; j < T; ++j)
                for (int i = 0; i < T; ++i)
                    ref_tile[j*T+i] = ref.at(tx+i, ty+j);

            // Initialise merged with reference
            std::copy(ref_tile.begin(), ref_tile.end(), merged_tile.begin());

            // Photon confidence (use mean intensity in tile)
            f32 mean = 0.f;
            for (f32 v : ref_tile) mean += v;
            mean /= (T*T);
            const f32 phc = cfg.photon_matrix
                          ? photon_confidence(mean, std::sqrt(sigma_n*sigma_n))
                          : 1.f;

            // Sum alternates
            int merged_count = 0;
            for (size_t f = 1; f < frames.size() && f < static_cast<size_t>(cfg.max_frames); ++f) {
                for (int j = 0; j < T; ++j)
                    for (int i = 0; i < T; ++i)
                        alt_tile[j*T+i] = frames[f].at(tx+i, ty+j);

                const f32 conf = cfg.safnet_selection
                               ? safnet_lite_confidence(ref_tile.data(), alt_tile.data(),
                                                          T, sigma_n, cfg.ghost_sigma)
                               : 1.f;
                if (conf < cfg.safnet_threshold) continue;

                wiener_merge_tile(merged_tile.data(), alt_tile.data(),
                                   T, sigma2_n, conf, phc,
                                   cfg.wiener_strength, merged_tile.data());
                ++merged_count;
            }

            // Apply window & accumulate
            for (int j = 0; j < T; ++j) {
                for (int i = 0; i < T; ++i) {
                    const int xx = tx+i, yy = ty+j;
                    const f32 w = win[j*T+i];
                    acc [yy*W+xx] += merged_tile[j*T+i] * w;
                    wsum[yy*W+xx] += w;
                }
            }
        }
    }

    ImageBuffer out(W, H, 1);
    for (int i = 0; i < W*H; ++i) {
        out.data[i] = wsum[i] > EPS ? acc[i] / wsum[i] : ref.data[i];
    }
    return out;
}

} // namespace ProXDR
