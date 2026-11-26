package com.humanblackbox.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Metadata-only runtime status of an on-device model.
 *
 * Holds no weights, tensors, training data, input samples or identifiable features. Status is
 * local-only; no upload path exists in this store.
 */
@Entity(tableName = "model_status")
data class ModelStatusEntity(
    @PrimaryKey val modality: String,
    val version: String,
    val loaded: Boolean,
    val quantized: Boolean,
    val lastInferenceMs: Float?,
)
