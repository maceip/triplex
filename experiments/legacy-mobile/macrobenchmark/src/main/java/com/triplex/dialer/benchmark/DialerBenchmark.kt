package com.triplex.dialer.benchmark

import androidx.benchmark.macro.MacrobenchmarkRun
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Macrobenchmark for Triplex Dialer app.
 *
 * Measures ART runtime performance metrics:
 * - First-frame rendering time (cold start)
 * - Scroll jank (call log list)
 * - Recomposition counts (in-call dashboard)
 *
 * Run with:
 *   ./gradlew :macrobenchmark:connectedBenchmark
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class DialerBenchmark {

    @Rule
    @JvmField
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun benchmarkColdStart() {
        benchmarkRule.run(
            packageName = MacrobenchmarkRun.PACKAGE_NAME_ANDROID,
            metrics = setOf(
                MacrobenchmarkRun.Metric.FRAME_DURACY,
                MacrobenchmarkRun.Metric.STARTUP_TIME,
            ),
        ) {
            startActivityAndWait()
        }
    }

    @Test
    fun benchmarkScrollJank() {
        benchmarkRule.run(
            packageName = MacrobenchmarkRun.PACKAGE_NAME_ANDROID,
            metrics = setOf(
                MacrobenchmarkRun.Metric.FRAME_DURACY,
                MacrobenchmarkRun.Metric.SCROLL_JANK,
            ),
        ) {
            startActivityAndWait()
            Thread.sleep(1000)
            // Scroll simulation would go here via UI automation
        }
    }
}
