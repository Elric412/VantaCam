#pragma once

// ─────────────────────────────────────────────────────────────────────────────
// photon_buffer.h — High-performance native photon buffer
// ─────────────────────────────────────────────────────────────────────────────
//
// Design principles:
//  1. RAII ownership — no naked new/delete at call sites
//  2. 64-byte alignment for SIMD/cache-line efficiency
//  3. Optional pool allocator for burst-capture reuse (avoids heap thrash)
//  4. Span-based channel views — zero-copy, no implicit copies
//  5. Const-correct throughout; noexcept where provably safe
//  6. Move-only (no copy) — photon buffers are large; accidental copies are bugs

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <memory>
#include <mutex>
#include <new>
#include <optional>
#include <span>
#include <stdexcept>
#include <string>
#include <vector>

namespace leica::native {

// ─────────────────────────────────────────────────────────────────────────────
// Bit-depth enum
// ─────────────────────────────────────────────────────────────────────────────

enum class BitDepth : uint8_t {
    BIT_10 = 10,
    BIT_12 = 12,
    BIT_16 = 16,
};

/** Returns the white-level (max valid value) for a given bit depth. */
[[nodiscard]] inline constexpr uint16_t white_level(BitDepth bd) noexcept {
    switch (bd) {
        case BitDepth::BIT_10: return (1u << 10u) - 1u;
        case BitDepth::BIT_12: return (1u << 12u) - 1u;
        case BitDepth::BIT_16: return 0xFFFFu;
    }
    return 0xFFFFu;
}

/** Returns the black-level offset typical for each bit depth. */
[[nodiscard]] inline constexpr uint16_t black_level(BitDepth bd) noexcept {
    switch (bd) {
        case BitDepth::BIT_10: return 64u;
        case BitDepth::BIT_12: return 256u;
        case BitDepth::BIT_16: return 1024u;
    }
    return 1024u;
}

// ─────────────────────────────────────────────────────────────────────────────
// Aligned allocator — 64-byte alignment for AVX2/NEON SIMD and cache lines
// ─────────────────────────────────────────────────────────────────────────────

inline constexpr std::size_t kCacheLineBytes = 64;

/**
 * Allocate [count] uint16_t with 64-byte alignment.
 * Returns nullptr on failure rather than throwing (safe for JNI boundaries).
 */
[[nodiscard]] inline uint16_t* aligned_alloc_u16(std::size_t count) noexcept {
    if (count == 0) return nullptr;
    const std::size_t bytes = count * sizeof(uint16_t);
    // Use std::aligned_alloc (C++17). Falls back to platform-specific on MSVC.
#if defined(_MSC_VER)
    return static_cast<uint16_t*>(_aligned_malloc(bytes, kCacheLineBytes));
#else
    void* ptr = nullptr;
    if (::posix_memalign(&ptr, kCacheLineBytes, bytes) != 0) return nullptr;
    return static_cast<uint16_t*>(ptr);
#endif
}

inline void aligned_free(void* ptr) noexcept {
    if (!ptr) return;
#if defined(_MSC_VER)
    _aligned_free(ptr);
#else
    ::free(ptr);
#endif
}

/** std::unique_ptr deleter for SIMD-aligned memory. */
struct AlignedDeleter {
    void operator()(uint16_t* p) const noexcept { aligned_free(p); }
};

using AlignedU16Ptr = std::unique_ptr<uint16_t[], AlignedDeleter>;

// ─────────────────────────────────────────────────────────────────────────────
// NativePhotonBuffer — the core imaging data container
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Flat multi-channel 16-bit photon buffer with 64-byte aligned storage.
 *
 * Layout: planar — all channel-0 samples, then all channel-1 samples, etc.
 * This maximises SIMD throughput for per-channel operations (demosaic, WB, CCM).
 *
 * Lifecycle:
 *  - Allocate via NativePhotonBuffer::allocate() or PooledPhotonBuffer.
 *  - Pass around by unique_ptr or raw non-owning pointer in native code.
 *  - Never copy — use channel_view() for zero-copy reads.
 */
struct alignas(kCacheLineBytes) NativePhotonBuffer {
    uint32_t  width    = 0;
    uint32_t  height   = 0;
    uint32_t  channels = 0;
    BitDepth  bit_depth = BitDepth::BIT_16;

private:
    AlignedU16Ptr data_;
    std::size_t   capacity_ = 0;   // samples per channel (may exceed width*height for pool reuse)

public:
    // Non-copyable, movable (Rule of Five)
    NativePhotonBuffer() noexcept = default;
    ~NativePhotonBuffer() noexcept = default;
    NativePhotonBuffer(const NativePhotonBuffer&)            = delete;
    NativePhotonBuffer& operator=(const NativePhotonBuffer&) = delete;

