package com.humanblackbox.core

import kotlin.collections.ArrayDeque

/** Lifecycle stage recorded for an event; no free-form stage names are stored. */
enum class AuditStage { DECISION, ALERT, CANCEL, DELIVERY, FAILURE, SYNC }

/** Coarse destination class, never an address, phone number or account id. */
enum class AuditDestination { LOCAL, CONTACT, EMERGENCY_SERVICES, CLOUD }

/** Fixed failure codes; diagnostic strings stay outside the audit buffer. */
enum class AuditFailure {
    SENSOR_UNAVAILABLE, MODEL_UNAVAILABLE, INFERENCE_TIMEOUT,
    DELIVERY_UNAVAILABLE, SYNC_UNAVAILABLE, STORAGE_UNAVAILABLE, PERMISSION_DENIED,
}

private val SAFE_REFERENCE = Regex("[A-Za-z0-9_-]{1,64}")

/**
 * Privacy-safe audit record: enums, counters and an opaque per-event token only.
 * The type has no field for sensor samples, media payloads or identifiable text.
 */
data class AuditRecord(
    val stage: AuditStage,
    val timestampMs: Long,
    val eventType: EventType? = null,
    val action: Action? = null,
    /** Emergency probability as a 0-10 decile; exact scores are not logged. */
    val probabilityBucket: Int? = null,
    val modalities: Set<Modality> = emptySet(),
    val destination: AuditDestination? = null,
    val failure: AuditFailure? = null,
    val referenceId: String? = null,
) {
    init {
        require(timestampMs >= 0L) { "timestampMs must be non-negative" }
        require(probabilityBucket == null || probabilityBucket in 0..10) {
            "probabilityBucket must be in 0..10"
        }
        require(stage != AuditStage.FAILURE || failure != null) {
            "FAILURE records require a failure code"
        }
        if (referenceId != null) {
            require(SAFE_REFERENCE.matches(referenceId)) {
                "referenceId must be a short opaque token"
            }
        }
    }
}

/**
 * Bounded, append-only in-memory audit trail. Callers supply only typed values;
 * raw sensor payloads and free-form notes cannot be represented here. Eviction
 * is the only removal and happens automatically at capacity.
 */
class PrivacyAuditLog(private val capacity: Int = DEFAULT_CAPACITY) {
    private val lock = Any()
    private val entries = ArrayDeque<AuditRecord>()

    init { require(capacity in 1..MAX_CAPACITY) { "capacity must be in 1..$MAX_CAPACITY" } }

    fun append(record: AuditRecord): AuditRecord = synchronized(lock) {
        if (entries.size == capacity) entries.removeFirst()
        entries.addLast(record)
        record
    }

    /** Immutable copy for transport or UI; records themselves never mutate. */
    fun snapshot(): List<AuditRecord> = synchronized(lock) { entries.toList() }

    fun size(): Int = synchronized(lock) { entries.size }

    fun latest(): AuditRecord? = synchronized(lock) { entries.lastOrNull() }

    fun recordDecision(
        timestampMs: Long,
        eventType: EventType,
        action: Action,
        emergencyProbability: Float,
        modalities: Set<Modality> = emptySet(),
        referenceId: String? = null,
    ): AuditRecord = append(
        AuditRecord(
            stage = AuditStage.DECISION,
            timestampMs = timestampMs,
            eventType = eventType,
            action = action,
            probabilityBucket = bucket(emergencyProbability),
            modalities = modalities.toSet(),
            referenceId = referenceId,
        )
    )

    fun recordAlert(
        timestampMs: Long,
        eventType: EventType,
        action: Action,
        modalities: Set<Modality> = emptySet(),
        referenceId: String? = null,
    ): AuditRecord = append(
        AuditRecord(
            stage = AuditStage.ALERT,
            timestampMs = timestampMs,
            eventType = eventType,
            action = action,
            modalities = modalities.toSet(),
            referenceId = referenceId,
        )
    )

    fun recordCancel(
        timestampMs: Long,
        eventType: EventType = EventType.UNKNOWN,
        referenceId: String? = null,
    ): AuditRecord = append(
        AuditRecord(
            stage = AuditStage.CANCEL,
            timestampMs = timestampMs,
            eventType = eventType,
            action = Action.NONE,
            referenceId = referenceId,
        )
    )

    fun recordDelivery(
        timestampMs: Long,
        destination: AuditDestination,
        eventType: EventType = EventType.UNKNOWN,
        referenceId: String? = null,
    ): AuditRecord = append(
        AuditRecord(
            stage = AuditStage.DELIVERY,
            timestampMs = timestampMs,
            eventType = eventType,
            destination = destination,
            referenceId = referenceId,
        )
    )

    fun recordFailure(
        timestampMs: Long,
        failure: AuditFailure,
        modalities: Set<Modality> = emptySet(),
        referenceId: String? = null,
    ): AuditRecord = append(
        AuditRecord(
            stage = AuditStage.FAILURE,
            timestampMs = timestampMs,
            modalities = modalities.toSet(),
            failure = failure,
            referenceId = referenceId,
        )
    )

    fun recordSync(
        timestampMs: Long,
        failure: AuditFailure? = null,
        referenceId: String? = null,
    ): AuditRecord = append(
        AuditRecord(
            stage = AuditStage.SYNC,
            timestampMs = timestampMs,
            failure = failure,
            referenceId = referenceId,
        )
    )

    companion object {
        const val DEFAULT_CAPACITY = 256
        const val MAX_CAPACITY = 4_096

        /** Coarsens a probability into a decile so exact scores are not logged. */
        fun bucket(probability: Float): Int = (clamp01(probability) * 10f).toInt().coerceIn(0, 10)
    }
}
