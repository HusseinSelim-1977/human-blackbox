package com.humanblackbox.data

import android.content.Context
import androidx.room.withTransaction
import com.humanblackbox.core.Action
import com.humanblackbox.core.Decision
import com.humanblackbox.core.EventType
import com.humanblackbox.core.Modality
import com.humanblackbox.core.ModelStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull

/** Coarse, non-identifying contribution label stored in the anonymized decision log. */
enum class ContributionBucket { LOW, MEDIUM, HIGH }

/** Metadata-only view of one event; [hasEncryptedEvidence] is a flag, never a payload path. */
data class EventHistory(
    val eventId: String,
    val timestampMs: Long,
    val eventType: EventType,
    val confidence: Float,
    val recommendedAction: Action,
    val summary: String,
    val hasEncryptedEvidence: Boolean,
)

/** Anonymized decision metadata. Contributions are buckets, not raw scores or features. */
data class AnonymizedDecisionLog(
    val id: Long,
    val timestampMs: Long,
    val eventType: EventType,
    val confidenceBucket: Int,
    val modalityContributions: Map<Modality, ContributionBucket>,
    val action: Action,
)

/**
 * Facade over event history, anonymized decision logs, model status and encrypted evidence.
 *
 * Privacy rules: all Room tables contain metadata only. No tensors, learned feature vectors,
 * raw sensor samples, PCM audio or ARGB frames are written to SQLite or preferences here. Raw
 * evidence stays encrypted on-device in [SecureEvidenceStore], and this facade never uploads
 * it; upload requires the user's explicit, separate consent in the cloud module. Privacy
 * toggles in [PrivacySettings] are never changed by deletion or retention operations.
 */
class BlackBoxDataRepository private constructor(
    private val database: BlackBoxDatabase,
    private val evidenceStore: SecureEvidenceStore,
    private val keyManager: KeyRotationManager,
    private val auditLogger: PrivacyAuditLogger,
) {
    private val dao: BlackBoxDao = database.blackBoxDao()

    fun observeHistory(): Flow<List<EventHistory>> =
        dao.observeEvents().map { rows -> rows.map { it.toDomain() } }

    fun observeHistorySince(sinceMs: Long): Flow<List<EventHistory>> =
        dao.observeEventsSince(sinceMs).map { rows -> rows.map { it.toDomain() } }

    fun observeDecisionLogs(): Flow<List<AnonymizedDecisionLog>> =
        dao.observeDecisionLogs().map { rows -> rows.map { it.toDomain() } }

    fun observeModelStatuses(): Flow<List<ModelStatus>> =
        dao.observeModelStatuses().map { rows -> rows.mapNotNull { it.toDomain() } }

    /**
     * Records decision metadata; callers must supply a non-identifying summary. Encrypted
     * evidence is saved separately through [SecureEvidenceStore] when the user allows it.
     */
    suspend fun recordEvent(
        eventId: String,
        decision: Decision,
        summary: String,
        hasEncryptedEvidence: Boolean,
    ) {
        val history = EventHistoryEntity(
            eventId = eventId,
            timestampMs = decision.timestampMs,
            eventType = decision.eventType.name,
            confidence = decision.emergencyProbability,
            recommendedAction = decision.recommendedAction.name,
            summary = summary,
            hasEncryptedEvidence = hasEncryptedEvidence,
        )
        val log = DecisionLogEntity(
            timestampMs = decision.timestampMs,
            eventType = decision.eventType.name,
            confidenceBucket = confidenceBucket(decision.emergencyProbability),
            modalityContributions = encodeContributions(decision.explanation.perModality),
            action = decision.recommendedAction.name,
        )
        database.withTransaction {
            dao.upsertEvent(history)
            dao.insertDecisionLog(log)
        }
        runCatching {
            auditLogger.append(
                AuditActor.DECISION,
                AuditEvent.DECISION_RECORDED,
                AuditOutcome.ALLOWED,
                decision.timestampMs,
            )
        }
    }

    suspend fun upsertModelStatus(status: ModelStatus) {
        dao.upsertModelStatus(
            ModelStatusEntity(
                modality = status.modality.name,
                version = status.version,
                loaded = status.loaded,
                quantized = status.quantized,
                lastInferenceMs = status.lastInferenceMs,
            ),
        )
    }

    suspend fun deleteEvent(eventId: String) {
        dao.deleteEvent(eventId)
        evidenceStore.delete(eventId)
    }

    /** Deletes all local metadata and encrypted evidence; privacy preferences are preserved. */
    suspend fun deleteAll() {
        database.withTransaction {
            dao.deleteAllEvents()
            dao.deleteAllDecisionLogs()
            dao.deleteAllModelStatuses()
        }
        evidenceStore.deleteAll()
        runCatching {
            auditLogger.append(AuditActor.STORAGE, AuditEvent.EVIDENCE_DELETED, AuditOutcome.ALLOWED)
        }
    }

    /**
     * Retention pruning by timestamp. Deletes expired metadata, then removes the matching
     * encrypted evidence files and any orphaned encrypted files left by an interrupted prune.
     */
    suspend fun pruneBefore(cutoffMs: Long) {
        val expiredIds = database.withTransaction {
            val ids = dao.eventIdsBefore(cutoffMs)
            dao.deleteEventsBefore(cutoffMs)
            dao.deleteDecisionLogsBefore(cutoffMs)
            ids
        }
        expiredIds.forEach { evidenceStore.delete(it) }
        val retainedIds = dao.allEventIds().toSet()
        evidenceStore.listEventIds()
            .filterNot { it in retainedIds }
            .forEach { evidenceStore.delete(it) }
        runCatching {
            auditLogger.append(AuditActor.RETENTION, AuditEvent.PRUNE_COMPLETED, AuditOutcome.ALLOWED)
            auditLogger.prune()
        }
    }

    /** Opaque on-device IDs only; no plaintext paths or payloads are exposed. */
    suspend fun storedEvidenceEventIds(): List<String> = evidenceStore.listEventIds()

    /** Re-encrypts evidence under the next Keystore alias without exposing key material. */
    suspend fun rotateEvidenceKeys(): KeyRotationManager.RotationResult {
        val result = keyManager.rotate(evidenceStore)
        runCatching {
            auditLogger.append(AuditActor.KEY_MANAGEMENT, AuditEvent.KEYS_ROTATED, AuditOutcome.ALLOWED)
        }
        return result
    }

    /** Bucketed, anonymized privacy audit rows for local inspection only. */
    suspend fun auditEntries(): List<PrivacyAuditEntry> = auditLogger.entries()

    companion object {
        fun create(context: Context): BlackBoxDataRepository = BlackBoxDataRepository(
            database = BlackBoxDatabase.get(context),
            evidenceStore = SecureEvidenceStore(context),
            keyManager = KeyRotationManager(context),
            auditLogger = PrivacyAuditLogger(context.applicationContext),
        )
    }
}

