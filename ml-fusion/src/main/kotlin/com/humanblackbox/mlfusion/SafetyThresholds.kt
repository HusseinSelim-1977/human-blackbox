package com.humanblackbox.mlfusion

import com.humanblackbox.core.EventType

/**
 * Scalar thresholds for the safety-critical decision layer.
 *
 * Shapes and units:
 *  - `escalationThreshold`, `cancelThreshold`, `notifyThreshold`: Float in [0,1].
 *  - `hysteresisTicks`: Int number of consecutive detection ticks (3..6).
 *  - The learned fusion tensor is unchanged: input `[1, 8, 27]` float32
 *    (8 ticks x [AUDIO(8), MOTION(8), VISION(8), 3 global metadata]) and output
 *    `[1, 11]` float32 (emergency + 7 classes + 3 modality contributions).
 *    This layer consumes only the resulting scalar `Decision`.
 *
 * Every floor below is hard-coded and enforced by the constructor; neither
 * per-user sensitivity nor context flags can relax it.
 */
data class SafetyThresholds(
    val escalationThreshold: Float,
    val cancelThreshold: Float,
    val hysteresisTicks: Int,
    val notifyThreshold: Float = MIN_NOTIFY_THRESHOLD,
) {
    init {
        require(escalationThreshold >= MIN_ESCALATION_THRESHOLD &&
            escalationThreshold <= MAX_ESCALATION_THRESHOLD) {
            "escalationThreshold must be in [$MIN_ESCALATION_THRESHOLD, $MAX_ESCALATION_THRESHOLD]"
        }
        require(cancelThreshold in MIN_CANCEL_THRESHOLD..MAX_CANCEL_THRESHOLD) {
            "cancelThreshold must be in [$MIN_CANCEL_THRESHOLD, $MAX_CANCEL_THRESHOLD]"
        }
        require(escalationThreshold - cancelThreshold >= MIN_SEPARATION) {
            "escalationThreshold must exceed cancelThreshold by at least $MIN_SEPARATION"
        }
        require(notifyThreshold >= MIN_NOTIFY_THRESHOLD && notifyThreshold <= 1f) {
            "notifyThreshold must be in [$MIN_NOTIFY_THRESHOLD, 1]"
        }
        require(notifyThreshold - escalationThreshold >= MIN_NOTIFY_GAP) {
            "notifyThreshold must exceed escalationThreshold by at least $MIN_NOTIFY_GAP"
        }
        require(hysteresisTicks in MIN_HYSTERESIS_TICKS..MAX_HYSTERESIS_TICKS) {
            "hysteresisTicks must be in [$MIN_HYSTERESIS_TICKS, $MAX_HYSTERESIS_TICKS]"
        }
    }

    companion object {
        /** Never escalate below this probability, regardless of sensitivity/context. */
        const val MIN_ESCALATION_THRESHOLD = 0.60f
        /** Cap keeps a gap to the 0.90 notification floor. */
        const val MAX_ESCALATION_THRESHOLD = 0.85f
        /** Never cancel on a probability above this value. */
        const val MAX_CANCEL_THRESHOLD = 0.40f
        /** Lower bound for the cancel threshold itself. */
        const val MIN_CANCEL_THRESHOLD = 0.15f
        const val MIN_SEPARATION = 0.10f
        /** Never notify contacts below this probability. */
        const val MIN_NOTIFY_THRESHOLD = 0.90f
        const val MIN_NOTIFY_GAP = 0.04f
        const val MIN_HYSTERESIS_TICKS = 3
        const val MAX_HYSTERESIS_TICKS = 6

        val DEFAULT = SafetyThresholds(0.72f, 0.40f, 3, MIN_NOTIFY_THRESHOLD)
    }
}

/** UNKNOWN is the explicit INDETERMINATE result; NORMAL is an explicit all-clear. */
internal fun isActionableEvent(event: EventType): Boolean =
    event != EventType.UNKNOWN && event != EventType.NORMAL
