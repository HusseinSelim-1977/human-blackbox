package com.humanblackbox.sensors

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.humanblackbox.core.Modality
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** 16 kHz mono PCM. 960 ms window / 480 ms hop => 15360 samples / 7680 hop. */
data class AudioFrame(val pcm: FloatArray, val sampleRateHz: Int = 16_000, val timestampMs: Long)

/** 100 Hz IMU sample; device-frame axes, gravity not yet removed. */
data class ImuSample(
    val ax: Float, val ay: Float, val az: Float,
    val gx: Float, val gy: Float, val gz: Float,
    val timestampNs: Long,
)

/** CameraX RGB frame downscaled to 320x320 before model input. */
data class VisionFrame(val argb: IntArray, val width: Int, val height: Int, val timestampMs: Long)

interface SensorPipeline {
    val audio: Flow<AudioFrame>
    val imu: Flow<ImuSample>
    val vision: Flow<VisionFrame>
    fun enable(modality: Modality, enabled: Boolean)
}

/**
 * SensorManager-backed IMU stream. Audio uses AudioRecord in the foreground
 * service and CameraX supplies vision frames; both are injected as Flows so the
 * ML modules remain platform-independent and testable.
 */
class AndroidSensorPipeline(
    private val sensorManager: SensorManager,
    audioSource: Flow<AudioFrame>,
    visionSource: Flow<VisionFrame>,
) : SensorPipeline {
    override val audio: Flow<AudioFrame> = audioSource
    override val vision: Flow<VisionFrame> = visionSource

    private val enabled = mutableMapOf(
        Modality.AUDIO to true, Modality.MOTION to true, Modality.VISION to true,
    )

    override fun enable(modality: Modality, enabled: Boolean) {
        this.enabled[modality] = enabled
    }

    override val imu: Flow<ImuSample> = callbackFlow {
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (enabled[Modality.MOTION] != true || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
                trySend(
                    ImuSample(
                        ax = event.values[0], ay = event.values[1], az = event.values[2],
                        gx = 0f, gy = 0f, gz = 0f, timestampNs = event.timestamp,
                    ),
                )
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (accelerometer != null) {
            sensorManager.registerListener(listener, accelerometer, 10_000) // 100 Hz
        }
        awaitClose { sensorManager.unregisterListener(listener) }
    }
}
