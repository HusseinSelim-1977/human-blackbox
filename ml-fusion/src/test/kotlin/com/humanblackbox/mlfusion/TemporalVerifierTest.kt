package com.humanblackbox.mlfusion

import com.humanblackbox.core.Action
import com.humanblackbox.core.EventType
import com.humanblackbox.core.Modality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TemporalVerifierTest {
    private val votes = mapOf(
        Modality.AUDIO to EventType.FALL,
        Modality.MOTION to EventType.FALL,
    )

    @Test
    fun singleStrongTickOnlyStartsCandidate() {
        val verifier = TemporalVerifier()
        val out = verifier.verify(
            tickWithVotes(0.80f, EventType.FALL, Action.CHECK_IN, votes),
            nowMs = 1_000L,
        )
        assertEquals(VerificationState.CANDIDATE, verifier.state)
        assertEquals(EventType.UNKNOWN, out.eventType)
        assertEquals(Action.NONE, out.recommendedAction)
    }

    @Test
    fun sustainedTwoModalityAgreementConfirms() {
        val verifier = TemporalVerifier()
        var out = verifier.verify(tickWithVotes(0.80f, EventType.FALL, Action.CHECK_IN, votes))
        out = verifier.verify(tickWithVotes(0.82f, EventType.FALL, Action.CHECK_IN, votes))
        assertEquals(VerificationState.CANDIDATE, verifier.state)
        assertEquals(Action.NONE, out.recommendedAction)
        out = verifier.verify(tickWithVotes(0.84f, EventType.FALL, Action.CHECK_IN, votes))
        assertEquals(VerificationState.CONFIRMED, verifier.state)
        assertEquals(EventType.FALL, out.eventType)
        assertEquals(Action.CHECK_IN, out.recommendedAction)
    }

    @Test
    fun oneModalityNeverConfirms() {
        val verifier = TemporalVerifier()
        repeat(4) {
            verifier.verify(
                tickWithVotes(
                    0.90f, EventType.FALL, Action.CHECK_IN,
                    mapOf(Modality.AUDIO to EventType.FALL),
                ),
            )
        }
        assertEquals(VerificationState.INDETERMINATE, verifier.state)
    }

    @Test
    fun disagreeingModalitiesNeverConfirm() {
        val verifier = TemporalVerifier()
        val conflicted = mapOf(
            Modality.AUDIO to EventType.FALL,
            Modality.MOTION to EventType.FIRE_OR_SMOKE,
        )
        repeat(4) {
            verifier.verify(tickWithVotes(0.90f, EventType.FALL, Action.CHECK_IN, conflicted))
        }
        assertEquals(VerificationState.INDETERMINATE, verifier.state)
    }

    @Test
    fun gapResetsSustainedStreak() {
        val verifier = TemporalVerifier()
        verifier.verify(tickWithVotes(0.80f, EventType.FALL, Action.CHECK_IN, votes))
        verifier.verify(tickWithVotes(0.80f, EventType.FALL, Action.CHECK_IN, votes))
        val gap = verifier.verify(tickWithVotes(0.55f, EventType.FALL, Action.CHECK_IN, votes))
        assertEquals(VerificationState.INDETERMINATE, verifier.state)
        assertEquals(EventType.UNKNOWN, gap.eventType)
        verifier.verify(tickWithVotes(0.80f, EventType.FALL, Action.CHECK_IN, votes))
        verifier.verify(tickWithVotes(0.80f, EventType.FALL, Action.CHECK_IN, votes))
        assertEquals(VerificationState.CANDIDATE, verifier.state)
        verifier.verify(tickWithVotes(0.80f, EventType.FALL, Action.CHECK_IN, votes))
        assertEquals(VerificationState.CONFIRMED, verifier.state)
    }

    @Test
    fun lowEvidenceForHysteresisTicksCancels() {
        val verifier = TemporalVerifier()
        repeat(3) {
            verifier.verify(tickWithVotes(0.85f, EventType.FALL, Action.CHECK_IN, votes))
        }
        assertEquals(VerificationState.CONFIRMED, verifier.state)
        var out = verifier.verify(tickWithVotes(0.30f, EventType.FALL))
        repeat(2) {
            out = verifier.verify(tickWithVotes(0.30f, EventType.FALL))
        }
        assertEquals(VerificationState.CANCELLED, verifier.state)
        assertEquals(EventType.NORMAL, out.eventType)
        assertEquals(Action.NONE, out.recommendedAction)
    }

    @Test
    fun explicitCancelEmitsAllClear() {
        val verifier = TemporalVerifier()
        verifier.verify(tickWithVotes(0.85f, EventType.FALL, Action.CHECK_IN, votes))
        val out = verifier.cancel(nowMs = 2_000L)
        assertEquals(VerificationState.CANCELLED, verifier.state)
        assertEquals(EventType.NORMAL, out.eventType)
        assertEquals(Action.NONE, out.recommendedAction)
    }

    @Test
    fun manualSosIsReturnedUntouchedAndStateIsUnchanged() {
        val verifier = TemporalVerifier()
        val manual = testDecision(1f, EventType.UNKNOWN, Action.MANUAL_SOS)
        val out = verifier.verify(VerificationTick(manual, emptyMap()))
        assertSame(manual, out)
        assertEquals(Action.MANUAL_SOS, out.recommendedAction)
        assertEquals(VerificationState.IDLE, verifier.state)
    }

    @Test
    fun configureResetsInFlightEvidence() {
        val verifier = TemporalVerifier()
        repeat(3) {
            verifier.verify(tickWithVotes(0.85f, EventType.FALL, Action.CHECK_IN, votes))
        }
        assertEquals(VerificationState.CONFIRMED, verifier.state)
        verifier.configure(SafetyThresholds.DEFAULT.copy(hysteresisTicks = 4))
        assertEquals(VerificationState.IDLE, verifier.state)
    }
}
