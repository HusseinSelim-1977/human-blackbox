package com.humanblackbox.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.humanblackbox.core.Action
import com.humanblackbox.core.Decision
import com.humanblackbox.core.EventType
import com.humanblackbox.core.Explanation

/**
 * Manual SOS entry point. It is deliberately independent of FusionEngine:
 * no interpreter, model asset, sensor stream, threshold or fuse decision is
 * consulted. A user assertion is therefore represented as a local Decision
 * with a sentinel probability of 1f, not as a calibrated model score; the UI
 * never renders that value as confidence.
 */
interface ManualSosController {
    fun trigger(testMode: Boolean): Decision
}

/** Default controller: fully local, usable without models or credentials. */
class LocalManualSosController : ManualSosController {
    override fun trigger(testMode: Boolean): Decision = Decision(
        eventType = EventType.UNKNOWN,
        emergencyProbability = 1f,
        explanation = Explanation(
            summary = if (testMode) {
                "TEST manual SOS requested by the user; no model, contact or cloud service used"
            } else {
                "Manual SOS requested by the user; no model, contact or cloud service used"
            },
            perModality = emptyMap(),
        ),
        recommendedAction = Action.MANUAL_SOS,
        timestampMs = System.currentTimeMillis(),
    )
}

/**
 * Posts the manual-SOS outcome through the platform NotificationManager.
 * Notification permission is requested separately; if the user denies it the
 * system drops the notification and the UI does not claim it was displayed.
 *
 * Wired: local notification + local event record only.
 * Placeholder: no emergency call, SMS, contact alert or cloud alert is sent.
 * TODO(cloud): only after explicit consent and a configured Firebase project,
 *   consider an opt-in alert gateway call; test mode must remain local-only.
 */
class LocalAlertNotifier(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun post(decision: Decision, testMode: Boolean) {
        createChannel()
        val openApp = Intent(appContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            0,
            openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = if (testMode) "TEST Manual SOS" else "Manual SOS"
        val notification = Notification.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sos_notification)
            .setContentTitle(title)
            .setContentText("Local event recorded. No call placed and no service contacted.")
            .setContentIntent(pendingIntent)
            .setCategory(Notification.CATEGORY_ALARM)
            .setAutoCancel(false)
            .build()
        manager.notify(if (testMode) TEST_NOTIFICATION_ID else SOS_NOTIFICATION_ID, notification)
    }

    private fun createChannel() {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Manual SOS (local only)",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "User-triggered SOS events stored and notified on this device only"
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "manual_sos_local"
        private const val SOS_NOTIFICATION_ID = 1001
        private const val TEST_NOTIFICATION_ID = 1002
    }
}
