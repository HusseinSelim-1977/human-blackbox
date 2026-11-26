package com.humanblackbox.cloud

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.humanblackbox.data.PrivacySettings
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * Uploads an already-encrypted evidence file, and nothing else.
 *
 * Privacy: only an event id plus a path to a local `.enc` file are passed to
 * WorkManager - never a raw payload parameter. The file is read as opaque
 * ciphertext; no on-device decryption happens on this path. Consent is read
 * from DataStore at execution time; if it is absent or revoked the worker
 * fails without uploading. Upload failures return retry() so WorkManager
 * applies exponential backoff; missing Firebase configuration is terminal.
 */
class ConsentedEvidenceUploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val eventId = inputData.getString(KEY_EVENT_ID)
        val encryptedPath = inputData.getString(KEY_ENCRYPTED_FILE_PATH)
        if (eventId == null || !eventId.matches(SAFE_EVIDENCE_EVENT_ID)) return Result.failure()
        if (encryptedPath.isNullOrBlank()) return Result.failure()

        val consentAtExecution = hasExplicitConsent()
        if (!consentAtExecution) return Result.failure()

        val ciphertext = runCatching { readCiphertext(applicationContext, encryptedPath) }
            .getOrElse { return Result.failure() }

        // Second read: consent might have been revoked while the file was read.
        if (!hasExplicitConsent()) return Result.failure()

        // The uploader checks its consent guard again immediately before putBytes.
        val uploader = ConsentedEvidenceUploader.createOrNull(applicationContext) { consentAtExecution }
            ?: return Result.failure()
        return runCatching {
            uploader.upload(eventId, ciphertext)
            Result.success()
        }.getOrElse { Result.retry() }
    }

    private suspend fun hasExplicitConsent(): Boolean =
        runCatching { PrivacySettings(applicationContext).evidenceUploadConsent().first() }
            .getOrDefault(false) // fail closed if settings cannot be read

    /** Reads raw ciphertext only; accepts files inside the app's filesDir. */
    private fun readCiphertext(context: Context, path: String): ByteArray {
        val file = File(path).canonicalFile
        val filesRoot = context.filesDir.canonicalFile.toPath()
        require(file.extension == "enc") { "Only encrypted (.enc) evidence is uploadable" }
        require(file.toPath().startsWith(filesRoot)) { "Evidence path is outside app storage" }
        require(file.isFile) { "Evidence file is missing" }
        require(file.length() in 1L..MAX_ENCRYPTED_BYTES) { "Evidence size is invalid" }
        return file.readBytes()
    }

    companion object {
        const val TAG = "consented_evidence_upload"
        const val KEY_EVENT_ID = "eventId"
        const val KEY_ENCRYPTED_FILE_PATH = "encryptedFilePath"
        const val MAX_ENCRYPTED_BYTES = 20L * 1024 * 1024

        /** Input carries a path only; callers must supply ciphertext, never plaintext. */
        fun inputData(eventId: String, encryptedFilePath: String): Data {
            require(eventId.matches(SAFE_EVIDENCE_EVENT_ID)) { "Invalid evidence event id" }
            require(encryptedFilePath.isNotBlank()) { "Encrypted evidence path is required" }
            return Data.Builder()
                .putString(KEY_EVENT_ID, eventId)
                .putString(KEY_ENCRYPTED_FILE_PATH, encryptedFilePath)
                .build()
        }

        /** Enqueueing is not uploading: consent is checked again when the worker runs. */
        fun enqueue(context: Context, eventId: String, encryptedFilePath: String): Operation {
            val request = OneTimeWorkRequestBuilder<ConsentedEvidenceUploadWorker>()
                .setInputData(inputData(eventId, encryptedFilePath))
                .build()
            return WorkManager.getInstance(context).enqueueUniqueWork(
                "$TAG-$eventId",
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
