/**
 * ╔══════════════════════════════════════════════════════════════════════════╗
 * ║  ProXDR v3  ·  src/neural_hooks.cpp                                     ║
 * ║  NeuralHooks — TFLite / ONNX integration plug points                    ║
 * ╠══════════════════════════════════════════════════════════════════════════╣
 * ║  This file defines the abstract interface that the C++ engine uses to   ║
 * ║  consume neural-network outputs (scene segmentation, portrait matting,  ║
 * ║  optional neural demosaic). The actual TFLite / ONNX Runtime calls live ║
 * ║  in the Android-side bridge (Kotlin/JNI), and the inference results are ║
 * ║  passed in as plain ImageBuffers via these hooks.                        ║
 * ║                                                                          ║
 * ║  Why this design?                                                        ║
 * ║   • Keeps the C++ engine free of TFLite/ONNX dependencies (smaller .so) ║
 * ║   • Lets you swap inference back-ends (TFLite/NCNN/ONNX/MNN) easily      ║
 * ║   • Allows running inference on the NPU/DSP via NNAPI without dragging  ║
 * ║     those libraries into the engine                                      ║
 * ║                                                                          ║
 * ║  See docs/INTEGRATION.md for the Kotlin/JNI implementation pattern.      ║
 * ╚══════════════════════════════════════════════════════════════════════════╝
 */

#include "../include/ProXDR_Engine.h"

namespace ProXDR {

// External (defined in scene_analyzer.cpp): merges a soft seg-mask into zones.
extern void apply_neural_zones(ZoneMap& zones, const ImageBuffer& seg_conf,
                                const int* class_map, int nclasses);

// ─── Inference back-end abstract interface ──────────────────────────────────
/**
 * Consumer code (Android side) implements this and registers it with the
 * engine before calling ProcessBurst(). The engine calls these methods at
 * the appropriate points in the pipeline.
 *
 * All inference is expected to be:
 *   • input  = float [1, H, W, 3] RGB (BT.709, linear or gamma — model dep.)
 *   • output = float [1, H, W, K] softmax (segmentation) or [1, H, W, 1] (mat)
 */
class INeuralBackend {
public:
    virtual ~INeuralBackend() = default;

    /// Run scene-segmentation network. `out` will receive the softmax tensor
    /// at the model's native resolution (typically 256×192). The caller
    /// upscales as needed via apply_neural_zones().
    virtual bool runSceneSegmentation(const ImageBuffer& rgb_lowres_gamma,
                                       ImageBuffer& out_softmax,
                                       int& out_num_classes) = 0;

    /// Run portrait alpha-matte network. `out` will receive a single-channel
    /// matte at the model's native resolution.
    virtual bool runPortraitMatting(const ImageBuffer& rgb_lowres_gamma,
                                     ImageBuffer& out_alpha) = 0;

    /// Optional neural demosaic for low-light / very noisy frames. Input is
    /// 1-channel Bayer (interleaved), output is 3-channel linear RGB.
    /// Return false if not supported / not enabled.
    virtual bool runNeuralDemosaic(const ImageBuffer& bayer,
                                    BayerPattern bp,
                                    ImageBuffer& out_rgb) {
        (void)bayer; (void)bp; (void)out_rgb;
        return false;
    }
};

// ─── Singleton registry — set from JNI bridge ───────────────────────────────
static INeuralBackend* g_backend = nullptr;
static std::mutex      g_backend_mu;

void register_neural_backend(INeuralBackend* be) {
    std::lock_guard<std::mutex> lk(g_backend_mu);
    g_backend = be;
}

INeuralBackend* get_neural_backend() {
    std::lock_guard<std::mutex> lk(g_backend_mu);
    return g_backend;
}

// ─── Standard class-index map for our reference seg model ───────────────────
// 0 = background, 1 = sky, 2 = face, 3 = vegetation, 4 = water, 5 = building
static const int kStdClassMap[6] = { 0, 1, 2, 3, 4, 5 };

// ─── High-level: run seg and merge into zone map ────────────────────────────
/**
 * Called by the pipeline orchestrator after rule-based zone analysis.
 * This is a no-op if neural is disabled or no backend is registered.
 *
 * @param rgb_full  Full-resolution linear RGB (will be downsampled to seg input size)
 * @param zones     Zone map (modified in-place)
 * @param ncfg      Neural configuration
 */
void run_neural_zone_refinement(const ImageBuffer& rgb_full,
                                  ZoneMap& zones,
                                  const NeuralCfg& ncfg) {
    if (!ncfg.enable_segmentation) return;
    INeuralBackend* be = get_neural_backend();
    if (!be) return;

    // Downsample to seg-model resolution
    const int sw = ncfg.seg_input_w, sh = ncfg.seg_input_h;
    if (sw < 32 || sh < 32) return;

    ImageBuffer rgb_low(sw, sh, 3);
    const f32 scale_x = static_cast<f32>(rgb_full.w) / sw;
    const f32 scale_y = static_cast<f32>(rgb_full.h) / sh;
    for (int y = 0; y < sh; ++y) {
        for (int x = 0; x < sw; ++x) {
            const f32 fx = (x + 0.5f) * scale_x - 0.5f;
            const f32 fy = (y + 0.5f) * scale_y - 0.5f;
            for (int c = 0; c < 3; ++c)
                rgb_low.at(x, y, c) = rgb_full.sample(fx, fy, c);
        }
    }

    ImageBuffer seg;
    int nclasses = 0;
    if (!be->runSceneSegmentation(rgb_low, seg, nclasses) || nclasses < 2) return;
    if (nclasses > 6) nclasses = 6;

    apply_neural_zones(zones, seg, kStdClassMap, nclasses);
}

/**
 * Optionally refine the face / skin map with portrait matting alpha.
 * The matte improves zone-aware tone mapping on faces/bodies — particularly
 * useful for backlit portraits where the face mask alone misses the shoulders.
 */
void run_portrait_mat_refinement(const ImageBuffer& rgb_full,
                                   ZoneMap& zones,
                                   const NeuralCfg& ncfg) {
    if (!ncfg.enable_portrait_mat) return;
    INeuralBackend* be = get_neural_backend();
    if (!be) return;

    const int sw = ncfg.seg_input_w, sh = ncfg.seg_input_h;
    ImageBuffer rgb_low(sw, sh, 3);
    const f32 scale_x = static_cast<f32>(rgb_full.w) / sw;
    const f32 scale_y = static_cast<f32>(rgb_full.h) / sh;
    for (int y = 0; y < sh; ++y) {
        for (int x = 0; x < sw; ++x) {
            const f32 fx = (x + 0.5f) * scale_x - 0.5f;
            const f32 fy = (y + 0.5f) * scale_y - 0.5f;
            for (int c = 0; c < 3; ++c)
                rgb_low.at(x, y, c) = rgb_full.sample(fx, fy, c);
        }
    }
    ImageBuffer alpha;
    if (!be->runPortraitMatting(rgb_low, alpha)) return;

    // Upscale and OR into zone labels (skin / person body)
    const int W = zones.w, H = zones.h;
    const f32 ux = static_cast<f32>(alpha.w) / W;
    const f32 uy = static_cast<f32>(alpha.h) / H;
    for (int y = 0; y < H; ++y) {
        for (int x = 0; x < W; ++x) {
            const f32 a = alpha.sample(x*ux, y*uy);
            if (a > 0.4f) zones.label_x(x,y) |= ZONEX_PERSON_BODY;
            zones.skin(x,y) = std::max(zones.skin(x,y), a);
        }
    }
}

} // namespace ProXDR
