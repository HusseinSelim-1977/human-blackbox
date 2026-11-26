package com.humanblackbox.cloud

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await

/** Outcome of an FCM registration attempt; callers never have to catch it. */
enum class FcmRegistrationStatus { REGISTERED, UNAVAILABLE, FAILED }

data class FcmRegistrationResult(
    val status: FcmRegistrationStatus,
    val topic: String? = null,
)

/**
 * Best-effort FCM token registration and topic subscription.
 *
 * Graceful degradation: when no FirebaseApp exists (for example the operator
 * has not supplied google-services.json), this returns UNAVAILABLE instead of
 * throwing. Network/permission failures return FAILED. The token is fetched
 * only to confirm registration; it is never logged, persisted here, or sent
 * to Firestore. Callers may retry later via WorkManager; no retry loop lives
 * in this helper.
 */
class FcmTopicRegistrar(private val context: Context) {
    suspend fun registerForAlerts(): FcmRegistrationResult = registerTopic(ALERTS_TOPIC)

    suspend fun registerTopic(topic: String): FcmRegistrationResult {
        if (topic.isBlank() || topic.any { it == '/' }) {
            return FcmRegistrationResult(FcmRegistrationStatus.FAILED)
        }
        if (FirebaseApp.getApps(context).isEmpty()) {
            return FcmRegistrationResult(FcmRegistrationStatus.UNAVAILABLE)
        }
        return runCatching {
            val messaging = FirebaseMessaging.getInstance()
            messaging.token.await()
            messaging.subscribeToTopic(topic).await()
            FcmRegistrationResult(FcmRegistrationStatus.REGISTERED, topic)
        }.getOrElse { FcmRegistrationResult(FcmRegistrationStatus.FAILED) }
    }

    companion object {
        const val ALERTS_TOPIC = "alerts"
    }
}

/** Subscription-only path used by [FirestoreAlertGateway.subscribeToAlerts]. */
internal suspend fun subscribeAlertsTopic(): FcmRegistrationResult {
    if (FirebaseApp.getApps().isEmpty()) {
        return FcmRegistrationResult(FcmRegistrationStatus.UNAVAILABLE)
    }
    return runCatching {
        FirebaseMessaging.getInstance()
            .subscribeToTopic(FcmTopicRegistrar.ALERTS_TOPIC).await()
        FcmRegistrationResult(FcmRegistrationStatus.REGISTERED, FcmTopicRegistrar.ALERTS_TOPIC)
    }.getOrElse { FcmRegistrationResult(FcmRegistrationStatus.FAILED) }
}
