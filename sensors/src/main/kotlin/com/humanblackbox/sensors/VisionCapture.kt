package com.humanblackbox.sensors

import android.graphics.Bitmap
import android.util.Size
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.Executor

/** Vision head contract: 320x320 RGB [1, 320, 320, 3] after normalisation. */
object VisionContracts {
    const val INPUT_SIZE = 320
    const val MIN_FPS = 5
    const val MAX_FPS = 15
    const val DEFAULT_FPS = 10
}

/** Converts one CameraX image into a 320x320 ARGB frame without storing it. */
fun interface VisionFrameConverter {
    fun convert(image: ImageProxy, timestampMs: Long): VisionFrame
}

/**
 * CameraX `ImageProxy.toBitmap()`-backed converter (CameraX >= 1.3). Center
 * crops to a square then scales to 320x320; pixels are read into an ARGB int
 * array and all intermediate bitmaps are recycled immediately. Raw frames are
 * never written to storage by this module.
 */
object BitmapVisionFrameConverter : VisionFrameConverter {
    override fun convert(image: ImageProxy, timestampMs: Long): VisionFrame {
        val source = image.toBitmap()
        val side = minOf(source.width, source.height)
        require(side > 0) { "empty camera image" }
        val cropped = Bitmap.createBitmap(
            source,
            (source.width - side) / 2,
            (source.height - side) / 2,
            side,
            side,
        )
        val scaled = Bitmap.createScaledBitmap(cropped, VisionContracts.INPUT_SIZE, VisionContracts.INPUT_SIZE, true)
        val argb = IntArray(VisionContracts.INPUT_SIZE * VisionContracts.INPUT_SIZE)
        scaled.getPixels(
            argb, 0, VisionContracts.INPUT_SIZE,
            0, 0, VisionContracts.INPUT_SIZE, VisionContracts.INPUT_SIZE,
        )
        if (scaled !== cropped) scaled.recycle()
        if (cropped !== source) cropped.recycle()
        source.recycle()
        return VisionFrame(argb, VisionContracts.INPUT_SIZE, VisionContracts.INPUT_SIZE, timestampMs)
    }
}

/**
 * CameraX ImageAnalysis.Analyzer that emits at most [setFps] frames per second
 * by skipping/dropping the newest images when the interval has not elapsed.
 * `STRATEGY_KEEP_ONLY_LATEST` means skipped images are closed, never queued.
 * Capture is off until [enable] is called; every analyzed image is closed.
 */
class CameraXVisionSource(
    private val converter: VisionFrameConverter = BitmapVisionFrameConverter,
    initialFps: Int = VisionContracts.DEFAULT_FPS,
) : VisionFrameSource, ImageAnalysis.Analyzer {
    init {
        require(initialFps in VisionContracts.MIN_FPS..VisionContracts.MAX_FPS) {
            "fps must be within ${VisionContracts.MIN_FPS}..${VisionContracts.MAX_FPS}"
        }
    }

    private val enabledState = MutableStateFlow(false)
    private val fpsState = MutableStateFlow(initialFps)
    private val emitted = MutableSharedFlow<VisionFrame>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private var lastEmissionMs = 0L

    override val frames: Flow<VisionFrame> get() = emitted.asSharedFlow()

    override fun enable(enabled: Boolean) {
        enabledState.value = enabled
        if (!enabled) lastEmissionMs = 0L
    }

    override fun setFps(fps: Int) {
        require(fps in VisionContracts.MIN_FPS..VisionContracts.MAX_FPS) {
            "fps must be within ${VisionContracts.MIN_FPS}..${VisionContracts.MAX_FPS}"
        }
        fpsState.value = fps
    }

    override fun analyze(image: ImageProxy) {
        try {
            if (!enabledState.value) return
            val nowMs = System.currentTimeMillis()
            if (nowMs - lastEmissionMs < 1_000L / fpsState.value) return
            val frame = converter.convert(image, nowMs)
            require(
                frame.width == VisionContracts.INPUT_SIZE &&
                    frame.height == VisionContracts.INPUT_SIZE,
            ) { "converter must emit a ${VisionContracts.INPUT_SIZE}x${VisionContracts.INPUT_SIZE} frame" }
            if (emitted.tryEmit(frame)) lastEmissionMs = nowMs
        } finally {
            image.close()
        }
    }

    /** Builds the analysis use case; the app binds it to a camera lifecycle. */
    fun createImageAnalysis(executor: Executor): ImageAnalysis =
        ImageAnalysis.Builder()
            .setTargetResolution(Size(VisionContracts.INPUT_SIZE, VisionContracts.INPUT_SIZE))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer(executor, this) }
}
