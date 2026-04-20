package com.leica.cam.feature.camera.ui

import com.leica.cam.common.result.LeicaResult
import com.leica.cam.common.result.PipelineStage
import com.leica.cam.ui_components.camera.CameraGesture
import com.leica.cam.ui_components.camera.CameraGestureAction
import com.leica.cam.ui_components.camera.CameraMode
import com.leica.cam.ui_components.camera.Phase9UiStateCalculator

private const val MAX_TOOL_INTENSITY = 1f
private const val MIN_TOOL_INTENSITY = -1f

/**
 * One post-capture adjustment tool.
 *
 * `isGpuAccelerated=true` marks tools expected to run in the accelerated edit graph.
 */
data class EditTool(
    val id: String,
    val label: String,
    val isGpuAccelerated: Boolean,
    val defaultIntensity: Float = 0f,
)

/** Catalog of phase-9 edit tools (40+). */
object PostCaptureEditToolCatalog {
    val allTools: List<EditTool> = listOf(
        EditTool("exposure", "Exposure", true),
        EditTool("contrast", "Contrast", true),
        EditTool("highlights", "Highlights", true),
        EditTool("shadows", "Shadows", true),
        EditTool("whites", "Whites", true),
        EditTool("blacks", "Blacks", true),
        EditTool("temperature", "Temperature", true),
        EditTool("tint", "Tint", true),
        EditTool("vibrance", "Vibrance", true),
        EditTool("saturation", "Saturation", true),
        EditTool("clarity", "Clarity", true),
        EditTool("texture", "Texture", true),
        EditTool("dehaze", "Dehaze", true),
        EditTool("sharpness", "Sharpness", true),
        EditTool("noise_reduction", "Noise Reduction", true),
        EditTool("luminance_nr", "Luminance NR", true),
        EditTool("color_nr", "Color NR", true),
        EditTool("vignette", "Vignette", true),
        EditTool("grain", "Film Grain", true),
        EditTool("split_tone", "Split Tone", true),
        EditTool("hsl_red", "HSL Red", true),
        EditTool("hsl_orange", "HSL Orange", true),
        EditTool("hsl_yellow", "HSL Yellow", true),
        EditTool("hsl_green", "HSL Green", true),
        EditTool("hsl_aqua", "HSL Aqua", true),
        EditTool("hsl_blue", "HSL Blue", true),
        EditTool("hsl_purple", "HSL Purple", true),
        EditTool("hsl_magenta", "HSL Magenta", true),
        EditTool("curve_rgb", "Tone Curve RGB", true),
        EditTool("curve_red", "Tone Curve Red", true),
        EditTool("curve_green", "Tone Curve Green", true),
        EditTool("curve_blue", "Tone Curve Blue", true),
        EditTool("lens_distortion", "Lens Distortion", true),
        EditTool("chromatic_aberration", "Chromatic Aberration", true),
        EditTool("perspective_vertical", "Perspective Vertical", true),
        EditTool("perspective_horizontal", "Perspective Horizontal", true),
        EditTool("rotate", "Rotate", true),
        EditTool("crop", "Crop", true),
        EditTool("heal", "Heal", true),
        EditTool("selective_mask", "Selective Mask", true),
        EditTool("subject_mask", "Subject Mask", true),
        EditTool("sky_mask", "Sky Mask", true),
    )
}

/** User-controlled state for one editing session. */
data class EditSessionState(
    val selectedToolId: String,
    val toolIntensities: Map<String, Float>,
    val historyDepth: Int,
    val canUndo: Boolean,
    val canRedo: Boolean,
)

/** Mode switching behavior with deterministic ordering. */
class CameraModeSwitcher(
    private val availableModes: List<CameraMode>,
    initialMode: CameraMode = CameraMode.AUTO,
) {
    private var currentIndex: Int = availableModes.indexOf(initialMode).coerceAtLeast(0)

    init {
        require(availableModes.isNotEmpty()) { "At least one camera mode must be available" }
    }

    fun currentMode(): CameraMode = availableModes[currentIndex]

    fun switchRelative(step: Int): CameraMode {
        if (step == 0) return currentMode()
        val nextIndex = (currentIndex + step).mod(availableModes.size)
        currentIndex = nextIndex
        return availableModes[currentIndex]
    }

    fun setMode(mode: CameraMode): LeicaResult<CameraMode> {
        val index = availableModes.indexOf(mode)
        return if (index >= 0) {
            currentIndex = index
            LeicaResult.Success(mode)
        } else {
            LeicaResult.Failure.Pipeline(PipelineStage.SMART_IMAGING, "Requested mode is not available on this device: $mode")
        }
    }
}

