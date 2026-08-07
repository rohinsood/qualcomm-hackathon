package dev.quad.shepherd.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.quad.shepherd.MainActivity
import dev.quad.shepherd.R
import dev.quad.shepherd.actuator.CaneActuator
import dev.quad.shepherd.actuator.NoOpActuator
import dev.quad.shepherd.feedback.HapticFeedback
import dev.quad.shepherd.feedback.SpeechFeedback
import dev.quad.shepherd.feedback.VoiceFetcher
import dev.quad.shepherd.guidance.GuidanceEngine
import dev.quad.shepherd.guidance.SceneBlackboard
import dev.quad.shepherd.guidance.SteerFusion
import dev.quad.shepherd.llm.GenieChat
import dev.quad.shepherd.llm.OcrReader
import dev.quad.shepherd.bt.CaneBleLink
import dev.quad.shepherd.nav.CompassNav
import dev.quad.shepherd.path.CommandAggregator
import dev.quad.shepherd.path.PathPipeline
import dev.quad.shepherd.vision.DepthEngine
import dev.quad.shepherd.vision.DetectionEngine
import dev.quad.shepherd.util.DebugLog
import dev.quad.shepherd.vision.FrameAnalyzer
import dev.quad.shepherd.vision.FrameResult
import dev.quad.shepherd.vision.SegEngine
import dev.quad.shepherd.vision.TrafficLightEye
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.math.asin
import kotlin.math.sqrt

/**
 * The always-on brain: camera, vision engines, guidance, blackboard,
 * companion SLM, and speech all live here as a camera foreground service,
 * so guidance haptics and the conversation keep working with the screen
 * off or the phone in a pocket. [MainActivity] is a thin shell that binds
 * for UI rendering and push-to-talk input.
 */
class ShepherdService : LifecycleService() {

    companion object {
        private const val TAG = "ShepherdService"
        private const val CHANNEL_ID = "shepherd"
        private const val NOTIFICATION_ID = 1

        /** Words in a question that trigger a one-shot OCR pass. */
        private val OCR_TRIGGERS = listOf(
            "read", "sign", "text", "written", "writing", "label", "menu", "says", "say on",
        )

        /** Spoken navigation commands, handled before the SLM sees them.
         *  Unanchored: "hey, can you take me to…" must match too. */
        private val NAV_START = Regex(
            "(?:take me to|navigate to|navigate me to|guide me to|walk me to|" +
                "bring me to|directions to|how do i get to|i want to go to|" +
                "let's go to)\\s+(.{3,80})",
            RegexOption.IGNORE_CASE,
        )
        private val NAV_STOP = Regex(
            "stop (?:the )?(?:navigation|navigating|guiding|route)|cancel (?:the )?(?:navigation|route)",
            RegexOption.IGNORE_CASE,
        )
    }

    inner class LocalBinder : Binder() {
        val service: ShepherdService get() = this@ShepherdService
    }

    /** UI hooks; called on arbitrary threads — implementors post to main. */
    interface UiListener {
        fun onFrame(result: FrameResult, guidance: GuidanceEngine.Guidance)
    }

    private val binder = LocalBinder()

    private val engine = DetectionEngine()
    private val depthEngine = DepthEngine()
    private val segEngine = SegEngine()
    private val pathPipeline = PathPipeline()
    private val guidanceEngine = GuidanceEngine() // v1 fallback when no depth model
    private val blackboard = SceneBlackboard()
    val genieChat = GenieChat()
    lateinit var speech: SpeechFeedback
        private set
    private lateinit var haptics: HapticFeedback
    private val actuator: CaneActuator = NoOpActuator()
    private val ocr = OcrReader()
    private val compassNav by lazy {
        CompassNav(this, lifecycleScope) { line ->
            DebugLog.d("NAV", line)
            speech.announce(line, interrupt = false)
        }
    }

    /** Map data source + destination setter for the activity. */
    val nav: CompassNav get() = compassNav

