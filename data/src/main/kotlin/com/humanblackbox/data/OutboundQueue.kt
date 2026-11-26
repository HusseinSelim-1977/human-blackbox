package com.humanblackbox.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * The only six fields permitted in a metadata alert. String fields are length-bounded and
 * the confidence/timestamp invariants mirror the cloud alert type. No sensor payload field
 * exists here, and the JSON parser rejects any extra key.
 */
data class OutboundMetadata(
    val eventType: String,
    val confidence: Float,
    val timestampMs: Long,
    val coarseLocation: String?,
    val deviceStatus: String,
    val recommendedAction: String,
) {
    init {
        require(eventType.isNotBlank() && eventType.length <= 64)
        require(confidence in 0f..1f)
        require(timestampMs > 0L)
        require(coarseLocation == null || coarseLocation.length <= 64)
        require(deviceStatus.isNotBlank() && deviceStatus.length <= 128)
        require(recommendedAction.isNotBlank() && recommendedAction.length <= 64)
    }

    internal fun toJson(): JSONObject = JSONObject()
        .put(KEY_EVENT_TYPE, eventType)
        .put(KEY_CONFIDENCE, confidence.toDouble())
        .put(KEY_TIMESTAMP, timestampMs)
        .put(KEY_LOCATION, coarseLocation ?: JSONObject.NULL)
        .put(KEY_DEVICE_STATUS, deviceStatus)
        .put(KEY_ACTION, recommendedAction)

    companion object {
        const val KEY_EVENT_TYPE = "eventType"
        const val KEY_CONFIDENCE = "confidence"
        const val KEY_TIMESTAMP = "timestampMs"
        const val KEY_LOCATION = "coarseLocation"
        const val KEY_DEVICE_STATUS = "deviceStatus"
        const val KEY_ACTION = "recommendedAction"

        private val ALLOWED_KEYS = setOf(KEY_EVENT_TYPE, KEY_CONFIDENCE, KEY_TIMESTAMP, KEY_LOCATION, KEY_DEVICE_STATUS, KEY_ACTION)

        /** Strict parser: unknown keys are rejected rather than silently forwarded. */
        fun fromJson(json: JSONObject): OutboundMetadata {
            require(json.length() == ALLOWED_KEYS.size) { "Metadata alert must have exactly six fields" }
            require(json.keys().asSequence().toSet() == ALLOWED_KEYS) { "Unexpected metadata alert field" }
            return OutboundMetadata(
                eventType = json.getString(KEY_EVENT_TYPE),
                confidence = json.getDouble(KEY_CONFIDENCE).toFloat(),
                timestampMs = json.getLong(KEY_TIMESTAMP),
                coarseLocation = if (json.isNull(KEY_LOCATION)) null else json.getString(KEY_LOCATION),
                deviceStatus = json.getString(KEY_DEVICE_STATUS),
                recommendedAction = json.getString(KEY_ACTION),
            )
        }
    }
}

enum class OutboundKind { METADATA_ALERT, ENCRYPTED_EVIDENCE_REF }

/** One queued item: either six-field metadata JSON or a validated local `.enc` reference. */
data class OutboundItem(
    val id: String,
    val kind: OutboundKind,
    val metadata: OutboundMetadata?,
    val encryptedPath: String?,
    val createdAtMs: Long,
    val attempts: Int,
) {
    init {
        require(SAFE_ID.matches(id)) { "invalid queue id" }
        require((metadata == null) != (encryptedPath == null)) { "queue item must carry exactly one body" }
        if (encryptedPath != null) require(isSafeEncryptedReference(encryptedPath))
    }

    companion object {
        private val SAFE_ID = Regex("[A-Za-z0-9_-]{1,128}")

        /** Only relative paths below the app's files directory with an `.enc` suffix. */
        fun isSafeEncryptedReference(path: String): Boolean =
            SAFE_RELATIVE_ENC_PATH.matches(path) && ".." !in path && !path.startsWith('/')
    }
}

internal val SAFE_RELATIVE_ENC_PATH = Regex("[A-Za-z0-9_-]+(/[A-Za-z0-9_-]+)*(\\.v[1-8])?\\.enc")

/**
 * Offline-first outbound queue. Items are either metadata-only alert JSON or a reference to
 * an already-encrypted local file; there is no field that could hold a sensor payload.
 *
 * The queue lives in a newline-delimited file rather than Room, so it needs no schema change
 * and can be rebuilt safely. Reads prune expired and over-cap items first (TTL, then oldest
 * first). Writes are serialized and every item is validated before and after serialization.
 */