    NativePhotonBuffer(NativePhotonBuffer&& o) noexcept
        : width(o.width), height(o.height), channels(o.channels),
          bit_depth(o.bit_depth), data_(std::move(o.data_)), capacity_(o.capacity_) {
        o.width = o.height = o.channels = 0;
        o.capacity_ = 0;
    }

    NativePhotonBuffer& operator=(NativePhotonBuffer&& o) noexcept {
        if (this != &o) {
            width = o.width; height = o.height; channels = o.channels;
            bit_depth = o.bit_depth;
            data_ = std::move(o.data_);
            capacity_ = o.capacity_;
            o.width = o.height = o.channels = 0;
            o.capacity_ = 0;
        }
        return *this;
    }

    // ── Factory ──────────────────────────────────────────────────────────────

    /**
     * Allocate a fresh buffer.  Returns nullptr on allocation failure —
     * callers MUST check before dereferencing.
     *
     * @param zero  If true, zero-initialise all pixels (safe default).
     *              Set false only in perf-critical paths where the caller
     *              guarantees full overwrite before reading.
     */
    [[nodiscard]] static std::unique_ptr<NativePhotonBuffer>
    allocate(uint32_t w, uint32_t h, uint32_t ch, BitDepth bd,
             bool zero = true) noexcept {
        if (w == 0 || h == 0 || ch == 0) return nullptr;

        const std::size_t spc = static_cast<std::size_t>(w) * h;
        const std::size_t total = spc * ch;

        auto buf = std::make_unique<NativePhotonBuffer>();
        buf->data_ = AlignedU16Ptr(aligned_alloc_u16(total));
        if (!buf->data_) return nullptr;

        buf->width    = w;
        buf->height   = h;
        buf->channels = ch;
        buf->bit_depth = bd;
        buf->capacity_ = spc;
        if (zero) std::memset(buf->data_.get(), 0, total * sizeof(uint16_t));
        return buf;
    }

    // ── Accessors ────────────────────────────────────────────────────────────

    /** Samples per channel (w × h). */
    [[nodiscard]] std::size_t samples_per_channel() const noexcept {
        return static_cast<std::size_t>(width) * height;
    }

    /** Total bytes allocated across all channels. */
    [[nodiscard]] std::size_t total_bytes() const noexcept {
        return samples_per_channel() * channels * sizeof(uint16_t);
    }

    /**
     * Zero-copy read/write view into a single channel.
     * [channel] must be < channels — undefined behaviour otherwise.
     */
    [[nodiscard]] std::span<uint16_t> channel_view(uint32_t channel) noexcept {
        return { data_.get() + static_cast<std::size_t>(channel) * capacity_,
                 samples_per_channel() };
    }

    [[nodiscard]] std::span<const uint16_t> channel_view(uint32_t channel) const noexcept {
        return { data_.get() + static_cast<std::size_t>(channel) * capacity_,
                 samples_per_channel() };
    }

    /** Returns raw pointer to channel start — for SIMD-optimised kernels. */
    [[nodiscard]] uint16_t* channel_ptr(uint32_t channel) noexcept {
        return data_.get() + static_cast<std::size_t>(channel) * capacity_;
    }
    [[nodiscard]] const uint16_t* channel_ptr(uint32_t channel) const noexcept {
        return data_.get() + static_cast<std::size_t>(channel) * capacity_;
    }

