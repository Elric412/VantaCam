package com.leica.cam.ui_components.camera

import kotlin.math.abs
import kotlin.math.hypot

private const val DEFAULT_LEVEL_TOLERANCE_DEGREES = 0.7f
private const val HISTOGRAM_BIN_COUNT = 256

/** All camera modes required by phase 9 mode switching UX. */
enum class CameraMode {
    AUTO,
    NIGHT,
    PORTRAIT,
    PRO,
    PANORAMA,
    MACRO,
    VIDEO,
    CINEMA,
    SLOW_MOTION,
    TIME_LAPSE,
    DOCUMENT,
}

/** Scene badge describing the dominant camera scene classification. */
data class SceneBadge(
    val label: String,
    val confidence: Float,
)

/** Face tracking rectangle in normalized [0,1] coordinates. */
data class FaceBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val confidence: Float,
) {
    init {
        require(left in 0f..1f && top in 0f..1f) { "Face origin must be normalized" }
        require(right in 0f..1f && bottom in 0f..1f) { "Face end must be normalized" }
        require(left <= right && top <= bottom) { "Invalid face box" }
        require(confidence in 0f..1f) { "Face confidence must be normalized" }
    }
}

/** Autofocus bracket in normalized [0,1] coordinates. */
data class AfBracket(
    val centerX: Float,
    val centerY: Float,
    val size: Float,
    val locked: Boolean,
)

/** Complete overlay state for the camera viewfinder. */
data class ViewfinderOverlayState(
    val afBracket: AfBracket,
    val faces: List<FaceBox>,
    val luminanceHistogram: IntArray,
    val shotQualityScore: Float,
    val horizonTiltDegrees: Float,
    val horizonLevelLocked: Boolean,
    val sceneBadge: SceneBadge,
)

/** Y plane frame used for histogram and quality estimation. */
data class LumaFrame(
    val width: Int,
    val height: Int,
    val yPlane: ByteArray,
) {
    init {
        require(width > 0 && height > 0) { "Frame dimensions must be positive" }
        require(yPlane.size >= width * height) { "Y plane does not contain enough pixels" }
    }
}

/** Gesture abstraction for camera interaction. */
sealed interface CameraGesture {
    data class Tap(val xNorm: Float, val yNorm: Float) : CameraGesture
    data class Pinch(val scaleFactor: Float) : CameraGesture
    data class HorizontalSwipe(val deltaNorm: Float) : CameraGesture
    data class VerticalSwipe(val deltaNorm: Float) : CameraGesture
    data object DoubleTap : CameraGesture
    data object LongPress : CameraGesture
}

/** Gesture action produced from normalized gesture input. */
sealed interface CameraGestureAction {
    data class FocusAt(val xNorm: Float, val yNorm: Float) : CameraGestureAction
    data class ZoomTo(val zoomRatio: Float) : CameraGestureAction
    data class SwitchModeRelative(val step: Int) : CameraGestureAction
    data class ExposureCompensationDelta(val deltaEv: Float) : CameraGestureAction
    data object SwitchLens : CameraGestureAction
    data object LockAeAfAwb : CameraGestureAction
    data object NoOp : CameraGestureAction
}

/** Stateless UI computation utilities for phase 9 overlays + gesture behavior. */
class Phase9UiStateCalculator(
    private val levelToleranceDegrees: Float = DEFAULT_LEVEL_TOLERANCE_DEGREES,
) {
    /**
     * Builds a full viewfinder overlay state from camera telemetry and analysis streams.
     */
    fun buildOverlayState(
        lumaFrame: LumaFrame,
        afBracket: AfBracket,
        faces: List<FaceBox>,
        shotQualityScore: Float,
        horizonTiltDegrees: Float,
        sceneBadge: SceneBadge,
    ): ViewfinderOverlayState {
        val clampedQuality = shotQualityScore.coerceIn(0f, 1f)
        val histogram = buildHistogram(lumaFrame)
        val horizonLocked = abs(horizonTiltDegrees) <= levelToleranceDegrees
        return ViewfinderOverlayState(
            afBracket = afBracket,
            faces = faces.sortedByDescending { it.confidence },
            luminanceHistogram = histogram,
            shotQualityScore = clampedQuality,
            horizonTiltDegrees = horizonTiltDegrees,
            horizonLevelLocked = horizonLocked,
            sceneBadge = sceneBadge.copy(confidence = sceneBadge.confidence.coerceIn(0f, 1f)),
        )
    }

    /** Maps a user gesture to an engine action with conservative fallbacks. */
    fun mapGesture(
        gesture: CameraGesture,
        currentZoomRatio: Float,
    ): CameraGestureAction {
        return when (gesture) {
            is CameraGesture.Tap -> CameraGestureAction.FocusAt(
                xNorm = gesture.xNorm.coerceIn(0f, 1f),
                yNorm = gesture.yNorm.coerceIn(0f, 1f),
            )

            is CameraGesture.Pinch -> {
                val safeScale = gesture.scaleFactor.coerceIn(0.5f, 2f)
                CameraGestureAction.ZoomTo((currentZoomRatio * safeScale).coerceIn(0.5f, 20f))
            }

            is CameraGesture.HorizontalSwipe -> {
                val step = when {
                    gesture.deltaNorm > 0.15f -> 1
                    gesture.deltaNorm < -0.15f -> -1
                    else -> 0
                }
                if (step == 0) CameraGestureAction.NoOp else CameraGestureAction.SwitchModeRelative(step)
            }

            is CameraGesture.VerticalSwipe -> {
                val delta = (-gesture.deltaNorm * 2f).coerceIn(-1f, 1f)
                if (abs(delta) < 0.1f) CameraGestureAction.NoOp else CameraGestureAction.ExposureCompensationDelta(delta)
            }

            CameraGesture.DoubleTap -> CameraGestureAction.SwitchLens
            CameraGesture.LongPress -> CameraGestureAction.LockAeAfAwb
        }
    }

    /** Returns a focus ring animation progress in [0,1] from elapsed animation time. */
    fun focusRingProgress(elapsedMs: Long, durationMs: Long = 220L): Float {
        if (durationMs <= 0L) return 1f
        val t = (elapsedMs.toFloat() / durationMs).coerceIn(0f, 1f)
        return 1f - (1f - t) * (1f - t)
    }

    /** Returns a normalized compositional guidance score based on face position. */
    fun compositionScore(faces: List<FaceBox>): Float {
        if (faces.isEmpty()) return 0.5f
        val weighted = faces.map { face ->
            val cx = (face.left + face.right) * 0.5f
            val cy = (face.top + face.bottom) * 0.5f
            val nearestThirdX = listOf(1f / 3f, 2f / 3f).minBy { abs(it - cx) }
            val nearestThirdY = listOf(1f / 3f, 2f / 3f).minBy { abs(it - cy) }
            val dist = hypot(cx - nearestThirdX, cy - nearestThirdY)
            val score = (1f - dist * 1.5f).coerceIn(0f, 1f)
            score * face.confidence
        }.sum()
        val confidenceSum = faces.sumOf { it.confidence.toDouble() }.toFloat().coerceAtLeast(1e-6f)
        return (weighted / confidenceSum).coerceIn(0f, 1f)
    }

    private fun buildHistogram(frame: LumaFrame): IntArray {
        val histogram = IntArray(HISTOGRAM_BIN_COUNT)
        val maxSize = frame.width * frame.height
        for (i in 0 until maxSize) {
            val sample = frame.yPlane[i].toInt() and 0xFF
            histogram[sample] += 1
        }
        return histogram
    }
}