    /** BLE link to the cane board (distance in, motor letters out). */
    val caneLink by lazy { CaneBleLink(this) }
    private val aggregator = CommandAggregator()
    @Volatile private var lastMotorLetter = ' '
    private var lastCanePresent = false
    private var analyzer: FrameAnalyzer? = null
    private var preview: Preview? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    @Volatile var guidanceEnabled = true
    @Volatile var visionLabel = "starting"
        private set
    @Volatile private var chatWarming = false
    @Volatile private var latestFrame: Bitmap? = null
    @Volatile private var uiListener: UiListener? = null
    @Volatile private var thermalNote = ""
    private var lastSeverity = GuidanceEngine.Severity.CLEAR
    private var lastPlanStop = false

    /** Camera pitch for the BEV grid, from the gravity sensor. */
    private val gravityListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val gx = event.values[0]
            val gy = event.values[1]
            val gz = event.values[2]
            val g = sqrt(gx * gx + gy * gy + gz * gz)
            if (g > 1f) {
                // Rear camera looks along -Z; pitch-down = asin(gz/|g|)
                pathPipeline.pitchRad = asin((gz / g).coerceIn(-1f, 1f))
                // Roll about the optical axis (0 = upright portrait)
                pathPipeline.rollRad = kotlin.math.atan2(gx, gy)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        thermalNote = if (status >= PowerManager.THERMAL_STATUS_MODERATE) " · warm" else ""
        DebugLog.d("SYS", "thermal -> $status")
    }

    override fun onCreate() {
        super.onCreate()
        speech = SpeechFeedback(this)
        haptics = HapticFeedback(this)
        actuator.connect()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification(), foregroundTypes())
        (getSystemService(POWER_SERVICE) as PowerManager)
            .addThermalStatusListener(thermalListener)
        (getSystemService(SENSOR_SERVICE) as SensorManager).let { sm ->
            sm.getDefaultSensor(Sensor.TYPE_GRAVITY)?.let {
                sm.registerListener(gravityListener, it, SensorManager.SENSOR_DELAY_UI)
            }
        }
        VoiceFetcher.ensureAsync(this, lifecycleScope) {
            speech.reloadNeural(this)
            speech.announce("Neural voice installed.")
        }
        startCaneLink()
        startMotorLoop()
        startPipeline()
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    fun setUiListener(listener: UiListener?) {
        uiListener = listener
    }

    @Volatile private var attachedSurface: Preview.SurfaceProvider? = null

    /** Route the activity's PreviewView into the service-owned camera. */
    fun attachPreview(surfaceProvider: Preview.SurfaceProvider) {
        attachedSurface = surfaceProvider
        preview?.setSurfaceProvider(surfaceProvider)
    }

    /**
     * Identity-checked: on activity recreation the OLD instance's teardown
     * runs after the NEW one attached — a blind detach here blanked the
     * fresh preview ("can't see the camera sometimes").
     */
    fun detachPreview(surfaceProvider: Preview.SurfaceProvider) {
        if (attachedSurface === surfaceProvider) {
            attachedSurface = null
            preview?.setSurfaceProvider(null)
        }
    }

    fun setDepthDebug(enabled: Boolean) {
        analyzer?.depthDebugEnabled = enabled
    }

    fun statusLine(latencyMs: Long, objects: Int): String =
        getString(R.string.status_format, visionLabel, latencyMs, objects) + thermalNote

    /** Cane BLE up: distance readings feed the grid's near-field ring. */
    private fun startCaneLink() {
        caneLink.start()
        lifecycleScope.launch {
            caneLink.reading.collect { r ->
                if (r?.present == true && r.mm != null) {
                    pathPipeline.grid.markNearObstacle(r.mm / 1000f)
                    if (!lastCanePresent) DebugLog.d("CANE", "obstacle at ${r.mm} mm")
                    lastCanePresent = true
                } else if (lastCanePresent) {
                    lastCanePresent = false
                    DebugLog.d("CANE", "clear")
                }
            }
        }
        lifecycleScope.launch {
            caneLink.state.collect { DebugLog.d("CANE", it.toString()) }
        }
    }

