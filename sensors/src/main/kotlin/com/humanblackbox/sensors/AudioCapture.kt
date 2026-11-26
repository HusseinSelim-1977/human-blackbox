package com.humanblackbox.sensors

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Shared audio contract with the :ml-audio head; no dependency cycle is introduced. */
object AudioContracts {
    const val SAMPLE_RATE_HZ = 16_000
    const val CHANNELS = 1

    /** 960 ms at 16 kHz. Model input for AudioClassifier is [1, 15360]. */
    const val WINDOW_SAMPLES = 15_360

    /** 480 ms hop between emitted overlapping windows. */
    const val HOP_SAMPLES = 7_680

    /** Log-mel tensor layout: [1, 96, 64, 1] float32. */
    const val MEL_FRAMES = 96
    const val MEL_BINS = 64
}

/**
 * Supplies a real log-mel spectrogram for one 960 ms window. The FFT/filterbank
 * is intentionally out of scope for :sensors: inject the trained export's
 * provider (or the :ml-audio implementation) at the wiring boundary.
 * Returned array must be row-major [MEL_FRAMES, MEL_BINS], i.e. 6,144 floats.
 */
fun interface LogMelFeatureProvider {
    fun compute(windowPcm: FloatArray): FloatArray
}

/**
 * Turns a continuous mono PCM stream into overlapping model-ready windows.
 * Raw audio never leaves memory and is never written to storage by this module.
 */
class AudioWindowPreprocessor(
    private val logMelProvider: LogMelFeatureProvider? = null,
) {
    private val window = FloatArray(AudioContracts.WINDOW_SAMPLES)
    private var filled = 0
    private var lastCompletedWindow: FloatArray? = null

    /**
     * Appends PCM samples in [-1, 1]. Every time the 15,360-sample window is
     * full, one [AudioFrame] is emitted and the newest 7,680 samples are kept
     * as the start of the next window (480 ms hop, 50 % overlap).
     * [timestampMs] is the wall-clock time of the read that completed the window.
     */
    fun accept(pcm: FloatArray, timestampMs: Long): List<AudioFrame> {
        val emitted = ArrayList<AudioFrame>(1)
        for (sample in pcm) {
            window[filled++] = sample
            if (filled == AudioContracts.WINDOW_SAMPLES) {
                val completed = window.copyOf()
                lastCompletedWindow = completed
                emitted += AudioFrame(
                    pcm = completed,
                    sampleRateHz = AudioContracts.SAMPLE_RATE_HZ,
                    timestampMs = timestampMs,
                )
                window.copyInto(window, 0, AudioContracts.HOP_SAMPLES, AudioContracts.WINDOW_SAMPLES)
                filled = AudioContracts.HOP_SAMPLES
            }
        }
        return emitted
    }

    /** Last completed 960 ms window, or null until one has been emitted. */
    fun latestWindow(): FloatArray? = lastCompletedWindow?.copyOf()

    /**
     * Pass-through for models that consume PCM directly: float32 [-1, 1],
     * shape [1, 15360]. No FFT is performed here.
     */
    fun pcmPassThrough(windowPcm: FloatArray): FloatArray {
        require(windowPcm.size == AudioContracts.WINDOW_SAMPLES) {
            "expected ${AudioContracts.WINDOW_SAMPLES} PCM samples"
        }
        return windowPcm.copyOf()
    }

    /**
     * Validates an injected provider's [96, 64] output and packs it as the
     * native-order direct ByteBuffer expected by TFLite: [1, 96, 64, 1].
     */
    fun logMelModelInput(windowPcm: FloatArray): ByteBuffer {
        val provider = logMelProvider
            ?: error("no LogMelFeatureProvider configured for this audio pipeline")
        val mel = provider.compute(windowPcm)
        require(mel.size == AudioContracts.MEL_FRAMES * AudioContracts.MEL_BINS) {
            "expected ${AudioContracts.MEL_FRAMES * AudioContracts.MEL_BINS} log-mel values"
        }
        return ByteBuffer.allocateDirect(4 * mel.size)
            .order(ByteOrder.nativeOrder())
            .also { buffer -> mel.forEach(buffer::putFloat) }
            .also { it.rewind() }
    }

    fun reset() {
        filled = 0
        lastCompletedWindow = null
    }
}

/**
 * Real AudioRecord capture at 16 kHz mono PCM 16-bit, converted to float32
 * [-1, 1] and windowed to 960 ms / 480 ms hop. Capture stays off until
 * [enable] is called (permission and consent are the app's responsibility).
 * When disabled, the record is released so no microphone samples are read.
 */
class AudioRecordSource(
    private val audioSource: Int = MediaRecorder.AudioSource.MIC,
    private val sampleRateHz: Int = AudioContracts.SAMPLE_RATE_HZ,
    initiallyEnabled: Boolean = false,
) : AudioFrameSource {
    private val enabledState = MutableStateFlow(initiallyEnabled)

    override val frames: Flow<AudioFrame> = callbackFlow {
        val preprocessor = AudioWindowPreprocessor()
        val shorts = ShortArray(READ_CHUNK_SAMPLES)
        var record: AudioRecord? = null

        readLoop@ while (isActive) {
            if (!enabledState.value) {
                record?.let(this@AudioRecordSource::releaseRecord)
                record = null
                preprocessor.reset()
                delay(IDLE_POLL_MS)
                continue
            }
            if (record == null) {
                val minBuffer = AudioRecord.getMinBufferSize(
                    sampleRateHz,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
                require(minBuffer > 0) { "16 kHz mono PCM 16-bit is unsupported on this device" }
                val created = AudioRecord(
                    audioSource,
                    sampleRateHz,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuffer, shorts.size * 2),
                )
                check(created.state == AudioRecord.STATE_INITIALIZED) {
                    "AudioRecord could not initialise; check RECORD_AUDIO permission and consent"
                }
                created.startRecording()
                record = created
            }
            val read = record!!.read(shorts, 0, shorts.size)
            if (read < 0) break@readLoop // AudioRecord error code; do not invent samples.
            if (read > 0) {
                val chunk = FloatArray(read) { shorts[it] / SHORT_TO_FLOAT }
                for (frame in preprocessor.accept(chunk, System.currentTimeMillis())) {
                    if (trySend(frame).isFailure) break@readLoop
                }
            }
        }
        record?.let(this@AudioRecordSource::releaseRecord)
        awaitClose { }
    }

    override fun enable(enabled: Boolean) {
        enabledState.value = enabled
    }

    private fun releaseRecord(record: AudioRecord) {
        if (record.state == AudioRecord.STATE_INITIALIZED) {
            runCatching { record.stop() }
        }
        record.release()
    }

    companion object {
        private const val SHORT_TO_FLOAT = 32_768f
        private const val READ_CHUNK_SAMPLES = 1_024
        private const val IDLE_POLL_MS = 100L
    }
}
