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

/**
 * Sends only the six metadata fields of a [MinimalAlert].
 *
 * Privacy: the WorkManager input contains no sensor samples and no evidence
 * reference. Retry/backoff is delegated to WorkManager (default exponential
 * backoff on Result.retry()); a missing Firebase configuration is terminal.
 */
class MinimalAlertSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val alert = runCatching { inputData.toMinimalAlert() }.getOrElse { return Result.failure() }
        val repository = FirestoreAlertRepository(applicationContext)
        return when (repository.dispatch(alert)) {
            AlertDispatchResult.SENT -> Result.success()
            AlertDispatchResult.FAILED -> Result.retry()
            AlertDispatchResult.SKIPPED_UNCONFIGURED -> Result.failure()
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

        fun enqueue(context: Context, alert: MinimalAlert): Operation {
            val request = OneTimeWorkRequestBuilder<MinimalAlertSyncWorker>()
                .setInputData(inputData(alert))
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
