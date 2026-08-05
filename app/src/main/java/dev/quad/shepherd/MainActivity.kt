package dev.quad.shepherd

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import android.view.View
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import dev.quad.shepherd.databinding.ActivityMainBinding
import dev.quad.shepherd.guidance.GuidanceEngine
import dev.quad.shepherd.llm.GenieBench
import dev.quad.shepherd.llm.GenieRuntime
import dev.quad.shepherd.service.ShepherdService
import dev.quad.shepherd.speech.VoiceInput
import dev.quad.shepherd.util.DebugLog
import dev.quad.shepherd.vision.FrameResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Thin UI shell over [ShepherdService], which owns the camera, vision,
 * guidance, and the companion. This activity renders the preview/overlay,
 * hosts the push-to-talk inputs (hold the button, or hold volume-down for
 * eyes-free use), and the developer bench.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var service: ShepherdService? = null
    private var bound = false
    private var voice: VoiceInput? = null

    @Volatile private var benching = false
    private var talkHeld = false
    private var googleMap: GoogleMap? = null
    private var drawnRoute: List<DoubleArray>? = null
    private var positionMarker: Marker? = null

    private val uiListener = object : ShepherdService.UiListener {
        override fun onFrame(result: FrameResult, guidance: GuidanceEngine.Guidance) {
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                binding.overlay.render(result, guidance)
                binding.steerView.render(guidance)
                if (!benching) {
                    binding.statusText.text = service?.statusLine(
                        result.latencyMs + result.depthLatencyMs,
                        result.detections.size,
                    ) ?: ""
                }
                updateNavMap()
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: android.os.IBinder) {
            val s = (binder as ShepherdService.LocalBinder).service
            service = s
            bound = true
            s.guidanceEnabled = binding.audioToggle.isChecked
            s.setDepthDebug(binding.depthToggle.isChecked)
            s.setUiListener(uiListener)
            s.attachPreview(binding.previewView.surfaceProvider)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            bound = false
        }
    }

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        grants[Manifest.permission.CAMERA]?.let { granted ->
            if (granted) startShepherd()
            else binding.statusText.text = getString(R.string.camera_permission_needed)
        }
        if (grants[Manifest.permission.RECORD_AUDIO] == false) {
            service?.speech?.announce("Microphone permission denied. Talking is disabled.")
        }
    }

    private val audioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        service?.speech?.announce(
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
            binding.steerView.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = (120 * density).toInt() + bars.bottom
            }
            binding.overlay.bottomInset = bars.bottom
            insets
        }

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
            service?.guidanceEnabled = checked
        }
        binding.depthToggle.setOnCheckedChangeListener { _, checked ->
            service?.setDepthDebug(checked)
        }
        binding.debugToggle.setOnCheckedChangeListener { _, checked ->
            binding.debugText.visibility = if (checked) View.VISIBLE else View.GONE
            if (checked) binding.debugText.text = DebugLog.snapshot()
        }
        DebugLog.setListener {
            runOnUiThread {
                if (!isDestroyed && binding.debugText.visibility == View.VISIBLE) {
                    binding.debugText.text = DebugLog.snapshot()
                }
            }
        }

        binding.mapView.onCreate(savedInstanceState)
        binding.mapView.getMapAsync { map ->
            googleMap = map
            map.uiSettings.isMapToolbarEnabled = false
        }

        val wanted = mutableListOf<String>()
        if (notGranted(Manifest.permission.CAMERA)) wanted += Manifest.permission.CAMERA
        if (notGranted(Manifest.permission.RECORD_AUDIO)) wanted += Manifest.permission.RECORD_AUDIO
        if (notGranted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            wanted += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33 && notGranted(Manifest.permission.POST_NOTIFICATIONS)) {
            wanted += Manifest.permission.POST_NOTIFICATIONS
        }

        if (Manifest.permission.CAMERA !in wanted) startShepherd()
        if (wanted.isNotEmpty()) permissionsLauncher.launch(wanted.toTypedArray())
    }

    private fun notGranted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED

    private fun startShepherd() {
        ContextCompat.startForegroundService(this, Intent(this, ShepherdService::class.java))
        bindService(Intent(this, ShepherdService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStart() {
        super.onStart()
        binding.mapView.onStart()
        service?.let {
            it.setUiListener(uiListener)
            it.attachPreview(binding.previewView.surfaceProvider)
        }
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
    }

    override fun onPause() {
        binding.mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        binding.mapView.onStop()
        service?.let {
            it.setUiListener(null)
            it.detachPreview()
        }
        super.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        binding.mapView.onLowMemory()
    }

    // ---- Push-to-talk: hold the big button, or hold volume-down --------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && bound) {
            if (event.repeatCount == 0) startTalking()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && bound) {
            stopTalking()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun startTalking() {
        val s = service ?: return
        if (talkHeld) return
        if (notGranted(Manifest.permission.RECORD_AUDIO)) {
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (!s.genieChat.ready) {
            if (!s.chatWarmingNow) s.warmChat()
            s.speech.announce(
                s.genieChat.failure?.let { "Companion unavailable. $it" }
                    ?: "Companion is still loading, one moment.",
                interrupt = true,
            )
            return
        }
        val v = ensureVoice()
        if (!v.supported) {
            s.speech.announce(
                "On-device speech recognition is not available on this phone.",
                interrupt = true,
            )
            return
        }
        talkHeld = true
        s.onPttDown()
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
        DebugLog.d("ASR", "\"${text.take(60)}\"")
        binding.talkButton.text = getString(R.string.talk_thinking)
        val s = service ?: run { resetTalkButton(); return }
        s.ask(text) { resetTalkButton() }
    }

    private fun ensureVoice(): VoiceInput {
        voice?.let { return it }
        return VoiceInput(
            this,
            onTranscript = ::onTranscript,
            onNoSpeech = {
                onListenDone()
                service?.speech?.announce("Didn't catch that.")
            },
            onError = { msg ->
                onListenDone()
                service?.speech?.announce(msg)
            },
        ).also { voice = it }
    }

    /** Route mini-map: visible while navigating, redrawn per route. */
    private fun updateNavMap() {
        val nav = service?.nav
        val route = nav?.routeLatLngs
        if (route == null) {
            if (binding.mapView.visibility != View.GONE) {
                binding.mapView.visibility = View.GONE
                drawnRoute = null
                positionMarker = null
                googleMap?.clear()
            }
            return
        }
        if (binding.mapView.visibility != View.VISIBLE) {
            binding.mapView.visibility = View.VISIBLE
        }
        val map = googleMap ?: return
        if (route !== drawnRoute) {
            drawnRoute = route
            positionMarker = null
            map.clear()
            val poly = PolylineOptions().color(0xFF2196F3.toInt()).width(8f)
            route.forEach { poly.add(LatLng(it[0], it[1])) }
            map.addPolyline(poly)
            nav.destLatLng?.let {
                map.addMarker(MarkerOptions().position(LatLng(it[0], it[1])))
            }
            // Lite-mode maps don't honor bounds-based camera updates (they
            // collapse to world zoom), so compute the zoom ourselves from
            // the route's span and the panel's pixel size, and use
            // newLatLngZoom — which lite mode supports properly.
            var minLat = route[0][0]
            var maxLat = route[0][0]
            var minLng = route[0][1]
            var maxLng = route[0][1]
            for (p in route) {
                if (p[0] < minLat) minLat = p[0]
                if (p[0] > maxLat) maxLat = p[0]
                if (p[1] < minLng) minLng = p[1]
                if (p[1] > maxLng) maxLng = p[1]
            }
            val centerLat = (minLat + maxLat) / 2
            val centerLng = (minLng + maxLng) / 2
            val cosLat = Math.cos(Math.toRadians(centerLat))
            val spanMeters = maxOf(
                (maxLat - minLat) * 110_540.0,
                (maxLng - minLng) * 111_320.0 * cosLat,
                120.0,
            )
            // Maps zoom is defined in dp (the world is 256 dp wide at zoom
            // 0), so the panel size goes in as its 160 dp — using pixels
            // overshot the zoom by log2(density) and cropped the route
            val viewDp = 160.0
            val zoom = (Math.log(156_543.03392 * cosLat * viewDp * 0.8 / spanMeters) /
                Math.log(2.0)).toFloat().coerceIn(3f, 18f)
            map.moveCamera(
                CameraUpdateFactory.newLatLngZoom(LatLng(centerLat, centerLng), zoom)
            )
        }
        nav.lastLatLng?.let { pos ->
            val here = LatLng(pos[0], pos[1])
            positionMarker?.let { it.position = here } ?: run {
                positionMarker = map.addMarker(
                    MarkerOptions()
                        .position(here)
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
                        .anchor(0.5f, 0.5f)
                )
            }
        }
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
        val s = service ?: return
        if (benching) return
        benching = true
        s.speech.announce("Starting language model benchmark.", interrupt = true)
        lifecycleScope.launch {
            val results = try {
                withContext(Dispatchers.IO) {
                    GenieBench.runAll(applicationContext) { msg ->
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
            benching = false
        }
    }

    override fun onDestroy() {
        DebugLog.setListener(null)
        binding.mapView.onDestroy()
        voice?.destroy()
        if (bound) unbindService(connection)
        super.onDestroy()
    }
}
