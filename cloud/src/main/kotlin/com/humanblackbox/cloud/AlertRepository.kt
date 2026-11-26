package com.humanblackbox.cloud

import android.content.Context

enum class AlertDispatchResult { SENT, SKIPPED_UNCONFIGURED, FAILED }

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
 * Retry/backoff is owned by WorkManager (see MinimalAlertSyncWorker): transient
 * failures return FAILED so the worker can retry with exponential backoff,
 * while a missing Firebase configuration is a permanent SKIPPED result.
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
}
