package com.humanblackbox.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Anonymized, metadata-only decision log row.
 *
 * Privacy rules: no event ID, account ID, device ID, exact score, tensor, learned feature
 * vector, raw sensor sample, PCM audio or ARGB frame is persisted. Confidence is a decile
 * bucket (0..9); modality contributions are coarse LOW/MEDIUM/HIGH labels. This table is
 * local-only and does not upload anything.
 */
@Entity(
    tableName = "decision_log",
    indices = [Index(value = ["timestampMs"])],
)
data class DecisionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long,
    val eventType: String,
    val confidenceBucket: Int,
    val modalityContributions: String,
    val action: String,
)
