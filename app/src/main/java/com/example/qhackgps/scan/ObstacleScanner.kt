package com.example.qhackgps.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.qhackgps.llm.SceneBlackboard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.FloatBuffer
import java.util.concurrent.Executors

sealed interface ScanState {
    data object Off : ScanState
    data object Starting : ScanState
    /** Camera + detector live; [provider] is where inference runs (GPU/NPU/CPU). */
    data class Running(val provider: String) : ScanState
    data class Failed(val reason: String) : ScanState
}

/**
 * The camera obstacle scan, assembled from the v3 branch's pipeline:
 * CameraX frames -> 640x640 letterbox -> YOLOv8 ([DetectionEngine]) ->
 * pinhole distances ([DistanceEstimator]) -> screen-thirds decision
 * ([ThirdsGuidance]), published on [decision].
 *
 * Detection-only by design: the seg/depth models v3 also ran are not
 * carried over, so the thirds logic runs on its seg-less fallback and an
 * obstacle here means "a recognized object, near". Untextured hazards
 * (walls, poles) are the cane sensor's job — the two sources complement
 * each other and the phone remains the only steering authority.
 *
 * No preview surface: the analysis use case binds alone, which sidesteps
 * the hidden-preview camera stall v3 had to fix. Frames analyze on a
 * single background thread with KEEP_ONLY_LATEST backpressure, so a slow
 * (CPU-tier) detector degrades the scan rate, never the UI.
 */
class ObstacleScanner(private val context: Context) : ImageAnalysis.Analyzer {

    companion object {
        private const val TAG = "ObstacleScanner"

        /** Duty-cycle cap, kept from v3: running back-to-back on every camera
         *  frame pinned the GPU and thermal-throttled the SoC — the thirds
         *  debounce assumes ~10 Hz and does not benefit from more. */
        private const val MIN_FRAME_INTERVAL_MS = 90L
    }

