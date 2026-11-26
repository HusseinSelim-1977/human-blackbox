package com.humanblackbox.mlfusion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyThresholdsTest {
    @Test
    fun defaultThresholdsRespectFloors() {
        val thresholds = SafetyThresholds.DEFAULT
        assertTrue(thresholds.escalationThreshold >= SafetyThresholds.MIN_ESCALATION_THRESHOLD)
        assertTrue(thresholds.cancelThreshold <= SafetyThresholds.MAX_CANCEL_THRESHOLD)
        assertTrue(thresholds.hysteresisTicks >= SafetyThresholds.MIN_HYSTERESIS_TICKS)
        assertEquals(SafetyThresholds.MIN_NOTIFY_THRESHOLD, thresholds.notifyThreshold)
    }

    @Test(expected = IllegalArgumentException::class)
    fun escalationBelowFloorIsRejected() {
        SafetyThresholds(
            escalationThreshold = 0.50f,
            cancelThreshold = 0.40f,
            hysteresisTicks = 3,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun notifyBelowFloorIsRejected() {
        SafetyThresholds(
            escalationThreshold = 0.70f,
            cancelThreshold = 0.30f,
            hysteresisTicks = 3,
            notifyThreshold = 0.85f,
        )
    }
}
