package com.humanblackbox.mlfusion

/** Coarse, privacy-safe context flags; never raw sensor data. */
enum class ContextFlag { VEHICLE, LOW_LIGHT, NOISY, POCKET, UNKNOWN }

private fun normalizeFlags(flags: Set<ContextFlag>): Set<ContextFlag> {
    val concrete = flags - ContextFlag.UNKNOWN
    return if (concrete.isEmpty()) setOf(ContextFlag.UNKNOWN) else concrete
}

/**
 * Conservative context adjustment of already-personalised thresholds.
 *
 * Adjustments may only make escalation harder (positive delta), cancellation
 * harder (negative delta) and confirmation slower (extra ticks). They can
 * never push a threshold below a [SafetyThresholds] floor. `UNKNOWN` is
 * neutral; multiple flags are capped so one noisy scene cannot freeze alerts.
 */
class ContextEngine(initialFlags: Set<ContextFlag> = setOf(ContextFlag.UNKNOWN)) {
    var flags: Set<ContextFlag> = normalizeFlags(initialFlags)
        set(value) {
            field = normalizeFlags(value)
        }

    fun effectiveThresholds(base: SafetyThresholds): SafetyThresholds {
        var escalationBoost = 0f
        var cancelReduction = 0f
        var extraTicks = 0
        if (ContextFlag.VEHICLE in flags) {
            escalationBoost += 0.05f; cancelReduction -= 0.03f; extraTicks += 1
        }
        if (ContextFlag.LOW_LIGHT in flags) {
            escalationBoost += 0.04f; cancelReduction -= 0.02f
        }
        if (ContextFlag.NOISY in flags) {
            escalationBoost += 0.04f; cancelReduction -= 0.02f; extraTicks += 1
        }
        if (ContextFlag.POCKET in flags) {
            escalationBoost += 0.06f; cancelReduction -= 0.03f; extraTicks += 1
        }

        val escalation = (base.escalationThreshold +
            escalationBoost.coerceAtMost(MAX_ESCALATION_BOOST))
            .coerceIn(
                SafetyThresholds.MIN_ESCALATION_THRESHOLD,
                SafetyThresholds.MAX_ESCALATION_THRESHOLD,
            )
        val cancelCeiling = minOf(
            SafetyThresholds.MAX_CANCEL_THRESHOLD,
            escalation - SafetyThresholds.MIN_SEPARATION,
        )
        val cancel = (base.cancelThreshold +
            cancelReduction.coerceAtLeast(-MAX_CANCEL_REDUCTION))
            .coerceIn(SafetyThresholds.MIN_CANCEL_THRESHOLD, cancelCeiling)
        val ticks = (base.hysteresisTicks + extraTicks.coerceAtMost(MAX_EXTRA_TICKS))
            .coerceIn(
                SafetyThresholds.MIN_HYSTERESIS_TICKS,
                SafetyThresholds.MAX_HYSTERESIS_TICKS,
            )
        val notify = maxOf(base.notifyThreshold, escalation + SafetyThresholds.MIN_NOTIFY_GAP)
            .coerceAtMost(1f)
        return SafetyThresholds(escalation, cancel, ticks, notify)
    }

    companion object {
        const val MAX_ESCALATION_BOOST = 0.10f
        const val MAX_CANCEL_REDUCTION = 0.06f
        const val MAX_EXTRA_TICKS = 2
    }
}
