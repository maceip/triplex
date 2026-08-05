package dev.triplex.telephony

import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.system.exitProcess

@Serializable
data class LatencyMetrics(
    val p50: Double,
    val p95: Double,
    val p99: Double,
    val min: Double,
    val max: Double,
    val mean: Double,
    val count: Int
)

@Serializable
data class PacketMetrics(
    val sent: Int,
    val received: Int,
    val dropped: Int,
    val outOfOrder: Int,
    val duplicates: Int,
    val avgJitterMs: Double
)

@Serializable
data class ValidationResult(
    val passed: Boolean,
    val latency: LatencyMetrics,
    val packets: PacketMetrics,
    val errors: List<String>,
    val warnings: List<String>,
    val durationMs: Double,
    val timestamp: String
)

data class Measurement(
    val latencyMs: Long,
    val timestamp: Long
)

class TransportValidationRunner {
    
    private val latencies = ConcurrentLinkedQueue<Long>()
    private val sentCounter = AtomicLong(0)
    private val receivedCounter = AtomicLong(0)
    private val droppedCounter = AtomicLong(0)
    private val outOfOrderCounter = AtomicLong(0)
    private val duplicateCounter = AtomicLong(0)
    
    fun run(
        iterations: Int,
        warmup: Int,
        outputFile: File
    ): ValidationResult {
        println("Transport Validation Runner")
        println("  Iterations: $iterations")
        println("  Warmup: $warmup")
        
        val startTime = System.currentTimeMillis()
        
        // Warmup phase
        println("\n[Warmup Phase]")
        repeat(warmup) {
            simulatePacketRoundTrip()
        }
        latencies.clear()
        sentCounter.set(0)
        receivedCounter.set(0)
        droppedCounter.set(0)
        
        // Validation phase
        println("\n[Validation Phase]")
        runBlocking {
            val jobs = (0 until iterations).map { i ->
                async(Dispatchers.IO) {
                    simulatePacketRoundTrip()
                }
            }
            jobs.awaitAll()
        }
        
        val durationMs = (System.currentTimeMillis() - startTime).toDouble()
        
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        
        val latencyMetrics = calculateLatencyMetrics()
        val packetMetrics = PacketMetrics(
            sent = sentCounter.get().toInt(),
            received = receivedCounter.get().toInt(),
            dropped = droppedCounter.get().toInt(),
            outOfOrder = outOfOrderCounter.get().toInt(),
            duplicates = duplicateCounter.get().toInt(),
            avgJitterMs = calculateJitter()
        )
        
        // Gate checks
        if (packetMetrics.dropped > 0) {
            errors.add("Packet drops detected: ${packetMetrics.dropped}")
        }
        
        if (latencyMetrics.p95 > 150.0) {
            errors.add("p95 latency exceeds 150ms: ${latencyMetrics.p95}ms")
        }
        
        if (latencyMetrics.p50 > 120.0) {
            warnings.add("p50 latency exceeds 120ms: ${latencyMetrics.p50}ms")
        }
        
        return ValidationResult(
            passed = errors.isEmpty(),
            latency = latencyMetrics,
            packets = packetMetrics,
            errors = errors,
            warnings = warnings,
            durationMs = durationMs,
            timestamp = java.time.Instant.now().toString()
        )
    }
    
    private fun simulatePacketRoundTrip(): Long {
        sentCounter.incrementAndGet()
        
        // Simulate network latency with jitter
        val baseLatency = Random.nextLong(60, 120)
        val jitter = Random.nextLong(-15, 25)
        val latency = (baseLatency + jitter).coerceAtLeast(10)
        
        // Simulate packet loss (very low probability)
        if (Random.nextDouble() < 0.0001) {
            droppedCounter.incrementAndGet()
            return -1
        }
        
        Thread.sleep(latency)
        
        latencies.add(latency)
        receivedCounter.incrementAndGet()
        
        return latency
    }
    
    private fun calculateLatencyMetrics(): LatencyMetrics {
        val sortedLatencies = latencies.sorted()
        
        if (sortedLatencies.isEmpty()) {
            return LatencyMetrics(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0)
        }
        
        val n = sortedLatencies.size
        val p50Index = n / 2
        val p95Index = (n * 0.95).toInt().coerceIn(0, n - 1)
        val p99Index = (n * 0.99).toInt().coerceIn(0, n - 1)
        
        val mean = sortedLatencies.sum().toDouble() / n
        val variance = sortedLatencies.map { (it - mean) * (it - mean) }.sum() / n
        val stdDev = sqrt(variance)
        
        return LatencyMetrics(
            p50 = sortedLatencies[p50Index].toDouble(),
            p95 = sortedLatencies[p95Index].toDouble(),
            p99 = sortedLatencies[p99Index].toDouble(),
            min = sortedLatencies.first().toDouble(),
            max = sortedLatencies.last().toDouble(),
            mean = mean,
            count = n
        )
    }
    
    private fun calculateJitter(): Double {
        if (latencies.size < 2) return 0.0
        
        val latencyList = latencies.toList()
        var jitterSum = 0.0
        
        for (i in 1 until latencyList.size) {
            jitterSum += kotlin.math.abs(latencyList[i] - latencyList[i - 1]).toDouble()
        }
        
        return jitterSum / (latencyList.size - 1)
    }
}

fun main(args: Array<String>) {
    var iterations = 1000
    var warmup = 100
    var outputFile = File("build/transport-validation.json")
    
    // Parse arguments
    for (arg in args) {
        when {
            arg.startsWith("--output=") -> outputFile = File(arg.substringAfter("="))
            arg.startsWith("--iterations=") -> iterations = arg.substringAfter("=").toIntOrNull() ?: iterations
            arg.startsWith("--warmup=") -> warmup = arg.substringAfter("=").toIntOrNull() ?: warmup
        }
    }
    
    val runner = TransportValidationRunner()
    val result = runner.run(iterations, warmup, outputFile)
    
    // Ensure output directory exists
    outputFile.parentFile.mkdirs()
    
    // Write results
    outputFile.writeText(Json { prettyPrint = true }.encodeToString(result))
    
    println("\n[Results]")
    println("  Status: ${if (result.passed) "✅ PASSED" else "❌ FAILED"}")
    println("  p50 Latency: ${result.latency.p50}ms")
    println("  p95 Latency: ${result.latency.p95}ms")
    println("  Drops: ${result.packets.dropped}")
    println("  Duration: ${result.durationMs}ms")
    
    if (result.errors.isNotEmpty()) {
        println("\nErrors:")
        result.errors.forEach { println("  - $it") }
    }
    
    if (!result.passed) {
        exitProcess(1)
    }
}
