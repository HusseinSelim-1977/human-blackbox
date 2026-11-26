package com.humanblackbox.data

import android.content.Context
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Versioned Android Keystore aliases for locally encrypted evidence.
 *
 * Design: every alias is `hbb_evidence_master_v<N>` and the writer version is kept in a
 * preference file that contains no evidence metadata. Evidence file names carry their own
 * version suffix (`<eventId>.enc` for v1, `<eventId>.v<N>.enc` afterwards), so a read can
 * always pick the correct alias even if the process dies mid-migration. [rotate] commits the
 * new writer version before re-encrypting and never logs key material, paths or plaintext.
 *
 * The Keystore aliases themselves are retained: deleting an old alias would make legacy
 * files unreadable. Explicit user deletion of all evidence is handled by the repository.
 */
class KeyRotationManager(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val rotationMutex = Mutex()

    /** Version used for newly written evidence files. */
    @Synchronized
    fun currentVersion(): Int = preferences.getInt(KEY_VERSION, VERSION_1).coerceIn(VERSION_1, MAX_VERSION)

    fun aliasFor(version: Int): String = "$KEY_ALIAS_PREFIX$version"

    @Synchronized
    fun masterKey(version: Int): MasterKey {
        require(version in VERSION_1..MAX_VERSION) { "Unsupported key version" }
        return MasterKey.Builder(appContext, aliasFor(version))
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    /**
     * Re-encrypts every stored evidence file under the next alias.
     *
     * The new writer version is committed first; each file is then copied to a staged
     * ciphertext with the new alias and atomically renamed. Older versions of a file remain
     * until their replacement is in place, so interruption cannot turn a file unreadable.
     */
    suspend fun rotate(store: SecureEvidenceStore): RotationResult = rotationMutex.withLock {
        val from = currentVersion()
        require(from < MAX_VERSION) { "Key version is already at the maximum" }
        val target = from + 1
        // Fail before committing anything if the Keystore cannot create the next alias.
        masterKey(target)
        val ids = store.listEventIds()
        check(preferences.edit().putInt(KEY_VERSION, target).commit()) { "Could not persist key version" }
        var migrated = 0
        var skipped = 0
        for (id in ids) {
            if (store.reencryptToVersion(id, target)) migrated++ else skipped++
        }
        RotationResult(previousVersion = from, currentVersion = target, migratedFiles = migrated, skippedFiles = skipped)
    }

    data class RotationResult(
        val previousVersion: Int,
        val currentVersion: Int,
        val migratedFiles: Int,
        val skippedFiles: Int,
    )

    companion object {
        const val VERSION_1 = 1
        const val MAX_VERSION = 8
        private const val PREFERENCES_NAME = "human_blackbox_key_rotation"
        private const val KEY_VERSION = "current_key_version"
        private const val KEY_ALIAS_PREFIX = "hbb_evidence_master_v"
    }
}
