package com.humanblackbox.sensors

/** Shared motion contract: 100 Hz, 32 steps, 9 features per step. */
object ImuContracts {
    const val SAMPLE_RATE_HZ = 100
    const val WINDOW_STEPS = 32
    const val FEATURES = 9
}

/**
 * Encodes 32 consecutive 100 Hz IMU samples into a flat float32 tensor of
 * shape [32, 9] (batch shape [1, 32, 9] when passed to MotionClassifier).
 * Per timestep the nine channels are:
 *  0..2 linear acceleration xyz (gravity removed), 3..5 gyro xyz (rad/s),
 *  6 |linear accel|, 7 |gyro|, 8 |linear accel| - |gravity estimate|.
 * Values are clipped to [-32, 32], matching the classifier's training
 * preprocessing. A missing gyroscope contributes a zero sentinel for 3..5
 * and 7, never a fabricated rotation.
 *
 * :sensors cannot depend on :ml-motion (that module already depends here), so
 * this mirrors MotionClassifier.encodeWindow rather than importing it.
 */
class ImuFeatureEncoder {
    fun encode(samples: List<ImuSample>): FloatArray {
        require(samples.size == ImuContracts.WINDOW_STEPS) {
            "expected ${ImuContracts.WINDOW_STEPS} IMU samples"
        }
        var gravityX = 0f
        var gravityY = 0f
        var gravityZ = 0f
        val out = FloatArray(ImuContracts.WINDOW_STEPS * ImuContracts.FEATURES)
        samples.forEachIndexed { step, sample ->
            gravityX = GRAVITY_ALPHA * gravityX + (1f - GRAVITY_ALPHA) * sample.ax
            gravityY = GRAVITY_ALPHA * gravityY + (1f - GRAVITY_ALPHA) * sample.ay
            gravityZ = GRAVITY_ALPHA * gravityZ + (1f - GRAVITY_ALPHA) * sample.az
            val ax = clip(sample.ax - gravityX)
            val ay = clip(sample.ay - gravityY)
            val az = clip(sample.az - gravityZ)
            val gx = if (sample.gyroAvailable) clip(sample.gx) else 0f
            val gy = if (sample.gyroAvailable) clip(sample.gy) else 0f
            val gz = if (sample.gyroAvailable) clip(sample.gz) else 0f
            val base = step * ImuContracts.FEATURES
            out[base] = ax
            out[base + 1] = ay
            out[base + 2] = az
            out[base + 3] = gx
            out[base + 4] = gy
            out[base + 5] = gz
            out[base + 6] = kotlin.math.sqrt(ax * ax + ay * ay + az * az)
            out[base + 7] = if (sample.gyroAvailable) {
                kotlin.math.sqrt(gx * gx + gy * gy + gz * gz)
            } else {
                0f
            }
            out[base + 8] = out[base + 6] -
                kotlin.math.sqrt(gravityX * gravityX + gravityY * gravityY + gravityZ * gravityZ)
        }
        return out
    }

    private fun clip(value: Float): Float = value.coerceIn(-32f, 32f)

    companion object {
        private const val GRAVITY_ALPHA = 0.8f
    }
}

/** Fixed-size in-memory queue of the newest 320 ms of IMU samples. */
class ImuWindowBuffer(private val capacity: Int = ImuContracts.WINDOW_STEPS) {
    private val samples = ArrayDeque<ImuSample>()

    val size: Int get() = samples.size

    fun add(sample: ImuSample) {
        samples.addLast(sample)
        while (samples.size > capacity) samples.removeFirst()
    }

    /** Null until 32 real samples have arrived; gaps are never padded. */
    fun latestWindow(): List<ImuSample>? =
        if (samples.size == capacity) samples.toList() else null

    fun clear() = samples.clear()
}
