package com.humanblackbox.mlfusion

import kotlin.math.round

/**
 * Per-user sensitivity mapped to scalar decision thresholds.
 *
 * `sensitivity` is a single Float in [0,1]; 0 is most conservative, 1 is most
 * sensitive. Only these setting values are persisted. No audio, video, IMU,
 * probabilities, detections, histories or evidence payloads are ever stored
 * here. The caller may write [settings] to its own settings store.
 *
 * Mapping (all floors come from [SafetyThresholds]):
 *  - escalation: 0.82 -> 0.60 as sensitivity goes 0 -> 1 (never below the floor)
 *  - cancel:     0.40 -> 0.22 as sensitivity goes 0 -> 1 (harder to cancel)
 *  - hysteresis: 5 -> 3 ticks (never below the 3-tick floor)
 *  - notify:     fixed at the 0.90 floor
 */
class AdaptiveThresholdStore(initialSensitivity: Float = DEFAULT_SENSITIVITY) {
    var sensitivity: Float = DEFAULT_SENSITIVITY
        set(value) {
            require(!value.isNaN() && !value.isInfinite() && value in 0f..1f) {
                "sensitivity must be a finite Float in [0,1]"
            }
            field = value
        }

    init {
        sensitivity = initialSensitivity
    }

    fun baseThresholds(): SafetyThresholds {
        val escalation = lerp(0.82f, SafetyThresholds.MIN_ESCALATION_THRESHOLD, sensitivity)
        val cancel = lerp(SafetyThresholds.MAX_CANCEL_THRESHOLD, 0.22f, sensitivity)
        val ticks = round(
            lerp(5f, SafetyThresholds.MIN_HYSTERESIS_TICKS.toFloat(), sensitivity)
        ).toInt()
        return SafetyThresholds(
            escalationThreshold = escalation,
            cancelThreshold = cancel,
            hysteresisTicks = ticks.coerceIn(
                SafetyThresholds.MIN_HYSTERESIS_TICKS,
                SafetyThresholds.MAX_HYSTERESIS_TICKS,
            ),
            notifyThreshold = SafetyThresholds.MIN_NOTIFY_THRESHOLD,
        )
    }

    /** Settings-only snapshot: two scalar strings, never user data. */
    fun settings(): Map<String, String> = mapOf(
        KEY_SCHEMA to SCHEMA_VERSION,
        KEY_SENSITIVITY to sensitivity.toString(),
    )

    companion object {
        const val DEFAULT_SENSITIVITY = 0.5f
        const val KEY_SCHEMA = "fusion.threshold_schema"
        const val KEY_SENSITIVITY = "fusion.sensitivity"
        const val SCHEMA_VERSION = "1"

        /** Invalid or foreign settings fall back to the safe default. */
        fun fromSettings(settings: Map<String, String>): AdaptiveThresholdStore {
            if (settings[KEY_SCHEMA] != SCHEMA_VERSION) return AdaptiveThresholdStore()
            val parsed = settings[KEY_SENSITIVITY]?.toFloatOrNull()
            val safe = if (parsed != null && !parsed.isNaN() && !parsed.isInfinite() && parsed in 0f..1f) {
                parsed
            } else {
                DEFAULT_SENSITIVITY
            }
            return AdaptiveThresholdStore(safe)
        }

        private fun lerp(from: Float, to: Float, fraction: Float): Float =
            from + (to - from) * fraction
    }
}
