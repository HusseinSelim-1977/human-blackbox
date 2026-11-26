package com.humanblackbox.testing

import java.util.Locale
import kotlin.math.ceil

/**
 * Wall-clock measurement helpers for operator-supplied inputs.
 *
 * These helpers only time blocks executed in the current process. They do not
 * run on a device, do not select a benchmark environment, and do not assert or
 * publish results. Callers must label any printed output as locally supplied
 * measurements.
 */
object BenchmarkMeasure {
    /** Time a synchronous block after optional warm-up iterations. */
    inline fun inlineMeasure(
        iterations: Int,
        warmups: Int = 5,
        crossinline block: () -> Unit,
    ): BenchmarkResult {
        require(iterations > 0) { "iterations must be positive" }
        require(warmups >= 0) { "warmups must be non-negative" }
        for (i in 0 until warmups) block()
        val elapsed = LongArray(iterations)
        for (i in 0 until iterations) {
            val start = System.nanoTime()
            block()
            elapsed[i] = System.nanoTime() - start
        }
        return fromNanos(elapsed.toList())
    }

    /** Time a suspend block after optional warm-up iterations. */
    suspend fun suspendMeasure(
        iterations: Int,
        warmups: Int = 5,
        block: suspend () -> Unit,
    ): BenchmarkResult {
        require(iterations > 0) { "iterations must be positive" }
        require(warmups >= 0) { "warmups must be non-negative" }
        for (i in 0 until warmups) block()
        val elapsed = LongArray(iterations)
        for (i in 0 until iterations) {
            val start = System.nanoTime()
            block()
            elapsed[i] = System.nanoTime() - start
        }
        return fromNanos(elapsed.toList())
    }

    @PublishedApi
    internal fun fromNanos(valuesNanos: List<Long>): BenchmarkResult {
        val valuesMs = valuesNanos.map { it / 1_000_000.0 }
        val sorted = valuesMs.sorted()
        // Nearest-rank p95, matching scripts/python/benchmark.py.
        val p95Index = (ceil(sorted.size * 0.95).toInt() - 1).coerceIn(sorted.indices)
        return BenchmarkResult(sorted.size, valuesMs.average(), sorted[p95Index], sorted.last())
    }
}

/** Formatting for locally measured [BenchmarkResult] values. */
object BenchmarkFormats {
    const val CSV_HEADER: String =
        "source_kind,samples,mean_ms,p95_ms,max_ms,threshold_ms,verdict"

    fun csvHeader(): String = CSV_HEADER

    /**
     * CSV row for a supplied result. With no threshold, budget columns are
     * empty: no PASS/FAIL is invented without a caller-supplied budget.
     */
    fun BenchmarkResult.toCsvRow(thresholdMs: Double? = null, criterion: String = "p95"): String {
        val threshold = thresholdMs?.let(::formatMs).orEmpty()
        val verdict = thresholdMs?.let { verdict(criterion, it) }.orEmpty()
        return listOf(
            "supplied_measurement",
            samples.toString(),
            formatMs(meanMs),
            formatMs(p95Ms),
            formatMs(maxMs),
            threshold,
            verdict,
        ).joinToString(",")
    }

    /** Human-readable summary that explicitly refuses to claim device results. */
    fun BenchmarkResult.formatSummary(thresholdMs: Double? = null, criterion: String = "p95"): String =
        buildString {
            append("SUPPLIED MEASUREMENTS (not app measurements; no device run claimed): ")
            append("n=$samples mean=${formatMs(meanMs)}ms p95=${formatMs(p95Ms)}ms ")
            append("max=${formatMs(maxMs)}ms")
            if (thresholdMs != null) {
                append(" | budget ($criterion <= ${formatMs(thresholdMs)}ms): ")
                append(verdict(criterion, thresholdMs))
            }
        }

    private fun BenchmarkResult.verdict(criterion: String, thresholdMs: Double): String {
        val selected = when (criterion) {
            "mean" -> meanMs
            "p95" -> p95Ms
            "max" -> maxMs
            else -> throw IllegalArgumentException("criterion must be mean, p95 or max")
        }
        return if (selected <= thresholdMs) "PASS" else "FAIL"
    }

    private fun formatMs(value: Double): String = String.format(Locale.US, "%.3f", value)
}
