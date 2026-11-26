package com.humanblackbox.sensors

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import com.humanblackbox.core.Modality
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlin.math.abs

/** 16 kHz mono PCM floats in [-1, 1]; 960 ms window / 480 ms hop. */
data class AudioFrame(val pcm: FloatArray, val sampleRateHz: Int = 16_000, val timestampMs: Long)

/**
 * One 100 Hz IMU instant in device-frame axes; gravity is still present.
 * When [gyroAvailable] is false the gyro triplet is a zero sentinel, not a
 * measured angular velocity; never treat it as a real gyroscope reading.
 */
data class ImuSample(
    val ax: Float, val ay: Float, val az: Float,
    val gx: Float, val gy: Float, val gz: Float,
    val timestampNs: Long,
    val gyroAvailable: Boolean = false,
)

/** 320x320 camera frame packed as ARGB ints for the vision head. */
data class VisionFrame(val argb: IntArray, val width: Int, val height: Int, val timestampMs: Long)

/** Real capture source for audio; implementations must stop capture in [enable]. */
interface AudioFrameSource {
    val frames: Flow<AudioFrame>
    fun enable(enabled: Boolean)
}

/** CameraX-style real frame source; [setFps] controls dynamic frame skipping. */
interface VisionFrameSource {
    val frames: Flow<VisionFrame>
    fun enable(enabled: Boolean)
    fun setFps(fps: Int)
}

interface SensorPipeline {
    val audio: Flow<AudioFrame>
    val imu: Flow<ImuSample>
    val vision: Flow<VisionFrame>
    fun enable(modality: Modality, enabled: Boolean)

    /** Observable kill-switch state, used by the service to clear evidence. */
    fun isEnabled(modality: Modality): StateFlow<Boolean>
}

/** Adapts plain Flows for callers that do not need source-level kill switches. */
private class FlowAudioFrameSource(flow: Flow<AudioFrame>) : AudioFrameSource {
    override val frames: Flow<AudioFrame> = flow
    override fun enable(enabled: Boolean) = Unit
}

private class FlowVisionFrameSource(flow: Flow<VisionFrame>) : VisionFrameSource {
    override val frames: Flow<VisionFrame> = flow
    override fun enable(enabled: Boolean) = Unit
    override fun setFps(fps: Int) = Unit
}

/**
 * SensorManager-backed 100 Hz IMU with optional gyroscope pairing, plus
 * platform-backed audio/vision sources. Listeners are unregistered while a
 * modality is disabled; no samples are emitted for disabled modalities.
 * Audio and vision are injected so ML modules stay platform-independent.
 */
class AndroidSensorPipeline(
    private val sensorManager: SensorManager,
    private val audioSource: AudioFrameSource,
    private val visionSource: VisionFrameSource,
) : SensorPipeline {
    constructor(
        sensorManager: SensorManager,
        audio: Flow<AudioFrame>,
        vision: Flow<VisionFrame>,
    ) : this(sensorManager, FlowAudioFrameSource(audio), FlowVisionFrameSource(vision))

    // Filtering here also stops injected plain Flows whose source has no switch.
    override val audio: Flow<AudioFrame> get() = audioSource.frames.filter { audioEnabled.value }
    override val vision: Flow<VisionFrame> get() = visionSource.frames.filter { visionEnabled.value }

    private val audioEnabled = MutableStateFlow(true)
    private val motionEnabled = MutableStateFlow(true)
    private val visionEnabled = MutableStateFlow(true)

    override fun enable(modality: Modality, enabled: Boolean) {
        when (modality) {
            Modality.AUDIO -> {
                audioEnabled.value = enabled
                audioSource.enable(enabled)
            }
            Modality.MOTION -> motionEnabled.value = enabled
            Modality.VISION -> {
                visionEnabled.value = enabled
                visionSource.enable(enabled)
            }
            Modality.FUSION, Modality.MANUAL -> Unit
        }
    }

    override fun isEnabled(modality: Modality): StateFlow<Boolean> = when (modality) {
        Modality.AUDIO -> audioEnabled.asStateFlow()
        Modality.MOTION -> motionEnabled.asStateFlow()
        Modality.VISION -> visionEnabled.asStateFlow()
        else -> error("no sensor stream for $modality")
    }

    override val imu: Flow<ImuSample> = callbackFlow {
        val lock = Any()
        val handler = Handler(Looper.getMainLooper())
        var gyroValues: FloatArray? = null
        var gyroTimestampNs = Long.MIN_VALUE
        var registered = false

        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (!motionEnabled.value) return
                if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
                    synchronized(lock) {
                        gyroValues = floatArrayOf(event.values[0], event.values[1], event.values[2])
                        gyroTimestampNs = event.timestamp
                    }
                    return
                }
                if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
                // Gyroscope timestamps share the SensorEvent nanosecond timebase.
                val gyro = synchronized(lock) {
                    if (gyroValues != null && abs(event.timestamp - gyroTimestampNs) <= GYRO_MATCH_NS) {
                        gyroValues!!.copyOf()
                    } else {
                        null
                    }
                }
                val sample = ImuSample(
                    ax = event.values[0], ay = event.values[1], az = event.values[2],
                    gx = gyro?.get(0) ?: 0f,
                    gy = gyro?.get(1) ?: 0f,
                    gz = gyro?.get(2) ?: 0f,
                    timestampNs = event.timestamp,
                    gyroAvailable = gyro != null,
                )
                trySend(sample)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        fun registerLocked() {
            accelerometer?.let {
                sensorManager.registerListener(listener, it, IMU_PERIOD_US, handler)
            }
            gyroscope?.let {
                sensorManager.registerListener(listener, it, IMU_PERIOD_US, handler)
            }
            registered = true
        }

        fun unregister() = synchronized(lock) {
            if (registered) {
                sensorManager.unregisterListener(listener)
                registered = false
            }
            gyroValues = null
        }

        fun register() = synchronized(lock) {
            if (!registered) registerLocked()
        }

        // Collecting a StateFlow applies the initial value immediately.
        val switchJob = launch {
            motionEnabled.collect { enabled ->
                if (enabled) register() else unregister()
            }
        }
        awaitClose {
            switchJob.cancel()
            unregister()
        }
    }

    companion object {
        /** 10,000 microseconds = 100 Hz. */
        const val IMU_PERIOD_US = 10_000

        /** Accept a gyro reading paired with an accelerometer reading within 20 ms. */
        private const val GYRO_MATCH_NS = 20_000_000L
    }
}
