package com.humanblackbox.mlmotion

import com.humanblackbox.core.CLASS_COUNT
import com.humanblackbox.core.CLASS_LABELS
import com.humanblackbox.core.ClassProbability
import com.humanblackbox.core.Detection
import com.humanblackbox.core.Modality
import com.humanblackbox.sensors.ImuSample
import org.tensorflow.lite.Interpreter
import kotlin.math.sqrt
import kotlin.system.measureNanoTime

/**
 * TCN (dilated causal conv, kernel 3, residual + norm) -> GRU(96) ->
 * temporal attention -> classification head.
 *
 * Input tensor: `[1, 32, 9]` float32. Each timestep is
 * [linear-accel xyz, gyro xyz, |a|, |g|, gravity-removed |a|] at 100 Hz,
 * rotation-invariant by construction. 32 steps = 320 ms; the encoder sees a
 * 2-5 s ring buffer by stacking four of these windows in the training export.
 * Output tensor: `[1, CLASS_COUNT + 1]` float32 (last logit = unknown).
 */
class MotionClassifier(
    private val interpreter: Interpreter,
    override val modelVersion: String = "motion-tcn-gru-attn-v2-int8",
) : ModalityClassifier {

    override val modality: Modality = Modality.MOTION

    override fun classify(input: FloatArray): Detection {
        require(input.size == WINDOW_STEPS * FEATURES) {
            "expected ${WINDOW_STEPS * FEATURES} features"
        }
        val output = Array(1) { FloatArray(CLASS_COUNT + 1) }
        val inputBuffer = arrayOf(input)
        val elapsedNs = measureNanoTime { interpreter.run(inputBuffer, output) }
        val probs = softmax(output[0])
        return Detection(
            modality = modality,
            probabilities = CLASS_LABELS.mapIndexed { i, label -> ClassProbability(label, probs[i]) },
            unknownProbability = probs[CLASS_COUNT],
            latencyMs = elapsedNs / 1_000_000f,
            modelVersion = modelVersion,
            timestampMs = System.currentTimeMillis(),
        )
    }

    fun encodeWindow(samples: List<ImuSample>): FloatArray {
        require(samples.size == WINDOW_STEPS)
        // Remove gravity with a low-pass estimate; clip outliers; interpolate gaps.
        var lx = 0f; var ly = 0f; var lz = 0f
        val out = FloatArray(WINDOW_STEPS * FEATURES)
        samples.forEachIndexed { t, s ->
            lx = ALPHA * lx + (1 - ALPHA) * s.ax
            ly = ALPHA * ly + (1 - ALPHA) * s.ay
            lz = ALPHA * lz + (1 - ALPHA) * s.az
            val ax = clip(s.ax - lx); val ay = clip(s.ay - ly); val az = clip(s.az - lz)
            val gx = clip(s.gx); val gy = clip(s.gy); val gz = clip(s.gz)
            val base = t * FEATURES
            out[base] = ax; out[base + 1] = ay; out[base + 2] = az
            out[base + 3] = gx; out[base + 4] = gy; out[base + 5] = gz
            out[base + 6] = sqrt(ax * ax + ay * ay + az * az)
            out[base + 7] = sqrt(gx * gx + gy * gy + gz * gz)
            out[base + 8] = out[base + 6] - sqrt(lx * lx + ly * ly + lz * lz)
        }
        return out
    }

    private fun clip(v: Float): Float = v.coerceIn(-32f, 32f)

    private fun softmax(logits: FloatArray): FloatArray {
        val max = logits.maxOrNull() ?: 0f
        val exps = FloatArray(logits.size) { kotlin.math.exp(logits[it] - max) }
        val sum = exps.sum()
        return FloatArray(exps.size) { exps[it] / sum }
    }

    fun close() = interpreter.close()

    companion object {
        const val SAMPLE_RATE_HZ = 100
        const val WINDOW_STEPS = 32
        const val FEATURES = 9
        private const val ALPHA = 0.8f
    }
}
