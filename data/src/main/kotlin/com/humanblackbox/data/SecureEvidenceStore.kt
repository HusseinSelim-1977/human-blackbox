package com.humanblackbox.data

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Local-only encrypted evidence store.
 *
 * Evidence files are written with AES-256-GCM through Keystore-backed, versioned
 * [MasterKey] aliases managed by [KeyRotationManager]. The 60-second rolling buffer is never
 * uploaded by this class; upload is a separate, explicit-consent operation in the cloud
 * module. Each file name carries its key version, so migration can be interrupted safely and
 * old aliases remain usable. [listEventIds] exposes only validated, opaque IDs; no plaintext
 * paths, key material or payloads leave this class.
 */
class SecureEvidenceStore(
    context: Context,
    private val keyManager: KeyRotationManager = KeyRotationManager(context),
) {
    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "evidence-v2").apply { mkdirs() }
    private val staging = File(root, "staging").apply { mkdirs() }

    private fun evidenceFile(eventId: String, version: Int): File {
        require(SAFE_EVENT_ID.matches(eventId)) { "unsafe eventId" }
        require(version in KeyRotationManager.VERSION_1..KeyRotationManager.MAX_VERSION) { "unsafe key version" }
        val suffix = if (version == KeyRotationManager.VERSION_1) "" else ".v$version"
        return File(root, "$eventId$suffix.enc")
    }

    private fun encryptedFile(eventId: String, version: Int): EncryptedFile = EncryptedFile.Builder(
        appContext,
        evidenceFile(eventId, version),
        keyManager.masterKey(version),
        EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
    ).build()

    private fun encryptedFileAt(file: File, version: Int): EncryptedFile = EncryptedFile.Builder(
        appContext,
        file,
        keyManager.masterKey(version),
        EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
    ).build()

    private fun storedVersions(eventId: String): List<Int> =
        (KeyRotationManager.VERSION_1..KeyRotationManager.MAX_VERSION)
            .filter { evidenceFile(eventId, it).isFile }

    suspend fun save(eventId: String, plaintext: ByteArray): File = withContext(Dispatchers.IO) {
        val version = keyManager.currentVersion()
        encryptedFile(eventId, version).openFileOutput().use { it.write(plaintext) }
        evidenceFile(eventId, version)
    }

    suspend fun read(eventId: String): ByteArray? = withContext(Dispatchers.IO) {
        // Newest version first; a failed decrypt falls back to an older, still-present file.
        for (version in storedVersions(eventId).sortedDescending()) {
            val decrypted = runCatching {
                encryptedFile(eventId, version).openFileInput().use { it.readBytes() }
            }.getOrNull()
            if (decrypted != null) return@withContext decrypted
        }
        null
    }

    suspend fun delete(eventId: String): Boolean = withContext(Dispatchers.IO) {
        storedVersions(eventId).fold(false) { acc, version -> acc or evidenceFile(eventId, version).delete() }
    }

    suspend fun deleteAll(): Unit = withContext(Dispatchers.IO) {
        root.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
        staging.listFiles()?.forEach { it.delete() }
    }

    /** Returns only validated, opaque event IDs whose encrypted files still exist locally. */
    suspend fun listEventIds(): List<String> = withContext(Dispatchers.IO) {
        root.listFiles()?.mapNotNull { file ->
            if (!file.isFile || !file.name.endsWith(".enc")) return@mapNotNull null
            val stem = file.name.removeSuffix(".enc")
            val id = VERSIONED_NAME.matchEntire(stem)?.groupValues?.get(1) ?: stem
            if (SAFE_EVENT_ID.matches(id)) id else null
        }?.distinct()?.sorted().orEmpty()
    }

    /** Relative, validated `.enc` reference suitable for an outbound queue entry. */
    suspend fun encryptedRelativePath(eventId: String): String? = withContext(Dispatchers.IO) {
        val version = storedVersions(eventId).maxOrNull() ?: return@withContext null
        "${root.name}/${evidenceFile(eventId, version).name}"
    }

    /**
     * Migration step used by [KeyRotationManager.rotate]. Writes a staged ciphertext under
     * [targetVersion], verifies it decrypts, then atomically replaces the live file. The old
     * version is removed only after the replacement exists. Returns false without throwing
     * when a file cannot be migrated; the caller records the count, not the cause.
     */
    internal suspend fun reencryptToVersion(eventId: String, targetVersion: Int): Boolean =
        withContext(Dispatchers.IO) {
            val sourceVersion = storedVersions(eventId).maxOrNull() ?: return@withContext false
            val plaintext = runCatching {
                encryptedFile(eventId, sourceVersion).openFileInput().use { it.readBytes() }
            }.getOrNull() ?: return@withContext false
            val target = evidenceFile(eventId, targetVersion)
            val staged = File(staging, "$eventId.v$targetVersion.tmp")
            val replaced = try {
                staged.delete()
                encryptedFileAt(staged, targetVersion).openFileOutput().use { it.write(plaintext) }
                encryptedFileAt(staged, targetVersion).openFileInput().use { it.readBytes() }
                staged.renameTo(target) || (target.delete() && staged.renameTo(target))
            } catch (_: Exception) {
                false
            } finally {
                staged.delete()
            }
            if (replaced && sourceVersion != targetVersion) evidenceFile(eventId, sourceVersion).delete()
            replaced
        }

    companion object {
        private val SAFE_EVENT_ID = Regex("[A-Za-z0-9_-]{1,128}")
        private val VERSIONED_NAME = Regex("^(.+)\\.v([1-8])$")
    }
}