class OutboundQueue(
    context: Context,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val maxItems: Int = DEFAULT_MAX_ITEMS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val file = File(context.applicationContext.filesDir, "outbound-queue/pending.jsonl")
    private val lock = Any()

    init {
        require(ttlMs > 0L && maxItems > 0)
    }

    /** Idempotent by [idempotencyKey] so a retried worker cannot duplicate an alert. */
    suspend fun enqueueMetadata(metadata: OutboundMetadata, idempotencyKey: String? = null): String =
        withContext(Dispatchers.IO) {
            val id = idempotencyKey?.let { "m-$it" } ?: "m-${metadata.timestampMs}-${abs(metadata.toJson().toString().hashCode())}"
            synchronized(lock) {
                if (readLocked().any { it.id == id }) id else {
                    appendLocked(
                        OutboundItem(
                            id = id,
                            kind = OutboundKind.METADATA_ALERT,
                            metadata = metadata,
                            encryptedPath = null,
                            createdAtMs = clock(),
                            attempts = 0,
                        ),
                    )
                    pruneLocked(clock())
                    id
                }
            }
        }

    /** Queues only an opaque reference; the referenced ciphertext is never copied here. */
    suspend fun enqueueEncryptedReference(
        eventId: String,
        relativeEncryptedPath: String,
        idempotencyKey: String? = null,
    ): String = withContext(Dispatchers.IO) {
        require(SAFE_EVENT_ID.matches(eventId)) { "invalid event id" }
        require(OutboundItem.isSafeEncryptedReference(relativeEncryptedPath)) { "invalid encrypted reference" }
        val id = idempotencyKey?.let { "e-$it" } ?: "e-${UUID.randomUUID().toString().replace("-", "")}"
        synchronized(lock) {
            if (readLocked().any { it.id == id }) id else {
                appendLocked(
                    OutboundItem(
                        id = id,
                        kind = OutboundKind.ENCRYPTED_EVIDENCE_REF,
                        metadata = null,
                        encryptedPath = relativeEncryptedPath,
                        createdAtMs = clock(),
                        attempts = 0,
                    ),
                )
                pruneLocked(clock())
                id
            }
        }
    }

    suspend fun next(kind: OutboundKind? = null): OutboundItem? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            pruneLocked(clock())
            readLocked().filter { kind == null || it.kind == kind }.minByOrNull { it.createdAtMs }
        }
    }

    suspend fun get(id: String): OutboundItem? = withContext(Dispatchers.IO) {
        synchronized(lock) { readLocked().firstOrNull { it.id == id } }
    }

    suspend fun remove(id: String): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val remaining = readLocked().filterNot { it.id == id }
            rewriteLocked(remaining)
            true
        }
    }

    suspend fun recordAttempt(id: String): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val items = readLocked()
            if (items.none { it.id == id }) false else {
                rewriteLocked(items.map { if (it.id == id) it.copy(attempts = it.attempts + 1) else it })
                true
            }
        }
    }

    suspend fun prune(): Int = withContext(Dispatchers.IO) {
        synchronized(lock) { pruneLocked(clock()) }
    }

    suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        synchronized(lock) {
            runCatching { file.delete() }
            Unit
        }
    }

    private fun appendLocked(item: OutboundItem) {
        file.parentFile?.mkdirs()
        file.appendText(encode(item) + '\n')
    }

    private fun readLocked(): List<OutboundItem> =
        if (!file.isFile) emptyList()
        else runCatching { file.readLines().mapNotNull(::decode) }.getOrDefault(emptyList())

    private fun rewriteLocked(items: List<OutboundItem>) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(items.joinToString("\n") { encode(it) } + if (items.isEmpty()) "" else "\n")
        }
    }

    private fun pruneLocked(nowMs: Long): Int {
        val cutoff = nowMs - ttlMs
        val existing = readLocked()
        val kept = existing.filter { it.createdAtMs >= cutoff }.takeLast(maxItems)
        if (kept.size == existing.size && file.isFile) return 0
        rewriteLocked(kept)
        return existing.size - kept.size
    }

    private fun encode(item: OutboundItem): JSONObject = JSONObject()
        .put("id", item.id)
        .put("kind", item.kind.name)
        .put("createdAtMs", item.createdAtMs)
        .put("attempts", item.attempts)
        .apply {
            if (item.metadata != null) put("metadata", item.metadata.toJson())
            else put("encryptedPath", item.encryptedPath)
        }

    private fun decode(line: String): OutboundItem? {
        val json = runCatching { JSONObject(line) }.getOrNull() ?: return null
        val id = json.optString("id")
        val kind = runCatching { OutboundKind.valueOf(json.optString("kind")) }.getOrNull() ?: return null
        val createdAt = json.optLong("createdAtMs", -1L)
        val attempts = json.optInt("attempts", -1)
        if (createdAt <= 0L || attempts < 0) return null
        return when (kind) {
            OutboundKind.METADATA_ALERT -> OutboundItem(
                id = id,
                kind = kind,
                metadata = runCatching { OutboundMetadata.fromJson(json.getJSONObject("metadata")) }.getOrNull() ?: return null,
                encryptedPath = null,
                createdAtMs = createdAt,
                attempts = attempts,
            )
            OutboundKind.ENCRYPTED_EVIDENCE_REF -> OutboundItem(
                id = id,
                kind = kind,
                metadata = null,
                encryptedPath = json.optString("encryptedPath"),
                createdAtMs = createdAt,
                attempts = attempts,
            )
        }
    }

    companion object {
        const val DEFAULT_TTL_MS = 7L * 24L * 60L * 60L * 1000L
        const val DEFAULT_MAX_ITEMS = 200
        private val SAFE_EVENT_ID = Regex("[A-Za-z0-9_-]{1,128}")
    }
}
