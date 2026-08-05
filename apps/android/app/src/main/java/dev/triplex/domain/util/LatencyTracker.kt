package dev.triplex.domain.util

import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

object LatencyTracker {
    private val events = ConcurrentHashMap<String, AtomicLong>()
    private val timings = ConcurrentHashMap<String, MutableList<Long>>()

    fun markStart(event: String): String {
        val markerId = "${event}_${System.nanoTime()}"
        events[markerId] = AtomicLong(System.nanoTime())
        return markerId
    }

    fun markEnd(markerId: String, event: String): Long {
        val start = events.remove(markerId)?.get() ?: return 0L
        val elapsed = abs(System.nanoTime() - start) / 1_000_000
        
        timings.getOrPut(event) { mutableListOf() }.apply {
            add(elapsed)
            if (size > 100) removeAt(0)
        }
        
        Timber.v("Latency [$event]: ${elapsed}ms")
        return elapsed
    }

    /** Pure helper: compute a percentile from a sorted list. */
    private fun percentile(sorted: List<Long>, fraction: Double): Long {
        if (sorted.isEmpty()) return 0L
        val index = (sorted.size * fraction).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    fun getP50(event: String): Long {
        val times = timings[event] ?: return 0L
        if (times.isEmpty()) return 0L
        val sorted = times.sorted()
        return sorted[sorted.size / 2]
    }

    fun getP95(event: String): Long {
        val times = timings[event] ?: return 0L
        if (times.isEmpty()) return 0L
        val sorted = times.sorted()
        return percentile(sorted, 0.95)
    }

    fun getStats(event: String): Map<String, Long> {
        val times = timings[event] ?: return emptyMap()
        if (times.isEmpty()) return emptyMap()
        val sorted = times.sorted()
        return mapOf(
            "p50" to sorted[sorted.size / 2],
            "p95" to percentile(sorted, 0.95),
            "min" to sorted.first(),
            "max" to sorted.last(),
            "count" to sorted.size.toLong()
        )
    }
}
