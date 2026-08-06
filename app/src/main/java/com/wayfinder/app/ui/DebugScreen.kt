package com.wayfinder.app.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wayfinder.app.core.model.Direction
import com.wayfinder.app.core.model.SteeringDecision
import com.wayfinder.app.core.model.direction
import com.wayfinder.app.core.safety.Watchdog
import com.wayfinder.app.output.haptics.DistanceToCadence
import com.wayfinder.app.perception.seg.WalkableMask
import kotlinx.coroutines.delay

/** A 10 Hz snapshot of the engine for the debug overlay. */
private data class Snapshot(
    val status: Watchdog.FailSafe = Watchdog.FailSafe.NOMINAL,
    val fps: Float = 0f,
    val inferenceAgeMs: Long = Long.MAX_VALUE,
    val runner: String = "",
    val decision: SteeringDecision? = null,
    val maskColors: IntArray? = null,
    val maskW: Int = 0,
    val maskH: Int = 0,
    val depthPerColumn: FloatArray? = null,
    val overrides: Int = 0,
    val depthActive: Boolean = false,
)

@Composable
fun DebugScreen(engine: WayfinderEngine) {
    var snap by remember { mutableStateOf(Snapshot()) }

    // Poll the engine at 10 Hz and lift everything the UI needs into [snap].
    LaunchedEffect(Unit) {
        while (true) {
            val d = engine.decisionStore.latest()
            val mask = d?.mask
            snap = Snapshot(
                status = engine.watchdog.currentState(),
                fps = engine.fps,
                inferenceAgeMs = engine.inferenceAgeMs,
                runner = engine.runnerName,
                decision = d,
                maskColors = mask?.let { maskToColors(it) },
                maskW = mask?.width ?: 0,
                maskH = mask?.height ?: 0,
                depthPerColumn = d?.depthPerColumn,
                overrides = d?.overrides ?: 0,
                depthActive = d?.depthActive ?: false,
            )
            delay(100)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF101216),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StatusHeader(snap)
            MaskPreview(snap)
            ClearanceBars(snap.decision)
            DepthStrip(snap.decision, engine.tunables)
            DecisionReadout(snap.decision)
            HapticVisualizer(
                decision = snap.decision,
                isFailSafe = snap.status != Watchdog.FailSafe.NOMINAL,
                tunables = engine.tunables,
            )
            TuningPanel(engine.tunables)
        }
    }
}

@Composable
private fun StatusHeader(snap: Snapshot) {
    val (label, color) = when (snap.status) {
        Watchdog.FailSafe.NOMINAL -> "NOMINAL" to Color(0xFF4CAF50)
        Watchdog.FailSafe.DECISION_STALE -> "DECISION STALE" to Color(0xFFFFA726)
        Watchdog.FailSafe.CAMERA_STALE -> "CAMERA STALE" to Color(0xFFEF5350)
    }
    Card(colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.15f))) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).clip(RoundedCornerShape(6.dp)).background(color))
                Spacer(Modifier.width(8.dp))
                Text(label, color = color, fontSize = 18.sp, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "runner: ${snap.runner}   fps: ${"%.1f".format(snap.fps)}   " +
                    "infer age: ${if (snap.inferenceAgeMs == Long.MAX_VALUE) "—" else "${snap.inferenceAgeMs}ms"}",
                color = Color(0xFFB0BEC5),
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun MaskPreview(snap: Snapshot) {
    val colors = snap.maskColors
    if (colors == null || snap.maskW == 0) {
        Text("mask: —", color = Color(0xFF607D8B), fontSize = 13.sp)
        return
    }
    val bmp = remember(colors, snap.maskW, snap.maskH) {
        Bitmap.createBitmap(snap.maskW, snap.maskH, Bitmap.Config.ARGB_8888)
            .also { it.setPixels(colors, 0, snap.maskW, 0, 0, snap.maskW, snap.maskH) }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(snap.maskW.toFloat() / snap.maskH.toFloat())
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black),
    ) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = "walkable mask preview",
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun ClearanceBars(decision: SteeringDecision?) {
    val clearance = decision?.clearance
    val gapIndex = remember(clearance) {
        if (clearance == null || clearance.isEmpty()) -1
        else clearance.indices.maxByOrNull { clearance[it] } ?: -1
    }
    Text("clearance per column (gap highlighted):", color = Color(0xFF90A4AE), fontSize = 13.sp)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(70.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF1B1F24)),
    ) {
        if (clearance == null) return@Canvas
        val n = clearance.size
        if (n == 0) return@Canvas
        val barW = size.width / n
        clearance.forEachIndexed { i, v ->
            val barH = size.height * v.coerceIn(0f, 1f)
            val isGap = i == gapIndex
            drawRect(
                color = if (isGap) Color(0xFF4CAF50) else Color(0xFF3949AB).copy(alpha = 0.7f),
                topLeft = Offset(i * barW + barW * 0.05f, size.height - barH),
                size = Size(barW * 0.9f, barH),
            )
        }
    }
}

