package com.humanblackbox.sensors

import com.humanblackbox.core.Modality

/** In-memory-only evidence payloads; none of these types has a persistence path. */
sealed interface EvidencePayload {
    data class Audio(val frame: AudioFrame) : EvidencePayload
    data class Motion(val sample: ImuSample) : EvidencePayload
    data class Vision(val frame: VisionFrame) : EvidencePayload

    fun deepCopy(): EvidencePayload = when (this) {
        is Audio -> Audio(AudioFrame(frame.pcm.copyOf(), frame.sampleRateHz, frame.timestampMs))
        is Motion -> Motion(sample.copy())
        is Vision -> Vision(VisionFrame(frame.argb.copyOf(), frame.width, frame.height, frame.timestampMs))
    }
}

/** One timestamped, framed piece of evidence plus small immutable metadata. */
data class EvidenceFrame(
    val modality: Modality,
    val timestampMs: Long,
    val payload: EvidencePayload,
    val metadata: Map<String, String> = emptyMap(),
) {
    internal fun deepCopy(): EvidenceFrame = copy(
        payload = payload.deepCopy(),
        metadata = LinkedHashMap(metadata),
    )
}

/**
 * Bounded, in-memory rolling evidence window (default 60 s). Entries are kept
 * sorted by timestamp; both time and approximate byte budget are enforced on
 * every append and snapshot. Snapshots are deep copies, so callers cannot
 * mutate retained samples. Nothing here serialises or persists raw media.
 *
 * Killing a modality must call [clear] with that modality; the owning service
 * does this in its kill-switch entry point. No disk I/O exists in :sensors.
 */
class RollingEvidenceBuffer(
    val windowMs: Long = DEFAULT_WINDOW_MS,
    val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    init {
        require(windowMs > 0) { "windowMs must be positive" }
        require(maxBytes > 0) { "maxBytes must be positive" }
    }

    private val lock = Any()
    private val entries = ArrayList<EvidenceFrame>()
    private var retainedBytes = 0L

    fun append(frame: AudioFrame, metadata: Map<String, String> = emptyMap()) {
        insert(
            EvidenceFrame(
                modality = Modality.AUDIO,
                timestampMs = frame.timestampMs,
                payload = EvidencePayload.Audio(
                    AudioFrame(frame.pcm.copyOf(), frame.sampleRateHz, frame.timestampMs),
                ),
                metadata = metadata,
            ),
        )
    }

    /** SensorEvent timestamps are monotonic; wall-clock receipt time is used for the window. */
    fun append(
        sample: ImuSample,
        metadata: Map<String, String> = emptyMap(),
        timestampMs: Long = System.currentTimeMillis(),
    ) {
        insert(
            EvidenceFrame(
                modality = Modality.MOTION,
                timestampMs = timestampMs,
                payload = EvidencePayload.Motion(sample.copy()),
                metadata = metadata,
            ),
        )
    }

    fun append(frame: VisionFrame, metadata: Map<String, String> = emptyMap()) {
        insert(
            EvidenceFrame(
                modality = Modality.VISION,
                timestampMs = frame.timestampMs,
                payload = EvidencePayload.Vision(
                    VisionFrame(frame.argb.copyOf(), frame.width, frame.height, frame.timestampMs),
                ),
                metadata = metadata,
            ),
        )
    }

    /** Inserts one already-tagged frame; duplicate raw arrays are copied here. */
    fun append(frame: EvidenceFrame) {
        val payloadMatches = when (frame.modality) {
            Modality.AUDIO -> frame.payload is EvidencePayload.Audio
            Modality.MOTION -> frame.payload is EvidencePayload.Motion
            Modality.VISION -> frame.payload is EvidencePayload.Vision
            else -> false
        }
        require(payloadMatches) { "modality ${frame.modality} does not match its payload" }
        insert(frame.deepCopy())
    }

    /** Inserts an already-copied frame; keeps the window sorted and bounded. */
    private fun insert(stored: EvidenceFrame) = synchronized(lock) {
        var index = entries.size
        while (index > 0 && entries[index - 1].timestampMs > stored.timestampMs) index--
        entries.add(index, stored)
        retainedBytes += approxBytes(stored)
        evictLocked(clock())
    }

    /** Deep-copied, inclusive time-range snapshot; expired entries are pruned first. */
    fun snapshot(fromMs: Long, toMs: Long = clock()): List<EvidenceFrame> {
        require(fromMs <= toMs) { "fromMs must not exceed toMs" }
        return synchronized(lock) {
            evictLocked(clock())
            entries.filter { it.timestampMs in fromMs..toMs }.map(EvidenceFrame::deepCopy)
        }
    }

    /** Convenience snapshot ending at the current wall clock. */
    fun snapshotLast(windowMs: Long = this.windowMs): List<EvidenceFrame> {
        require(windowMs > 0) { "windowMs must be positive" }
        val now = clock()
        return snapshot(now - windowMs, now)
    }

    /** Clears one modality, or the whole window when [modality] is null. */
    fun clear(modality: Modality? = null) = synchronized(lock) {
        if (modality == null) {
            entries.clear()
            retainedBytes = 0L
        } else {
            val iterator = entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.modality == modality) {
                    retainedBytes -= approxBytes(entry)
                    iterator.remove()
                }
            }
        }
    }

    fun clearAll() = clear(null)

    fun retainedBytes(): Long = synchronized(lock) { retainedBytes }

    fun retainedCount(): Int = synchronized(lock) { entries.size }

    private fun evictLocked(nowMs: Long) {
        val cutoff = nowMs - windowMs
        while (entries.isNotEmpty() &&
            (entries[0].timestampMs < cutoff || retainedBytes > maxBytes)
        ) {
            retainedBytes -= approxBytes(entries.removeAt(0))
        }
    }

    /** Approximate in-memory footprint; metadata overhead is charged conservatively. */
    private fun approxBytes(frame: EvidenceFrame): Long {
        val payloadBytes = when (val payload = frame.payload) {
            is EvidencePayload.Audio -> payload.frame.pcm.size * 4L
            is EvidencePayload.Motion -> 64L
            is EvidencePayload.Vision -> payload.frame.argb.size * 4L
        }
        return payloadBytes + frame.metadata.size * 64L
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 60_000L

        /** Caps raw vision frames (~0.4 MB each at 320x320) to bound heap use. */
        const val DEFAULT_MAX_BYTES = 32L * 1024L * 1024L
    }
}
