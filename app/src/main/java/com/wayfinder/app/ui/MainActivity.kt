package com.wayfinder.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat

/**
 * M1 entry point. Requests camera permission, constructs the [WayfinderEngine],
 * and renders the debug overlay. The engine starts on resume (when permitted)
 * and stops on pause, so camera/NPU resources are released in the background.
 */
class MainActivity : ComponentActivity() {

    private lateinit var engine: WayfinderEngine
    private var hasCamera by mutableStateOf(false)

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasCamera = granted
            if (granted) engine.start(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep the screen on while the app is foregrounded (nav aid is always-on during use;
        // also prevents the camera from pausing during dev testing).
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        engine = WayfinderEngine(this)
        hasCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF101216)) {
                    if (hasCamera) {
                        DebugScreen(engine)
                    } else {
                        PermissionScreen {
                            cameraPermission.launch(Manifest.permission.CAMERA)
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasCamera) engine.start(this)
    }

    override fun onPause() {
        super.onPause()
        if (this::engine.isInitialized) engine.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (this::engine.isInitialized) engine.dispose()
    }
}
