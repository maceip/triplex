package com.triplex.dialer.triplex

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.max
import kotlin.system.measureTimeMillis

/**
 * Lightweight per-stage profiler for the audio pipeline.
 *
 * Records median, p99.9, and maximum processing duration per stage,
 * plus underrun counts. Designed for release-build measurement on the
 * slowest target device.
 *
 * Stages:
 * - capture_conversion: ByteArray → ShortArray
 * - capture_vad: VAD threshold check
 * - playback_conversion: ShortArray → ByteArray
 * - playback_write: AudioTrack.write()
 * - echo_filter: NLMS filter convolution
 * - echo_subtract: Near-end subtraction
 * - echo_update: Coefficient update
 * - synthesis_oscillator: SineOscillator.render()
 * - synthesis_convert: FloatArray → ShortArray
 * - interruption_teardown: Full teardown sequence
 *
 * Usage:
 *   val profiler = AudioProfiler()
 *   profiler.measure("capture_conversion") { ... }
 *   val report = profiler.report()  // call at end of session
 */
class AudioProfiler {

    data class StageSample(val durationUs: Long, val timestampMs: Long)

    private val stages = mutableMapOf<String, ConcurrentLinkedQueue<StageSample>>()
    private var underrunCount = 0
    private var totalFrames = 0L

    companion object {
        private val ENABLED = true // toggle for release builds
    }

    /** Measure one stage execution in microseconds. Returns the block result. */
    fun <T> measure(stage: String, block: () -> T): T {
        if (!ENABLED) return block()
        val t0 = System.nanoTime()
        val result = block()
        val elapsedNs = System.nanoTime() - t0
        val elapsedUs = elapsedNs / 1000L
        stages.getOrPut(stage) { ConcurrentLinkedQueue() }
            .offer(StageSample(elapsedUs, System.currentTimeMillis()))
        return result
    }

    /** Record an underrun (callback deadline missed). */
    fun recordUnderrun() {
        underrunCount++
    }

    /** Record total frames processed. */
    fun recordFrame() {
        totalFrames++
    }

    /** Produce profiling report. */
    fun report(): ProfilingReport {
        val stageReports = stages.mapValues { (_, samples) ->
            computeStats(samples.toList())
        }
        return ProfilingReport(
            stageReports = stageReports,
            underrunCount = underrunCount,
            totalFrames = totalFrames,
        )
    }

    /** Reset all accumulated data. */
    fun reset() {
        stages.clear()
        underrunCount = 0
        totalFrames = 0
    }

    private fun computeStats(samples: List<StageSample>): StageStats {
        if (samples.isEmpty()) return StageStats(0, 0, 0, 0, 0)

        val sorted = samples.map { it.durationUs }.sorted()
        val count = sorted.size
        val median = sorted[count / 2]
        val p99 = sorted[(count * 99 / 100).coerceAtMost(count - 1)]
        val p999 = sorted[(count * 999 / 1000).coerceAtMost(count - 1)]
        val maxVal = sorted.last()
        return StageStats(
            medianUs = median,
            p99Us = p99,
            p999Us = p999,
            maxUs = maxVal,
            sampleCount = count,
        )
    }
}

data class StageStats(
    val medianUs: Long,
    val p99Us: Long,
    val p999Us: Long,
    val maxUs: Long,
    val sampleCount: Int,
)

data class ProfilingReport(
    val stageReports: Map<String, StageStats>,
    val underrunCount: Int,
    val totalFrames: Long,
) {
    /** Format report as readable string. */
    fun formatted(): String {
        val sb = StringBuilder()
        sb.appendLine("=== AudioProfiler Report ===")
        sb.appendLine("Total frames: $totalFrames | Underruns: $underrunCount")
        sb.appendLine()
        sb.appendLine(String.format("%-30s %8s %8s %8s %8s", "Stage", "Median", "p99", "p99.9", "Max"))
        sb.appendLine("-".repeat(70))
        for ((stage, stats) in stageReports) {
            sb.appendLine(String.format(
                "%-30s %5dµs %5dµs %5dµs %5dµs",
                stage, stats.medianUs, stats.p99Us, stats.p999Us, stats.maxUs,
            ))
        }
        sb.appendLine("-".repeat(70))
        return sb.toString()
    }
}
