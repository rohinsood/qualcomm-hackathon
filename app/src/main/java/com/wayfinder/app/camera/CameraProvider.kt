package com.wayfinder.app.camera

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import com.wayfinder.app.core.loop.Frame
import com.wayfinder.app.core.loop.FrameSlot
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * Owns the CameraX back-camera [ImageAnalysis] in KEEP_ONLY_LATEST mode (frame
 * dropping, never queueing). Converts each frame to an RGBA [Frame], hands it to
 * the [FrameSlot], and immediately closes the ImageProxy. Reports each frame to
 * the [onFrame] heartbeat (feeds the camera-freshness watchdog).
 */
class CameraProvider(
    private val context: Context,
    private val frameSlot: FrameSlot,
    private val onFrame: () -> Unit,
) {
    private val executor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var bound = false

    fun start(lifecycleOwner: LifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                try {
                    val provider = future.get()
                    cameraProvider = provider
                    bindAnalysis(lifecycleOwner, provider)
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to acquire ProcessCameraProvider", t)
                }
            },
            androidx.core.content.ContextCompat.getMainExecutor(context),
        )
    }

    private fun bindAnalysis(lifecycleOwner: LifecycleOwner, provider: ProcessCameraProvider) {
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        analysis.setAnalyzer(executor) { imageProxy ->
            try {
                process(imageProxy)
            } finally {
                imageProxy.close()
            }
        }

        provider.unbindAll()
        provider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            analysis,
        )
        bound = true
    }

    private fun process(imageProxy: ImageProxy) {
        val plane = imageProxy.planes.first()
        val buffer: ByteBuffer = plane.buffer
        val rgba = ByteArray(buffer.remaining())
        buffer.get(rgba)

        val frame = Frame(
            rgba = rgba,
            width = imageProxy.width,
            height = imageProxy.height,
            rotationDegrees = imageProxy.imageInfo.rotationDegrees,
            timestampMs = SystemClock.elapsedRealtime(),
        )
        frameSlot.put(frame)
        onFrame()
    }

    fun stop() {
        cameraProvider?.unbindAll()
        bound = false
    }

    companion object {
        private const val TAG = "CameraProvider"
    }
}
