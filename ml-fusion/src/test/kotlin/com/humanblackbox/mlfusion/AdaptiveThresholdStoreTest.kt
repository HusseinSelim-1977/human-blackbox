package com.humanblackbox.mlfusion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveThresholdStoreTest {
    @Test
    fun zeroSensitivityUsesConservativeThresholds() {
        val thresholds = AdaptiveThresholdStore(0f).baseThresholds()
        assertEquals(0.82f, thresholds.escalationThreshold, 0.0001f)
        assertEquals(SafetyThresholds.MAX_CANCEL_THRESHOLD, thresholds.cancelThreshold)
        assertEquals(5, thresholds.hysteresisTicks)
    }

    @Test
    fun fullSensitivityNeverCrossesFloors() {
        val thresholds = AdaptiveThresholdStore(1f).baseThresholds()
        assertEquals(SafetyThresholds.MIN_ESCALATION_THRESHOLD, thresholds.escalationThreshold)
        assertEquals(0.22f, thresholds.cancelThreshold, 0.0001f)
        assertEquals(SafetyThresholds.MIN_HYSTERESIS_TICKS, thresholds.hysteresisTicks)
    }

    @Test
    fun settingsRoundTripPreservesOnlyScalarSensitivity() {
        val settings = AdaptiveThresholdStore(0.35f).settings()
        assertEquals(
            setOf(AdaptiveThresholdStore.KEY_SCHEMA, AdaptiveThresholdStore.KEY_SENSITIVITY),
            settings.keys,
        )
        val restored = AdaptiveThresholdStore.fromSettings(settings)
        assertEquals(0.35f, restored.sensitivity, 0.0001f)
    }

    @Test
    fun invalidSettingsFallBackToDefault() {
        val restored = AdaptiveThresholdStore.fromSettings(
            mapOf(
                AdaptiveThresholdStore.KEY_SCHEMA to AdaptiveThresholdStore.SCHEMA_VERSION,
                AdaptiveThresholdStore.KEY_SENSITIVITY to "not-a-float",
            ),
        )
        assertEquals(AdaptiveThresholdStore.DEFAULT_SENSITIVITY, restored.sensitivity)
    }

    @Test(expected = IllegalArgumentException::class)
    fun sensitivityOutOfRangeIsRejected() {
        AdaptiveThresholdStore(1.5f)
    }
}
