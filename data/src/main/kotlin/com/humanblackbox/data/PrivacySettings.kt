package com.humanblackbox.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.humanblackbox.core.Modality
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.privacyDataStore by preferencesDataStore("human_blackbox_privacy")

/**
 * Per-modality kill switches and explicit evidence-upload consent.
 *
 * Privacy rules: every modality switch is independent and must be set deliberately; upload
 * consent defaults to false and is never silently enabled by this module. These preferences
 * contain only flags — no tensors, learned feature vectors, raw sensor samples, PCM audio
 * or ARGB frames.
 */
class PrivacySettings(private val context: Context) {
    private val auditLogger by lazy { PrivacyAuditLogger(context.applicationContext) }

    private fun modalityKey(modality: Modality) =
        booleanPreferencesKey("modality_${modality.name.lowercase()}_enabled")

    fun modalityEnabled(modality: Modality): Flow<Boolean> =
        context.privacyDataStore.data.map { it[modalityKey(modality)] ?: true }

    suspend fun setModalityEnabled(modality: Modality, enabled: Boolean) {
        context.privacyDataStore.edit { it[modalityKey(modality)] = enabled }
        runCatching {
            auditLogger.append(
                AuditActor.MODALITY,
                if (enabled) AuditEvent.MODALITY_ENABLED else AuditEvent.MODALITY_DISABLED,
                AuditOutcome.ALLOWED,
            )
        }
    }

    fun evidenceUploadConsent(): Flow<Boolean> =
        context.privacyDataStore.data.map { it[CONSENT_EVIDENCE_UPLOAD] ?: false }

    suspend fun setEvidenceUploadConsent(enabled: Boolean) {
        context.privacyDataStore.edit { it[CONSENT_EVIDENCE_UPLOAD] = enabled }
        runCatching {
            auditLogger.append(
                AuditActor.CONSENT,
                if (enabled) AuditEvent.CONSENT_GRANTED else AuditEvent.CONSENT_REVOKED,
                AuditOutcome.ALLOWED,
            )
        }
    }

    companion object {
        val CONSENT_EVIDENCE_UPLOAD = booleanPreferencesKey("consent_evidence_upload")
    }
}