    private val engine = DetectionEngine()
    private val thirds = ThirdsGuidance()
    private val analysisExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "obstacle-scan")
    }
    private var cameraProvider: ProcessCameraProvider? = null
    private var engineReady = false
    @Volatile private var running = false

    private val _state = MutableStateFlow<ScanState>(ScanState.Off)
    val state: StateFlow<ScanState> = _state.asStateFlow()

    private val _decision = MutableStateFlow(ThirdsGuidance.Decision.STRAIGHT)
    val decision: StateFlow<ThirdsGuidance.Decision> = _decision.asStateFlow()

    /** The most recent upright camera frame, for OCR; null while off. */
    @Volatile var latestFrame: Bitmap? = null
        private set

    /** Optional scene sink for the voice companion: fed the frame's
     *  detections and a severity mirroring the thirds decision, and
     *  cleared on [stop] so the companion never reasons from stale frames. */
    @Volatile var blackboard: SceneBlackboard? = null

    // Preprocessing scratch, touched only on the analysis thread.
    private val size = DetectionEngine.INPUT_SIZE
    private val inputBuffer: FloatBuffer = FloatBuffer.allocate(3 * size * size)
    private val pixels = IntArray(size * size)
    private val letterboxBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(letterboxBitmap)
    private val clearPaint = Paint().apply { color = Color.BLACK }
    private var lastRunAt = 0L

    /**
     * Bring the scan up: initialize the detector (once; retried if it
     * failed) and bind the camera to [owner]. Idempotent while running.
     */
    fun start(owner: LifecycleOwner) {
        if (running) return
        running = true
        _state.value = ScanState.Starting
        analysisExecutor.execute {
            if (!engineReady) engineReady = engine.initialize(context)
            if (!engineReady) {
                _state.value = ScanState.Failed("detector model missing")
                running = false
                return@execute
            }
            ContextCompat.getMainExecutor(context).execute {
                if (running) bindCamera(owner)
            }
        }
    }

    /** Tear the camera down and forget any latched dodge. */
    fun stop() {
        running = false
        ContextCompat.getMainExecutor(context).execute {
            cameraProvider?.unbindAll()
        }
        analysisExecutor.execute { thirds.reset() }
        _decision.value = ThirdsGuidance.Decision.STRAIGHT
        _state.value = ScanState.Off
        latestFrame = null
        blackboard?.updateFrame(emptyList(), 0)
        blackboard?.updateGuidance(SceneBlackboard.Severity.CLEAR, null, null)
    }

    /** Final teardown; the scanner is unusable afterwards. */
    fun shutdown() {
        stop()
        analysisExecutor.execute { engine.close() }
        analysisExecutor.shutdown()
    }

    private fun bindCamera(owner: LifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor, this)
                provider.unbindAll()
                if (!running) return@addListener
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
                _state.value = ScanState.Running(engine.activeProvider)
                Log.i(TAG, "scan running on ${engine.activeProvider}")
            } catch (e: Exception) {
                Log.e(TAG, "camera bind failed", e)
                _state.value = ScanState.Failed("camera unavailable")
                running = false
            }
        }, ContextCompat.getMainExecutor(context))
    }

    override fun analyze(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (!running || now - lastRunAt < MIN_FRAME_INTERVAL_MS) {
            image.close()
            return
        }
        lastRunAt = now

        // Upright bitmap: the analysis rotation tracks the (landscape-locked)
        // display, matching the grip the compass remap assumes.
        val rotation = image.imageInfo.rotationDegrees
        val bitmap = image.toBitmap()
        image.close()
        val upright = if (rotation != 0) {
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, false)
        } else bitmap

        // Letterbox into 640x640, preserving aspect ratio.
        val scale = size.toFloat() / maxOf(upright.width, upright.height)
        val padX = (size - upright.width * scale) / 2f
        val padY = (size - upright.height * scale) / 2f
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), clearPaint)
        val m = Matrix().apply {
            postScale(scale, scale)
            postTranslate(padX, padY)
        }
        canvas.drawBitmap(upright, m, null)

        fillTensor(letterboxBitmap)
        val modelSpace = engine.detect(inputBuffer)

        // Model-space boxes -> camera-frame space, with pinhole distances
        // (closeness corrections use model-space geometry, as in v3).
        val sizeF = size.toFloat()
        val frameDetections = modelSpace.map { d ->
            val dist = DistanceEstimator.applyCloseness(
                estimate = DistanceEstimator.estimate(d.label, d.height),
                areaFraction = (d.width * d.height) / (sizeF * sizeF),
                touchesTop = d.y1 < 6f,
                touchesBottom = d.y2 > sizeF - 6f,
            )
            d.copy(
                x1 = ((d.x1 - padX) / scale).coerceIn(0f, upright.width.toFloat()),
                y1 = ((d.y1 - padY) / scale).coerceIn(0f, upright.height.toFloat()),
                x2 = ((d.x2 - padX) / scale).coerceIn(0f, upright.width.toFloat()),
                y2 = ((d.y2 - padY) / scale).coerceIn(0f, upright.height.toFloat()),
                distanceMeters = dist,
            )
        }
        val obstacles = frameDetections.map { d ->
            ThirdsGuidance.obstacle(
                d.x1, d.x2, d.y1, d.y2,
                upright.width, upright.height, d.distanceMeters,
            )
        }

        val decision = thirds.update(obstacles, segClearance = null)
        _decision.value = decision
        latestFrame = upright

        blackboard?.let { bb ->
            bb.updateFrame(frameDetections, upright.width)
            val nearest = frameDetections
                .filter { it.distanceMeters != null }
                .minByOrNull { it.distanceMeters!! }
            bb.updateGuidance(
                severity = when (decision) {
                    ThirdsGuidance.Decision.STRAIGHT -> SceneBlackboard.Severity.CLEAR
                    ThirdsGuidance.Decision.LEFT,
                    ThirdsGuidance.Decision.RIGHT -> SceneBlackboard.Severity.CAUTION
                    ThirdsGuidance.Decision.STOP -> SceneBlackboard.Severity.DANGER
                },
                nearestLabel = nearest?.label,
                nearestDistance = nearest?.distanceMeters,
            )
        }
    }

    /** ARGB bitmap -> CHW float tensor, RGB in 0..1. */
    private fun fillTensor(bitmap: Bitmap) {
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
        inputBuffer.rewind()
        val area = size * size
        val data = inputBuffer.array()
        for (i in 0 until area) {
            val p = pixels[i]
            data[i] = ((p shr 16) and 0xFF) / 255f            // R
            data[area + i] = ((p shr 8) and 0xFF) / 255f      // G
            data[2 * area + i] = (p and 0xFF) / 255f          // B
        }
        inputBuffer.rewind()
    }
}
