package com.leica.cam.gpu_compute.vulkan

/**
 * Registry of all Vulkan compute shaders available in the GPU compute module.
 *
 * Shader GLSL source lives in `src/main/resources/shaders/` and is compiled
 * to SPIR-V at build time via the Android NDK's `glslc` compiler.
 *
 * Each entry maps a logical shader name to its asset path. The
 * [VulkanComputePipeline] loads SPIR-V from assets at pipeline creation time.
 */
object ShaderRegistry {

    /** All registered compute shaders, keyed by logical name. */
    val shaders: Map<String, ShaderEntry> = mapOf(
        // ── Existing shaders ──────────────────────────────────────────────
        "tone_map" to ShaderEntry(
            name = "tone_map",
            assetPath = "shaders/tone_map.comp",
            description = "Global / local tone mapping compute kernel.",
            workgroupSize = Triple(16, 16, 1),
        ),
        "wb_tile" to ShaderEntry(
            name = "wb_tile",
            assetPath = "shaders/wb_tile.comp",
            description = "Per-tile white balance gain application.",
            workgroupSize = Triple(16, 16, 1),
        ),
        "lut_3d_compute" to ShaderEntry(
            name = "lut_3d_compute",
            assetPath = "shaders/lut_3d_compute.comp",
            description = "3D LUT colour grading compute kernel.",
            workgroupSize = Triple(16, 16, 1),
        ),

        // ── D2 new shaders ────────────────────────────────────────────────
        "hdr_merge_wiener" to ShaderEntry(
            name = "hdr_merge_wiener",
            assetPath = "shaders/hdr_merge_wiener.comp",
            description = "Per-channel Wiener HDR merge. D2.5: fixes luminance-only sigma^2 bug.",
            workgroupSize = Triple(16, 16, 1),
        ),
        "laplacian_pyramid_blend" to ShaderEntry(
            name = "laplacian_pyramid_blend",
            assetPath = "shaders/laplacian_pyramid_blend.comp",
            description = "Laplacian pyramid level blending for Mertens exposure fusion (D2.2).",
            workgroupSize = Triple(16, 16, 1),
        ),
    )

    /**
     * Get a shader entry by name.
     * @throws IllegalArgumentException if the shader is not registered.
     */
    fun get(name: String): ShaderEntry =
        shaders[name] ?: throw IllegalArgumentException("Shader '$name' not registered in ShaderRegistry")

    data class ShaderEntry(
        val name: String,
        val assetPath: String,
        val description: String,
        /** (x, y, z) workgroup dimensions. */
        val workgroupSize: Triple<Int, Int, Int>,
    )
}
