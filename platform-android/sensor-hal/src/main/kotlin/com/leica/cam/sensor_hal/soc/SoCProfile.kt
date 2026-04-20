package com.leica.cam.sensor_hal.soc

/**
 * Runtime SoC detection and compute routing for imaging pipeline stages.
 *
 * Detects the device's System-on-Chip at runtime and provides per-SoC
 * configuration for GPU compute dispatch, NPU delegate selection, and
 * ISP integration parameters.
 *
 * Detection priority: Build.HARDWARE → /proc/cpuinfo → CameraCharacteristics
 * vendor tags → fallback to generic profile.
 *
 * References:
 * - Qualcomm Adreno GPU: VK_KHR_shader_float16_int8, wave size 64
 * - Mali GPU: subgroupSize query, TBDR architecture
 * - AMD RDNA (Xclipse): wave64, VK_AMD_shader_info
 */
data class SoCProfile(
    val vendor: SoCVendor,
    val family: String,
    val modelName: String,
    val gpuConfig: GpuComputeConfig,
    val npuConfig: NpuDelegateConfig,
    val ispConfig: IspIntegrationConfig,
) {
    companion object {
        /**
         * Detect SoC profile from available system information.
         *
         * @param hardwareString Value of android.os.Build.HARDWARE
         * @param cpuInfo        Contents of /proc/cpuinfo (first 2048 chars sufficient)
         * @return Detected [SoCProfile] with full configuration
         */
        fun detect(hardwareString: String, cpuInfo: String = ""): SoCProfile {
            val hw = hardwareString.lowercase()
            val cpu = cpuInfo.lowercase()

            return when {
                // MediaTek Dimensity detection
                hw.contains("mt") || hw.contains("mediatek") || cpu.contains("dimensity") -> {
                    val family = detectMtkFamily(hw, cpu)
                    buildMediaTekProfile(family)
                }
                // Qualcomm Snapdragon detection
                hw.contains("qcom") || hw.contains("kona") || hw.contains("lahaina") ||
                hw.contains("taro") || hw.contains("kalama") || hw.contains("pineapple") ||
                cpu.contains("qualcomm") -> {
                    val family = detectQcomFamily(hw, cpu)
                    buildSnapdragonProfile(family)
                }
                // Samsung Exynos detection
                hw.contains("exynos") || hw.contains("samsungexynos") || cpu.contains("exynos") -> {
                    val family = detectExynosFamily(hw, cpu)
                    buildExynosProfile(family)
                }
                // Fallback: generic profile with safe defaults
                else -> buildGenericProfile(hw)
            }
        }

        private fun detectMtkFamily(hw: String, cpu: String): String = when {
            hw.contains("mt6985") || cpu.contains("dimensity 9200") -> "dimensity_9200"
            hw.contains("mt6983") || cpu.contains("dimensity 9000") -> "dimensity_9000"
            hw.contains("mt6895") || cpu.contains("dimensity 8100") -> "dimensity_8100"
            hw.contains("mt6893") || cpu.contains("dimensity 1200") -> "dimensity_1200"
            else -> "dimensity_generic"
        }

        private fun detectQcomFamily(hw: String, cpu: String): String = when {
            hw.contains("pineapple") || cpu.contains("gen 3") -> "snapdragon_8_gen3"
            hw.contains("kalama") || cpu.contains("gen 2") -> "snapdragon_8_gen2"
            hw.contains("taro") || cpu.contains("gen 1") -> "snapdragon_8_gen1"
            hw.contains("lahaina") -> "snapdragon_888"
            else -> "snapdragon_generic"
        }

        private fun detectExynosFamily(hw: String, cpu: String): String = when {
            hw.contains("2400") || cpu.contains("2400") -> "exynos_2400"
            hw.contains("2200") || cpu.contains("2200") -> "exynos_2200"
            else -> "exynos_generic"
        }

        private fun buildMediaTekProfile(family: String) = SoCProfile(
            vendor = SoCVendor.MEDIATEK,
            family = family,
            modelName = "MediaTek $family",
            gpuConfig = GpuComputeConfig(
                gpuArch = GpuArchitecture.MALI,
                // Mali: query subgroupSize at runtime, typically 16
                preferredWorkgroupSize = 16,
                supportsFp16 = true,
                supportsShaderImageAtomicInt64 = false, // Often unavailable on Mali
                usesSsboForAccumulation = true,          // Use SSBOs instead of atomics
                preferredVulkanVersion = "1.1",
            ),
            npuConfig = NpuDelegateConfig(
                preferredDelegate = NpuDelegate.MTK_APU,
                nnapiDeviceName = "mtk-apu",
                supportsInt4 = family.contains("9000") || family.contains("9200"),
                delegatePriority = listOf(NpuDelegate.MTK_APU, NpuDelegate.GPU, NpuDelegate.CPU),
                useNeuroPilotSdk = true,
                compilationCachingEnabled = true,
            ),
            ispConfig = IspIntegrationConfig(
                ispName = "Imagiq",
                supportsYuvReprocessing = family.contains("9200"),
                checkHalPreAppliedProcessing = true,
                hardwareFaceDetectionAvailable = true,
                hardwareAwbConflict = true, // Imagiq AWB conflicts with HyperTone multi-modal
                checkDistortionPreCorrection = true,
                eisCropRegionTracking = false,
                hardwareDepthStream = false,
                hardwareMfnrAvailable = false,
                sensorNoiseProfileTrusted = false, // Less reliable than Spectra
                hardwareHistogramAvailable = false,
            ),
        )

        private fun buildSnapdragonProfile(family: String) = SoCProfile(
            vendor = SoCVendor.QUALCOMM,
            family = family,
            modelName = "Qualcomm $family",
            gpuConfig = GpuComputeConfig(
                gpuArch = GpuArchitecture.ADRENO,
                // Adreno: workgroup size multiples of 64
                preferredWorkgroupSize = 64,
                supportsFp16 = true,
                supportsShaderImageAtomicInt64 = true,
                usesSsboForAccumulation = false,
                preferredVulkanVersion = "1.1",
            ),
            npuConfig = NpuDelegateConfig(
                preferredDelegate = NpuDelegate.HEXAGON,
                nnapiDeviceName = null, // Use default NNAPI
                supportsInt4 = false,
                delegatePriority = listOf(NpuDelegate.HEXAGON, NpuDelegate.GPU, NpuDelegate.CPU),
                useNeuroPilotSdk = false,
                compilationCachingEnabled = true,
            ),
            ispConfig = IspIntegrationConfig(
                ispName = "Spectra",
                supportsYuvReprocessing = true,
                checkHalPreAppliedProcessing = false,
                hardwareFaceDetectionAvailable = true,
                hardwareAwbConflict = false,
                checkDistortionPreCorrection = false,
                eisCropRegionTracking = true, // Spectra EIS modifies crop region
                hardwareDepthStream = family.contains("gen3") || family.contains("gen2"),
                hardwareMfnrAvailable = true,
                // Spectra factory-calibrated noise model is the most accurate
                sensorNoiseProfileTrusted = true,
                hardwareHistogramAvailable = false,
            ),
        )

        private fun buildExynosProfile(family: String) = SoCProfile(
            vendor = SoCVendor.SAMSUNG,
            family = family,
            modelName = "Samsung $family",
            gpuConfig = GpuComputeConfig(
                gpuArch = GpuArchitecture.RDNA,
                // RDNA wave64: workgroup sizes of 64 total threads
                preferredWorkgroupSize = 64,
                supportsFp16 = true,
                supportsShaderImageAtomicInt64 = true,
                usesSsboForAccumulation = false,
                preferredVulkanVersion = "1.3",
            ),
            npuConfig = NpuDelegateConfig(
                preferredDelegate = NpuDelegate.NNAPI,
                nnapiDeviceName = null,
                supportsInt4 = false,
                delegatePriority = listOf(NpuDelegate.NNAPI, NpuDelegate.GPU, NpuDelegate.CPU),
                useNeuroPilotSdk = false,
                compilationCachingEnabled = true,
            ),
            ispConfig = IspIntegrationConfig(
                ispName = "Exynos ISP",
                supportsYuvReprocessing = true,
                checkHalPreAppliedProcessing = false,
                hardwareFaceDetectionAvailable = true,
                hardwareAwbConflict = false,
                checkDistortionPreCorrection = false,
                eisCropRegionTracking = false,
                hardwareDepthStream = false,
                hardwareMfnrAvailable = true,
                sensorNoiseProfileTrusted = true,
                hardwareHistogramAvailable = true,
            ),
        )

        private fun buildGenericProfile(hw: String) = SoCProfile(
            vendor = SoCVendor.UNKNOWN,
            family = "generic",
            modelName = "Unknown ($hw)",
            gpuConfig = GpuComputeConfig(
                gpuArch = GpuArchitecture.UNKNOWN,
                preferredWorkgroupSize = 64,
                supportsFp16 = false,
                supportsShaderImageAtomicInt64 = false,
                usesSsboForAccumulation = true,
                preferredVulkanVersion = "1.0",
            ),
            npuConfig = NpuDelegateConfig(
                preferredDelegate = NpuDelegate.CPU,
                nnapiDeviceName = null,
                supportsInt4 = false,
                delegatePriority = listOf(NpuDelegate.CPU),
                useNeuroPilotSdk = false,
                compilationCachingEnabled = false,
            ),
            ispConfig = IspIntegrationConfig(
                ispName = "Generic",
                supportsYuvReprocessing = false,
                checkHalPreAppliedProcessing = true,
                hardwareFaceDetectionAvailable = false,
                hardwareAwbConflict = false,
                checkDistortionPreCorrection = true,
                eisCropRegionTracking = false,
                hardwareDepthStream = false,
                hardwareMfnrAvailable = false,
                sensorNoiseProfileTrusted = false,
                hardwareHistogramAvailable = false,
            ),
        )
    }
}