    /** One motor letter per 200 ms period, aggregated + failsafe-friendly. */
    private fun startMotorLoop() {
        lifecycleScope.launch {
            while (true) {
                delay(CommandAggregator.PERIOD_MS)
                val letter = aggregator.decide()
                caneLink.write(letter.toString())
                if (letter != lastMotorLetter) {
                    lastMotorLetter = letter
                    DebugLog.d("BT", "motor → $letter")
                }
            }
        }
    }

    private fun startPipeline() {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val detectionOk = engine.initialize(this@ShepherdService)
                if (detectionOk) {
                    depthEngine.initialize(this@ShepherdService)
                    segEngine.initialize(this@ShepherdService)
                }
                detectionOk
            }
            if (!ok) {
                visionLabel = "no model"
                speech.announce(getString(R.string.model_missing), interrupt = true)
                return@launch
            }
            visionLabel = buildString {
                append(engine.activeProvider)
                if (depthEngine.available) {
                    append(" +depth(").append(depthEngine.activeProvider).append(")")
                }
                if (segEngine.available) {
                    append(" +seg(").append(segEngine.activeProvider).append(")")
                }
            }
            DebugLog.d("VIS", visionLabel)
            if (!notGrantedLocation()) compassNav.startPassive()
            bindCamera()
            warmChat()
        }
    }

    private fun bindCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val previewUseCase = Preview.Builder().build()
            preview = previewUseCase

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            val fa = FrameAnalyzer(
                engine,
                depthEngine,
                ::onFrame,
                segEngine.takeIf { it.available },
                pathPipeline.takeIf { depthEngine.available },
            )
            analyzer = fa
            analysis.setAnalyzer(analysisExecutor, fa)

            provider.unbindAll()
            provider.bindToLifecycle(
                this, CameraSelector.DEFAULT_BACK_CAMERA, previewUseCase, analysis
            )
            // The activity may have attached before the camera was bound
            attachedSurface?.let { previewUseCase.setSurfaceProvider(it) }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Load the companion SLM onto the NPU in the background. */
    fun warmChat() {
        if (chatWarming || genieChat.ready) return
        chatWarming = true
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = genieChat.warmUp(this@ShepherdService) { msg -> Log.i("GenieChat", msg) }
            chatWarming = false
            speech.announce(
                if (ok) "Companion ready. Hold the button or volume down to talk."
                else "Companion failed to load. ${genieChat.failure ?: ""}"
            )
        }
    }

    val chatWarmingNow: Boolean get() = chatWarming

    private fun onFrame(result: FrameResult) {
        latestFrame = result.frame
        val now = SystemClock.elapsedRealtime()
        val detections = TrafficLightEye.decorate(result.detections, result.frame)
        // Feed the compass-bearing goal to the planner for the next frame:
        // obstacles bend the path around, the bearing pulls it back on line
        pathPipeline.goalAngleDeg = compassNav.goalAngleDeg?.coerceIn(-90f, 90f)
        // v2: guidance comes from the polar plan on the BEV grid; the v1
        // column engine remains the fallback when depth/seg models are absent
        val guidance = result.plan?.guidance ?: guidanceEngine.update(
            detections, result.frameWidth, result.columnDistances, now,
        )
        result.plan?.let { p ->
            if (p.stop != lastPlanStop) {
                lastPlanStop = p.stop
                if (p.stop) DebugLog.d("PLAN", "no corridor — STOP")
            }
        }
        blackboard.updateFrame(detections, result.frameWidth)
        blackboard.updateGuidance(guidance)
        // Nothing about obstacles is spoken; danger onsets are logged so the
        // companion can talk about them when asked
        if (guidance.severity == GuidanceEngine.Severity.DANGER &&
            lastSeverity != GuidanceEngine.Severity.DANGER
        ) {
            val action = when {
                guidance.steer < -0.2f -> "steering left"
                guidance.steer > 0.2f -> "steering right"
                else -> "stopping"
            }
            blackboard.noteAlert("danger: ${guidance.nearestLabel ?: "obstacle"}, $action", now)
            DebugLog.d("GUID", "danger: ${guidance.nearestLabel ?: "obstacle"} → $action")
        }
        lastSeverity = guidance.severity
        blackboard.navSummary = compassNav.summary
        // With the polar plan active the compass goal is already inside the
        // planner's cost function; the additive fusion only serves the
        // v1 fallback path
        val fused = if (result.plan != null) guidance
        else guidance.copy(
            steer = SteerFusion.fuse(guidance, compassNav.goalAngleDeg?.let { it / 60f }),
        )
        aggregator.offer(fused)
        actuator.sendGuidance(fused)
        // Haptics follow the FUSED command so turns are felt, not just seen
        if (guidanceEnabled) haptics.update(fused)
        uiListener?.onFrame(result.copy(detections = detections), fused)
    }

    // ---- Conversation ---------------------------------------------------

    /** The user pressed talk: the friend goes quiet and listens. */
    fun onPttDown() {
        genieChat.requestStop(null)
        speech.stopAll()
    }

    /**
     * One conversation turn. OCR runs first when the question sounds like
     * a read request; [onDone] is called on the main thread.
     */
    fun ask(text: String, onDone: (Boolean) -> Unit) {
        // Navigation commands bypass the SLM entirely
        NAV_START.find(text.trim())?.let { m ->
            val dest = m.groupValues[1].trim()
                .trimEnd('.', '!', '?', ',')
                .replace(Regex("\\s+(please|now|thanks|thank you)$", RegexOption.IGNORE_CASE), "")
            DebugLog.d("NAV", "intent: start → \"$dest\"")
            if (notGrantedLocation()) {
                speech.announce("I need location permission for navigation.", interrupt = true)
            } else {
                // Re-promote with the location FGS type now that we have it,
                // so guidance continues with the screen off
                startForeground(NOTIFICATION_ID, buildNotification(), foregroundTypes())
                compassNav.setSpokenDestination(dest)
            }
            onDone(true)
            return
        }
        if (NAV_STOP.containsMatchIn(text)) {
            DebugLog.d("NAV", "intent: stop")
            compassNav.stop()
            onDone(true)
            return
        }
        DebugLog.d("CHAT", "→ SLM: ${text.take(50)}")
        lifecycleScope.launch(Dispatchers.IO) {
            // A barged-in reply may still be winding down after stopStream
            var waited = 0
            while (genieChat.busy && waited < 2000) {
                delay(50)
                waited += 50
            }
            var digest = blackboard.digest(SystemClock.elapsedRealtime())
            val lower = text.lowercase()
            if (OCR_TRIGGERS.any { lower.contains(it) }) {
                latestFrame?.let { frame ->
                    ocr.read(frame)?.let { seen ->
                        digest += " Text visible through the camera: \"$seen\"."
                        Log.i(TAG, "ocr contributed ${seen.length} chars")
                    }
                }
            }
            val reply = genieChat.ask(text, digest) { sentence -> speech.announceChat(sentence) }
            if (reply == null) speech.announce("Sorry, I lost my train of thought.")
            withContext(Dispatchers.Main) { onDone(reply != null) }
        }
    }

    // ---- Notification ---------------------------------------------------

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun foregroundTypes(): Int =
        ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
            (if (notGrantedLocation()) 0 else ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)

    private fun notGrantedLocation(): Boolean =
        ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) != android.content.pm.PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        (getSystemService(POWER_SERVICE) as PowerManager)
            .removeThermalStatusListener(thermalListener)
        (getSystemService(SENSOR_SERVICE) as SensorManager)
            .unregisterListener(gravityListener)
        compassNav.stop(announce = false)
        caneLink.shutdown()
        genieChat.close()
        speech.shutdown()
        ocr.close()
        actuator.disconnect()
        analysisExecutor.shutdown()
        segEngine.close()
        depthEngine.close()
        engine.close()
        super.onDestroy()
    }
}
