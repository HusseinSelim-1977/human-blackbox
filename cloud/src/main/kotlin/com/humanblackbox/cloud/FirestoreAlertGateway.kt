package com.humanblackbox.cloud

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.storage.FirebaseStorage
import com.humanblackbox.core.Action
import com.humanblackbox.core.Decision
import com.humanblackbox.core.EventType
import kotlinx.coroutines.tasks.await

/**
 * The complete set of fields allowed to leave the device for an alert.
 *
 * Privacy: this is deliberately metadata-only. Sensor payloads stay on-device
 * inside the locked evidence buffer; adding a payload field here would be a
 * privacy regression. `coarseLocation` must already be coarse (never GPS
 * coordinates). Evidence is a separate, explicit-consent capability below.
 */
data class MinimalAlert(
    val eventType: EventType,
    val confidence: Float,
    val timestampMs: Long,
    val coarseLocation: String?,
    val deviceStatus: String,
    val recommendedAction: Action,
) {
    init {
        require(confidence in 0f..1f) { "confidence must be in [0,1]" }
        require(timestampMs > 0L) { "timestampMs must be positive" }
    }
}

/** Drop the explanation and all evidence; keep only dispatchable metadata. */
fun Decision.toMinimalAlert(coarseLocation: String?, deviceStatus: String): MinimalAlert = MinimalAlert(
    eventType = eventType,
    confidence = emergencyProbability,
    timestampMs = timestampMs,
    coarseLocation = coarseLocation,
    deviceStatus = deviceStatus,
    recommendedAction = recommendedAction,
)

/** Firestore gateway for privacy-preserving alerts. */
class FirestoreAlertGateway(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {
    /** Writes exactly the six [MinimalAlert] fields; no payload is ever attached. */
    suspend fun sendMinimalAlert(alert: MinimalAlert) {
        val payload = hashMapOf(
            "eventType" to alert.eventType.name,
            "confidence" to alert.confidence.toDouble(),
            "timestampMs" to alert.timestampMs,
            "coarseLocation" to alert.coarseLocation,
            "deviceStatus" to alert.deviceStatus,
            "recommendedAction" to alert.recommendedAction.name,
        )
        firestore.collection("alerts").add(payload).await()
    }

    /** Compatibility entry point for callers holding a [Decision]. */
    suspend fun sendMinimalAlert(
        decision: Decision,
        coarseLocation: String?,
        deviceStatus: String,
    ) = sendMinimalAlert(decision.toMinimalAlert(coarseLocation, deviceStatus))

    /**
     * Topic subscription only. Never throws when Firebase is not configured;
     * pass a context to [FcmTopicRegistrar.registerForAlerts] when token
     * registration is also required.
     */
    suspend fun subscribeToAlerts(context: Context? = null): FcmRegistrationResult =
        context?.let { FcmTopicRegistrar(it).registerForAlerts() } ?: subscribeAlertsTopic()

    companion object {
        /** Returns null instead of throwing when Firebase has not been initialised. */
        fun createOrNull(context: Context): FirestoreAlertGateway? =
            if (FirebaseApp.getApps(context).isEmpty()) null
            else runCatching { FirestoreAlertGateway() }.getOrNull()
    }
}

internal val SAFE_EVIDENCE_EVENT_ID = Regex("[A-Za-z0-9_-]{1,128}")

/**
 * Encrypted-evidence upload is a separate capability and is impossible until
 * the user has explicitly granted consent. Only ciphertext may be passed to
 * [upload]; the caller is responsible for encrypting on-device first.
 */
class ConsentedEvidenceUploader(
    private val storage: FirebaseStorage = FirebaseStorage.getInstance(),
    private val hasConsent: () -> Boolean,
) {
    /**
     * Re-checks consent at the moment of upload (not merely at scheduling time)
     * so a revocation between enqueue and execution is honoured.
     */
    suspend fun upload(eventId: String, encryptedBytes: ByteArray) {
        require(eventId.matches(SAFE_EVIDENCE_EVENT_ID)) { "Invalid evidence event id" }
        require(encryptedBytes.isNotEmpty()) { "Encrypted evidence is required" }
        require(hasConsent()) { "Explicit evidence-upload consent is required" }
        storage.reference.child("evidence/$eventId.enc").putBytes(encryptedBytes).await()
    }

    companion object {
        /** Returns null instead of throwing when Firebase has not been initialised. */
        fun createOrNull(
            context: Context,
            hasConsent: () -> Boolean,
        ): ConsentedEvidenceUploader? =
            if (FirebaseApp.getApps(context).isEmpty()) null
            else runCatching { ConsentedEvidenceUploader(hasConsent = hasConsent) }.getOrNull()
    }
}
