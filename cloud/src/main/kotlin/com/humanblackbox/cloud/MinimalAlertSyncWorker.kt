package com.humanblackbox.cloud

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.humanblackbox.core.Action
import com.humanblackbox.core.EventType
import com.humanblackbox.data.AuditActor
import com.humanblackbox.data.AuditEvent
import com.humanblackbox.data.AuditOutcome
import com.humanblackbox.data.OutboundQueue
import com.humanblackbox.data.PrivacyAuditLogger
import java.util.concurrent.TimeUnit

/**
 * Sends only the six metadata fields of a [MinimalAlert].
 *
 * Privacy: the WorkManager input contains six typed metadata fields, no sensor samples and
 * no evidence reference. On execution the item is placed in [OutboundQueue] idempotently,
 * then drained by the repository with an offline guard and exponential backoff. WorkManager
 * retries are deduplicated by a deterministic queue id; a missing Firebase configuration is
 * terminal.
 */
class MinimalAlertSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val alert = runCatching { inputData.toMinimalAlert() }.getOrElse { return Result.failure() }
        val queue = OutboundQueue(applicationContext)
        runCatching {
            queue.enqueueMetadata(alert.toOutboundMetadata(), idempotencyKey = queueKey(alert))
        }.getOrElse { return Result.retry() }

        val auditLogger = PrivacyAuditLogger(applicationContext)
        runCatching {
            auditLogger.append(AuditActor.CLOUD, AuditEvent.ALERT_QUEUED, AuditOutcome.ALLOWED, alert.timestampMs)
        }
        val repository = FirestoreAlertRepository(applicationContext)
        return when (repository.dispatchPending(queue)) {
            AlertDispatchResult.SENT -> {
                runCatching {
                    auditLogger.append(AuditActor.CLOUD, AuditEvent.ALERT_SENT, AuditOutcome.ALLOWED, alert.timestampMs)
                }
                Result.success()
            }
            AlertDispatchResult.FAILED, AlertDispatchResult.OFFLINE -> Result.retry()
            AlertDispatchResult.SKIPPED_UNCONFIGURED -> Result.success()
            AlertDispatchResult.EMPTY, AlertDispatchResult.INVALID -> Result.success()
        }
    }

    companion object {
        const val TAG = "minimal_alert_sync"
        const val KEY_EVENT_TYPE = "eventType"
        const val KEY_CONFIDENCE = "confidence"
        const val KEY_TIMESTAMP_MS = "timestampMs"
        const val KEY_COARSE_LOCATION = "coarseLocation"
        const val KEY_DEVICE_STATUS = "deviceStatus"
        const val KEY_RECOMMENDED_ACTION = "recommendedAction"

        fun inputData(alert: MinimalAlert): Data = Data.Builder()
            .putString(KEY_EVENT_TYPE, alert.eventType.name)
            .putFloat(KEY_CONFIDENCE, alert.confidence)
            .putLong(KEY_TIMESTAMP_MS, alert.timestampMs)
            .putString(KEY_COARSE_LOCATION, alert.coarseLocation)
            .putString(KEY_DEVICE_STATUS, alert.deviceStatus)
            .putString(KEY_RECOMMENDED_ACTION, alert.recommendedAction.name)
            .build()

        /** Stable across retries so the same alert is never queued twice. */
        private fun queueKey(alert: MinimalAlert): String =
            "${alert.timestampMs}-${alert.eventType.name}-${alert.confidence.toBits().toLong()}"

        fun enqueue(context: Context, alert: MinimalAlert): Operation {
            val backoff = BackoffPolicy.DEFAULT
            val request = OneTimeWorkRequestBuilder<MinimalAlertSyncWorker>()
                .setInputData(inputData(alert))
                .setBackoffCriteria(backoff.workBackoff(), backoff.initialDelayMs, backoff.initialDelayTimeUnit())
                .setInitialDelay(backoff.delayMs(0), TimeUnit.MILLISECONDS)
                .build()
            return WorkManager.getInstance(context).enqueueUniqueWork(
                "$TAG-${alert.timestampMs}-${alert.eventType.name}",
                ExistingWorkPolicy.KEEP,
                request,
            )
        }

        private fun Data.toMinimalAlert(): MinimalAlert {
            fun required(key: String): String = getString(key) ?: error("Missing $key")
            return MinimalAlert(
                eventType = EventType.valueOf(required(KEY_EVENT_TYPE)),
                confidence = getFloat(KEY_CONFIDENCE, -1f),
                timestampMs = getLong(KEY_TIMESTAMP_MS, -1L),
                coarseLocation = getString(KEY_COARSE_LOCATION),
                deviceStatus = required(KEY_DEVICE_STATUS),
                recommendedAction = Action.valueOf(required(KEY_RECOMMENDED_ACTION)),
            )
        }
    }
}
