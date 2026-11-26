package com.humanblackbox.cloud

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.humanblackbox.data.AuditActor
import com.humanblackbox.data.AuditEvent
import com.humanblackbox.data.AuditOutcome
import com.humanblackbox.data.OutboundItem
import com.humanblackbox.data.OutboundQueue
import com.humanblackbox.data.PrivacyAuditLogger
import com.humanblackbox.data.PrivacySettings
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Uploads an already-encrypted evidence file, and nothing else.
 *
 * Privacy: WorkManager input carries only an event id and a relative `.enc` reference -
 * never a payload parameter. The reference is queued in [OutboundQueue] with an event-scoped
 * id, so a retry cannot duplicate it. Consent is read from DataStore at execution time and
 * fail-closed; the uploader re-reads it again immediately before the network write. Offline
 * runs retry with exponential backoff; missing Firebase configuration is terminal.
 */
class ConsentedEvidenceUploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val eventId = inputData.getString(KEY_EVENT_ID)
        val encryptedPath = inputData.getString(KEY_ENCRYPTED_FILE_PATH)
        if (eventId == null || !eventId.matches(SAFE_EVIDENCE_EVENT_ID)) return Result.failure()
        if (encryptedPath.isNullOrBlank() || !OutboundItem.isSafeEncryptedReference(encryptedPath)) {
            return Result.failure()
        }

        val queue = OutboundQueue(applicationContext)
        val queuedId = runCatching {
            queue.enqueueEncryptedReference(eventId, encryptedPath, idempotencyKey = eventId)
        }.getOrElse { return Result.retry() }
        runCatching {
            PrivacyAuditLogger(applicationContext).append(
                AuditActor.CLOUD,
                AuditEvent.EVIDENCE_REF_QUEUED,
                AuditOutcome.ALLOWED,
            )
        }

        if (!hasExplicitConsent()) {
            // Revocation is terminal: drop the queued reference instead of retrying it.
            queue.remove(queuedId)
            runCatching {
                PrivacyAuditLogger(applicationContext).append(
                    AuditActor.CLOUD,
                    AuditEvent.EVIDENCE_UPLOAD_DENIED,
                    AuditOutcome.DENIED,
                )
            }
            return Result.failure()
        }

        if (ConnectivityGuard.isOffline(applicationContext)) return Result.retry()

        val uploader = ConsentedEvidenceUploader.createOrNull(applicationContext) { hasExplicitConsent() }
            ?: run {
                queue.remove(queuedId)
                return Result.failure()
            }
        return runCatching {
            uploader.upload(eventId, encryptedPath)
            queue.remove(queuedId)
            Result.success()
        }.getOrElse {
            queue.recordAttempt(queuedId)
            Result.retry()
        }
    }

    private suspend fun hasExplicitConsent(): Boolean =
        runCatching { PrivacySettings(applicationContext).evidenceUploadConsent().first() }
            .getOrDefault(false) // fail closed if settings cannot be read

    companion object {
        const val TAG = "consented_evidence_upload"
        const val KEY_EVENT_ID = "eventId"
        const val KEY_ENCRYPTED_FILE_PATH = "encryptedFilePath"

        /** Input carries a relative `.enc` reference only; callers never supply plaintext. */
        fun inputData(eventId: String, encryptedFilePath: String): Data {
            require(eventId.matches(SAFE_EVIDENCE_EVENT_ID)) { "Invalid evidence event id" }
            require(OutboundItem.isSafeEncryptedReference(encryptedFilePath)) { "Invalid encrypted reference" }
            return Data.Builder()
                .putString(KEY_EVENT_ID, eventId)
                .putString(KEY_ENCRYPTED_FILE_PATH, encryptedFilePath)
                .build()
        }

        /** Enqueueing is not uploading: consent is checked again when the worker runs. */
        fun enqueue(context: Context, eventId: String, encryptedFilePath: String): Operation {
            val backoff = BackoffPolicy.DEFAULT
            val request = OneTimeWorkRequestBuilder<ConsentedEvidenceUploadWorker>()
                .setInputData(inputData(eventId, encryptedFilePath))
                .setBackoffCriteria(backoff.workBackoff(), backoff.initialDelayMs, backoff.initialDelayTimeUnit())
                .setInitialDelay(backoff.delayMs(0), TimeUnit.MILLISECONDS)
                .build()
            return WorkManager.getInstance(context).enqueueUniqueWork(
                "$TAG-$eventId",
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
