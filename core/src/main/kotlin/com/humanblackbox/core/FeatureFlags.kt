package com.humanblackbox.core

/**
 * Where the current flag values came from. A failed remote fetch keeps the
 * local defaults active, so offline behaviour never depends on a network call.
 */
enum class ConfigSource { LOCAL_DEFAULT, REMOTE_OVERRIDE, OPERATOR_OVERRIDE }

/** Kill-switch keys; the string form is the stable remote-config contract. */
enum class FeatureFlag(val key: String, val defaultEnabled: Boolean) {
    AUDIO("audio", true),
    MOTION("motion", true),
    VISION("vision", true),
    FUSION("fusion", true),
    ;

    companion object {
        fun fromKey(key: String): FeatureFlag? = entries.firstOrNull {
            it.name.equals(key, ignoreCase = true) || it.key.equals(key, ignoreCase = true)
        }
    }
}

/**
 * Data-driven kill switches for every model path. Values are injected as a
 * plain key/value map by the host app; this module has no config or network
 * dependency. MANUAL is never switchable: manual SOS bypasses all models.
 */
data class FeatureFlags(
    val audio: Boolean = true,
    val motion: Boolean = true,
    val vision: Boolean = true,
    val fusion: Boolean = true,
    val source: ConfigSource = ConfigSource.LOCAL_DEFAULT,
    val revision: Int = 0,
) {
    init { require(revision >= 0) { "revision must be non-negative" } }

    fun isEnabled(modality: Modality): Boolean = when (modality) {
        Modality.AUDIO -> audio
        Modality.MOTION -> motion
        Modality.VISION -> vision
        Modality.FUSION -> fusion
        Modality.MANUAL -> true
    }

    /** Switchable model modalities only; MANUAL is always available. */
    fun enabledModalities(): Set<Modality> = listOf(
        Modality.AUDIO, Modality.MOTION, Modality.VISION, Modality.FUSION,
    ).filter(::isEnabled).toSet()

    /**
     * Applies known boolean keys and ignores unknown keys or malformed values.
     * Accepts booleans, strings ("true"/"false", "on"/"off", "1"/"0") and ints
     * so any remote-config provider can be adapted without changing this API.
     */
    fun withOverrides(
        overrides: Map<String, Any?>,
        source: ConfigSource = ConfigSource.REMOTE_OVERRIDE,
        revision: Int = if (this.revision == Int.MAX_VALUE) Int.MAX_VALUE else this.revision + 1,
    ): FeatureFlags {
        var next = this
        for ((key, value) in overrides) {
            val parsed = parseBoolean(value) ?: continue
            val flag = FeatureFlag.fromKey(key) ?: continue
            next = when (flag) {
                FeatureFlag.AUDIO -> next.copy(audio = parsed)
                FeatureFlag.MOTION -> next.copy(motion = parsed)
                FeatureFlag.VISION -> next.copy(vision = parsed)
                FeatureFlag.FUSION -> next.copy(fusion = parsed)
            }
        }
        return next.copy(source = source, revision = revision)
    }

    companion object {
        /** Offline-safe operating point: all local heads remain enabled. */
        val DEFAULT: FeatureFlags = FeatureFlags(
            audio = FeatureFlag.AUDIO.defaultEnabled,
            motion = FeatureFlag.MOTION.defaultEnabled,
            vision = FeatureFlag.VISION.defaultEnabled,
            fusion = FeatureFlag.FUSION.defaultEnabled,
        )

        private fun parseBoolean(value: Any?): Boolean? = when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> when (value.trim().lowercase()) {
                "true", "on", "enabled", "1" -> true
                "false", "off", "disabled", "0" -> false
                else -> null
            }
            else -> null
        }
    }
}