enum class SoCVendor { QUALCOMM, MEDIATEK, SAMSUNG, UNKNOWN }

enum class GpuArchitecture { ADRENO, MALI, RDNA, UNKNOWN }

enum class NpuDelegate { HEXAGON, MTK_APU, NNAPI, GPU, CPU }

/**
 * GPU compute dispatch configuration derived from SoC detection.
 *
 * @param gpuArch                      Detected GPU architecture
 * @param preferredWorkgroupSize       Optimal workgroup total thread count
 * @param supportsFp16                 VK_KHR_shader_float16_int8 available
 * @param supportsShaderImageAtomicInt64 VK_EXT_shader_image_atomic_int64 available
 * @param usesSsboForAccumulation      Use SSBOs instead of image atomics (Mali)
 * @param preferredVulkanVersion       Minimum Vulkan version to request
 */
data class GpuComputeConfig(
    val gpuArch: GpuArchitecture,
    val preferredWorkgroupSize: Int,
    val supportsFp16: Boolean,
    val supportsShaderImageAtomicInt64: Boolean,
    val usesSsboForAccumulation: Boolean,
    val preferredVulkanVersion: String,
)

/**
 * NPU/DSP delegate routing configuration.
 *
 * @param preferredDelegate      Primary delegate for INT8 inference
 * @param nnapiDeviceName        NNAPI device name filter (e.g., "mtk-apu")
 * @param supportsInt4           INT4 quantisation support (Dimensity 9000+)
 * @param delegatePriority       Ordered fallback list for delegate selection
 * @param useNeuroPilotSdk       Whether MediaTek NeuroPilot SDK is available
 * @param compilationCachingEnabled Whether to cache compiled models
 */
