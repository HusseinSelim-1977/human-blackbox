package com.humanblackbox.mlfusion

import com.humanblackbox.core.Action
import com.humanblackbox.core.EventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AlertPolicyTest {
    private val verifiedFall = testDecision(0.80f, EventType.FALL, Action.CHECK_IN)

    @Test
    fun manualSosBypassesStaging() {
        val policy = AlertPolicy()
        val manual = testDecision(1f, EventType.UNKNOWN, Action.MANUAL_SOS)
        val outcome = policy.evaluate(manual, nowMs = 0L)
        assertEquals(Action.MANUAL_SOS, outcome.action)
        assertEquals(AlertStage.MANUAL_SOS, outcome.stage)
        assertSame(manual, policy.apply(manual, nowMs = 0L))
    }

    @Test
    fun nonEmergencyDecisionClearsPendingAlert() {
        val policy = AlertPolicy()
        val outcome = policy.evaluate(testDecision(0.20f, EventType.NORMAL), 0L)
        assertEquals(Action.NONE, outcome.action)
        assertEquals(AlertStage.CANCELLED, outcome.stage)
    }

    @Test
    fun unknownEventDoesNotStartAlertEvenAtHighProbability() {
        val policy = AlertPolicy()
        val outcome = policy.evaluate(testDecision(0.85f, EventType.UNKNOWN, Action.CHECK_IN), 0L)
        assertEquals(Action.NONE, outcome.action)
        assertEquals(AlertStage.IDLE, outcome.stage)
    }

    @Test
    fun verifiedDecisionStartsCheckIn() {
        val policy = AlertPolicy()
        val outcome = policy.evaluate(verifiedFall, 1_000L)
        assertEquals(Action.CHECK_IN, outcome.action)
        assertEquals(AlertStage.CHECK_IN, outcome.stage)
        assertEquals(1_000L + AlertPolicy.DEFAULT_CHECK_IN_TIMEOUT_MS, outcome.nextEscalationAtMs)
    }

    @Test
    fun noResponseEscalatesToSilentLocalAlert() {
        val policy = AlertPolicy()
        policy.evaluate(verifiedFall, 1_000L)
        val outcome = policy.evaluate(verifiedFall, 21_000L)
        assertEquals(Action.SILENT_LOCAL_ALERT, outcome.action)
        assertEquals(AlertStage.SILENT_LOCAL_ALERT, outcome.stage)
        assertEquals(21_000L + AlertPolicy.DEFAULT_LOCAL_ALERT_TIMEOUT_MS, outcome.nextEscalationAtMs)
    }

    @Test
    fun contactsNotifiedOnlyAboveNotifyFloor() {
        val policy = AlertPolicy()
        policy.evaluate(verifiedFall, 1_000L)
        policy.evaluate(verifiedFall, 21_000L)
        val belowFloor = policy.evaluate(testDecision(0.88f, EventType.FALL, Action.CHECK_IN), 36_000L)
        assertEquals(Action.SILENT_LOCAL_ALERT, belowFloor.action)
        val aboveFloor = policy.evaluate(testDecision(0.92f, EventType.FALL, Action.CHECK_IN), 36_000L)
        assertEquals(Action.NOTIFY_CONTACTS, aboveFloor.action)
        assertEquals(AlertStage.NOTIFY_CONTACTS, aboveFloor.stage)
    }

    @Test
    fun cancelFalseAlarmResetsAndAllowsFreshCheckIn() {
        val policy = AlertPolicy()
        policy.evaluate(verifiedFall, 1_000L)
        val cancelled = policy.cancelFalseAlarm()
        assertEquals(Action.NONE, cancelled.action)
        assertEquals(AlertStage.CANCELLED, cancelled.stage)
        val restarted = policy.evaluate(verifiedFall, 2_000L)
        assertEquals(Action.CHECK_IN, restarted.action)
        assertEquals(2_000L + AlertPolicy.DEFAULT_CHECK_IN_TIMEOUT_MS, restarted.nextEscalationAtMs)
    }

    @Test
    fun acknowledgementPreventsStaleEscalation() {
        val policy = AlertPolicy()
        policy.evaluate(verifiedFall, 1_000L)
        policy.acknowledgeCheckIn()
        val outcome = policy.evaluate(verifiedFall, 21_000L)
        assertEquals(Action.CHECK_IN, outcome.action)
        assertEquals(AlertStage.CHECK_IN, outcome.stage)
    }

    @Test
    fun belowCancelThresholdClearsPendingAlert() {
        val policy = AlertPolicy()
        policy.evaluate(verifiedFall, 1_000L)
        val outcome = policy.evaluate(testDecision(0.30f, EventType.FALL), 2_000L)
        assertEquals(Action.NONE, outcome.action)
        assertEquals(AlertStage.CANCELLED, outcome.stage)
    }

    @Test
    fun notifiedStageRequiresExplicitCancel() {
        val policy = AlertPolicy()
        policy.evaluate(verifiedFall, 1_000L)
        policy.evaluate(verifiedFall, 21_000L)
        policy.evaluate(testDecision(0.92f, EventType.FALL, Action.CHECK_IN), 36_000L)
        val lowEvidence = policy.evaluate(testDecision(0.20f, EventType.FALL), 37_000L)
        assertEquals(Action.NOTIFY_CONTACTS, lowEvidence.action)
        assertEquals(Action.NONE, policy.cancelFalseAlarm().action)
    }
}
