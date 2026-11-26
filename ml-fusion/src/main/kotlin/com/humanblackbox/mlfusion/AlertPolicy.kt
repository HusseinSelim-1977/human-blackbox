package com.humanblackbox.mlfusion

import com.humanblackbox.core.Action
import com.humanblackbox.core.Decision
import com.humanblackbox.core.EventType

/** Stages of the user-cancellable alert ladder (not a new EventType). */
enum class AlertStage { IDLE, CHECK_IN, SILENT_LOCAL_ALERT, NOTIFY_CONTACTS, CANCELLED, MANUAL_SOS }

data class AlertPolicyResult(
    val action: Action,
    val stage: AlertStage,
    /** Caller-supplied clock value at which the next escalation becomes due. */
    val nextEscalationAtMs: Long?,
    val reason: String,
)

/**
 * Converts a verified [Decision] into staged actions.
 *
 * Transitions (time is supplied by the caller as `nowMs`; no timers/device
 * access live here):
 *  - IDLE/CANCELLED + verified emergency -> CHECK_IN
 *  - CHECK_IN + no response for [checkInTimeoutMs] -> SILENT_LOCAL_ALERT
 *  - SILENT_LOCAL_ALERT + no response for [localAlertTimeoutMs] and p >= notify
 *    floor -> NOTIFY_CONTACTS
 *  - any pending stage + p <= cancel threshold or NORMAL -> CANCELLED/NONE
 *  - [cancelFalseAlarm] or [acknowledgeCheckIn] -> CANCELLED/IDLE
 *  - MANUAL_SOS input -> immediate MANUAL_SOS, all staging bypassed
 *
 * Notifications are never sent on the first tick. Once NOTIFY_CONTACTS or
 * MANUAL_SOS is reached, low evidence cannot unsend it; only an explicit
 * cancel/reset moves that latch. This class stores no evidence or raw data.
 */
