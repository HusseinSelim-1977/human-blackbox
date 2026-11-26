package com.humanblackbox.cloud

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlin.random.Random
import java.util.concurrent.TimeUnit

/**
 * Retry timing for metadata sync and consented upload.
 *
 * Delay is exponential in the attempt number and jittered symmetrically, so a fleet of
 * devices does not retry in lockstep. WorkManager receives the EXPONENTIAL policy plus a
 * jittered initial delay; the computed delay is also available for callers that schedule
 * their own retries. No payload is involved in this decision.
 */
data class BackoffPolicy(
    val initialDelayMs: Long = DEFAULT_INITIAL_DELAY_MS,
    val maxDelayMs: Long = DEFAULT_MAX_DELAY_MS,
    val multiplier: Double = 2.0,
    val jitterFraction: Double = 0.2,
) {
    init {
        require(initialDelayMs > 0L && maxDelayMs >= initialDelayMs)
        require(multiplier >= 1.0 && jitterFraction in 0.0..0.5)
    }

    fun delayMs(attempt: Int, random: Random = Random.Default): Long {
        val exponent = attempt.coerceAtLeast(0)
        val base = minOf(maxDelayMs.toDouble(), initialDelayMs * Math.pow(multiplier, exponent.toDouble()))
        val jitter = base * jitterFraction * random.nextDouble(-1.0, 1.0)
        return base.plus(jitter).toLong().coerceIn(1L, maxDelayMs)
    }

    /** WorkManager-compatible exponential policy paired with a jittered initial delay. */
    fun workBackoff(): androidx.work.BackoffPolicy = androidx.work.BackoffPolicy.EXPONENTIAL

    fun initialDelayTimeUnit(): TimeUnit = TimeUnit.MILLISECONDS

    companion object {
        const val DEFAULT_INITIAL_DELAY_MS = 10_000L
        const val DEFAULT_MAX_DELAY_MS = 15L * 60L * 1000L
        val DEFAULT = BackoffPolicy()
    }
}

/** Connectivity gate: offline work is retried without ever touching the network. */
object ConnectivityGuard {
    fun isOnline(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun isOffline(context: Context): Boolean = !isOnline(context)
}
