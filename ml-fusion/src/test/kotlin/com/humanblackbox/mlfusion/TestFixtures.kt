package com.humanblackbox.mlfusion

import com.humanblackbox.core.Action
import com.humanblackbox.core.ClassProbability
import com.humanblackbox.core.Decision
import com.humanblackbox.core.Detection
import com.humanblackbox.core.EventType
import com.humanblackbox.core.Explanation
import com.humanblackbox.core.Modality

internal fun testDecision(
    probability: Float,
    event: EventType,
    action: Action = Action.NONE,
    timestampMs: Long = 100L,
): Decision = Decision(
    eventType = event,
    emergencyProbability = probability,
    explanation = Explanation("test decision", emptyMap()),
    recommendedAction = action,
    timestampMs = timestampMs,
)

internal fun testDetection(
    modality: Modality,
    event: EventType,
    probability: Float = 0.80f,
): Detection = Detection(
    modality = modality,
    probabilities = listOf(ClassProbability(event, probability)),
    unknownProbability = 0.10f,
    latencyMs = 1f,
    modelVersion = "test",
    timestampMs = 100L,
)

internal fun tickWithVotes(
    probability: Float,
    event: EventType,
    action: Action = Action.NONE,
    votes: Map<Modality, EventType> = emptyMap(),
    voteProbability: Float = 0.80f,
): VerificationTick = VerificationTick(
    fusionDecision = testDecision(probability, event, action),
    detections = votes.mapValues { (modality, label) ->
        testDetection(modality, label, voteProbability)
    },
)