/** Decile index in 0..9, derived from a probability and never storing the exact value. */
private fun confidenceBucket(confidence: Float): Int =
    (confidence.coerceIn(0f, 1f) * 10f).toInt().coerceIn(0, 9)

private fun contributionBucket(value: Float): ContributionBucket = when {
    value < 0.25f -> ContributionBucket.LOW
    value < 0.75f -> ContributionBucket.MEDIUM
    else -> ContributionBucket.HIGH
}

private fun encodeContributions(contributions: Map<Modality, Float>): String =
    contributions.entries
        .sortedBy { it.key.name }
        .joinToString(";") { "${it.key.name}=${contributionBucket(it.value).name}" }

private fun decodeContributions(encoded: String): Map<Modality, ContributionBucket> =
    encoded.split(';').mapNotNull { token ->
        val parts = token.split('=', limit = 2)
        if (parts.size != 2) return@mapNotNull null
        val modality = enumValueOrNull<Modality>(parts[0]) ?: return@mapNotNull null
        val bucket = enumValueOrNull<ContributionBucket>(parts[1]) ?: return@mapNotNull null
        modality to bucket
    }.toMap()

private inline fun <reified T : Enum<T>> enumValueOrNull(name: String): T? =
    enumValues<T>().firstOrNull { it.name == name }

private fun EventHistoryEntity.toDomain() = EventHistory(
    eventId = eventId,
    timestampMs = timestampMs,
    eventType = enumValueOrNull<EventType>(eventType) ?: EventType.UNKNOWN,
    confidence = confidence,
    recommendedAction = enumValueOrNull<Action>(recommendedAction) ?: Action.NONE,
    summary = summary,
    hasEncryptedEvidence = hasEncryptedEvidence,
)

private fun DecisionLogEntity.toDomain() = AnonymizedDecisionLog(
    id = id,
    timestampMs = timestampMs,
    eventType = enumValueOrNull<EventType>(eventType) ?: EventType.UNKNOWN,
    confidenceBucket = confidenceBucket,
    modalityContributions = decodeContributions(modalityContributions),
    action = enumValueOrNull<Action>(action) ?: Action.NONE,
)

private fun ModelStatusEntity.toDomain(): ModelStatus? {
    val modality = enumValueOrNull<Modality>(modality) ?: return null
    return ModelStatus(
        modality = modality,
        version = version,
        loaded = loaded,
        quantized = quantized,
        lastInferenceMs = lastInferenceMs,
    )
}
