package com.vrtmv.app.data.recording

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FpsMeter @Inject constructor() {

    private val timestamps = ArrayDeque<Long>()
    private val lock = Any()

    fun onFrame(timestampMs: Long) {
        synchronized(lock) {
            timestamps.addLast(timestampMs)
            val threshold = timestampMs - WINDOW_MS
            while (timestamps.isNotEmpty() && timestamps.first() < threshold) {
                timestamps.removeFirst()
            }
        }
    }

    fun currentFps(): Float = synchronized(lock) {
        val n = timestamps.size
        if (n < 2) return 0f
        val span = timestamps.last() - timestamps.first()
        if (span <= 0L) return 0f
        return (n - 1) * 1000f / span
    }

    fun reset() {
        synchronized(lock) { timestamps.clear() }
    }

    companion object {
        private const val WINDOW_MS = 1000L
    }
}
