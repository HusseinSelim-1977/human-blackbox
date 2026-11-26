package com.humanblackbox.testing

import com.humanblackbox.core.ModalityClassifier
import kotlin.math.ceil

/** Measured latency of a classifier on supplied inputs; no results are asserted. */
data class BenchmarkResult(
    val samples: Int,
    val meanMs: Double,
    val p95Ms: Double,
    val maxMs: Double,
)

/** Local benchmark harness for on-device inference latency. */
class BenchmarkHarness(private val classifier: ModalityClassifier) {
    fun measure(inputs: List<FloatArray>, warmups: Int = 5): BenchmarkResult {
        require(inputs.isNotEmpty()) { "At least one input is required" }
        repeat(warmups) { inputs.forEach { classifier.classify(it) } }
        val times = inputs.map { input ->
            val start = System.nanoTime()
            classifier.classify(input)
            (System.nanoTime() - start) / 1_000_000.0
        }.sorted()
        val p95Index = ceil(times.size * 0.95).toInt().coerceIn(times.indices)
        return BenchmarkResult(times.size, times.average(), times[p95Index], times.last())
    }
}
