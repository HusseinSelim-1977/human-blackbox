package com.humanblackbox.mlfusion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextEngineTest {
    @Test
    fun unknownContextLeavesThresholdsUnchanged() {
        val base = AdaptiveThresholdStore(0.5f).baseThresholds()
        val effective = ContextEngine(setOf(ContextFlag.UNKNOWN)).effectiveThresholds(base)
        assertEquals(base, effective)
    }

    @Test
    fun vehicleNeverLowersEscalationFloor() {
        val base = AdaptiveThresholdStore(1f).baseThresholds()
        val effective = ContextEngine(setOf(ContextFlag.VEHICLE)).effectiveThresholds(base)
        assertTrue(effective.escalationThreshold >= SafetyThresholds.MIN_ESCALATION_THRESHOLD)
        assertTrue(effective.escalationThreshold > base.escalationThreshold)
        assertTrue(effective.cancelThreshold < base.cancelThreshold)
        assertTrue(effective.hysteresisTicks > base.hysteresisTicks)
    }

    @Test
    fun multipleFlagsAreCappedAndFloorsHold() {
        val base = AdaptiveThresholdStore(0f).baseThresholds()
        val effective = ContextEngine(
            setOf(
                ContextFlag.VEHICLE,
                ContextFlag.LOW_LIGHT,
                ContextFlag.NOISY,
                ContextFlag.POCKET,
                ContextFlag.UNKNOWN,
            ),
        ).effectiveThresholds(base)
        assertTrue(effective.escalationThreshold <= SafetyThresholds.MAX_ESCALATION_THRESHOLD)
        assertTrue(effective.cancelThreshold >= SafetyThresholds.MIN_CANCEL_THRESHOLD)
        assertTrue(effective.hysteresisTicks <= SafetyThresholds.MAX_HYSTERESIS_TICKS)
        assertTrue(effective.notifyThreshold >= SafetyThresholds.MIN_NOTIFY_THRESHOLD)
    }

    @Test
    fun unknownIsDroppedWhenConcreteFlagsExist() {
        val engine = ContextEngine(setOf(ContextFlag.UNKNOWN, ContextFlag.NOISY))
        assertEquals(setOf(ContextFlag.NOISY), engine.flags)
    }
}