/**
 * Enterprise-friendly PRO controls that clamp and validate values before emitting requests.
 */
class ProModeController(
    isoRange: IntRange = 50..6400,
    shutterUsRange: LongRange = 125L..30_000_000L,
    whiteBalanceKelvinRange: IntRange = 2000..12000,
    focusDistanceRange: ClosedFloatingPointRange<Float> = 0f..1f,
) {
    private val safeIsoRange = isoRange
    private val safeShutterRange = shutterUsRange
    private val safeWhiteBalanceRange = whiteBalanceKelvinRange
    private val safeFocusRange = focusDistanceRange

    fun buildManualRequest(
        iso: Int,
        shutterUs: Long,
        whiteBalanceKelvin: Int,
        focusDistanceNorm: Float,
        exposureCompensationEv: Float,
    ): ProCaptureRequest {
        return ProCaptureRequest(
            iso = iso.coerceIn(safeIsoRange),
            shutterUs = shutterUs.coerceIn(safeShutterRange),
            whiteBalanceKelvin = whiteBalanceKelvin.coerceIn(safeWhiteBalanceRange),
            focusDistanceNorm = focusDistanceNorm.coerceIn(safeFocusRange.start, safeFocusRange.endInclusive),
            exposureCompensationEv = exposureCompensationEv.coerceIn(-5f, 5f),
        )
    }
}

/** Manual capture request payload emitted by PRO mode panel. */
data class ProCaptureRequest(
    val iso: Int,
    val shutterUs: Long,
    val whiteBalanceKelvin: Int,
    val focusDistanceNorm: Float,
    val exposureCompensationEv: Float,
)

/** Post-capture edit engine state reducer with bounded adjustments. */
class PostCaptureEditor(
    private val tools: List<EditTool> = PostCaptureEditToolCatalog.allTools,
) {
    private val toolById: Map<String, EditTool> = tools.associateBy { it.id }

    init {
        require(tools.size >= 40) { "Phase 9 requires at least 40 editing tools" }
    }

    fun startSession(): EditSessionState {
        val defaultTool = tools.first().id
        return EditSessionState(
            selectedToolId = defaultTool,
            toolIntensities = tools.associate { it.id to it.defaultIntensity },
            historyDepth = 0,
            canUndo = false,
            canRedo = false,
        )
    }

    fun selectTool(state: EditSessionState, toolId: String): LeicaResult<EditSessionState> {
        if (!toolById.containsKey(toolId)) {
            return LeicaResult.Failure.Pipeline(PipelineStage.SMART_IMAGING, "Unknown editing tool: $toolId")
        }
        return LeicaResult.Success(state.copy(selectedToolId = toolId))
    }

    fun updateIntensity(state: EditSessionState, toolId: String, intensity: Float): LeicaResult<EditSessionState> {
        if (!toolById.containsKey(toolId)) {
            return LeicaResult.Failure.Pipeline(PipelineStage.SMART_IMAGING, "Unknown editing tool: $toolId")
        }
        val clamped = intensity.coerceIn(MIN_TOOL_INTENSITY, MAX_TOOL_INTENSITY)
        val nextMap = state.toolIntensities.toMutableMap().apply { put(toolId, clamped) }
        return LeicaResult.Success(
            state.copy(
                toolIntensities = nextMap,
                historyDepth = state.historyDepth + 1,
                canUndo = true,
                canRedo = false,
            ),
        )
    }
}

/** High-level orchestrator coordinating overlay gestures + mode switching. */
class CameraUiOrchestrator(
    private val uiStateCalculator: Phase9UiStateCalculator,
    private val modeSwitcher: CameraModeSwitcher,
) {
    fun handleGesture(
        gesture: CameraGesture,
        currentZoomRatio: Float,
    ): LeicaResult<CameraMode> {
        return when (val action = uiStateCalculator.mapGesture(gesture, currentZoomRatio)) {
            is CameraGestureAction.SwitchModeRelative -> LeicaResult.Success(modeSwitcher.switchRelative(action.step))
            else -> LeicaResult.Success(modeSwitcher.currentMode())
        }
    }
}
