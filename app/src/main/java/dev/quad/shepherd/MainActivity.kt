package dev.quad.shepherd

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.quad.shepherd.actuator.CaneActuator
import dev.quad.shepherd.actuator.NoOpActuator
import dev.quad.shepherd.databinding.ActivityMainBinding
import dev.quad.shepherd.feedback.HapticFeedback
import dev.quad.shepherd.feedback.SpeechFeedback
import dev.quad.shepherd.guidance.AnnouncementPolicy
import dev.quad.shepherd.guidance.GuidanceEngine
import dev.quad.shepherd.llm.ClaudeSceneDescriber
import dev.quad.shepherd.llm.GenieBench
import dev.quad.shepherd.llm.SceneDescriber
import dev.quad.shepherd.vision.Detection
import dev.quad.shepherd.vision.DepthEngine
import dev.quad.shepherd.vision.DetectionEngine
import dev.quad.shepherd.vision.FrameAnalyzer
import dev.quad.shepherd.vision.FrameResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val engine = DetectionEngine()
    private val depthEngine = DepthEngine()
    private val guidanceEngine = GuidanceEngine()
    private val announcer = AnnouncementPolicy()
    private lateinit var speech: SpeechFeedback
    private lateinit var haptics: HapticFeedback
    private val actuator: CaneActuator = NoOpActuator()
    private var describer: SceneDescriber? = null
    private var analyzer: FrameAnalyzer? = null

    private val analysisExecutor = Executors.newSingleThreadExecutor()

    @Volatile private var latestFrame: Bitmap? = null
    @Volatile private var latestDetections: List<Detection> = emptyList()
    @Volatile private var guidanceEnabled = true
    @Volatile private var describing = false
    @Volatile private var benching = false

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startEngine()
        else {
            binding.statusText.text = getString(R.string.camera_permission_needed)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        speech = SpeechFeedback(this)
        haptics = HapticFeedback(this)
        describer = if (BuildConfig.CLAUDE_API_KEY.isNotBlank()) ClaudeSceneDescriber() else null

        binding.describeButton.isEnabled = describer != null
        if (describer == null) {
            binding.describeButton.text = getString(R.string.describe_disabled)
        }
        binding.describeButton.setOnClickListener { describeScene() }
        // Step-0 SLM bake-off: long-press runs Qwen3.5-2B on NPU/GPU/CPU
        binding.describeButton.setOnLongClickListener {
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

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startEngine()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
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
        latestFrame = result.frame
        latestDetections = result.detections

        val now = SystemClock.elapsedRealtime()
        val guidance = guidanceEngine.update(
            result.detections, result.frameWidth, result.columnDistances, now,
        )
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
                announcer.decide(guidance, now)?.let {
                    speech.announce(it.text, interrupt = it.interrupt, urgent = it.urgent)
                }
            }
        }
    }

    /** Step-0 bake-off: Qwen3.5-2B via GenieX on each compute unit. */
    private fun benchLlm() {
        if (benching) return
        benching = true
        speech.announce("Starting language model benchmark. This downloads about one gigabyte on first run.", interrupt = true)
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
                .setTitle("Qwen3.5-2B on ${GenieBench.MODEL.substringBefore('/')}")
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

    private fun describeScene() {
        val d = describer ?: return
        val frame = latestFrame ?: run {
            Toast.makeText(this, R.string.no_frame_yet, Toast.LENGTH_SHORT).show()
            return
        }
        if (describing) return
        describing = true
        binding.describeButton.isEnabled = false
        speech.announce(getString(R.string.describing), interrupt = true)

        lifecycleScope.launch {
            val text = try {
                d.describe(frame, latestDetections)
            } catch (e: Exception) {
                getString(R.string.describe_failed)
            }
            speech.announce(text, interrupt = true)
            describing = false
            binding.describeButton.isEnabled = true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speech.shutdown()
        actuator.disconnect()
        analysisExecutor.shutdown()
        depthEngine.close()
        engine.close()
    }
}
