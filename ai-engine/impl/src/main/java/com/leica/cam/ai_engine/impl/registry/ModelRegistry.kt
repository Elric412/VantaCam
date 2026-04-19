package com.leica.cam.ai_engine.impl.registry

import java.io.File
import java.io.InputStream

/**
 * Unified model discovery, validation, format detection, and lifecycle management
 * for all AI model files in the /Model directory.
 *
 * At startup, scans the model directory, detects each file's format via magic-byte
 * fingerprinting, assigns a pipeline role, and logs the full catalogue at INFO level.
 *
 * Supported formats: TFLite (.tflite), MediaPipe Tasks (.task), ONNX (.onnx),
 * PyTorch Mobile (.ptl), and auto-detected binary formats (.bin, .weights, .model).
 *
 * @param modelDir Root directory containing model files (recursive scan)
 * @param logger   Logging callback for catalogue output
 */
class ModelRegistry(
    private val modelDir: File,
    private val logger: (level: LogLevel, tag: String, message: String) -> Unit =
        { _, _, msg -> println(msg) },
) {
    /**
     * Pipeline role that a model can fulfil in the LUMO imaging system.
     */
    enum class PipelineRole {
        SCENE_CLASSIFIER,
        FACE_DETECTOR,
        FACE_LANDMARKER,
        SEMANTIC_SEGMENTER,
        DEPTH_ESTIMATOR,
        SPECTRAL_RECONSTRUCTOR,
        NOISE_MODEL,
        SUPER_RESOLUTION,
        DENOISER,
        EXPOSURE_PREDICTOR,
        MICRO_ISP,
        IMAGE_CLASSIFIER,
        UNKNOWN,
    }

    /**
     * Detected model file format.
     */
    enum class ModelFormat {
        TFLITE,
        MEDIAPIPE_TASK,
        ONNX,
        PYTORCH_LITE,
        HDF5_KERAS,
        UNKNOWN,
    }

    enum class LogLevel { DEBUG, INFO, WARN, ERROR }

    /**
     * Single entry in the model catalogue.
     *
     * @param file       Absolute path to the model file
     * @param format     Detected file format
     * @param role       Assigned pipeline role
     * @param sizeBytes  File size in bytes
     * @param roleSource How the role was determined (filename, metadata, shape, unknown)
     */
    data class ModelEntry(
        val file: File,
        val format: ModelFormat,
        val role: PipelineRole,
        val sizeBytes: Long,
        val roleSource: String,
    )

    /**
     * Lazily built catalogue mapping pipeline roles to their best model entry.
     * When multiple models qualify for the same role, the first found wins.
     */
    val catalogue: Map<PipelineRole, ModelEntry> by lazy { scanAndAssign() }

    /**
     * All discovered models (including duplicates and UNKNOWN roles).
     */
    val allEntries: List<ModelEntry> by lazy { scanAll() }

    /**
     * Log the full catalogue at INFO level. Call at app startup.
     */
    fun logCatalogue() {
        logger(LogLevel.INFO, TAG, "═══ ModelRegistry Catalogue ═══")
        logger(LogLevel.INFO, TAG, "Model directory: ${modelDir.absolutePath}")
        logger(LogLevel.INFO, TAG, "Total models discovered: ${allEntries.size}")
        allEntries.forEach { entry ->
            logger(
                LogLevel.INFO, TAG,
                "  [${entry.role}] ${entry.file.name} " +
                    "(${entry.format}, ${entry.sizeBytes / 1024}KB, via ${entry.roleSource})",
            )
        }
        val assigned = catalogue.size
        val unknown = allEntries.count { it.role == PipelineRole.UNKNOWN }
        logger(LogLevel.INFO, TAG, "Assigned roles: $assigned, Unknown: $unknown")
        logger(LogLevel.INFO, TAG, "═══════════════════════════════")
    }

    // ─────────────────────────────────────────────────────────────────────
    // Format detection via magic bytes
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Detect model format by reading the first 16 bytes of the file header.
     *
     * Magic signatures:
     * - TFLite:  bytes [0x18, 0x00, 0x00, 0x00] or "TFL3" at offset 4
     * - ONNX:    bytes [0x08] at offset 0 (protobuf varint for field 1)
     * - HDF5:    bytes [0x89, 'H', 'D', 'F']
     * - PK zip:  bytes [0x50, 0x4B] (MediaPipe .task files are zip archives)
     * - PyTorch: pickle protocol header
     *
     * @param file Model file to inspect
     * @return Detected [ModelFormat]
     */
    fun detectModelFormat(file: File): ModelFormat {
        if (!file.exists() || file.length() < 16) return ModelFormat.UNKNOWN

        val header = ByteArray(16)
        file.inputStream().use { stream -> stream.read(header) }

        return when {
            // TFLite: check for "TFL3" at various offsets or flatbuffer signature
            isTfLiteHeader(header) -> ModelFormat.TFLITE
            // Extension-based TFLite detection
            file.extension.equals("tflite", ignoreCase = true) -> ModelFormat.TFLITE
            // PK zip header → MediaPipe .task bundle
            header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() -> ModelFormat.MEDIAPIPE_TASK
            // Extension-based .task detection
            file.extension.equals("task", ignoreCase = true) -> ModelFormat.MEDIAPIPE_TASK
            // HDF5 / Keras
            header[0] == 0x89.toByte() && header[1] == 'H'.code.toByte() &&
                header[2] == 'D'.code.toByte() && header[3] == 'F'.code.toByte() -> ModelFormat.HDF5_KERAS
            // ONNX: protobuf with "onnx" in the file or .onnx extension
            file.extension.equals("onnx", ignoreCase = true) -> ModelFormat.ONNX
            // PyTorch: .ptl extension or pickle header
            file.extension.equals("ptl", ignoreCase = true) -> ModelFormat.PYTORCH_LITE
            isPytorchPickle(header) -> ModelFormat.PYTORCH_LITE
            else -> {
                // Log unknown format with hex dump for debugging
                val hexDump = header.joinToString(" ") { "%02X".format(it) }
                logger(
                    LogLevel.WARN, TAG,
                    "Unknown model format: ${file.name} (${file.length()} bytes), " +
                        "header: $hexDump",
                )
                ModelFormat.UNKNOWN
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Pipeline role assignment
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Assign a pipeline role to a model using priority order:
     * 1. Filename keyword matching
     * 2. Parent directory name keyword matching
     * 3. File extension + size heuristic
     * 4. UNKNOWN with developer warning
     *
     * @param file   Model file
     * @param format Detected format
     * @return Pair of (role, source description)
     */
    fun assignPipelineRole(file: File, format: ModelFormat): Pair<PipelineRole, String> {
        // Priority 1: filename keywords
        val nameRole = matchFilenameKeywords(file.nameWithoutExtension.lowercase())
        if (nameRole != null) return nameRole to "filename"

        // Priority 2: parent directory name
        val dirRole = matchFilenameKeywords(file.parentFile?.name?.lowercase() ?: "")
        if (dirRole != null) return dirRole to "directory"

        // Priority 3: format + size heuristic
        val sizeRole = matchBySizeHeuristic(format, file.length())
        if (sizeRole != null) return sizeRole to "size_heuristic"

        // Priority 4: unknown
        logger(
            LogLevel.WARN, TAG,
            "Cannot determine pipeline role for: ${file.name} " +
                "(format=$format, size=${file.length()} bytes)",
        )
        return PipelineRole.UNKNOWN to "unmatched"
    }

    // ─────────────────────────────────────────────────────────────────────
    // Internal implementation
    // ─────────────────────────────────────────────────────────────────────

    private fun scanAll(): List<ModelEntry> {
        if (!modelDir.exists() || !modelDir.isDirectory) {
            logger(LogLevel.WARN, TAG, "Model directory not found: ${modelDir.absolutePath}")
            return emptyList()
        }

        val modelExtensions = setOf("tflite", "task", "onnx", "ptl", "bin", "weights", "model", "pb")
        return modelDir.walkTopDown()
            .filter { it.isFile }
            .filter { file ->
                file.extension.lowercase() in modelExtensions ||
                    file.extension.isEmpty() ||
                    file.length() > 100_000 // Large files without extension might be models
            }
            .filter { !it.name.equals("Temp", ignoreCase = true) }
            .map { file ->
                val format = detectModelFormat(file)
                val (role, source) = assignPipelineRole(file, format)
                ModelEntry(
                    file = file,
                    format = format,
                    role = role,
                    sizeBytes = file.length(),
                    roleSource = source,
                )
            }
            .toList()
    }

    private fun scanAndAssign(): Map<PipelineRole, ModelEntry> {
        val entries = allEntries
        val result = mutableMapOf<PipelineRole, ModelEntry>()
        for (entry in entries) {
            if (entry.role == PipelineRole.UNKNOWN) continue
            // First found wins; in production, compare accuracy metadata
            result.putIfAbsent(entry.role, entry)
        }
        return result
    }

    private fun matchFilenameKeywords(name: String): PipelineRole? = when {
        name.contains("face_land") || name.contains("faceland") ||
            name.contains("face_mesh") -> PipelineRole.FACE_LANDMARKER
        name.contains("face_detect") || name.contains("facedetect") ||
            name.contains("face detect") -> PipelineRole.FACE_DETECTOR
        name.contains("deeplab") || name.contains("segmen") ||
            name.contains("scene_understand") || name.contains("scene understanding") -> PipelineRole.SEMANTIC_SEGMENTER
        name.contains("depth") || name.contains("midas") -> PipelineRole.DEPTH_ESTIMATOR
        name.contains("scene_class") || name.contains("scene class") ||
            name.contains("image_class") || name.contains("image class") ||
            name.contains("classifier") -> PipelineRole.SCENE_CLASSIFIER
        name.contains("denois") || name.contains("noise") -> PipelineRole.DENOISER
        name.contains("super_res") || name.contains("superres") ||
            name.contains("sr_") || name.contains("upscal") -> PipelineRole.SUPER_RESOLUTION
        name.contains("spectral") -> PipelineRole.SPECTRAL_RECONSTRUCTOR
        name.contains("exposure") || name.contains("metering") -> PipelineRole.EXPOSURE_PREDICTOR
        name.contains("microisp") || name.contains("micro_isp") ||
            name.contains("isp") -> PipelineRole.MICRO_ISP
        name.contains("image classifier") -> PipelineRole.IMAGE_CLASSIFIER
        else -> null
    }

    private fun matchBySizeHeuristic(format: ModelFormat, sizeBytes: Long): PipelineRole? {
        // MediaPipe .task files are always specific task types
        if (format == ModelFormat.MEDIAPIPE_TASK && sizeBytes > 1_000_000) {
            return PipelineRole.FACE_LANDMARKER // Most common .task type
        }
        return null
    }

    private fun isTfLiteHeader(header: ByteArray): Boolean {
        // FlatBuffer file identifier "TFL3" at offset 4
        if (header.size >= 8 &&
            header[4] == 'T'.code.toByte() &&
            header[5] == 'F'.code.toByte() &&
            header[6] == 'L'.code.toByte() &&
            header[7] == '3'.code.toByte()
        ) return true
        // Alternative: check for FlatBuffer size prefix
        if (header.size >= 4 && header[0].toInt() and 0xFF < 0x20 &&
            header[1] == 0x00.toByte() && header[2] == 0x00.toByte() && header[3] == 0x00.toByte()
        ) return true
        return false
    }

    private fun isPytorchPickle(header: ByteArray): Boolean {
        // PyTorch uses Python pickle protocol; check for pickle opcode 0x80
        return header.size >= 2 && header[0] == 0x80.toByte() && header[1].toInt() in 2..5
    }

    companion object {
        private const val TAG = "ModelRegistry"
    }
}
