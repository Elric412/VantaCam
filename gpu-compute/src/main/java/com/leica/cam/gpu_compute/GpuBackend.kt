package com.leica.cam.gpu_compute

import com.leica.cam.common.result.LeicaResult

/**
 * Sealed interface for GPU compute backends.
 * All implementations must be in this module to satisfy sealed constraints.
 */
sealed interface GpuBackend {
    suspend fun dispatch(
        shader: CompiledShader,
        inputs: List<GpuBuffer>,
        output: GpuBuffer,
    ): LeicaResult<Unit>

    suspend fun loadShader(name: String): LeicaResult<CompiledShader>

    fun release()

    data class VulkanBackend(
        val pipeline: com.leica.cam.gpu_compute.vulkan.VulkanComputePipeline = com.leica.cam.gpu_compute.vulkan.VulkanComputePipeline(),
    ) : GpuBackend {
        override suspend fun dispatch(
            shader: CompiledShader,
            inputs: List<GpuBuffer>,
            output: GpuBuffer,
        ): LeicaResult<Unit> = pipeline.dispatchGeneric(shader, inputs, output)

        override suspend fun loadShader(name: String): LeicaResult<CompiledShader> =
            pipeline.loadShader(name)

        override fun release() = pipeline.release()

        suspend fun applyWbTile(
            inputBuffer: GpuBuffer,
            ccmBuffer: GpuBuffer,
            outputBuffer: GpuBuffer,
        ): LeicaResult<Unit> = pipeline.dispatchWbTile(inputBuffer, ccmBuffer, outputBuffer)

        suspend fun applyLut3D(
            inputBuffer: GpuBuffer,
            lutBuffer: GpuBuffer,
            outputBuffer: GpuBuffer,
            lutSize: Int,
        ): LeicaResult<Unit> = pipeline.dispatchLut3D(inputBuffer, lutBuffer, outputBuffer, lutSize)
    }

    data class OpenGlEsBackend(
        private val initialized: Boolean = false,
    ) : GpuBackend {
        override suspend fun dispatch(
            shader: CompiledShader,
            inputs: List<GpuBuffer>,
            output: GpuBuffer,
        ): LeicaResult<Unit> = LeicaResult.Success(Unit)

        override suspend fun loadShader(name: String): LeicaResult<CompiledShader> =
            LeicaResult.Success(object : CompiledShader {})

        override fun release() {}
    }

    data class CpuFallbackBackend(
        private val initialized: Boolean = false,
    ) : GpuBackend {
        override suspend fun dispatch(
            shader: CompiledShader,
            inputs: List<GpuBuffer>,
            output: GpuBuffer,
        ): LeicaResult<Unit> = LeicaResult.Success(Unit)

        override suspend fun loadShader(name: String): LeicaResult<CompiledShader> =
            LeicaResult.Success(object : CompiledShader {})

        override fun release() {}
    }
}

interface CompiledShader

interface GpuBuffer {
    val width: Int
    val height: Int
    val format: BufferFormat
}

/**
 * GPU buffer pixel formats.
 *
 * Each pipeline stage requires specific precision:
 * - [RGBA_16F]: Standard colour processing (WB, tone mapping, colour grading)
 * - [RGBA_32F]: High-precision accumulation (FusionLM Wiener merge, HDR stacking)
 * - [RG_16F]: Dual-channel maps (CoC diameter + sign, flow vectors)
 * - [R_16F]: Single-channel maps (luminance, depth, alpha masks)
 * - [R_32F]: High-precision single-channel (depth accumulation, noise variance)
 */
enum class BufferFormat {
    RGBA_16F,
    RGBA_32F,
    RG_16F,
    R_16F,
    R_32F,

    ;

    /** Bytes per pixel for this format. */
    val bytesPerPixel: Int
        get() = when (this) {
            RGBA_16F -> 8
            RGBA_32F -> 16
            RG_16F -> 4
            R_16F -> 2
            R_32F -> 4
        }
}

