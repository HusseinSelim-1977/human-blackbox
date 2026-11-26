package com.humanblackbox.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Fixed vocabulary for audit actors; no caller-supplied text is accepted. */
enum class AuditActor { DECISION, CONSENT, MODALITY, STORAGE, CLOUD, RETENTION, KEY_MANAGEMENT }

/** Fixed vocabulary for privacy-relevant events; no caller-supplied text is accepted. */
enum class AuditEvent {
    DECISION_RECORDED,
    CONSENT_GRANTED,
    CONSENT_REVOKED,
    MODALITY_ENABLED,
    MODALITY_DISABLED,
    ALERT_QUEUED,
    ALERT_SENT,
    EVIDENCE_REF_QUEUED,
    EVIDENCE_UPLOAD_DENIED,
    EVIDENCE_DELETED,
    PRUNE_COMPLETED,
    KEYS_ROTATED,
}

enum class AuditOutcome { ALLOWED, DENIED, RETRIED, FAILED, SKIPPED }

/** One anonymized audit row. The instant is coarsened to a bucket before storage. */
data class PrivacyAuditEntry(
    val timestampBucketMs: Long,
    val actor: AuditActor,
    val event: AuditEvent,
    val outcome: AuditOutcome,
)

/**
 * Append-only, local-only privacy audit log.
 *
 * Privacy rules: the only stored fields are a bucketed timestamp and three enum labels.
 * There is no free-form message, event id, path, exact score, tensor, learned feature vector,
 * sensor sample, PCM audio or ARGB frame. The file is rewritten only during retention pruning,
 * which enforces both a time window and a hard entry cap. Failures are swallowed: auditing
 * must never prevent the privacy action it describes.
 */
class PrivacyAuditLogger(
    context: Context,
    private val bucketSizeMs: Long = DEFAULT_BUCKET_MS,
    private val retentionMs: Long = DEFAULT_RETENTION_MS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private val file = File(context.applicationContext.filesDir, "privacy-audit/audit.log")
    private val lock = Any()

    init {
        require(bucketSizeMs > 0L && retentionMs > 0L && maxEntries > 0)
    }

    suspend fun append(
        actor: AuditActor,
        event: AuditEvent,
        outcome: AuditOutcome,
        timestampMs: Long = System.currentTimeMillis(),
    ): Unit = withContext(Dispatchers.IO) {
        synchronized(lock) {
            runCatching {
                val entry = PrivacyAuditEntry(bucket(timestampMs), actor, event, outcome)
                file.parentFile?.mkdirs()
                file.appendText(encode(entry) + '\n')
                pruneLocked(timestampMs)
            }
            Unit
        }
    }

    suspend fun entries(): List<PrivacyAuditEntry> = withContext(Dispatchers.IO) {
        synchronized(lock) { readLocked() }
    }

    /** Drops entries older than the retention window and anything beyond the entry cap. */
    suspend fun prune(nowMs: Long = System.currentTimeMillis()): Int = withContext(Dispatchers.IO) {
        synchronized(lock) { pruneLocked(nowMs) }
    }

    suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        synchronized(lock) {
            runCatching { file.delete() }
            Unit
        }
    }

    private fun bucket(timestampMs: Long): Long = (timestampMs / bucketSizeMs) * bucketSizeMs

    private fun readLocked(): List<PrivacyAuditEntry> =
        if (!file.isFile) emptyList()
        else runCatching { file.readLines().mapNotNull(::decode) }.getOrDefault(emptyList())

    private fun pruneLocked(nowMs: Long): Int {
        val cutoff = bucket(nowMs - retentionMs)
        val existing = readLocked()
        val kept = existing.filter { it.timestampBucketMs >= cutoff }.takeLast(maxEntries)
        val removed = existing.size - kept.size
        if (removed == 0 && file.isFile) return 0
        return runCatching {
            file.parentFile?.mkdirs()
            file.writeText(kept.joinToString("\n") { encode(it) } + if (kept.isEmpty()) "" else "\n")
            removed
        }.getOrDefault(0)
    }

    private fun encode(entry: PrivacyAuditEntry): String = listOf(
        entry.timestampBucketMs.toString(),
        entry.actor.name,
        entry.event.name,
        entry.outcome.name,
    ).joinToString("|")

    private fun decode(line: String): PrivacyAuditEntry? {
        val parts = line.split('|')
        if (parts.size != 4) return null
        val timestamp = parts[0].toLongOrNull() ?: return null
        val actor = enumValueOrNull<AuditActor>(parts[1]) ?: return null
        val event = enumValueOrNull<AuditEvent>(parts[2]) ?: return null
        val outcome = enumValueOrNull<AuditOutcome>(parts[3]) ?: return null
        return PrivacyAuditEntry(timestamp, actor, event, outcome)
    }

    private inline fun <reified T : Enum<T>> enumValueOrNull(name: String): T? =
        enumValues<T>().firstOrNull { it.name == name }

    companion object {
        const val DEFAULT_BUCKET_MS = 15L * 60L * 1000L
        const val DEFAULT_RETENTION_MS = 30L * 24L * 60L * 60L * 1000L
        const val DEFAULT_MAX_ENTRIES = 500
    }
}
