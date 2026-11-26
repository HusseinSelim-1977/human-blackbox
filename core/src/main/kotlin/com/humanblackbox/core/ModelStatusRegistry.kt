package com.humanblackbox.core

/** Operational state derived from load state, failures and inference latency. */
enum class ModelAvailability { READY, DEGRADED, UNAVAILABLE }

/** Why a model is degraded or disabled; codes only, no diagnostic free text. */
enum class DegradationReason {
    NOT_LOADED, LOAD_FAILURE, INFERENCE_TIMEOUT, REPEATED_FAILURE,
    ENERGY_POLICY, PRIVACY_POLICY, MANUAL_OVERRIDE,
}

data class ModelHealth(
    val availability: ModelAvailability,
    val reason: DegradationReason? = null,
)

/**
 * Point-in-time view of all heads. Callers use [activeModalities] for fusion
 * input and [degradedModalities] for monitoring; MANUAL is always ready
 * because it bypasses models entirely. Only metadata is exposed here, never
 * tensor buffers or sensor samples.
 */
data class ModelStatusSnapshot(
    val statuses: Map<Modality, ModelStatus>,
    val health: Map<Modality, ModelHealth>,
    val timestampMs: Long,
) {
    val activeModalities: Set<Modality>
        get() = health.filterValues { it.availability != ModelAvailability.UNAVAILABLE }.keys

    val degradedModalities: Set<Modality>
        get() = health.filterValues { it.availability == ModelAvailability.DEGRADED }.keys

    fun availability(modality: Modality): ModelAvailability = when (modality) {
        Modality.MANUAL -> ModelAvailability.READY
        else -> health[modality]?.availability ?: ModelAvailability.UNAVAILABLE
    }

    fun isUsable(modality: Modality): Boolean =
        availability(modality) != ModelAvailability.UNAVAILABLE
}

/**
 * Thread-safe status registry for model hot-swap and graceful degradation.
 * Each published [ModelStatus] carries the version/load/quantisation/latency
 * metadata used by the fusion head to mask missing modalities.
 */
class ModelStatusRegistry(
    /** Above this latency a loaded model is treated as degraded, not failed. */
    private val latencyBudgetMs: Float = 500f,
    /** Consecutive failures that make a loaded model unavailable. */
    private val failureLimit: Int = 3,
) {
    private val lock = Any()
    private val statuses = mutableMapOf<Modality, ModelStatus>()
    private val failures = mutableMapOf<Modality, Int>()
    private val lastReason = mutableMapOf<Modality, DegradationReason>()
    private val overrides = mutableMapOf<Modality, DegradationReason>()

    init {
        require(latencyBudgetMs > 0f) { "latencyBudgetMs must be positive" }
        require(failureLimit >= 1) { "failureLimit must be at least 1" }
    }

    /** Publishes a newly loaded or replaced model; a fresh load clears failures. */
    fun publish(status: ModelStatus): ModelStatus = synchronized(lock) {
        require(status.modality != Modality.MANUAL) { "MANUAL has no model" }
        statuses[status.modality] = status
        if (status.loaded) {
            failures.remove(status.modality)
            lastReason.remove(status.modality)
        }
        status
    }

    fun status(modality: Modality): ModelStatus? = synchronized(lock) { statuses[modality] }

    /** Updates the last inference time; slow calls count towards degradation. */
    fun recordInference(modality: Modality, latencyMs: Float): ModelStatus? = synchronized(lock) {
        require(modality != Modality.MANUAL) { "MANUAL has no model" }
        require(latencyMs >= 0f) { "latencyMs must be non-negative" }
        statuses[modality]?.let { current ->
            val updated = current.copy(lastInferenceMs = latencyMs)
            statuses[modality] = updated
            if (latencyMs > latencyBudgetMs) {
                failures[modality] = (failures[modality] ?: 0) + 1
                lastReason[modality] = DegradationReason.INFERENCE_TIMEOUT
            } else {
                failures.remove(modality)
                lastReason.remove(modality)
            }
            updated
        }
    }

    /** Marks a load or inference failure; repeated failures mask the modality. */
    fun recordFailure(
        modality: Modality,
        reason: DegradationReason = DegradationReason.LOAD_FAILURE,
    ): Unit = synchronized(lock) {
        require(modality != Modality.MANUAL) { "MANUAL has no model" }
        failures[modality] = (failures[modality] ?: 0) + 1
        lastReason[modality] = reason
        statuses[modality]?.let { statuses[modality] = it.copy(loaded = false) }
    }

    /** Operator/policy kill switch for one head, independent of load state. */
    fun deactivate(
        modality: Modality,
        reason: DegradationReason = DegradationReason.MANUAL_OVERRIDE,
    ): Unit = synchronized(lock) {
        require(modality != Modality.MANUAL) { "MANUAL cannot be deactivated" }
        overrides[modality] = reason
        lastReason[modality] = reason
    }

    /** Clears an override and prior failure counters for a recovered model. */
    fun reactivate(modality: Modality): Boolean = synchronized(lock) {
        require(modality != Modality.MANUAL) { "MANUAL cannot be deactivated" }
        val wasOverridden = overrides.remove(modality) != null
        failures.remove(modality)
        lastReason.remove(modality)
        wasOverridden
    }

    fun availability(modality: Modality): ModelAvailability = synchronized(lock) { health(modality).availability }

    fun isUsable(modality: Modality): Boolean =
        availability(modality) != ModelAvailability.UNAVAILABLE

    fun snapshot(nowMs: Long = System.currentTimeMillis()): ModelStatusSnapshot = synchronized(lock) {
        val known = statuses.keys + overrides.keys
        ModelStatusSnapshot(
            statuses = statuses.toMap(),
            health = known.associateWith(::health),
            timestampMs = nowMs,
        )
    }

    private fun health(modality: Modality): ModelHealth {
        if (modality == Modality.MANUAL) return ModelHealth(ModelAvailability.READY)
        overrides[modality]?.let { return ModelHealth(ModelAvailability.UNAVAILABLE, it) }
        val status = statuses[modality]
            ?: return ModelHealth(ModelAvailability.UNAVAILABLE, DegradationReason.NOT_LOADED)
        if (!status.loaded) {
            return ModelHealth(
                ModelAvailability.UNAVAILABLE,
                lastReason[modality] ?: DegradationReason.NOT_LOADED,
            )
        }
        val reason = lastReason[modality]
        if ((failures[modality] ?: 0) >= failureLimit) {
            return ModelHealth(
                ModelAvailability.UNAVAILABLE,
                reason ?: DegradationReason.REPEATED_FAILURE,
            )
        }
        val slow = status.lastInferenceMs?.let { it > latencyBudgetMs } == true
        if ((failures[modality] ?: 0) > 0 || slow) {
            return ModelHealth(
                ModelAvailability.DEGRADED,
                reason ?: DegradationReason.INFERENCE_TIMEOUT,
            )
        }
        return ModelHealth(ModelAvailability.READY)
    }
}
