package com.leica.cam.hypertone_wb.pipeline

import java.util.ArrayDeque

class WbTemporalMemory(
    private val memoryStore: WbMemoryStore,
    private val maxHistory: Int = 8,
    private val memoryBlendFactor: Float = 0.25f,
    private val maxMemoryAgeMillis: Long = 2L * 60L * 60L * 1000L,
    private val kalmanQ: Float = 0.01f,
    private val kalmanR: Float = 0.05f,
) {
    private var smoothedCct: Float? = null
    private var smoothedTint: Float? = null
    private var kalmanP: Float = 1.0f

    fun applySceneMemoryBlend(cctKelvin: Float, tint: Float, sceneContext: SceneContext?): Pair<Float, Float> {
        if (sceneContext == null) return cctKelvin to tint
        val sceneHash = computeSceneHash(sceneContext)
        val match = memoryStore
            .loadRecent(maxHistory)
            .firstOrNull { it.sceneHash == sceneHash && (sceneContext.timestampMillis - it.timestampMillis) in 0..maxMemoryAgeMillis }
            ?: return cctKelvin to tint

        val blendedCct = cctKelvin * (1f - memoryBlendFactor) + match.cctKelvin * memoryBlendFactor
        val blendedTint = tint * (1f - memoryBlendFactor) + match.tint * memoryBlendFactor
        return blendedCct to blendedTint
    }

    fun stabilizeForVideo(cctKelvin: Float, tint: Float): Pair<Float, Float> {
        val previousCct = smoothedCct
        val previousTint = smoothedTint
        if (previousCct == null || previousTint == null) {
            smoothedCct = cctKelvin
            smoothedTint = tint
            return cctKelvin to tint
        }

        // Exponential Kalman filter: Q=0.01, R=0.05
        kalmanP = kalmanP + kalmanQ
        val kalmanK = kalmanP / (kalmanP + kalmanR)
        val nextCct = previousCct + kalmanK * (cctKelvin - previousCct)
        val nextTint = previousTint + kalmanK * (tint - previousTint)
        kalmanP = (1f - kalmanK) * kalmanP

        smoothedCct = nextCct
        smoothedTint = nextTint
        return nextCct to nextTint
    }

    fun persistEstimate(cctKelvin: Float, tint: Float, sceneContext: SceneContext?) {
        if (sceneContext == null) return
        val estimate = StoredWbEstimate(
            cctKelvin = cctKelvin,
            tint = tint,
            sceneHash = computeSceneHash(sceneContext),
            timestampMillis = sceneContext.timestampMillis,
        )
        memoryStore.save(estimate)
    }

    fun computeSceneHash(sceneContext: SceneContext): String =
        "${sceneContext.sceneCategory.lowercase()}|${sceneContext.hourBucket}|${sceneContext.locationGeohash}"

    fun reset() {
        smoothedCct = null
        smoothedTint = null
        kalmanP = 1.0f
    }
}

class InMemoryWbMemoryStore : WbMemoryStore {
    private val estimates = ArrayDeque<StoredWbEstimate>()
    private val maxSize = 8

    override fun loadRecent(limit: Int): List<StoredWbEstimate> = estimates.take(limit)
    override fun save(estimate: StoredWbEstimate) {
        estimates.removeAll { it.sceneHash == estimate.sceneHash }
        estimates.addFirst(estimate)
        while (estimates.size > maxSize) estimates.removeLast()
    }
}
