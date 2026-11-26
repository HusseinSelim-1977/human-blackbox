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
 * Evidence files are written with AES-256-GCM through Android Keystore-backed
 * [MasterKey]. The 60-second rolling buffer is never uploaded by this class;
 * upload is a separate, explicit-consent operation in the cloud module.
 * Key rotation is handled by versioning event file names; old keys remain in
 * the Keystore until explicitly deleted by the user. [listEventIds] exposes only
 * validated, opaque IDs for retention; no plaintext paths or payloads leave this class.
 */
class SecureEvidenceStore(context: Context) {
    private val root = File(context.filesDir, "evidence-v2").apply { mkdirs() }
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private fun evidenceFile(eventId: String): File {
        require(SAFE_EVENT_ID.matches(eventId)) { "unsafe eventId" }
        return File(root, "$eventId.enc")
    }

    private fun encryptedFile(eventId: String): EncryptedFile = EncryptedFile.Builder(
        context,
        evidenceFile(eventId),
        masterKey,
        EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
    ).build()

    suspend fun save(eventId: String, plaintext: ByteArray): File = withContext(Dispatchers.IO) {
        encryptedFile(eventId).openFileOutput().use { it.write(plaintext) }
        evidenceFile(eventId)
    }

    suspend fun read(eventId: String): ByteArray? = withContext(Dispatchers.IO) {
        val file = evidenceFile(eventId)
        if (!file.exists()) null else encryptedFile(eventId).openFileInput().use { it.readBytes() }
    }

    suspend fun delete(eventId: String): Boolean = withContext(Dispatchers.IO) {
        evidenceFile(eventId).delete()
    }

    suspend fun deleteAll(): Unit = withContext(Dispatchers.IO) {
        root.listFiles()?.forEach { it.delete() }
    }

    /** Returns only validated, opaque event IDs whose encrypted files still exist locally. */
    suspend fun listEventIds(): List<String> = withContext(Dispatchers.IO) {
        root.listFiles()?.mapNotNull { file ->
            val name = file.name
            val id = name.removeSuffix(".enc")
            if (file.isFile && name.endsWith(".enc") && SAFE_EVENT_ID.matches(id)) id else null
        }?.sorted().orEmpty()
    }

    companion object {
        private val SAFE_EVENT_ID = Regex("[A-Za-z0-9._-]+")
    }
}
