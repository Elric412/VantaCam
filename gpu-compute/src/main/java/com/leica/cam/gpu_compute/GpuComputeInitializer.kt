package com.leica.cam.gpu_compute

import android.content.Context
import com.leica.cam.common.logging.LeicaLogger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GpuComputeInitializer @Inject constructor(
    private val logger: LeicaLogger,
) {
    fun initialize(context: Context): GpuBackend {
        logger.info("GpuComputeInitializer", "Attempting Vulkan initialization")
        val hasVulkan = context.packageManager.hasSystemFeature("android.hardware.vulkan.level")
        return if (hasVulkan) {
            val pipeline = vulkan.VulkanComputePipeline()
            pipeline.initialize()
            GpuBackend.VulkanBackend(pipeline)
        } else {
            logger.info("GpuComputeInitializer", "Vulkan unavailable; falling back to CPU path")
            GpuBackend.CpuFallbackBackend()
        }
    }
}