@Composable
private fun DecisionReadout(decision: SteeringDecision?) {
    val text = if (decision == null) "decision: —" else buildString {
        appendLine("direction: ${decision.direction}")
        appendLine("command:   ${"%+.2f".format(decision.command)}   proximity: ${"%.2f".format(decision.proximity)}")
        appendLine("nearest:   ${decision.nearestObstacleMeters?.let { "${"%.2f".format(it)} m" } ?: "—"}")
        appendLine("reason:    ${decision.reason}")
    }
    Text(
        text,
        color = Color(0xFFECEFF1),
        fontSize = 13.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1B1F24))
            .padding(10.dp),
    )
}

/**
 * On-screen mirror of the HapticLoop. Re-derives the same direction + cadence the
 * phone's motor would use, so on an emulator (no vibrator) you can still SEE the
 * output behavior: which side, what pattern, and how the pulse cadence scales with
 * proximity. Flash brightness = pulse, side = direction.
 */
@Composable
private fun HapticVisualizer(
    decision: SteeringDecision?,
    isFailSafe: Boolean,
    tunables: com.wayfinder.app.core.config.Tunables,
) {
    val interval: Long
    val showLeft: Boolean
    val showRight: Boolean
    val color: Color
    val label: String

    when {
        isFailSafe -> {
            interval = 450L
            showLeft = true; showRight = true
            color = Color(0xFFEF5350); label = "FAIL-SAFE · urgent double"
        }
        decision == null || decision.proximity <= 0f -> {
            interval = tunables.clearPulseIntervalMs
            showLeft = true; showRight = true
            color = Color(0xFF66BB6A); label = "CLEAR · heartbeat"
        }
        else -> {
            interval = DistanceToCadence.intervalMs(decision.nearestObstacleMeters, tunables)
            when (decision.direction) {
                Direction.LEFT -> {
                    showLeft = true; showRight = false
                    color = Color(0xFFFFA726); label = "LEFT · double pulse"
                }
                Direction.RIGHT -> {
                    showLeft = false; showRight = true
                    color = Color(0xFFFFA726); label = "RIGHT · long pulse"
                }
                Direction.NEUTRAL -> {
                    showLeft = true; showRight = true
                    color = Color(0xFFEF5350); label = "STOP · obstacle ahead"
                }
                Direction.CLEAR -> {
                    showLeft = true; showRight = true
                    color = Color(0xFF66BB6A); label = "clear"
                }
            }
        }
    }

    val flash = remember { Animatable(0f) }
    val fadeMs = interval.coerceIn(60L, 250L).toInt()
    LaunchedEffect(interval, showLeft, showRight, color) {
        while (true) {
            flash.snapTo(1f)
            flash.animateTo(0f, tween(fadeMs))
            delay((interval - fadeMs).coerceAtLeast(0L))
        }
    }

    val a = flash.value
    Column {
        Text("haptic (visualized): $label · ${interval}ms", color = Color(0xFF90A4AE), fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            HapBox(Modifier.weight(1f), active = showLeft, color = color, alpha = a, text = "L")
            HapBox(Modifier.weight(1f), active = showRight, color = color, alpha = a, text = "R")
        }
    }
}

@Composable
private fun RowScope.HapBox(
    modifier: Modifier,
    active: Boolean,
    color: Color,
    alpha: Float,
    text: String,
) {
    Box(
        modifier
            .height(44.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) color.copy(alpha = alpha) else Color(0xFF1B1F24)),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White.copy(alpha = if (active) alpha else 0.25f), fontSize = 16.sp)
    }
}

