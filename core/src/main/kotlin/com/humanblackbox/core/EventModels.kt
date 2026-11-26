package com.humanblackbox.core

import kotlin.math.max
import kotlin.math.min

/** Shared, serialisable contracts for every v2 module. */
enum class Modality { AUDIO, MOTION, VISION, FUSION, MANUAL }

enum class EventType {
    FALL, SUDDEN_IMPACT, MEDICAL_DISTRESS, VERBAL_DISTRESS,
    PHYSICAL_ASSAULT, FIRE_OR_SMOKE, UNKNOWN, NORMAL,
}

data class ClassProbability(
    val label: EventType,
    val probability: Float,
) {
    init { require(probability in 0f..1f) { "probability must be in [0,1]" } }
}

/** Every model returns calibrated multi-class probabilities, never a bare score. */
data class Detection(
    val modality: Modality,
    val probabilities: List<ClassProbability>,
    val unknownProbability: Float,
    val latencyMs: Float,
    val modelVersion: String,
    val timestampMs: Long,
) {
    fun top(): ClassProbability = probabilities.maxByOrNull { it.probability }
        ?: ClassProbability(EventType.UNKNOWN, unknownProbability)

    /** Ignore a modality whose evidence is dominated by the unknown/normal class. */
    val isInformative: Boolean
        get() = unknownProbability < 0.5f && top().label != EventType.NORMAL
}

data class Explanation(
    val summary: String,
    val perModality: Map<Modality, Float>,
    val evidenceWindowMs: Long = 60_000L,
)

data class Decision(
    val eventType: EventType,
    val emergencyProbability: Float,
    val explanation: Explanation,
    val recommendedAction: Action,
    val timestampMs: Long,
)

enum class Action { NONE, CHECK_IN, SILENT_LOCAL_ALERT, NOTIFY_CONTACTS, MANUAL_SOS }

/** Locked 60 s evidence snapshot; raw payloads stay on-device until consent. */
data class EvidenceBundle(
    val eventId: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val modalities: Set<Modality>,
    val encryptedPayload: ByteArray,
    val decision: Decision,
)

data class ModelStatus(
    val modality: Modality,
    val version: String,
    val loaded: Boolean,
    val quantized: Boolean,
    val lastInferenceMs: Float?,
)

fun clamp01(value: Float): Float = min(1f, max(0f, value))

/** Built-in class list shared by all three modality heads and the fusion head. */
val EVENT_CLASSES: List<EventType> = listOf(
    EventType.FALL,
    EventType.SUDDEN_IMPACT,
    EventType.MEDICAL_DISTRESS,
    EventType.VERBAL_DISTRESS,
    EventType.PHYSICAL_ASSAULT,
    EventType.FIRE_OR_SMOKE,
    EventType.NORMAL,
)

/** Count of learned actionable classes; UNKNOWN is an extra output logit. */
val CLASS_COUNT: Int = EVENT_CLASSES.size
val CLASS_LABELS: List<EventType> = EVENT_CLASSES

/** Contract implemented by audio, motion and vision heads. */
interface ModalityClassifier {
    val modality: Modality
    val modelVersion: String
    fun classify(input: FloatArray): Detection
}
