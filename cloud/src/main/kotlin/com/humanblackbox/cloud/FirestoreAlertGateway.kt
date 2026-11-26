package com.humanblackbox.cloud

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.storage.FirebaseStorage
import com.humanblackbox.core.Action
import com.humanblackbox.core.Decision
import com.humanblackbox.core.EventType
import com.humanblackbox.data.OutboundMetadata
import kotlinx.coroutines.tasks.await
import java.io.File

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

/** Queue representation of the same six fields; no other data is copied. */
fun MinimalAlert.toOutboundMetadata(): OutboundMetadata = OutboundMetadata(
    eventType = eventType.name,
    confidence = confidence,
    timestampMs = timestampMs,
    coarseLocation = coarseLocation,
    deviceStatus = deviceStatus,
    recommendedAction = recommendedAction.name,
)

/** Rebuilds a typed alert from a strictly validated six-field queue item. */
fun OutboundMetadata.toMinimalAlert(): MinimalAlert = MinimalAlert(
    eventType = EventType.valueOf(eventType),
    confidence = confidence,
    timestampMs = timestampMs,
    coarseLocation = coarseLocation,
    deviceStatus = deviceStatus,
    recommendedAction = Action.valueOf(recommendedAction),
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
internal val SAFE_ENCRYPTED_PATH = Regex("[A-Za-z0-9_-]+(/[A-Za-z0-9_-]+)*(\\.v[1-8])?\\.enc")

/**
 * Encrypted-evidence upload is a separate capability and is impossible until the user has
 * explicitly granted consent. The public API accepts only an event id and a validated local
 * `.enc` reference; ciphertext is read from that file on-device and never decrypted here.
 */
class ConsentedEvidenceUploader(
    private val context: Context,
    private val storage: FirebaseStorage = FirebaseStorage.getInstance(),
    private val hasConsent: suspend () -> Boolean,
) {
    /**
     * Re-reads consent at the moment of upload (not merely at scheduling time), after the
     * ciphertext is read, so a revocation is honoured immediately before [putBytes].
     */
    suspend fun upload(eventId: String, encryptedPath: String) {
        require(eventId.matches(SAFE_EVIDENCE_EVENT_ID)) { "Invalid evidence event id" }
        require(encryptedPath.matches(SAFE_ENCRYPTED_PATH) && ".." !in encryptedPath) {
            "Only local encrypted evidence references are accepted"
        }
        val encryptedBytes = readCiphertext(encryptedPath)
        require(hasConsent()) { "Explicit evidence-upload consent is required" }
        storage.reference.child("evidence/$eventId.enc").putBytes(encryptedBytes).await()
    }

    private fun readCiphertext(encryptedPath: String): ByteArray {
        val file = File(context.filesDir, encryptedPath).canonicalFile
        val filesRoot = context.filesDir.canonicalFile.toPath()
        require(file.extension == "enc") { "Only encrypted (.enc) evidence is uploadable" }
        require(file.toPath().startsWith(filesRoot)) { "Evidence reference is outside app storage" }
        require(file.isFile && file.length() in 1L..MAX_ENCRYPTED_BYTES) { "Evidence reference is invalid" }
        return file.readBytes()
    }

    companion object {
        const val MAX_ENCRYPTED_BYTES = 20L * 1024 * 1024

        /** Returns null instead of throwing when Firebase has not been initialised. */
        fun createOrNull(
            context: Context,
            hasConsent: suspend () -> Boolean,
        ): ConsentedEvidenceUploader? =
            if (FirebaseApp.getApps(context).isEmpty()) null
            else runCatching { ConsentedEvidenceUploader(context, hasConsent = hasConsent) }.getOrNull()
    }
}
