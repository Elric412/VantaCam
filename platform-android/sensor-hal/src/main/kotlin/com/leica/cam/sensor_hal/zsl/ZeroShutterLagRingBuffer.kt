package com.leica.cam.sensor_hal.zsl

import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fixed-capacity ring buffer used for zero-shutter-lag burst frame retention.
 */
class ZeroShutterLagRingBuffer<T>(
    private val capacity: Int,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val frames: ConcurrentLinkedDeque<BufferedFrame<T>> = ConcurrentLinkedDeque()
    private val sizeCounter = AtomicInteger(0)

    init {
        require(capacity > 0) { "Capacity must be greater than zero" }
    }

    fun push(frame: T) {
        frames.addLast(BufferedFrame(payload = frame, timestamp = Instant.now(clock)))
        while (sizeCounter.incrementAndGet() > capacity) {
            if (frames.pollFirst() != null) {
                sizeCounter.decrementAndGet()
            } else {
                break
            }
        }
    }

    fun snapshot(): List<BufferedFrame<T>> = frames.toList()

    fun latest(count: Int): List<BufferedFrame<T>> {
        val safeCount = count.coerceIn(0, sizeCounter.get())
        return frames.toList().takeLast(safeCount)
    }

    fun clear() {
        frames.clear()
        sizeCounter.set(0)
    }
}

/** Frame payload with acquisition time metadata. */
data class BufferedFrame<T>(
    val payload: T,
    val timestamp: Instant,
)