class AlertPolicy(
    private var thresholds: SafetyThresholds = SafetyThresholds.DEFAULT,
    private val checkInTimeoutMs: Long = DEFAULT_CHECK_IN_TIMEOUT_MS,
    private val localAlertTimeoutMs: Long = DEFAULT_LOCAL_ALERT_TIMEOUT_MS,
) {
    var stage: AlertStage = AlertStage.IDLE
        private set
    private var stageEnteredMs: Long = 0L

    init {
        require(checkInTimeoutMs > 0L) { "checkInTimeoutMs must be positive" }
        require(localAlertTimeoutMs > 0L) { "localAlertTimeoutMs must be positive" }
    }

    fun configure(newThresholds: SafetyThresholds) {
        thresholds = newThresholds
        reset()
    }

    fun evaluate(decision: Decision, nowMs: Long): AlertPolicyResult {
        if (decision.recommendedAction == Action.MANUAL_SOS) {
            stage = AlertStage.MANUAL_SOS
            return result(Action.MANUAL_SOS, stage, null, "manual SOS bypasses staging")
        }

        val probability = decision.emergencyProbability.coerceIn(0f, 1f)
        val cancelledEvidence = decision.eventType == EventType.NORMAL ||
            probability <= thresholds.cancelThreshold
        if (cancelledEvidence) {
            return if (stage == AlertStage.NOTIFY_CONTACTS || stage == AlertStage.MANUAL_SOS) {
                result(actionFor(stage), stage, null, "alert already sent; explicit cancel required")
            } else {
                cancelFalseAlarm()
            }
        }

        val verified = decision.recommendedAction == Action.CHECK_IN &&
            isActionableEvent(decision.eventType) &&
            probability >= thresholds.escalationThreshold
        if (!verified) {
            return when (stage) {
                AlertStage.IDLE, AlertStage.CANCELLED ->
                    result(Action.NONE, stage, null, "no verified emergency")
                AlertStage.CHECK_IN, AlertStage.SILENT_LOCAL_ALERT, AlertStage.NOTIFY_CONTACTS ->
                    result(
                        actionFor(stage), stage, deadline(nowMs),
                        "holding ${stage.name}; evidence not fresh enough to escalate",
                    )
                AlertStage.MANUAL_SOS ->
                    result(Action.MANUAL_SOS, stage, null, "manual SOS latched")
            }
        }

        return when (stage) {
            AlertStage.IDLE, AlertStage.CANCELLED ->
                enter(AlertStage.CHECK_IN, nowMs, checkInTimeoutMs)
            AlertStage.CHECK_IN ->
                if (nowMs - stageEnteredMs >= checkInTimeoutMs) {
                    enter(AlertStage.SILENT_LOCAL_ALERT, nowMs, localAlertTimeoutMs)
                } else {
                    result(Action.CHECK_IN, stage, deadline(nowMs), "check-in pending")
                }
            AlertStage.SILENT_LOCAL_ALERT ->
                if (nowMs - stageEnteredMs >= localAlertTimeoutMs &&
                    probability >= thresholds.notifyThreshold
                ) {
                    enter(AlertStage.NOTIFY_CONTACTS, nowMs, null)
                } else {
                    result(
                        Action.SILENT_LOCAL_ALERT, stage, deadline(nowMs),
                        "silent local alert; waiting for response or notify floor",
                    )
                }
            AlertStage.NOTIFY_CONTACTS ->
                result(Action.NOTIFY_CONTACTS, stage, null, "contacts already notified")
            AlertStage.MANUAL_SOS ->
                result(Action.MANUAL_SOS, stage, null, "manual SOS latched")
        }
    }

    /** Convenience: same input Decision with the staged action applied. */
    fun apply(decision: Decision, nowMs: Long): Decision {
        if (decision.recommendedAction == Action.MANUAL_SOS) return decision
        val outcome = evaluate(decision, nowMs)
        return decision.copy(
            recommendedAction = outcome.action,
            explanation = decision.explanation.copy(
                summary = "${decision.explanation.summary} [alert:${outcome.stage.name}]",
            ),
            timestampMs = nowMs,
        )
    }

    fun cancelFalseAlarm(): AlertPolicyResult {
        stage = AlertStage.CANCELLED
        stageEnteredMs = 0L
        return result(Action.NONE, stage, null, "user cancelled false alarm")
    }

    /** User answered the check-in; no alert is sent. */
    fun acknowledgeCheckIn(): AlertPolicyResult {
        stage = AlertStage.IDLE
        stageEnteredMs = 0L
        return result(Action.NONE, stage, null, "check-in acknowledged")
    }

    fun reset() {
        stage = AlertStage.IDLE
        stageEnteredMs = 0L
    }

    private fun enter(
        next: AlertStage,
        nowMs: Long,
        timeoutMs: Long?,
    ): AlertPolicyResult {
        stage = next
        stageEnteredMs = nowMs
        return result(
            actionFor(next), next,
            if (timeoutMs == null) null else nowMs + timeoutMs,
            "entered ${next.name}",
        )
    }

    private fun deadline(nowMs: Long): Long? = when (stage) {
        AlertStage.CHECK_IN -> stageEnteredMs + checkInTimeoutMs
        AlertStage.SILENT_LOCAL_ALERT -> stageEnteredMs + localAlertTimeoutMs
        else -> null
    }.let { if (it == null || it < nowMs) null else it }

    private fun actionFor(current: AlertStage): Action = when (current) {
        AlertStage.IDLE, AlertStage.CANCELLED -> Action.NONE
        AlertStage.CHECK_IN -> Action.CHECK_IN
        AlertStage.SILENT_LOCAL_ALERT -> Action.SILENT_LOCAL_ALERT
        AlertStage.NOTIFY_CONTACTS -> Action.NOTIFY_CONTACTS
        AlertStage.MANUAL_SOS -> Action.MANUAL_SOS
    }

    private fun result(
        action: Action,
        stage: AlertStage,
        deadline: Long?,
        reason: String,
    ) = AlertPolicyResult(action, stage, deadline, reason)

    companion object {
        const val DEFAULT_CHECK_IN_TIMEOUT_MS = 20_000L
        const val DEFAULT_LOCAL_ALERT_TIMEOUT_MS = 15_000L
    }
}
