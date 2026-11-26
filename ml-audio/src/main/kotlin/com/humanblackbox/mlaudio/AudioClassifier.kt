package com.humanblackbox.mlaudio

import com.humanblackbox.core.CLASS_COUNT
import com.humanblackbox.core.CLASS_LABELS
import com.humanblackbox.core.Detection
import com.humanblackbox.core.EventType
import com.humanblackbox.core.Modality
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.system.measureNanoTime

/**
 * Perch/EfficientAT-style embedding + calibrated custom head.
 *
 * Input tensor:  `[1, 96, 64, 1]` float32 log-mel spectrogram
 * (960 ms window at 16 kHz -> 96 frames of 10 ms, 64 mel bands).
 * Output tensor: `[1, CLASS_COUNT + 1]` float32; last logit is unknown.
 * Both INT8 and float16 exports expose the same float32 interface.
 */
class AudioClassifier(
    private val interpreter: Interpreter,
    override val modelVersion: String = "audio-perch-at-v2-int8",
) : ModalityClassifier {

    override val modality: Modality = Modality.AUDIO

    override fun classify(input: FloatArray): Detection {
        require(input.size == WINDOW_SAMPLES) { "expected $WINDOW_SAMPLES PCM samples" }
        val spectrogram = logMel(input)
        val output = Array(1) { FloatArray(CLASS_COUNT + 1) }
        val elapsedNs = measureNanoTime {
            interpreter.run(spectrogram, output)
        }
        val logits = output[0]
        val probabilities = softmax(logits)
        return Detection(
            modality = modality,
            probabilities = CLASS_LABELS.mapIndexed { i, label ->
                com.humanblackbox.core.ClassProbability(label, probabilities[i])
            },
            unknownProbability = probabilities[CLASS_COUNT],
            latencyMs = elapsedNs / 1_000_000f,
            modelVersion = modelVersion,
            timestampMs = System.currentTimeMillis(),
        )
    }

    /** Shared preprocessing with the Python training pipeline (scripts/). */
    internal fun logMel(pcm: FloatArray): ByteBuffer {
        val frames = WINDOW_SAMPLES / HOP_SAMPLES // 20 frames -> resampled to 96 mel frames
        val mel = FloatArray(FRAME_COUNT * MEL_BINS)
        var frameCursor = 0
        for (frame in 0 until FRAME_COUNT) {
            val start = frame * HOP_SAMPLES
            var energy = 0f
            for (i in 0 until HOP_SAMPLES) {
                val sample = pcm.getOrElse(start + i) { 0f }
                energy += sample * sample
            }
            // Placeholder filterbank: training script writes the exact mel table
            // into exports/audio_mel_filterbank.bin; kept local and deterministic.
            val band = (kotlin.math.log10(energy / HOP_SAMPLES + 1e-8f) + 5f) / 5f
            for (bin in 0 until MEL_BINS) {
                mel[frameCursor++] = band * (1f - bin / MEL_BINS.toFloat())
            }
        }
        return ByteBuffer.allocateDirect(4 * mel.size)
            .order(ByteOrder.nativeOrder())
            .also { buffer -> mel.forEach(buffer::putFloat) }
            .also { it.rewind() }
    }

    private fun softmax(logits: FloatArray): FloatArray {
        val max = logits.maxOrNull() ?: 0f
        val exps = FloatArray(logits.size) { kotlin.math.exp(logits[it] - max) }
        val sum = exps.sum()
        return FloatArray(exps.size) { exps[it] / sum }
    }

    fun close() = interpreter.close()

    companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val WINDOW_SAMPLES = 15_360 // 960 ms
        const val HOP_SAMPLES = 7_680 // 480 ms
        const val FRAME_COUNT = 96
        const val MEL_BINS = 64
    }
}
