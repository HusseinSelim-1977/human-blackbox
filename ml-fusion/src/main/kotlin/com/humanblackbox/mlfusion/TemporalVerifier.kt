package com.humanblackbox.mlfusion

import com.humanblackbox.core.Action
import com.humanblackbox.core.Decision
import com.humanblackbox.core.Detection
import com.humanblackbox.core.EventType
import com.humanblackbox.core.Explanation
import com.humanblackbox.core.Modality

/** One detection tick: the fused Decision plus the per-modality detections. */
data class VerificationTick(
    val fusionDecision: Decision,
    val detections: Map<Modality, Detection>,
)

/**
 * State names of the temporal verifier. The explicit INDETERMINATE state is
 * emitted as `EventType.UNKNOWN`; there is no custom indeterminate event enum.
 */
enum class VerificationState { IDLE, INDETERMINATE, CANDIDATE, CONFIRMED, CANCELLED }

/**
 * Safety gate in front of alert staging.
 *
 * Escalation requires (1) probability >= the effective escalation threshold,
 * (2) the same actionable event from at least two distinct modalities, and
 * (3) that combination sustained for [sustainedTicks] consecutive ticks. A gap
 * above the cancel threshold resets the high streak; low evidence for
 * `thresholds.hysteresisTicks` ticks cancels. `MANUAL_SOS` is returned
 * untouched without changing state.
 *
 * Retained state is scalar only (state, streaks, candidate event); detections
 * are consumed per tick and never stored. `Detection` contains probabilities,
 * not raw audio/video/IMU data.
 */
class TemporalVerifier(
    private var thresholds: SafetyThresholds = SafetyThresholds.DEFAULT,
    private val requiredModalities: Int = MIN_AGREEING_MODALITIES,
    private val sustainedTicks: Int = thresholds.hysteresisTicks,
) {
    var state: VerificationState = VerificationState.IDLE
        private set
    private var highStreak: Int = 0
    private var lowStreak: Int = 0
    private var candidateEvent: EventType = EventType.UNKNOWN

    init {
        require(requiredModalities >= MIN_AGREEING_MODALITIES) {
            "requiredModalities must be at least $MIN_AGREEING_MODALITIES"
        }
        require(sustainedTicks >= SafetyThresholds.MIN_HYSTERESIS_TICKS) {
            "sustainedTicks must be at least ${SafetyThresholds.MIN_HYSTERESIS_TICKS}"
        }
    }

    /** Changing thresholds also drops any in-flight streak (no mixed policy). */
    fun configure(newThresholds: SafetyThresholds) {
        thresholds = newThresholds
        reset()
    }

    fun verify(tick: VerificationTick, nowMs: Long = System.currentTimeMillis()): Decision {
        val incoming = tick.fusionDecision
        if (incoming.recommendedAction == Action.MANUAL_SOS) return incoming

        val probability = incoming.emergencyProbability.coerceIn(0f, 1f)
        val event = incoming.eventType
        val agreeing = agreeingModalities(tick, event)
        val strong = probability >= thresholds.escalationThreshold &&
            isActionableEvent(event) &&
            agreeing.size >= requiredModalities &&
            (candidateEvent == EventType.UNKNOWN || candidateEvent == event)
        val low = probability <= thresholds.cancelThreshold

        when {
            strong -> {
                candidateEvent = event
                highStreak++
                lowStreak = 0
                state = if (highStreak >= sustainedTicks) {
                    VerificationState.CONFIRMED
                } else {
                    VerificationState.CANDIDATE
                }
            }
            low -> {
                lowStreak++
                highStreak = 0
                if (lowStreak >= thresholds.hysteresisTicks) {
                    candidateEvent = EventType.UNKNOWN
                    state = VerificationState.CANCELLED
                } else if (state != VerificationState.CONFIRMED) {
                    candidateEvent = EventType.UNKNOWN
                    state = VerificationState.INDETERMINATE
                }
            }
            else -> {
                highStreak = 0
                lowStreak = 0
                if (state != VerificationState.CONFIRMED) {
                    candidateEvent = EventType.UNKNOWN
                    state = VerificationState.INDETERMINATE
                }
            }
        }

        return when (state) {
            VerificationState.CONFIRMED ->
                emit(incoming, candidateEvent, Action.CHECK_IN, state, nowMs)
            VerificationState.CANCELLED ->
                emit(incoming, EventType.NORMAL, Action.NONE, state, nowMs)
            else ->
                emit(incoming, EventType.UNKNOWN, Action.NONE, state, nowMs)
        }
    }

    /** Explicit user/system cancel of a suspected false alarm. */
    fun cancel(nowMs: Long = System.currentTimeMillis()): Decision {
        clearStreaks()
        state = VerificationState.CANCELLED
        return Decision(
            eventType = EventType.NORMAL,
            emergencyProbability = 0f,
            explanation = Explanation("Verifier cancelled pending emergency", emptyMap()),
            recommendedAction = Action.NONE,
            timestampMs = nowMs,
        )
    }

    fun reset() {
        clearStreaks()
        state = VerificationState.IDLE
    }

    private fun clearStreaks() {
        highStreak = 0
        lowStreak = 0
        candidateEvent = EventType.UNKNOWN
    }

    private fun agreeingModalities(tick: VerificationTick, event: EventType): Set<Modality> {
        if (!isActionableEvent(event)) return emptySet()
        return tick.detections.entries
            .filter { (modality, detection) ->
                modality in FusionEngine.MODALITIES &&
                    detection.isInformative &&
                    detection.top().let { vote ->
                        vote.label == event && vote.probability >= MIN_VOTE_CONFIDENCE
                    }
            }
            .map { it.key }
            .toSet()
    }

    private fun emit(
        source: Decision,
        event: EventType,
        action: Action,
        state: VerificationState,
        nowMs: Long,
    ): Decision = source.copy(
        eventType = event,
        recommendedAction = action,
        explanation = source.explanation.copy(
            summary = "${source.explanation.summary} [verifier:${state.name}]",
        ),
        timestampMs = nowMs,
    )

    companion object {
        /** Two independent modalities are required before any escalation. */
        const val MIN_AGREEING_MODALITIES = 2
        /** Hard floor for an individual modality vote. */
        const val MIN_VOTE_CONFIDENCE = 0.45f
    }
}