    /** Zeroes all channels. */
    void clear() noexcept {
        if (data_) std::memset(data_.get(), 0, samples_per_channel() * channels * sizeof(uint16_t));
    }
};

// ─────────────────────────────────────────────────────────────────────────────
// PooledPhotonBuffer — object pool for burst-capture allocation reuse
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Fixed-size pool of pre-allocated photon buffers.
 *
 * **Problem:** Allocating a 50 MP × 3 channel × 16-bit buffer = ~300 MB on
 * every shutter press causes GC pressure, heap fragmentation, and latency spikes.
 *
 * **Solution:** Pre-allocate [capacity] buffers at session start.
 * Acquire returns a buffer from the free list in O(1); Release returns it.
 *
 * Thread-safe (mutex-protected free list).
 * Buffer dimensions must match the pool's configured size.
 */
class PooledPhotonBuffer {
public:
    PooledPhotonBuffer() = default;

    /**
     * Initialise the pool.
     * Must be called once before first acquire().
     * Returns false if any allocation fails.
     */
    bool init(uint32_t w, uint32_t h, uint32_t ch, BitDepth bd,
              std::size_t capacity) noexcept {
        std::lock_guard lock(mu_);
        width_ = w; height_ = h; channels_ = ch; bit_depth_ = bd;
        pool_.clear();
        pool_.reserve(capacity);

        for (std::size_t i = 0; i < capacity; ++i) {
            auto buf = NativePhotonBuffer::allocate(w, h, ch, bd, false);
            if (!buf) return false;
            pool_.push_back(std::move(buf));
        }
        free_mask_.assign(capacity, true);
        return true;
    }

    /**
     * Acquire a buffer from the pool.
     * Returns nullptr if no free buffer available (caller must handle).
     */
    [[nodiscard]] NativePhotonBuffer* acquire() noexcept {
        std::lock_guard lock(mu_);
        for (std::size_t i = 0; i < free_mask_.size(); ++i) {
            if (free_mask_[i]) {
                free_mask_[i] = false;
                return pool_[i].get();
            }
        }
        return nullptr;   // pool exhausted — caller falls back to direct allocation
    }

    /**
     * Return a buffer to the free list.
     * Zeroes the buffer before returning it (prevents data leakage between frames).
     */
    void release(const NativePhotonBuffer* buf) noexcept {
        if (!buf) return;
        std::lock_guard lock(mu_);
        for (std::size_t i = 0; i < pool_.size(); ++i) {
            if (pool_[i].get() == buf) {
                pool_[i]->clear();
                free_mask_[i] = true;
                return;
            }
        }
    }

    /** Available (free) buffer count. */
    [[nodiscard]] std::size_t available() const noexcept {
        std::lock_guard lock(mu_);
        std::size_t count = 0;
        for (bool f : free_mask_) if (f) ++count;
        return count;
    }

private:
    mutable std::mutex mu_;
    std::vector<std::unique_ptr<NativePhotonBuffer>> pool_;
    std::vector<bool> free_mask_;
    uint32_t width_ = 0, height_ = 0, channels_ = 0;
    BitDepth bit_depth_ = BitDepth::BIT_16;
};

// ─────────────────────────────────────────────────────────────────────────────
// RAII pool buffer handle
// ─────────────────────────────────────────────────────────────────────────────

/**
 * RAII scope guard that returns a pooled buffer on destruction.
 *
 * Usage:
 *   auto handle = pool.acquire_scoped();   // returns std::optional<PoolHandle>
 *   if (!handle) { /* pool exhausted */ }
 *   handle->buffer().channel_view(0) ...
 */
class PoolHandle {
public:
    PoolHandle(PooledPhotonBuffer& pool, NativePhotonBuffer* buf) noexcept
        : pool_(pool), buf_(buf) {}
    ~PoolHandle() noexcept { pool_.release(buf_); }

    PoolHandle(const PoolHandle&)            = delete;
    PoolHandle& operator=(const PoolHandle&) = delete;
    PoolHandle(PoolHandle&&)                 = delete;

    [[nodiscard]] NativePhotonBuffer& buffer()       noexcept { return *buf_; }
    [[nodiscard]] const NativePhotonBuffer& buffer() const noexcept { return *buf_; }

private:
    PooledPhotonBuffer& pool_;
    NativePhotonBuffer* buf_;
};

}  // namespace leica::native
