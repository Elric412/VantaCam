package com.leica.cam.sensor_hal.zsl

import java.time.Clock
import java.time.Instant
import java.util.ArrayDeque

/**
 * Fixed-capacity ring buffer used for zero-shutter-lag burst frame retention.
 */
class ZeroShutterLagRingBuffer<T>(
    private val capacity: Int,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val frames: ArrayDeque<BufferedFrame<T>> = ArrayDeque()

    init {
        require(capacity > 0) { "Capacity must be greater than zero" }
    }

    fun push(frame: T) {
        if (frames.size == capacity) {
            frames.removeFirst()
        }
        frames.addLast(BufferedFrame(payload = frame, timestamp = Instant.now(clock)))
    }

    fun snapshot(): List<BufferedFrame<T>> = frames.toList()

    fun latest(count: Int): List<BufferedFrame<T>> {
        val safeCount = count.coerceIn(0, frames.size)
        return frames.toList().takeLast(safeCount)
    }

    fun clear() {
        frames.clear()
    }
}

/** Frame payload with acquisition time metadata. */
data class BufferedFrame<T>(
    val payload: T,
    val timestamp: Instant,
)
