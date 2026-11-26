package com.humanblackbox.mlfusion

import com.humanblackbox.core.Action
import com.humanblackbox.core.CLASS_COUNT
import com.humanblackbox.core.CLASS_LABELS
import com.humanblackbox.core.ClassProbability
import com.humanblackbox.core.Decision
import com.humanblackbox.core.Detection
import com.humanblackbox.core.EventType
import com.humanblackbox.core.Explanation
import com.humanblackbox.core.Modality
import org.tensorflow.lite.Interpreter
import kotlin.math.exp
import kotlin.math.max

/**
 * Learned explainable fusion: 1D-CNN -> GRU -> attention in TFLite.
 *
 * Input tensor: `[1, 8, 27]` float32.
 *   8 timesteps = last 8 detection ticks (~4 s at 2 Hz).
 *   27 features = AUDIO(8) + MOTION(8) + VISION(8) class probabilities
 *   (7 learned classes + unknown) + [presence ratio, mean confidence,
 *   informative ratio]. Missing modalities are zeroed (modality dropout).
 * Output tensor: `[1, 1 + 7 + 3]` float32 =
 *   [emergency_probability, 7 type probabilities, audio/motion/vision contributions].
 */
class FusionEngine(
    private val interpreter: Interpreter,
    override val modelVersion: String = "fusion-cnn-gru-attn-v2-int8",
) {
    private var consecutiveHigh = 0
    private var consecutiveLow = 0

    fun decide(
        history: List<Map<Modality, Detection>>,
        emergencyThreshold: Float = 0.72f,
        cancelThreshold: Float = 0.40f,
        hysteresisTicks: Int = 3,
        checkInDelayMs: Long = 20_000L,
    ): Decision {
        require(history.isNotEmpty() && history.size <= HISTORY_TICKS)
        val input = FloatArray(HISTORY_TICKS * FEATURES)
        for ((t, tick) in history.withIndex()) {
            val base = t * FEATURES
            var cursor = base
            MODALITIES.forEach { modality ->
                val detection = tick[modality]
                val probs = detection?.probabilities?.map { it.probability }
                CLASS_LABELS.indices.forEach { i -> input[cursor++] = probs?.getOrElse(i) { 0f } ?: 0f }
                input[cursor++] = detection?.unknownProbability ?: 0f
            }
            val present = MODALITIES.count { tick[it] != null }
            val confidences = MODALITIES.mapNotNull { tick[it]?.top()?.probability }
            input[cursor++] = present.toFloat() / MODALITIES.size
            input[cursor++] = if (confidences.isEmpty()) 0f else confidences.average().toFloat()
            input[cursor++] = MODALITIES.count { tick[it]?.isInformative == true }.toFloat() / MODALITIES.size
            require(cursor == base + FEATURES) {
                "Fusion input layout mismatch: wrote ${cursor - base} features, expected $FEATURES"
            }
        }
        val output = Array(1) { FloatArray(OUTPUT_SIZE) }
        interpreter.run(arrayOf(input), output)
        val raw = output[0]
        val emergency = raw[0].coerceIn(0f, 1f)
        val typeProbs = CLASS_LABELS.mapIndexed { i, label ->
            ClassProbability(label, raw.getOrElse(1 + i) { 0f }.coerceIn(0f, 1f))
        }
        val contributions = MODALITIES.mapIndexed { i, modality ->
            modality to raw.getOrElse(1 + CLASS_COUNT + i) { 0f }.coerceIn(0f, 1f)
        }.toMap()

        // Hysteresis prevents threshold flapping during the check-in window.
        if (emergency >= emergencyThreshold) {
            consecutiveHigh++; consecutiveLow = 0
        } else if (emergency <= cancelThreshold) {
            consecutiveLow++; consecutiveHigh = 0
        }
        val confirmed = consecutiveHigh >= hysteresisTicks
        val cancelled = consecutiveLow >= hysteresisTicks
        val event = if (confirmed) typeProbs.maxByOrNull { it.probability }?.label ?: EventType.UNKNOWN
        else if (cancelled) EventType.NORMAL else EventType.UNKNOWN
        val action = when {
            event == EventType.NORMAL || event == EventType.UNKNOWN -> Action.NONE
            emergency >= 0.90f -> Action.NOTIFY_CONTACTS
            else -> Action.CHECK_IN
        }
        return Decision(
            eventType = event,
            emergencyProbability = emergency,
            explanation = Explanation(
                summary = buildSummary(event, emergency, contributions),
                perModality = contributions,
            ),
            recommendedAction = action,
            timestampMs = System.currentTimeMillis(),
        )
    }

    /** Manual SOS bypasses every model, threshold and fuse decision path. */
    fun manualSos(): Decision = Decision(
        eventType = EventType.UNKNOWN,
        emergencyProbability = 1f,
        explanation = Explanation("Manual SOS activated by the user", emptyMap()),
        recommendedAction = Action.MANUAL_SOS,
        timestampMs = System.currentTimeMillis(),
    )

    private fun buildSummary(
        event: EventType,
        emergency: Float,
        contributions: Map<Modality, Float>,
    ): String {
        val top = contributions.maxByOrNull { it.value }
        return "Suspected ${event.name.replace('_', ' ').lowercase()} " +
            "(p=${"%.2f".format(emergency)}); strongest evidence: " +
            "${top?.key?.name?.lowercase() ?: "none"} (${"%.2f".format(top?.value ?: 0f)})."
    }

    fun reset() {
        consecutiveHigh = 0; consecutiveLow = 0
    }

    companion object {
        const val HISTORY_TICKS = 8
        const val FEATURES = 3 * (CLASS_COUNT + 1) + 3 // 8 probabilities per modality + global metadata
        const val OUTPUT_SIZE = 1 + CLASS_COUNT + 3
        val MODALITIES = listOf(Modality.AUDIO, Modality.MOTION, Modality.VISION)
    }
}
