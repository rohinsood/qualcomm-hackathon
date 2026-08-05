package dev.quad.shepherd

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import dev.quad.shepherd.actuator.CaneActuator
import dev.quad.shepherd.actuator.NoOpActuator
import dev.quad.shepherd.databinding.ActivityMainBinding
import dev.quad.shepherd.feedback.HapticFeedback
import dev.quad.shepherd.feedback.SpeechFeedback
import dev.quad.shepherd.guidance.AnnouncementPolicy
import dev.quad.shepherd.guidance.GuidanceEngine
import dev.quad.shepherd.guidance.SceneBlackboard
import dev.quad.shepherd.llm.GenieBench
import dev.quad.shepherd.llm.GenieChat
import dev.quad.shepherd.llm.GenieRuntime
import dev.quad.shepherd.speech.VoiceInput
import dev.quad.shepherd.vision.DepthEngine
import dev.quad.shepherd.vision.DetectionEngine
import dev.quad.shepherd.vision.FrameAnalyzer
import dev.quad.shepherd.vision.FrameResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val engine = DetectionEngine()
    private val depthEngine = DepthEngine()
    private val guidanceEngine = GuidanceEngine()
    private val announcer = AnnouncementPolicy()
    private val blackboard = SceneBlackboard()
    private val genieChat = GenieChat()
    private lateinit var speech: SpeechFeedback
    private lateinit var haptics: HapticFeedback
    private val actuator: CaneActuator = NoOpActuator()
    private var analyzer: FrameAnalyzer? = null
    private var voice: VoiceInput? = null

    private val analysisExecutor = Executors.newSingleThreadExecutor()

    @Volatile private var guidanceEnabled = true
    @Volatile private var benching = false
    @Volatile private var chatWarming = false
    private var talkHeld = false

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        grants[Manifest.permission.CAMERA]?.let { granted ->
            if (granted) startEngine()
            else binding.statusText.text = getString(R.string.camera_permission_needed)
        }
        if (grants[Manifest.permission.RECORD_AUDIO] == false) {
            speech.announce("Microphone permission denied. Hold to talk is disabled.")
        }
    }

    private val audioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        speech.announce(
            if (granted) "Microphone ready. Hold the button to talk."
            else "Microphone permission denied."
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // API 35 draws edge-to-edge: keep the camera full-bleed but move the
        // controls out from under the status bar and gesture/nav bar
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val density = resources.displayMetrics.density
            binding.topBar.updatePadding(top = (8 * density).toInt() + bars.top)
            binding.talkButton.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = (16 * density).toInt() + bars.bottom
            }
            binding.overlay.bottomInset = bars.bottom
            insets
        }

        speech = SpeechFeedback(this)
        haptics = HapticFeedback(this)

        // Phase 1: the big bottom button is push-to-talk for the companion
        binding.talkButton.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startTalking()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.performClick()
                    stopTalking()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    stopTalking()
                    true
                }
                else -> false
            }
        }
        // Dev tool: long-press the status line for the SLM bake-off
        binding.statusText.setOnLongClickListener {
            benchLlm()
            true
        }
        binding.audioToggle.isChecked = true
        binding.audioToggle.setOnCheckedChangeListener { _, checked ->
            guidanceEnabled = checked
        }
        binding.depthToggle.setOnCheckedChangeListener { _, checked ->
            analyzer?.depthDebugEnabled = checked
        }

        actuator.connect()

        // Make sure the offline speech model for the current language is on
        // the phone — downloads in the background when missing (the cause
        // of recognizer error 13 on a fresh device)
        ensureVoice().ensureModel()

        val wanted = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) wanted += Manifest.permission.CAMERA
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) wanted += Manifest.permission.RECORD_AUDIO

        if (Manifest.permission.CAMERA !in wanted) startEngine()
        if (wanted.isNotEmpty()) permissionsLauncher.launch(wanted.toTypedArray())
    }

    private fun startEngine() {
        binding.statusText.text = getString(R.string.loading_model)
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val detectionOk = engine.initialize(this@MainActivity)
                if (detectionOk) depthEngine.initialize(this@MainActivity)
                detectionOk
            }
            if (!ok) {
                binding.statusText.text = getString(R.string.model_missing)
                speech.announce(getString(R.string.model_missing), interrupt = true)
                return@launch
            }
            binding.statusText.text = providerLabel()
            bindCamera()
            warmChat()
        }
    }

    /** Load the companion SLM onto the NPU in the background. */
    private fun warmChat() {
        if (chatWarming || genieChat.ready) return
        chatWarming = true
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = genieChat.warmUp(this@MainActivity) { msg -> Log.i("GenieChat", msg) }
            chatWarming = false
            speech.announce(
                if (ok) "Companion ready. Hold the bottom button to talk."
                else "Companion failed to load. ${genieChat.failure ?: ""}"
            )
        }
    }

    private fun providerLabel(): String = buildString {
        append(engine.activeProvider)
        if (depthEngine.available) append(" +depth(").append(depthEngine.activeProvider).append(")")
    }

    private fun bindCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            val fa = FrameAnalyzer(engine, depthEngine, ::onFrame)
            fa.depthDebugEnabled = binding.depthToggle.isChecked
            analyzer = fa
            analysis.setAnalyzer(analysisExecutor, fa)

            provider.unbindAll()
            provider.bindToLifecycle(
                this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onFrame(result: FrameResult) {
        val now = SystemClock.elapsedRealtime()
        val guidance = guidanceEngine.update(
            result.detections, result.frameWidth, result.columnDistances, now,
        )
        blackboard.updateFrame(result.detections, result.frameWidth)
        blackboard.updateGuidance(guidance)
        actuator.sendGuidance(guidance)

        runOnUiThread {
            binding.overlay.render(result, guidance)
            if (!benching) {
                binding.statusText.text = getString(
                    R.string.status_format,
                    providerLabel(),
                    result.latencyMs + result.depthLatencyMs,
                    result.detections.size,
                )
            }
            if (guidanceEnabled) {
                haptics.update(guidance)
                announcer.decide(guidance, now)?.let { arbitrate(it, now) }
            }
        }
    }

    /**
     * Final speech arbitration between safety alerts and companion chat:
     * any danger cuts the companion off mid-sentence (and the model is told
     * about it), while cautions never talk over the conversation — the
     * policy re-offers them on its own cadence once the chat goes quiet.
     */
    private fun arbitrate(u: AnnouncementPolicy.Utterance, now: Long) {
        val chatBusy = genieChat.busy || speech.chatActive
        when {
            !chatBusy -> speech.announce(u.text, u.interrupt, u.urgent)
            u.urgent -> {
                genieChat.requestStop(u.text)
                speech.announce(u.text, interrupt = true, urgent = true)
            }
            else -> return
        }
        blackboard.noteAlert(u.text, now)
    }

    // ---- Push-to-talk conversation -------------------------------------

    private fun startTalking() {
        if (talkHeld) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (!genieChat.ready) {
            if (!chatWarming) warmChat()
            speech.announce(
                genieChat.failure?.let { "Companion unavailable. $it" }
                    ?: "Companion is still loading, one moment.",
                interrupt = true,
            )
            return
        }
        val v = ensureVoice()
        if (!v.supported) {
            speech.announce(
                "On-device speech recognition is not available on this phone.",
                interrupt = true,
            )
            return
        }
        talkHeld = true
        // The friend stops mid-sentence to listen; drop any half-made reply
        genieChat.requestStop(null)
        speech.stopAll()
        binding.talkButton.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        binding.talkButton.text = getString(R.string.talk_listening)
        v.start()
    }

    private fun stopTalking() {
        if (!talkHeld) return
        talkHeld = false
        if (voice?.listening == true) {
            binding.talkButton.text = getString(R.string.talk_thinking)
            voice?.stop()
        } else {
            resetTalkButton()
        }
    }

    private fun onTranscript(text: String) {
        binding.talkButton.text = getString(R.string.talk_thinking)
        lifecycleScope.launch(Dispatchers.IO) {
            // If a barged-in reply is still winding down after stopStream,
            // give it a moment before starting the next turn
            var waited = 0
            while (genieChat.busy && waited < 2000) {
                delay(50)
                waited += 50
            }
            val digest = blackboard.digest(SystemClock.elapsedRealtime())
            val reply = genieChat.ask(text, digest) { sentence -> speech.announceChat(sentence) }
            withContext(Dispatchers.Main) {
                resetTalkButton()
                if (reply == null) speech.announce("Sorry, I lost my train of thought.")
            }
        }
    }

    private fun ensureVoice(): VoiceInput {
        voice?.let { return it }
        return VoiceInput(
            this,
            onTranscript = ::onTranscript,
            onNoSpeech = {
                onListenDone()
                speech.announce("Didn't catch that.")
            },
            onError = { msg ->
                onListenDone()
                speech.announce(msg)
            },
        ).also { voice = it }
    }

    private fun onListenDone() {
        if (!talkHeld) resetTalkButton()
    }

    private fun resetTalkButton() {
        binding.talkButton.text = getString(R.string.talk_button)
    }

    // ---- Dev: step-0 bake-off ------------------------------------------

    /** Step-0 bake-off: the companion SLM via GenieX on each compute unit. */
    private fun benchLlm() {
        if (benching) return
        benching = true
        speech.announce(
            "Starting language model benchmark. This downloads about one gigabyte on first run.",
            interrupt = true,
        )
        lifecycleScope.launch {
            val results = try {
                withContext(Dispatchers.IO) {
                    GenieBench.runAll(this@MainActivity) { msg ->
                        runOnUiThread { binding.statusText.text = msg }
                    }
                }
            } catch (e: Exception) {
                Log.e("GenieBench", "suite failed", e)
                listOf(
                    GenieBench.UnitResult(
                        "init", false, 0, 0f,
                        (e.message ?: e.javaClass.simpleName).take(160),
                    )
                )
            }

            val report = results.joinToString("\n") { r ->
                if (r.ok) "${r.unit}: ${"%.1f".format(r.tokensPerSec)} tok/s, first token ${r.firstTokenMs} ms"
                else "${r.unit}: FAILED — ${r.note}"
            }
            Log.i("GenieBench", "RESULTS\n$report")
            AlertDialog.Builder(this@MainActivity)
                .setTitle("${GenieRuntime.MODEL.substringAfterLast('/')} bake-off")
                .setMessage(report)
                .setPositiveButton("OK", null)
                .show()

            val best = results.filter { it.ok }.maxByOrNull { it.tokensPerSec }
            speech.announce(
                if (best != null)
                    "Benchmark done. Best backend: ${best.unit}, ${best.tokensPerSec.toInt()} tokens per second."
                else "Benchmark failed. Details on screen.",
                interrupt = true,
            )
            benching = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        voice?.destroy()
        genieChat.close()
        speech.shutdown()
        actuator.disconnect()
        analysisExecutor.shutdown()
        depthEngine.close()
        engine.close()
    }
}
