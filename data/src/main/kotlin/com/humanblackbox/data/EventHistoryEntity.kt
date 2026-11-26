package com.humanblackbox.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Metadata-only row for one detection event.
 *
 * Privacy rules: this table holds an opaque event ID, a timestamp, coarse decision metadata,
 * a human-readable summary and a flag indicating that encrypted evidence exists locally. It
 * never stores tensors, learned feature vectors, raw sensor samples, PCM audio or ARGB frames.
 * There is no upload path here; moving evidence off-device is a separate operation that must
 * respect the user's explicit consent.
 */
@Entity(
    tableName = "event_history",
    indices = [Index(value = ["timestampMs"])],
)
data class EventHistoryEntity(
    @PrimaryKey val eventId: String,
    val timestampMs: Long,
    val eventType: String,
    val confidence: Float,
    val recommendedAction: String,
    val summary: String,
    val hasEncryptedEvidence: Boolean,
)
