package com.humanblackbox.cloud

import android.content.Context
import com.humanblackbox.data.OutboundKind
import com.humanblackbox.data.OutboundQueue

enum class AlertDispatchResult {
    SENT,
    SKIPPED_UNCONFIGURED,
    FAILED,
    OFFLINE,
    EMPTY,
    INVALID,
}

/**
 * Single alert entry point. The type only accepts [MinimalAlert], so a raw
 * sensor payload cannot be dispatched through this interface by construction.
 * Evidence is a separate consented operation handled by the upload worker.
 */
interface AlertDispatcher {
    suspend fun dispatch(alert: MinimalAlert): AlertDispatchResult
}

/**
 * Firestore-backed dispatcher for minimal metadata alerts.
 *
 * Offline-first flow: [dispatchPending] pulls the oldest queued metadata item, checks
 * connectivity, then dispatches exactly six fields. Successful and permanently skipped
 * items are removed; transient failures increment the attempt count so the worker's
 * exponential backoff can retry without duplicating state. Missing Firebase configuration
 * is permanent and never retried.
 */
class FirestoreAlertRepository(
    private val context: Context,
    private val gatewayProvider: (Context) -> FirestoreAlertGateway? = { FirestoreAlertGateway.createOrNull(it) },
) : AlertDispatcher {
    override suspend fun dispatch(alert: MinimalAlert): AlertDispatchResult {
        val gateway = gatewayProvider(context) ?: return AlertDispatchResult.SKIPPED_UNCONFIGURED
        return runCatching {
            gateway.sendMinimalAlert(alert)
            AlertDispatchResult.SENT
        }.getOrElse { AlertDispatchResult.FAILED }
    }

    suspend fun dispatchPending(queue: OutboundQueue): AlertDispatchResult {
        if (ConnectivityGuard.isOffline(context)) return AlertDispatchResult.OFFLINE
        val item = queue.next(OutboundKind.METADATA_ALERT) ?: return AlertDispatchResult.EMPTY
        val metadata = item.metadata ?: run {
            queue.remove(item.id)
            return AlertDispatchResult.INVALID
        }
        val alert = runCatching { metadata.toMinimalAlert() }.getOrElse {
            queue.remove(item.id)
            return AlertDispatchResult.INVALID
        }
        return when (val result = dispatch(alert)) {
            AlertDispatchResult.SENT, AlertDispatchResult.SKIPPED_UNCONFIGURED -> {
                queue.remove(item.id)
                result
            }
            AlertDispatchResult.FAILED -> {
                queue.recordAttempt(item.id)
                result
            }
            else -> result
        }
    }
}