@Composable
private fun DepthStrip(decision: SteeringDecision?, tunables: com.wayfinder.app.core.config.Tunables) {
    val depth = decision?.depthPerColumn
    if (depth == null || decision == null || !decision.depthActive) {
        Text("depth: — (inactive)", color = Color(0xFF607D8B), fontSize = 13.sp)
        return
    }
    val overrides = decision.overrides
    Text(
        "depth per column (red=close, green=far)" +
            if (overrides > 0) "   ⚠ OVERRIDE ×$overrides" else "",
        color = if (overrides > 0) Color(0xFFEF5350) else Color(0xFF90A4AE),
        fontSize = 13.sp,
    )
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF1B1F24)),
    ) {
        val n = depth.size
        if (n == 0) return@Canvas
        val barW = size.width / n
        val maxR = tunables.maxRangeMeters
        depth.forEachIndexed { i, v ->
            val open = !v.isFinite() || v >= maxR
            // 0 = far/open .. 1 = very close
            val frac = if (open) 0f else (1f - (v / maxR)).coerceIn(0f, 1f)
            val barH = if (open) size.height * 0.08f else size.height * (0.1f + 0.9f * frac)
            val col = when {
                open -> Color(0xFF2E7D32)
                frac > 0.6f -> Color(0xFFEF5350)
                frac > 0.3f -> Color(0xFFFFA726)
                else -> Color(0xFF66BB6A)
            }
            drawRect(
                color = col,
                topLeft = Offset(i * barW + barW * 0.05f, size.height - barH),
                size = Size(barW * 0.9f, barH),
            )
        }
    }
}

@Composable
private fun TuningPanel(t: com.wayfinder.app.core.config.Tunables) {
    Text("live tuning", color = Color(0xFF90A4AE), fontSize = 13.sp)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FloatSlider("sensitivity (m)", t.sensitivityMeters, 0.5f..4f) { t.sensitivityMeters = it }
        FloatSlider("proximity exponent", t.proximityExponent, 0.3f..1.5f) { t.proximityExponent = it }
        FloatSlider("gap exponent (boost)", t.gapExponent, 0.2f..1.0f) { t.gapExponent = it }
        FloatSlider("close floor", t.closeFloor, 0.1f..1f) { t.closeFloor = it }
        FloatSlider("band start", t.verticalBandStart, 0.1f..0.5f) { t.verticalBandStart = it.coerceAtMost(t.verticalBandEnd - 0.05f) }
        FloatSlider("band end", t.verticalBandEnd, 0.5f..0.9f) { t.verticalBandEnd = it.coerceAtLeast(t.verticalBandStart + 0.05f) }
        FloatSlider("gap history (frames)", t.gapHistorySize.toFloat(), 1f..15f) { t.gapHistorySize = it.toInt().coerceAtLeast(1) }

        Spacer(Modifier.height(6.dp))
        Text("depth layer (M3)", color = Color(0xFF90A4AE), fontSize = 13.sp)
        var depthOn by remember { mutableStateOf(t.depthEnabled) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = depthOn, onCheckedChange = { depthOn = it; t.depthEnabled = it })
            Spacer(Modifier.width(8.dp))
            Text(if (depthOn) "depth ON" else "depth OFF", color = Color(0xFFCFD8DC), fontSize = 12.sp)
        }
        FloatSlider("depth every N frames", t.depthEveryNFrames.toFloat(), 1f..10f) { t.depthEveryNFrames = it.toInt().coerceAtLeast(1) }
        FloatSlider("depth override (m)", t.depthOverrideMeters, 0.3f..3f) { t.depthOverrideMeters = it }
        FloatSlider("depth ROI trigger (m)", t.depthRoiTriggerMeters, 0.3f..3f) { t.depthRoiTriggerMeters = it }
    }
}

@Composable
private fun FloatSlider(
    label: String,
    initial: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    var v by remember { mutableFloatStateOf(initial) }
    Column {
        Text("$label: ${"%.2f".format(v)}", color = Color(0xFFCFD8DC), fontSize = 12.sp)
        Slider(value = v, onValueChange = { v = it; onChange(it) }, valueRange = range)
    }
}

@Composable
fun PermissionScreen(onRequest: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF101216),
    ) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Wayfinder",
                color = Color.White,
                fontSize = 28.sp,
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "An on-device navigation aid using the gap-seeking steering algorithm.\n\n" +
                    "Camera access is required for obstacle avoidance.",
                color = Color(0xFFB0BEC5),
                fontSize = 15.sp,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onRequest) { Text("Grant camera permission") }
        }
    }
}

private fun maskToColors(mask: WalkableMask): IntArray {
    val out = IntArray(mask.width * mask.height)
    val walk = Color(0xFF2E7D32).toArgb()      // green
    val blocked = Color(0xFF4E1C1C).toArgb()   // dark red
    for (i in mask.pixels.indices) {
        out[i] = if (mask.pixels[i] == WalkableMask.WALKABLE) walk else blocked
    }
    return out
}
