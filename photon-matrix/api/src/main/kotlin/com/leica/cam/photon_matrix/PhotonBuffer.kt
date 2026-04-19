package com.leica.cam.photon_matrix

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import java.nio.ShortBuffer

/**
 * The canonical 16-bit photon buffer. SEALED — no module may subclass or copy-construct.
 * All pixel data is scene-referred linear light. No gamma encoding.
 *
 * RULE: BitDepth must be BIT_10, BIT_12, or BIT_16. NEVER 8-bit.
 * RULE: planeView() returns a READ-ONLY buffer. Callers must not cast it.
 * RULE: Instances are immutable after construction. No defensive copies needed.
 */
@Immutable
sealed class PhotonBuffer {
    abstract val planes: ImmutableList<PhotonPlane>
    abstract val width: Int
    abstract val height: Int
    abstract val bitDepth: BitDepth
    abstract val colorSpace: PhotonColorSpace
    abstract val captureTimestampNs: Long
    abstract val frameMetadata: FrameMetadata

    enum class BitDepth(val bitsPerChannel: Int, val maxValue: Int) {
        BIT_10(10, 1023),
        BIT_12(12, 4095),
        BIT_16(16, 65535),
    }

    /** Zero-copy read-only view into a single colour channel plane. */
    fun planeView(channel: ColorChannel): ShortBuffer =
        planes[channel.index].buffer.asReadOnlyBuffer()

    val pixelCount: Int get() = width * height
    val totalSamples: Int get() = pixelCount * planes.size

    @Immutable
    internal data class Raw internal constructor(
        override val planes: ImmutableList<PhotonPlane>,
        override val width: Int,
        override val height: Int,
        override val bitDepth: BitDepth,
        override val colorSpace: PhotonColorSpace,
        override val captureTimestampNs: Long,
        override val frameMetadata: FrameMetadata,
    ) : PhotonBuffer()

    companion object {
        /**
         * Factory — validates bit depth and plane count before construction.
         * Only [PhotonMatrixIngestor] should call this.
         */
        internal fun create(
            planes: ImmutableList<PhotonPlane>,
            width: Int,
            height: Int,
            bitDepth: BitDepth,
            colorSpace: PhotonColorSpace,
            captureTimestampNs: Long,
            frameMetadata: FrameMetadata,
        ): PhotonBuffer {
            require(planes.isNotEmpty()) { "PhotonBuffer must have at least 1 plane" }
            require(planes.all { it.bitDepth == bitDepth }) { "All planes must share the same BitDepth" }
            require(width > 0 && height > 0) { "Dimensions must be positive" }
            return Raw(planes, width, height, bitDepth, colorSpace, captureTimestampNs, frameMetadata)
        }
    }
}

/**
 * Type-safe proof that FusionLM 2.0 has processed this buffer.
 * All Layer 3 engines must accept [FusedPhotonBuffer], not [PhotonBuffer].
 * Only [FusionLM2Engine] may produce this type.
 */
@Immutable
class FusedPhotonBuffer internal constructor(
    val underlying: PhotonBuffer,
    val fusionQuality: Float,
    val frameCount: Int,
    val motionMagnitude: Float,
) : PhotonBuffer() {
    override val planes get() = underlying.planes
    override val width get() = underlying.width
    override val height get() = underlying.height
    override val bitDepth get() = underlying.bitDepth
    override val colorSpace get() = underlying.colorSpace
    override val captureTimestampNs get() = underlying.captureTimestampNs
    override val frameMetadata get() = underlying.frameMetadata

    init {
        require(fusionQuality in 0f..1f) { "fusionQuality must be in [0, 1]" }
        require(frameCount >= 1) { "frameCount must be >= 1" }
        require(motionMagnitude >= 0f) { "motionMagnitude must be >= 0" }
    }
}
