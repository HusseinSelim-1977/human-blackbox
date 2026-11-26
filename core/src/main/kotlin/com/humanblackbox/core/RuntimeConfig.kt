package com.humanblackbox.core

/** Power/privacy operating point selected by the user or an operator policy. */
enum class EnergyPolicy { LOW_POWER, BALANCED, HIGH_ALERT, PRIVACY }

/**
 * Typed scalars for the detection policy. Probabilities are in [0,1] and all
 * durations are milliseconds; tensor shapes and preprocessing stay in the
 * modality/fusion modules. PRIVACY tightens defaults but never overrides
 * explicit user consent for evidence sharing.
 */
data class RuntimeConfig(
    val emergencyThreshold: Float = 0.72f,
    val cancelThreshold: Float = 0.40f,
    val notifyContactsThreshold: Float = 0.90f,
    val hysteresisTicks: Int = 3,
    val checkInDelayMs: Long = 20_000L,
    val cooldownMs: Long = 30_000L,
    /** Time allowed for the user to confirm or cancel an alert: 3-10 s. */
    val verificationWindowMs: Long = 10_000L,
    val energyPolicy: EnergyPolicy = EnergyPolicy.BALANCED,
) {
    init {
        require(emergencyThreshold in 0f..1f) { "emergencyThreshold must be in [0,1]" }
        require(cancelThreshold in 0f..1f) { "cancelThreshold must be in [0,1]" }
        require(notifyContactsThreshold in 0f..1f) { "notifyContactsThreshold must be in [0,1]" }
        require(cancelThreshold < emergencyThreshold) { "cancelThreshold must be below emergencyThreshold" }
        require(emergencyThreshold <= notifyContactsThreshold) {
            "notifyContactsThreshold must be at least emergencyThreshold"
        }
        require(hysteresisTicks in 1..10) { "hysteresisTicks must be in 1..10" }
        require(checkInDelayMs >= 0L) { "checkInDelayMs must be non-negative" }
        require(cooldownMs >= 0L) { "cooldownMs must be non-negative" }
        require(verificationWindowMs in 3_000L..10_000L) {
            "verificationWindowMs must be in 3000..10000"
        }
    }

    val verificationWindowSeconds: Float get() = verificationWindowMs / 1_000f

    /** Inference cadence suggested to the sensor scheduler for this policy. */
    val samplingIntervalMs: Long = when (energyPolicy) {
        EnergyPolicy.LOW_POWER -> 1_000L
        EnergyPolicy.BALANCED -> 500L
        EnergyPolicy.HIGH_ALERT -> 250L
        EnergyPolicy.PRIVACY -> 1_000L
    }

    val allowBackgroundInference: Boolean
        get() = energyPolicy == EnergyPolicy.BALANCED || energyPolicy == EnergyPolicy.HIGH_ALERT

    /** Policy-level brake only; consent is still required by the uploader. */
    val allowEvidenceUpload: Boolean get() = energyPolicy != EnergyPolicy.PRIVACY

    fun isEmergency(probability: Float): Boolean = clamp01(probability) >= emergencyThreshold

    fun isCancelled(probability: Float): Boolean = clamp01(probability) <= cancelThreshold

    /** Single-probability fallback; hysteresis and fusion may refine this. */
    fun actionFor(probability: Float): Action {
        val value = clamp01(probability)
        return when {
            value >= notifyContactsThreshold -> Action.NOTIFY_CONTACTS
            value >= emergencyThreshold -> Action.SILENT_LOCAL_ALERT
            value > cancelThreshold -> Action.CHECK_IN
            else -> Action.NONE
        }
    }

    companion object { val DEFAULT: RuntimeConfig = RuntimeConfig() }
}
