package com.humanblackbox.mlvision

import com.humanblackbox.core.CLASS_COUNT
import com.humanblackbox.core.CLASS_LABELS
import com.humanblackbox.core.ClassProbability
import com.humanblackbox.core.Detection
import com.humanblackbox.core.Modality
import com.humanblackbox.sensors.VisionFrame
import org.tensorflow.lite.Interpreter
import kotlin.math.abs
import kotlin.math.exp
import kotlin.system.measureNanoTime

/**
 * YOLO11-nano detector + MoveNet/BlazePose-style keypoints, followed by a
 * lightweight temporal head (keypoint smoothing + transition features, no
 * optical flow).
 *
 * Detector input: `[1, 320, 320, 3]` float32 RGB in [0,1].
 * Pose input:     `[1, 320, 320, 3]` float32 RGB in [0,1].
 * Temporal head:  `[1, 17*2 + 6]` float32 (17 smoothed xy keypoints plus
 * normalised wrist/hip/head transition deltas).
 * Output:         `[1, CLASS_COUNT + 1]` float32 (last logit = unknown).
 *
 * Vision is user-disableable and frames are skipped dynamically to hold 5-15 FPS.
 */
class VisionClassifier(
    private val detector: Interpreter,
    private val pose: Interpreter,
    private val temporalHead: Interpreter,
    override val modelVersion: String = "vision-yolo11n-pose-v2-int8",
) {
    val modality: Modality = Modality.VISION

    private val smoothed = FloatArray(KEYPOINTS * 2)
    private var previous = FloatArray(KEYPOINTS * 2)

    fun classify(frame: VisionFrame, fps: Int = 10): Detection {
        require(fps in 5..15) { "fps must be within 5..15" }
        val rgb = normalise(frame)
        val boxes = Array(1) { Array(MAX_BOXES) { FloatArray(6) } }
        val keypoints = Array(1) { FloatArray(KEYPOINTS * 3) } // x, y, score
        val elapsedNs = measureNanoTime {
            detector.run(arrayOf(rgb), boxes)
            pose.run(arrayOf(rgb), keypoints)
        }
        val features = temporalFeatures(keypoints[0])
        val logits = Array(1) { FloatArray(CLASS_COUNT + 1) }
        temporalHead.run(arrayOf(features), logits)
        val probs = softmax(logits[0])
        return Detection(
            modality = modality,
            probabilities = CLASS_LABELS.mapIndexed { i, label -> ClassProbability(label, probs[i]) },
            unknownProbability = probs[CLASS_COUNT],
            latencyMs = elapsedNs / 1_000_000f,
            modelVersion = modelVersion,
            timestampMs = frame.timestampMs,
        )
    }

    private fun normalise(frame: VisionFrame): FloatArray {
        require(frame.width == INPUT_SIZE && frame.height == INPUT_SIZE)
        val out = FloatArray(INPUT_SIZE * INPUT_SIZE * 3)
        frame.argb.forEachIndexed { i, pixel ->
            val base = i * 3
            out[base] = ((pixel shr 16) and 0xFF) / 255f
            out[base + 1] = ((pixel shr 8) and 0xFF) / 255f
            out[base + 2] = (pixel and 0xFF) / 255f
        }
        return out
    }

    private fun temporalFeatures(raw: FloatArray): FloatArray {
        for (i in smoothed.indices) {
            val value = if (i % 3 == 2) raw.getOrElse(i) { 0f }
            else raw.getOrElse(i / 3 * 3 + i % 3) { 0f }
            smoothed[i] = SMOOTHING * smoothed[i] + (1 - SMOOTHING) * value
        }
        val out = FloatArray(KEYPOINTS * 2 + 6)
        // Repack xy pairs (drop confidence) then append transition deltas.
        var cursor = 0
        for (k in 0 until KEYPOINTS) {
            out[cursor++] = smoothed[k * 3]
            out[cursor++] = smoothed[k * 3 + 1]
        }
        val deltas = listOf(0, 5, 6, 7, 11, 12) // head, shoulders, wrists, hips
        deltas.forEach { k ->
            out[cursor++] = abs(smoothed[k * 3] - previous.getOrElse(k * 3) { smoothed[k * 3] })
            out[cursor++] = abs(smoothed[k * 3 + 1] - previous.getOrElse(k * 3 + 1) { smoothed[k * 3 + 1] })
        }
        previous = smoothed.copyOf()
        return out
    }

    private fun softmax(logits: FloatArray): FloatArray {
        val max = logits.maxOrNull() ?: 0f
        val exps = FloatArray(logits.size) { exp(logits[it] - max) }
        val sum = exps.sum()
        return FloatArray(exps.size) { exps[it] / sum }
    }

    fun close() {
        detector.close(); pose.close(); temporalHead.close()
    }

    companion object {
        const val INPUT_SIZE = 320
        const val KEYPOINTS = 17
        const val MAX_BOXES = 20
        private const val SMOOTHING = 0.65f
    }
}
