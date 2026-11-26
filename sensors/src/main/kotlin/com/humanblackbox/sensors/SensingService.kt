package com.humanblackbox.sensors

import android.app.Notification
import android.content.Intent
import android.os.IBinder
import androidx.lifecycle.LifecycleService
import com.humanblackbox.core.Modality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Foreground-service contract for continuous sensing. A concrete subclass in
 * :app supplies the real pipeline/buffer and a notification (with a channel
 * created by :app on Android 8+), and must be declared in the app manifest.
 *
 * No raw audio/video/IMU is persisted. Killing a modality disables its source
 * and immediately drops that modality from the rolling evidence window.
 * START_NOT_STICKY prevents capture from silently restarting after process
 * death without a fresh consented app start.
 */
abstract class SensingService : LifecycleService() {
    abstract val pipeline: SensorPipeline
    abstract val buffer: RollingEvidenceBuffer

    protected abstract fun recordingNotification(): Notification

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(FOREGROUND_NOTIFICATION_ID, recordingNotification())
        if (intent != null) {
            val modality = runCatching {
                intent.getStringExtra(EXTRA_MODALITY)?.let { Modality.valueOf(it) }
            }.getOrNull()
            if (modality != null && modality in CAPTURE_MODALITIES) {
                setModalityEnabled(modality, intent.getBooleanExtra(EXTRA_ENABLED, false))
            }
        }
        super.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    /** Single kill-switch entry point: stop capture, then drop buffered evidence. */
    fun setModalityEnabled(modality: Modality, enabled: Boolean) {
        if (modality !in CAPTURE_MODALITIES) return
        pipeline.enable(modality, enabled)
        if (!enabled) buffer.clear(modality)
    }

    fun clearEvidence() = buffer.clearAll()

    /**
     * Wires the three real streams into the bounded window. The caller owns the
     * [scope] and cancels it with the service lifecycle; kill switches are
     * rechecked at append time to close disable/emit races.
     */
    protected fun startEvidenceCollection(scope: CoroutineScope) {
        scope.launch {
            pipeline.audio.collect {
                if (pipeline.isEnabled(Modality.AUDIO).value) buffer.append(it)
            }
        }
        scope.launch {
            pipeline.imu.collect {
                if (pipeline.isEnabled(Modality.MOTION).value) buffer.append(it)
            }
        }
        scope.launch {
            pipeline.vision.collect {
                if (pipeline.isEnabled(Modality.VISION).value) buffer.append(it)
            }
        }
    }

    override fun onDestroy() {
        CAPTURE_MODALITIES.forEach { setModalityEnabled(it, false) }
        clearEvidence()
        super.onDestroy()
    }

    companion object {
        const val FOREGROUND_NOTIFICATION_ID = 0x5E5
        const val EXTRA_MODALITY = "com.humanblackbox.sensors.extra.MODALITY"
        const val EXTRA_ENABLED = "com.humanblackbox.sensors.extra.ENABLED"
        private val CAPTURE_MODALITIES = listOf(Modality.AUDIO, Modality.MOTION, Modality.VISION)
    }
}
