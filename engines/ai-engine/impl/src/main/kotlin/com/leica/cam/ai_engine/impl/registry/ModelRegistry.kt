package com.leica.cam.ai_engine.impl.registry

import android.content.res.AssetManager
import com.leica.cam.ai_engine.impl.runtime.DelegatePicker
import com.leica.cam.ai_engine.impl.runtime.LiteRtSession
import com.leica.cam.common.result.LeicaResult
import com.leica.cam.common.result.PipelineStage
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Unified model discovery, validation, format detection, and lifecycle management
 * for all AI model files.
 */
class ModelRegistry internal constructor(
    private val modelDirOrNull: File? = null,
    private val assetManagerOrNull: AssetManager? = null,
    private val logger: (level: LogLevel, tag: String, message: String) -> Unit = { _, _, msg -> println(msg) },
) {
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
        AUTO_WHITE_BALANCE,
        UNKNOWN,
    }

    enum class ModelFormat {
        TFLITE,
        MEDIAPIPE_TASK,
        ONNX,
        PYTORCH_LITE,
        HDF5_KERAS,
        UNKNOWN,
    }

    enum class LogLevel { DEBUG, INFO, WARN, ERROR }

    data class ModelEntry(
        val assetPath: String,
        val fileOrNull: File?,
        val format: ModelFormat,
        val role: PipelineRole,
        val sizeBytes: Long,
        val roleSource: String,
    ) {
        val displayName: String get() = fileOrNull?.name ?: assetPath.substringAfterLast('/')
    }

    val catalogue: Map<PipelineRole, ModelEntry> by lazy { scanAndAssign() }
    val allEntries: List<ModelEntry> by lazy { scanAll() }

    fun logCatalogue() {
        logger(LogLevel.INFO, TAG, "═══ ModelRegistry Catalogue ═══")
        logger(
            LogLevel.INFO,
            TAG,
            when {
                assetManagerOrNull != null -> "Model asset root: $MODELS_ROOT"
                modelDirOrNull != null -> "Model directory: ${modelDirOrNull.absolutePath}"
                else -> "Model source unavailable"
            },
        )
        logger(LogLevel.INFO, TAG, "Total models discovered: ${allEntries.size}")
        allEntries.forEach { entry ->
            logger(
                LogLevel.INFO,
                TAG,
                "  [${entry.role}] ${entry.displayName} (${entry.format}, ${entry.sizeBytes / 1024}KB, via ${entry.roleSource})",
            )
        }
        val assigned = catalogue.size
        val unknown = allEntries.count { it.role == PipelineRole.UNKNOWN }
        logger(LogLevel.INFO, TAG, "Assigned roles: $assigned, Unknown: $unknown")
        logger(LogLevel.INFO, TAG, "═══════════════════════════════")
    }

    fun detectModelFormat(file: File): ModelFormat {
        if (!file.exists() || !file.isFile || file.length() < 4) {
            return ModelFormat.UNKNOWN
        }
        val header = file.inputStream().use { readHeader(it) }
        return detectModelFormat(file.name, header)
    }

    fun assignPipelineRole(file: File, format: ModelFormat): Pair<PipelineRole, String> =
        assignPipelineRole(
            name = file.nameWithoutExtension.lowercase(),
            parentName = file.parentFile?.name?.lowercase().orEmpty(),
            format = format,
            sizeBytes = file.length(),
        )

    fun loadBytesForRole(
        role: PipelineRole,
        assetBytes: (path: String) -> ByteBuffer,
    ): ByteBuffer? {
        val entry = catalogue[role] ?: return null
        return if (assetManagerOrNull != null) {
            assetBytes(entry.assetPath)
        } else {
            entry.fileOrNull?.let { file ->
                val bytes = file.readBytes()
                ByteBuffer.allocateDirect(bytes.size)
                    .order(ByteOrder.nativeOrder())
                    .apply {
                        put(bytes)
                        rewind()
                    }
            }
        }
    }

    fun openSession(
        role: PipelineRole,
        assetBytes: (path: String) -> ByteBuffer,
        priority: List<LiteRtSession.DelegateKind> = DelegatePicker.priorityForCurrentDevice(),
    ): LeicaResult<LiteRtSession> {
        val buffer = loadBytesForRole(role, assetBytes)
            ?: return LeicaResult.Failure.Pipeline(
                PipelineStage.AI_ENGINE,
                "No model asset found for role $role",
            )
        return LiteRtSession.open(buffer, priority)
    }

    suspend fun warmUpAll(
        assetBytes: (path: String) -> ByteBuffer,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ): Int = withContext(dispatcher) {
        var warmed = 0
        for (role in catalogue.keys) {
            val inputSize = warmUpInputSize(role)
            val outputSize = warmUpOutputSize(role)
            if (inputSize == 0 || outputSize == 0) {
                logger(LogLevel.DEBUG, TAG, "Skipping warm-up for $role (no LiteRT tensor buffers required)")
                continue
            }

            when (val sessionResult = openSession(role, assetBytes)) {
                is LeicaResult.Success -> {
                    val input = ByteBuffer.allocateDirect(inputSize).order(ByteOrder.nativeOrder())
                    val output = ByteBuffer.allocateDirect(outputSize).order(ByteOrder.nativeOrder())
                    val runResult = sessionResult.value.run(input, output)
                    sessionResult.value.close()
                    when (runResult) {
                        is LeicaResult.Success -> warmed++
                        is LeicaResult.Failure -> logger(
                            LogLevel.WARN,
                            TAG,
                            "Warm-up inference failed for role $role: ${runResult.message}",
                        )
                    }
                }
                is LeicaResult.Failure -> logger(
                    LogLevel.WARN,
                    TAG,
                    "Warm-up failed for role $role: ${sessionResult.message}",
                )
            }
        }
        logger(LogLevel.INFO, TAG, "Model warm-up complete: $warmed / ${catalogue.size} models ready")
        warmed
    }

    private fun scanAll(): List<ModelEntry> = when {
        assetManagerOrNull != null -> scanAllFromAssets(assetManagerOrNull)
        modelDirOrNull != null -> scanAllFromDirectory(modelDirOrNull)
        else -> emptyList()
    }

    private fun scanAllFromAssets(assetManager: AssetManager): List<ModelEntry> =
        listAssetsRecursive(assetManager, MODELS_ROOT)
            .filter(::isSupportedAssetPath)
            .map { assetPath ->
                val header = assetManager.open(assetPath).use { readHeader(it) }
                val format = detectModelFormat(assetPath.substringAfterLast('/'), header)
                val fileName = assetPath.substringAfterLast('/').substringBeforeLast('.')
                val parentName = assetPath.substringBeforeLast('/').substringAfterLast('/').lowercase()
                val (role, source) = assignPipelineRole(
                    name = fileName.lowercase(),
                    parentName = parentName,
                    format = format,
                    sizeBytes = assetSize(assetManager, assetPath),
                )
                ModelEntry(
                    assetPath = assetPath,
                    fileOrNull = null,
                    format = format,
                    role = role,
                    sizeBytes = assetSize(assetManager, assetPath),
                    roleSource = source,
                )
            }

    private fun scanAllFromDirectory(modelDir: File): List<ModelEntry> {
        if (!modelDir.exists() || !modelDir.isDirectory) {
            logger(LogLevel.WARN, TAG, "Model directory not found: ${modelDir.absolutePath}")
            return emptyList()
        }

        val allowedExtensions = setOf("tflite", "task", "onnx", "ptl", "bin", "weights", "model", "pb")
        return modelDir.walkTopDown()
            .filter { it.isFile }
            .filterNot { it.absolutePath.contains("${File.separator}Temp${File.separator}") }
            .filter { file ->
                val extension = file.extension.lowercase()
                extension in allowedExtensions || (extension.isEmpty() && file.length() > 100_000)
            }
            .map { file ->
                val format = detectModelFormat(file)
                val (role, source) = assignPipelineRole(file, format)
                ModelEntry(
                    assetPath = "$MODELS_ROOT/${file.relativeTo(modelDir).path.replace(File.separatorChar, '/')}",
                    fileOrNull = file,
                    format = format,
                    role = role,
                    sizeBytes = file.length(),
                    roleSource = source,
                )
            }
            .toList()
    }

    private fun scanAndAssign(): Map<PipelineRole, ModelEntry> {
        val result = linkedMapOf<PipelineRole, ModelEntry>()
        for (entry in allEntries) {
            if (entry.role == PipelineRole.UNKNOWN) continue
            result.putIfAbsent(entry.role, entry)
        }
        return result
    }

    private fun detectModelFormat(name: String, header: ByteArray): ModelFormat {
        val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return when {
            isTfLiteHeader(header) || extension == "tflite" -> ModelFormat.TFLITE
            isZipHeader(header) || extension == "task" -> ModelFormat.MEDIAPIPE_TASK
            extension == "onnx" -> ModelFormat.ONNX
            extension == "ptl" || isPytorchPickle(header) -> ModelFormat.PYTORCH_LITE
            isHdf5Header(header) -> ModelFormat.HDF5_KERAS
            else -> ModelFormat.UNKNOWN
        }
    }

    private fun assignPipelineRole(
        name: String,
        parentName: String,
        format: ModelFormat,
        sizeBytes: Long,
    ): Pair<PipelineRole, String> {
        val nameRole = matchFilenameKeywords(name)
        if (nameRole != null) return nameRole to "filename"

        val directoryRole = matchFilenameKeywords(parentName)
        if (directoryRole != null) return directoryRole to "directory"

        val sizeRole = matchBySizeHeuristic(format, sizeBytes)
        if (sizeRole != null) return sizeRole to "size_heuristic"

        logger(
            LogLevel.WARN,
            TAG,
            "Cannot determine pipeline role for: $name (format=$format, size=$sizeBytes bytes)",
        )
        return PipelineRole.UNKNOWN to "unmatched"
    }

    private fun matchFilenameKeywords(name: String): PipelineRole? = when {
        name.contains("face_land") || name.contains("faceland") || name.contains("face_mesh") -> PipelineRole.FACE_LANDMARKER
        name.contains("face_detect") || name.contains("facedetect") || name.contains("face detect") -> PipelineRole.FACE_DETECTOR
        name.contains("deeplab") || name.contains("segmen") || name.contains("scene_understand") || name.contains("scene understanding") -> PipelineRole.SEMANTIC_SEGMENTER
        name.contains("depth") || name.contains("midas") -> PipelineRole.DEPTH_ESTIMATOR
        name.contains("image_class") || name.contains("image class") || name.contains("image classifier") -> PipelineRole.IMAGE_CLASSIFIER
        name.contains("scene_class") || name.contains("scene class") || name.contains("classifier") -> PipelineRole.SCENE_CLASSIFIER
        name.contains("denois") || name.contains("noise") -> PipelineRole.DENOISER
        name.contains("super_res") || name.contains("superres") || name.contains("sr_") || name.contains("upscal") -> PipelineRole.SUPER_RESOLUTION
        name.contains("spectral") -> PipelineRole.SPECTRAL_RECONSTRUCTOR
        name.contains("exposure") || name.contains("metering") -> PipelineRole.EXPOSURE_PREDICTOR
        name.contains("microisp") || name.contains("micro_isp") || name.contains("neural_isp") -> PipelineRole.MICRO_ISP
        name.contains("awb") || name.contains("white_balance") || name.contains("white balance") -> PipelineRole.AUTO_WHITE_BALANCE
        else -> null
    }

    private fun matchBySizeHeuristic(format: ModelFormat, sizeBytes: Long): PipelineRole? {
        if (format == ModelFormat.MEDIAPIPE_TASK && sizeBytes > 1_000_000) {
            return PipelineRole.FACE_LANDMARKER
        }
        return null
    }

    private fun assetSize(assetManager: AssetManager, assetPath: String): Long =
        runCatching { assetManager.openFd(assetPath).length }
            .getOrElse { assetManager.open(assetPath).use { it.available().toLong() } }

    private fun isSupportedAssetPath(assetPath: String): Boolean {
        val lower = assetPath.lowercase()
        if (lower.contains("/temp/") || lower.endsWith("/temp")) return false
        val extension = lower.substringAfterLast('.', missingDelimiterValue = "")
        return extension in setOf("tflite", "task", "onnx", "ptl", "bin", "weights", "model", "pb")
    }

    private fun readHeader(stream: InputStream): ByteArray {
        val header = ByteArray(16)
        val read = stream.read(header)
        return if (read <= 0) ByteArray(0) else header.copyOf(read)
    }

    private fun isTfLiteHeader(header: ByteArray): Boolean =
        header.size >= 8 &&
            header[4] == 'T'.code.toByte() &&
            header[5] == 'F'.code.toByte() &&
            header[6] == 'L'.code.toByte() &&
            header[7] == '3'.code.toByte()

    private fun isZipHeader(header: ByteArray): Boolean =
        header.size >= 2 &&
            header[0] == 0x50.toByte() &&
            header[1] == 0x4B.toByte()

    private fun isHdf5Header(header: ByteArray): Boolean =
        header.size >= 4 &&
            header[0] == 0x89.toByte() &&
            header[1] == 'H'.code.toByte() &&
            header[2] == 'D'.code.toByte() &&
            header[3] == 'F'.code.toByte()

    private fun isPytorchPickle(header: ByteArray): Boolean =
        header.size >= 2 && header[0] == 0x80.toByte() && header[1].toInt() in 2..5

    private fun warmUpInputSize(role: PipelineRole): Int = when (role) {
        PipelineRole.AUTO_WHITE_BALANCE -> 224 * 224 * 3 * 4
        PipelineRole.SCENE_CLASSIFIER, PipelineRole.IMAGE_CLASSIFIER -> 224 * 224 * 3 * 4
        PipelineRole.SEMANTIC_SEGMENTER -> 257 * 257 * 3 * 4
        PipelineRole.MICRO_ISP -> 256 * 256 * 4 * 4
        PipelineRole.FACE_LANDMARKER -> 0
        else -> 0
    }

    private fun warmUpOutputSize(role: PipelineRole): Int = when (role) {
        PipelineRole.AUTO_WHITE_BALANCE -> 3 * 4
        PipelineRole.SCENE_CLASSIFIER, PipelineRole.IMAGE_CLASSIFIER -> 1000 * 4
        PipelineRole.SEMANTIC_SEGMENTER -> 257 * 257 * 21 * 4
        PipelineRole.MICRO_ISP -> 256 * 256 * 4 * 4
        else -> 0
    }

    companion object {
        private const val TAG = "ModelRegistry"
        private const val MODELS_ROOT = "models"

        fun fromAssets(
            assetManager: AssetManager,
            logger: (level: LogLevel, tag: String, message: String) -> Unit = { _, _, message -> println(message) },
        ): ModelRegistry = ModelRegistry(
            modelDirOrNull = null,
            assetManagerOrNull = assetManager,
            logger = logger,
        )

        private fun listAssetsRecursive(assetManager: AssetManager, dir: String): List<String> {
            val children = assetManager.list(dir) ?: return emptyList()
            if (children.isEmpty()) {
                return listOf(dir)
            }
            return children.flatMap { child ->
                val full = if (dir.isEmpty()) child else "$dir/$child"
                listAssetsRecursive(assetManager, full)
            }
        }
    }
}