data class NpuDelegateConfig(
    val preferredDelegate: NpuDelegate,
    val nnapiDeviceName: String?,
    val supportsInt4: Boolean,
    val delegatePriority: List<NpuDelegate>,
    val useNeuroPilotSdk: Boolean,
    val compilationCachingEnabled: Boolean,
)

/**
 * ISP integration configuration for hardware-software pipeline cooperation.
 *
 * @param ispName                      Human-readable ISP name
 * @param supportsYuvReprocessing      Strategy B ZSL available
 * @param checkHalPreAppliedProcessing Check TotalCaptureResult for pre-applied stages
 * @param hardwareFaceDetectionAvailable STATISTICS_FACE_DETECT_MODE_FULL usable
 * @param hardwareAwbConflict          ISP AWB conflicts with HyperTone multi-modal
 * @param checkDistortionPreCorrection Check DISTORTION_CORRECTION_MODE before LensCorrectionSuite
 * @param eisCropRegionTracking        Read per-frame SCALER_CROP_REGION for EIS accounting
 * @param hardwareDepthStream          Hardware ToF depth via DEPTH_OUTPUT stream
 * @param hardwareMfnrAvailable        Hardware MFNR via NOISE_REDUCTION_MODE_HIGH_QUALITY
 * @param sensorNoiseProfileTrusted    SENSOR_NOISE_PROFILE from factory calibration
 * @param hardwareHistogramAvailable   STATISTICS_HISTOGRAM_MODE usable
 */
data class IspIntegrationConfig(
    val ispName: String,
    val supportsYuvReprocessing: Boolean,
    val checkHalPreAppliedProcessing: Boolean,
    val hardwareFaceDetectionAvailable: Boolean,
    val hardwareAwbConflict: Boolean,
    val checkDistortionPreCorrection: Boolean,
    val eisCropRegionTracking: Boolean,
    val hardwareDepthStream: Boolean,
    val hardwareMfnrAvailable: Boolean,
    val sensorNoiseProfileTrusted: Boolean,
    val hardwareHistogramAvailable: Boolean,
)
