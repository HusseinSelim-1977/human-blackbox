package com.humanblackbox.app

import android.content.Context
import android.content.SharedPreferences
import android.location.Location
import android.location.LocationManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/** Contact data is stored on-device only; this build never dials or messages anyone. */
data class EmergencyContact(
    val id: String,
    val name: String,
    val phone: String,
)

/** eventType/action are stored as enum names so history needs no model or network. */
data class LocalSosEvent(
    val id: String,
    val timestampMs: Long,
    val eventType: String,
    val action: String,
    val summary: String,
    val location: String?,
    val testMode: Boolean,
)

/**
 * Encrypted local storage for contacts, event history and location consent.
 * Uses the same Keystore-backed scheme as the data module; no cloud client is
 * constructed here. Empty storage renders honestly as empty UI.
 */
class LocalEventStore(context: Context) {
    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            "human_blackbox_app_local",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun contacts(): List<EmergencyContact> = prefs.getStringSet(KEY_CONTACTS, mutableSetOf())
        .orEmpty()
        .mapNotNull { raw ->
            runCatching {
                val json = JSONObject(raw)
                EmergencyContact(
                    id = json.getString("id"),
                    name = json.getString("name"),
                    phone = json.getString("phone"),
                )
            }.getOrNull()
        }
        .sortedBy { it.name }

    fun addContact(name: String, phone: String): EmergencyContact {
        val contact = EmergencyContact(UUID.randomUUID().toString(), name.trim(), phone.trim())
        prefs.edit().putStringSet(KEY_CONTACTS, (contacts() + contact).map(::encodeContact).toMutableSet()).apply()
        return contact
    }

    fun removeContact(id: String) {
        prefs.edit().putStringSet(KEY_CONTACTS, contacts().filterNot { it.id == id }.map(::encodeContact).toMutableSet()).apply()
    }

    fun events(): List<LocalSosEvent> = prefs.getStringSet(KEY_EVENTS, mutableSetOf())
        .orEmpty()
        .mapNotNull { raw ->
            runCatching {
                val json = JSONObject(raw)
                LocalSosEvent(
                    id = json.getString("id"),
                    timestampMs = json.getLong("timestampMs"),
                    eventType = json.getString("eventType"),
                    action = json.getString("action"),
                    summary = json.getString("summary"),
                    location = if (json.isNull("location")) null else json.getString("location"),
                    testMode = json.optBoolean("testMode", false),
                )
            }.getOrNull()
        }
        .sortedByDescending { it.timestampMs }

    fun addEvent(event: LocalSosEvent) {
        val keep = (events() + event)
            .sortedByDescending { it.timestampMs }
            .take(MAX_EVENTS)
            .map(::encodeEvent)
            .toMutableSet()
        prefs.edit().putStringSet(KEY_EVENTS, keep).apply()
    }

    fun clearEvents() {
        prefs.edit().remove(KEY_EVENTS).apply()
    }

    fun locationConsent(): Boolean = prefs.getBoolean(KEY_LOCATION_CONSENT, false)

    fun setLocationConsent(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LOCATION_CONSENT, enabled).apply()
    }

    private fun encodeContact(contact: EmergencyContact) = JSONObject()
        .put("id", contact.id)
        .put("name", contact.name)
        .put("phone", contact.phone)
        .toString()

    private fun encodeEvent(event: LocalSosEvent) = JSONObject()
        .put("id", event.id)
        .put("timestampMs", event.timestampMs)
        .put("eventType", event.eventType)
        .put("action", event.action)
        .put("summary", event.summary)
        .put("location", event.location ?: JSONObject.NULL)
        .put("testMode", event.testMode)
        .toString()

    companion object {
        private const val KEY_CONTACTS = "emergency_contacts"
        private const val KEY_EVENTS = "local_sos_events"
        private const val KEY_LOCATION_CONSENT = "coarse_location_consent"
        private const val MAX_EVENTS = 100
    }
}

/** Cached device fixes only, rounded to ~1 km. No location service is started. */
object CoarseLocation {
    fun read(context: Context): String? {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        val fix: Location? = providers.firstNotNullOfOrNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }
        return fix?.let { String.format(Locale.US, "%.2f, %.2f", it.latitude, it.longitude) }
    }
}
