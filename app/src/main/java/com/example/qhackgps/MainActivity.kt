package com.example.qhackgps

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.qhackgps.bt.BluetoothGuidanceLink
import com.example.qhackgps.bt.BtLinkState
import com.example.qhackgps.bt.CaneBleLink
import com.example.qhackgps.bt.CaneLinkState
import com.example.qhackgps.guidance.GuidanceBus
import com.example.qhackgps.guidance.GuidanceUpdate
import com.example.qhackgps.guidance.TurnDirection
import com.example.qhackgps.haptics.ObstacleHaptics
import com.example.qhackgps.ui.theme.HudAlertSurface
import com.example.qhackgps.ui.theme.HudBlue
import com.example.qhackgps.ui.theme.HudGreen
import com.example.qhackgps.ui.theme.HudInk
import com.example.qhackgps.ui.theme.HudInkMuted
import com.example.qhackgps.ui.theme.HudOrange
import com.example.qhackgps.ui.theme.HudRed
import com.example.qhackgps.ui.theme.HudSurface
import com.example.qhackgps.ui.theme.QhackGPSTheme
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Pointing within this many degrees of the target counts as "on course"... */
private const val ALIGN_ENTER_DEG = 15f

/** ...and you stay "on course" until you drift beyond this (hysteresis stops flicker). */
private const val ALIGN_EXIT_DEG = 25f

/** Nominal turn (degrees) exported while steering the user around an obstacle. */
private const val AVOID_TURN_DEG = 45

/** Route turns smaller than this are not sent to the cane wheel (deadband). */
private const val CANE_TURN_MIN_DEG = 15

/**
 * Below this speed a GPS bearing is mostly noise, so it can't be used as the
 * reference when calibrating the compass. Roughly a slow walk.
 */
private const val MIN_CALIBRATION_SPEED_MPS = 0.5f

private val LOCATION_PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

/**
 * Asked for on launch: GPS plus, on Android 12+, the Bluetooth runtime
 * permissions — both radio links connect on their own, so we need them before
 * the user touches anything.
 */
private val STARTUP_PERMISSIONS: Array<String> = buildList {
    addAll(LOCATION_PERMISSIONS)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_CONNECT)
        add(Manifest.permission.BLUETOOTH_SCAN)
    }
}.toTypedArray()

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            QhackGPSTheme {
                NavigatorScreen()
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun NavigatorScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ---------- Location permission ----------
    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var hasBtPermission by remember {
        mutableStateOf(BluetoothGuidanceLink.hasConnectPermission(context))
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (LOCATION_PERMISSIONS.any { grants[it] == true }) hasLocationPermission = true
        hasBtPermission = BluetoothGuidanceLink.hasConnectPermission(context)
    }

    LaunchedEffect(Unit) {
        if (!hasLocationPermission || !hasBtPermission) {
            permissionLauncher.launch(STARTUP_PERMISSIONS)
        }
    }

    // ---------- Live location ----------
    var currentLocation by remember { mutableStateOf<Location?>(null) }
    DisposableEffect(hasLocationPermission) {
        if (!hasLocationPermission) return@DisposableEffect onDispose { }
        val client = LocationServices.getFusedLocationProviderClient(context)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { currentLocation = it }
            }
        }
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        client.lastLocation.addOnSuccessListener { loc ->
            if (loc != null && currentLocation == null) currentLocation = loc
        }
        onDispose { client.removeLocationUpdates(callback) }
    }

    // ---------- Compass (rotation vector sensor) ----------
    var magneticAzimuth by remember { mutableStateOf(Float.NaN) }
    var compassAccuracy by remember { mutableStateOf(SensorManager.SENSOR_STATUS_ACCURACY_HIGH) }
    var compassAvailable by remember { mutableStateOf(true) }

    // Manual trim added on top of the sensor. The remap below assumes one grip
    // (upright landscape, aiming with the back camera); strapped to a cane or held
    // another way the raw heading is off by a fixed amount, and this is that amount.
    var headingOffset by remember { mutableStateOf(NavSettings.headingOffset(context)) }

    // Bumping this tears the listener down and re-registers it, which is what makes
    // "reset" a real reset: the smoothing filter's state lives in the listener, so a
    // new listener starts from the next raw sample instead of easing over from a
    // stale one.
    var sensorEpoch by remember { mutableStateOf(0) }
    DisposableEffect(sensorEpoch) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sensor == null) {
            compassAvailable = false
            return@DisposableEffect onDispose { }
        }
        val listener = object : SensorEventListener {
            private val rotationMatrix = FloatArray(9)
            private val remappedMatrix = FloatArray(9)
            private val orientation = FloatArray(3)
            private var smoothed = Float.NaN

            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                // AR-style grip: phone held upright in landscape, screen facing you,
                // pointing with the BACK CAMERA. This remap makes azimuth track the
                // camera's look direction (device -Z). It is independent of roll, so
                // it stays exact in upright landscape; it only degrades if the phone
                // is laid flat (camera facing the ground).
                SensorManager.remapCoordinateSystem(
                    rotationMatrix, SensorManager.AXIS_X, SensorManager.AXIS_Z, remappedMatrix
                )
                SensorManager.getOrientation(remappedMatrix, orientation)
                val raw = ((Math.toDegrees(orientation[0].toDouble()) + 360.0) % 360.0).toFloat()
                smoothed = if (smoothed.isNaN()) raw
                else (smoothed + shortestSignedDelta(raw, smoothed) * 0.25f + 360f) % 360f
                // Only push to state when it visibly moved, to avoid recomposing 50x/s.
                if (magneticAzimuth.isNaN() || abs(shortestSignedDelta(smoothed, magneticAzimuth)) > 0.5f) {
                    magneticAzimuth = smoothed
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                compassAccuracy = accuracy
            }
        }
        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sensorManager.unregisterListener(listener) }
    }

    // Correct magnetic north -> true north so the compass agrees with map bearings.
    val declination = remember(currentLocation) {
        currentLocation?.let {
            GeomagneticField(
                it.latitude.toFloat(), it.longitude.toFloat(),
                it.altitude.toFloat(), System.currentTimeMillis()
            ).declination
        } ?: 0f
    }
    val trueHeading = if (magneticAzimuth.isNaN()) Float.NaN
    else wrap360(magneticAzimuth + declination + headingOffset)

    // Course over ground: the one heading reference that doesn't come from the
    // magnetometer, so it's what the trim gets calibrated against. Only meaningful
    // while actually walking — standing still, GPS bearing is noise.
    val gpsCourse = currentLocation
        ?.takeIf { it.hasBearing() && it.hasSpeed() && it.speed >= MIN_CALIBRATION_SPEED_MPS }
        ?.bearing

    // ---------- Destination + route ----------
    var destination by remember { mutableStateOf<LatLng?>(null) }
    var roadRoute by remember { mutableStateOf<List<LatLng>?>(null) }
    var routeLoading by remember { mutableStateOf(false) }

    // true  = Google walking directions (follows pavements and crossings)
    // false = straight line from here to the destination
    var roadRouting by remember { mutableStateOf(NavSettings.roadRouting(context)) }

    val curLatLng = currentLocation?.let { LatLng(it.latitude, it.longitude) }
    val hasFix = curLatLng != null

    LaunchedEffect(destination, hasFix, roadRouting) {
        roadRoute = null
        // Cleared up front, and in the finally below, so no early return or
        // mid-flight cancellation can leave the HUD stuck on "finding route…".
        routeLoading = false
        if (!roadRouting) return@LaunchedEffect
        val dest = destination ?: return@LaunchedEffect
        val origin = curLatLng ?: return@LaunchedEffect
        routeLoading = true
        try {
            roadRoute = fetchWalkingRoute(origin, dest, mapsApiKey(context))
        } finally {
            routeLoading = false
        }
    }

    // ---------- Guidance math ----------
    val dest = destination
    val targetPoint = if (curLatLng != null && dest != null) {
        roadRoute?.takeIf { it.size >= 2 }?.let { guidanceTarget(it, curLatLng) } ?: dest
    } else null
    val targetBearing =
        if (curLatLng != null && targetPoint != null) bearingBetween(curLatLng, targetPoint)
        else null
    val headingDelta =
        if (targetBearing != null && !trueHeading.isNaN()) shortestSignedDelta(targetBearing, trueHeading)
        else null
    val distanceToDest =
        if (curLatLng != null && dest != null) distanceMeters(curLatLng, dest) else null

    // On-course with hysteresis so the light doesn't flicker at the threshold.
    var aligned by remember { mutableStateOf(false) }
    val alignedNow = when {
        headingDelta == null -> false
        aligned -> abs(headingDelta) <= ALIGN_EXIT_DEG
        else -> abs(headingDelta) <= ALIGN_ENTER_DEG
    }
    if (alignedNow != aligned) SideEffect { aligned = alignedNow }

    // ---------- Smart cane (BLE obstacle sensor) ----------
    val caneLink = remember { CaneBleLink(context.applicationContext) }
    val caneState by caneLink.state.collectAsState()
    val caneReading by caneLink.reading.collectAsState()
    LaunchedEffect(hasBtPermission) { caneLink.start() }

    // An obstacle overrides route guidance: dodge toward the side the route target
    // is on (default right), and hold that side until the path is clear again so
    // the arrow doesn't flip while you turn.
    val obstaclePresent = caneReading?.present == true && caneState is CaneLinkState.Connected
    var latchedAvoid by remember { mutableStateOf<TurnDirection?>(null) }
    val avoidance: TurnDirection? = when {
        !obstaclePresent -> null
        latchedAvoid != null -> latchedAvoid
        (headingDelta ?: 0f) < 0f -> TurnDirection.LEFT
        else -> TurnDirection.RIGHT
    }
    if (avoidance != latchedAvoid) SideEffect { latchedAvoid = avoidance }

    // ---------- "Stop!" haptics ----------
    // The user is walking and may not be looking at the screen, so an obstacle
    // buzzes the phone until the path is clear. Silenced while we're in the
    // background — a phone buzzing forever in someone's pocket helps nobody.
    val haptics = remember { ObstacleHaptics(context.applicationContext) }
    var appVisible by remember { mutableStateOf(true) }
    DisposableEffect(context) {
        val lifecycle = (context as? ComponentActivity)?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> appVisible = true
                Lifecycle.Event.ON_STOP -> appVisible = false
                else -> Unit
            }
        }
        lifecycle?.addObserver(observer)
        onDispose {
            lifecycle?.removeObserver(observer)
            haptics.stop()
        }
    }
    LaunchedEffect(obstaclePresent, appVisible) {
        if (obstaclePresent && appVisible) haptics.startStopAlert() else haptics.stop()
    }

    // ---------- Guidance export (Bluetooth -> Arduino, broadcasts -> other apps) ----------
    val btLink = remember { BluetoothGuidanceLink(context.applicationContext) }
    DisposableEffect(Unit) {
        onDispose {
            btLink.shutdown()
            caneLink.shutdown()
        }
    }
    val btState by btLink.state.collectAsState()
    val btAutoConnecting by btLink.autoConnecting.collectAsState()

    // Find and hold the Arduino's serial module without anyone tapping "BT".
    LaunchedEffect(hasBtPermission) {
        if (hasBtPermission) btLink.startAutoConnect()
    }

    val routeDirection = when {
        headingDelta == null -> TurnDirection.NONE
        alignedNow -> TurnDirection.STRAIGHT
        headingDelta < 0f -> TurnDirection.LEFT
        else -> TurnDirection.RIGHT
    }
    val guidanceUpdate = GuidanceUpdate(
        direction = avoidance ?: routeDirection,
        deltaDeg =
            if (avoidance != null) AVOID_TURN_DEG
            else headingDelta?.let { abs(it).roundToInt() } ?: 0,
        aligned = alignedNow && avoidance == null,
        distanceM = distanceToDest?.roundToInt() ?: -1,
        headingDeg = if (trueHeading.isNaN()) -1 else trueHeading.roundToInt(),
        bearingDeg = targetBearing?.roundToInt() ?: -1,
        lat = curLatLng?.latitude ?: Double.NaN,
        lng = curLatLng?.longitude ?: Double.NaN,
        destLat = dest?.latitude ?: Double.NaN,
        destLng = dest?.longitude ?: Double.NaN,
        obstaclePresent = obstaclePresent,
        obstacleMm = caneReading?.mm ?: -1,
    )
    SideEffect { GuidanceBus.publish(guidanceUpdate) }

    // Stream steering to the cane so its wheel physically follows the path:
    // obstacle dodges as AVOID LEFT/RIGHT (wheel at full speed), route turns as
    // TURN LEFT/RIGHT <deg> (wheel speed scales with the angle — a 90° corner
    // starts the wheel turning the moment guidance calls it), CLEAR when
    // aligned or idle. Degrees are bucketed to 5° so a BLE write only fires on
    // a real change; each message also shows on the cane's dashboard.
    val caneSteerMsg = when {
        avoidance == TurnDirection.LEFT -> "AVOID LEFT"
        avoidance == TurnDirection.RIGHT -> "AVOID RIGHT"
        routeDirection == TurnDirection.LEFT || routeDirection == TurnDirection.RIGHT -> {
            val deg = headingDelta?.let { abs(it).roundToInt() } ?: 0
            if (deg < CANE_TURN_MIN_DEG) "CLEAR"
            else "TURN ${if (routeDirection == TurnDirection.LEFT) "LEFT" else "RIGHT"} ${(deg / 5) * 5}"
        }
        else -> "CLEAR"
    }
    LaunchedEffect(caneSteerMsg, caneState) {
        if (caneState is CaneLinkState.Connected) {
            caneLink.write(caneSteerMsg)
        }
    }

    // Steady ~5 Hz frame stream to the Arduino; the fixed cadence doubles as its
    // failsafe heartbeat (no frames for 1 s -> motor stops).
    LaunchedEffect(Unit) {
        while (isActive) {
            GuidanceBus.updates.value?.let { btLink.sendOrReconnect(it.toWireLine()) }
            delay(200L)
        }
    }

    // Broadcast to other apps only when the actionable payload changes.
    LaunchedEffect(Unit) {
        var lastKey: List<Any>? = null
        GuidanceBus.updates.collect { update ->
            update ?: return@collect
            val key = listOf(
                update.direction, update.deltaDeg, update.aligned, update.distanceM,
                update.obstaclePresent, update.obstacleMm / 100,
            )
            if (key != lastKey) {
                lastKey = key
                context.sendBroadcast(update.toBroadcastIntent())
                Log.d("qhackGPS", "guidance broadcast: ${update.toWireLine().trim()}")
            }
        }
    }

    // ---------- Map ----------
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(20.0, 0.0), 2f)
    }
    var centeredOnFirstFix by remember { mutableStateOf(false) }
    LaunchedEffect(hasFix) {
        if (hasFix && !centeredOnFirstFix) {
            centeredOnFirstFix = true
            cameraPositionState.animate(
                CameraUpdateFactory.newLatLngZoom(curLatLng!!, 17f), 800
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(mapType = MapType.NORMAL),
            uiSettings = MapUiSettings(
                zoomControlsEnabled = false,
                myLocationButtonEnabled = false,
                compassEnabled = true,
            ),
            onMapClick = { latLng -> destination = latLng },
        ) {
            val blueArrow = remember { headingArrowDescriptor(0xFF1E88E5.toInt()) }
            val greenArrow = remember { headingArrowDescriptor(0xFF00C853.toInt()) }
            val userMarkerState = remember { MarkerState() }
            val destMarkerState = remember { MarkerState() }

            if (curLatLng != null) {
                userMarkerState.position = curLatLng
                Marker(
                    state = userMarkerState,
                    icon = if (alignedNow) greenArrow else blueArrow,
                    rotation = if (trueHeading.isNaN()) 0f else trueHeading,
                    flat = true,
                    anchor = Offset(0.5f, 0.5f),
                    zIndex = 2f,
                    title = "You",
                )
            }
            if (dest != null) {
                destMarkerState.position = dest
                Marker(state = destMarkerState, title = "Destination", zIndex = 1f)
            }
            val pathPoints = roadRoute ?: listOfNotNull(curLatLng, dest)
            if (dest != null && pathPoints.size >= 2) {
                Polyline(
                    points = pathPoints,
                    color = Color(0xFF1E88E5),
                    width = 14f,
                    geodesic = true,
                    zIndex = 0f,
                )
            }
        }

        // ---------- Guidance HUD ----------
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .safeDrawingPadding()
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            GuidanceCard(
                hasPermission = hasLocationPermission,
                compassAvailable = compassAvailable,
                hasFix = hasFix,
                destinationSet = dest != null,
                aligned = alignedNow,
                headingDelta = headingDelta,
                distanceToDest = distanceToDest,
                trueHeading = trueHeading,
                targetBearing = targetBearing,
                routeIsRoad = roadRoute != null,
                routeLoading = routeLoading,
                roadRoutingEnabled = roadRouting,
                compassNeedsCalibration =
                    compassAccuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW,
                avoidance = avoidance,
                obstacleMm = caneReading?.mm,
                onGrantPermission = { permissionLauncher.launch(LOCATION_PERMISSIONS) },
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.Center) {
                if (caneState !is CaneLinkState.Disconnected) {
                    HudChip(
                        text = when (caneState) {
                            is CaneLinkState.Connected -> "Cane " + (caneReading?.mm?.let {
                                String.format(Locale.US, "%.2f m", it / 1000f)
                            } ?: "clear")
                            is CaneLinkState.Connecting -> "Cane connecting…"
                            CaneLinkState.Scanning -> "Cane searching…"
                            else -> ""
                        },
                        dotColor = when {
                            obstaclePresent -> HudOrange
                            caneState is CaneLinkState.Connected -> HudGreen
                            else -> HudInkMuted
                        },
                        highlighted = obstaclePresent,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                if (btState !is BtLinkState.Disconnected || btAutoConnecting) {
                    HudChip(
                        text = when (val s = btState) {
                            is BtLinkState.Connected -> "Arduino ${s.deviceName}"
                            is BtLinkState.Connecting -> "Arduino connecting…"
                            else -> "Arduino searching…"
                        },
                        dotColor =
                            if (btState is BtLinkState.Connected) HudGreen else HudInkMuted,
                    )
                }
            }
        }

        // ---------- Buttons ----------
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .safeDrawingPadding()
                .padding(16.dp),
            horizontalAlignment = Alignment.End,
        ) {
            var showBtDialog by remember { mutableStateOf(false) }
            val btPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { grants -> if (grants.values.all { it }) showBtDialog = true }

            SmallFloatingActionButton(
                onClick = {
                    if (BluetoothGuidanceLink.hasConnectPermission(context)) showBtDialog = true
                    else btPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.BLUETOOTH_CONNECT,
                            Manifest.permission.BLUETOOTH_SCAN,
                        )
                    )
                },
                containerColor =
                    if (btState is BtLinkState.Connected) Color(0xFF00C853)
                    else FloatingActionButtonDefaults.containerColor,
            ) {
                Text("BT", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))

            if (showBtDialog) {
                BtDevicePickerDialog(
                    link = btLink,
                    state = btState,
                    caneLink = caneLink,
                    caneState = caneState,
                    onDismiss = { showBtDialog = false },
                )
            }

            // ----- Direction calibration -----
            // Amber whenever a trim is applied, so it's obvious at a glance that
            // the heading is not the raw sensor reading.
            var showCalibration by remember { mutableStateOf(false) }
            val trimmed = headingOffset != 0f
            SmallFloatingActionButton(
                onClick = { showCalibration = true },
                containerColor =
                    if (trimmed) HudOrange else FloatingActionButtonDefaults.containerColor,
                contentColor =
                    if (trimmed) Color.White else MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription =
                        if (trimmed) "Direction calibration, trimmed ${headingOffset.roundToInt()} degrees"
                        else "Direction calibration",
                )
            }
            Spacer(Modifier.height(12.dp))

            if (showCalibration) {
                HeadingCalibrationDialog(
                    rawAzimuth = magneticAzimuth,
                    declination = declination,
                    trueHeading = trueHeading,
                    headingOffset = headingOffset,
                    gpsCourse = gpsCourse,
                    needsFigureEight =
                        compassAccuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW,
                    onOffsetChange = {
                        headingOffset = it
                        NavSettings.setHeadingOffset(context, it)
                    },
                    onResetSensor = {
                        // Drop the current reading too, so the fresh listener starts
                        // from the next raw sample rather than easing off a stale one.
                        magneticAzimuth = Float.NaN
                        sensorEpoch++
                    },
                    onDismiss = { showCalibration = false },
                )
            }

            // ----- Routing mode -----
            SmallFloatingActionButton(
                onClick = {
                    roadRouting = !roadRouting
                    NavSettings.setRoadRouting(context, roadRouting)
                },
                modifier = Modifier.semantics {
                    contentDescription =
                        if (roadRouting) "Routing: Google walking directions. Tap for straight line."
                        else "Routing: straight line. Tap for Google walking directions."
                },
                containerColor =
                    if (roadRouting) HudBlue else FloatingActionButtonDefaults.containerColor,
                contentColor =
                    if (roadRouting) Color.White else MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Text(
                    text = if (roadRouting) "MAP" else "LINE",
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                )
            }
            Spacer(Modifier.height(12.dp))

            if (dest != null) {
                SmallFloatingActionButton(onClick = { destination = null }) {
                    Icon(Icons.Default.Close, contentDescription = "Clear destination")
                }
                Spacer(Modifier.height(12.dp))
            }
            FloatingActionButton(onClick = {
                curLatLng?.let {
                    scope.launch {
                        cameraPositionState.animate(
                            CameraUpdateFactory.newLatLngZoom(it, 17f), 600
                        )
                    }
                }
            }) {
                Icon(Icons.Default.LocationOn, contentDescription = "Recenter on me")
            }
        }
    }
}

@Composable
private fun GuidanceCard(
    hasPermission: Boolean,
    compassAvailable: Boolean,
    hasFix: Boolean,
    destinationSet: Boolean,
    aligned: Boolean,
    headingDelta: Float?,
    distanceToDest: Float?,
    trueHeading: Float,
    targetBearing: Float?,
    routeIsRoad: Boolean,
    routeLoading: Boolean,
    roadRoutingEnabled: Boolean,
    compassNeedsCalibration: Boolean,
    avoidance: TurnDirection?,
    obstacleMm: Int?,
    onGrantPermission: () -> Unit,
) {
    Card(
        // Cap the width so the HUD doesn't span the whole screen in landscape.
        modifier = Modifier
            .widthIn(max = 520.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        // Fixed colors, not theme-derived: a themed surface with an alpha copy
        // loses its matching contentColor and renders black-on-black in dark mode.
        colors = CardDefaults.cardColors(
            containerColor = if (avoidance != null) HudAlertSurface else HudSurface,
            contentColor = HudInk,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
    ) {
        when {
            !hasPermission -> Row(
                modifier = Modifier.padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Location permission is required to navigate.",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    color = HudInk,
                )
                TextButton(onClick = onGrantPermission) {
                    Text("Grant", color = HudBlue, fontWeight = FontWeight.Bold)
                }
            }

            !compassAvailable -> StatusText("This device has no orientation sensor — can't show heading.")

            // Obstacle reported by the cane: stop the user (the phone is buzzing),
            // then steer around it. This wins over route guidance until clear.
            avoidance != null -> Row(
                modifier = Modifier.padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HudLight(color = HudRed) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(52.dp),
                    )
                }
                Spacer(Modifier.width(18.dp))
                Column {
                    Text(
                        text = "STOP",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Black,
                        color = HudRed,
                    )
                    Text(
                        text = obstacleMm?.let {
                            String.format(Locale.US, "Object %.2f m ahead", it / 1000f)
                        } ?: "Object ahead",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = HudInk,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (avoidance == TurnDirection.LEFT) Icons.AutoMirrored.Filled.ArrowBack
                            else Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            tint = HudOrange,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (avoidance == TurnDirection.LEFT)
                                "Then step around to the left"
                            else "Then step around to the right",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = HudOrange,
                        )
                    }
                }
            }

            !destinationSet -> StatusText("Tap anywhere on the map to set your destination.")

            !hasFix -> StatusRow("Waiting for a GPS fix…")

            headingDelta == null -> StatusRow("Reading the compass — move the phone a little…")

            else -> Row(
                modifier = Modifier.padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The "light": green when pointing the right way, red + turn arrow otherwise.
                val lightColor by animateColorAsState(
                    targetValue = if (aligned) HudGreen else HudRed,
                    label = "guidanceLight",
                )
                HudLight(color = lightColor) {
                    val icon = when {
                        aligned -> Icons.Default.Check
                        headingDelta < 0f -> Icons.AutoMirrored.Filled.ArrowBack
                        else -> Icons.AutoMirrored.Filled.ArrowForward
                    }
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(52.dp),
                    )
                }
                Spacer(Modifier.width(18.dp))
                Column {
                    Text(
                        text = when {
                            aligned -> "Head straight"
                            headingDelta < 0f -> "Turn left ${abs(headingDelta).roundToInt()}°"
                            else -> "Turn right ${abs(headingDelta).roundToInt()}°"
                        },
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Black,
                        color = if (aligned) HudGreen else HudInk,
                    )
                    // Distinguish the straight line you asked for from the straight
                    // line you got because Directions had nothing for us.
                    val mode = when {
                        !roadRoutingEnabled -> "straight line"
                        routeLoading -> "finding route…"
                        routeIsRoad -> "following route"
                        else -> "no route — straight line"
                    }
                    Text(
                        text = "${distanceToDest?.let { formatDistance(it) } ?: "—"} to go • $mode",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = HudInk,
                    )
                    Text(
                        text = "Heading ${trueHeading.roundToInt()}° • Target ${targetBearing?.roundToInt() ?: "—"}°",
                        style = MaterialTheme.typography.bodyMedium,
                        color = HudInkMuted,
                    )
                    if (compassNeedsCalibration) {
                        Text(
                            text = "Compass needs calibration — wave the phone in a figure-8.",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = HudOrange,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Trim for the compass, and a way to clear it.
 *
 * The sensor remap in [NavigatorScreen] assumes one grip — phone upright in
 * landscape, aiming with the back camera. Strapped to the cane, or carried any
 * other way, the raw heading is off by a roughly constant angle; everything
 * downstream (the turn arrow, the cane's wheel) inherits that error. This sets
 * the angle, preferably from GPS course over ground, otherwise by hand.
 */
@Composable
private fun HeadingCalibrationDialog(
    rawAzimuth: Float,
    declination: Float,
    trueHeading: Float,
    headingOffset: Float,
    gpsCourse: Float?,
    needsFigureEight: Boolean,
    onOffsetChange: (Float) -> Unit,
    onResetSensor: () -> Unit,
    onDismiss: () -> Unit,
) {
    val canAlign = gpsCourse != null && !rawAzimuth.isNaN()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Direction calibration") },
        text = {
            // The app is used in landscape, where dialog height is tight — an
            // AlertDialog body doesn't scroll on its own.
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "Heading now: " +
                        if (trueHeading.isNaN()) "—" else "${trueHeading.roundToInt()}°",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "sensor ${if (rawAzimuth.isNaN()) "—" else "${rawAzimuth.roundToInt()}°"}" +
                        " · declination ${declination.roundToInt()}°" +
                        " · trim ${headingOffset.roundToInt()}°",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (needsFigureEight) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "The magnetometer is uncalibrated — wave the phone in a " +
                            "figure-8 first, or any trim set now will drift away.",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = HudOrange,
                    )
                }

                // One compact action row: the dialog has to fit in landscape, which
                // is where this app lives, and anything below the fold won't be found.
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            gpsCourse?.let { onOffsetChange(wrap180(it - rawAzimuth - declination)) }
                        },
                        enabled = canAlign,
                    ) { Text("Align to GPS") }
                    TextButton(onClick = { onOffsetChange(wrap180(headingOffset - 5f)) }) {
                        Text("−5°")
                    }
                    TextButton(onClick = { onOffsetChange(wrap180(headingOffset + 5f)) }) {
                        Text("+5°")
                    }
                }
                Text(
                    text = if (gpsCourse != null)
                        "GPS says you're walking ${gpsCourse.roundToInt()}° — Align makes the " +
                            "compass agree. Or nudge until the arrow matches the way the " +
                            "phone actually faces."
                    else "Align needs you walking in a straight line, holding the phone the " +
                        "way you'll carry it. Standing still, nudge until the arrow matches " +
                        "the way the phone actually faces.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = {
            // Back to the raw sensor: clears the trim and re-registers the listener.
            TextButton(onClick = {
                onOffsetChange(0f)
                onResetSensor()
            }) { Text("Reset") }
        },
    )
}

@SuppressLint("MissingPermission")
@Composable
private fun BtDevicePickerDialog(
    link: BluetoothGuidanceLink,
    state: BtLinkState,
    caneLink: CaneBleLink,
    caneState: CaneLinkState,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Guidance over Bluetooth") },
        text = {
            Column {
                Text("Smart cane (BLE)", fontWeight = FontWeight.Bold)
                Text(
                    text = when (caneState) {
                        is CaneLinkState.Connected ->
                            "Connected to ${caneState.deviceName} — receiving distance."
                        is CaneLinkState.Connecting -> "Connecting to ${caneState.deviceName}…"
                        CaneLinkState.Scanning -> "Searching for \"Distance Watch\"…"
                        else -> "Not connected. Power the cane and it will be found automatically."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row {
                    TextButton(onClick = { caneLink.start() }) { Text("Search now") }
                    if (caneState !is CaneLinkState.Disconnected) {
                        TextButton(onClick = { caneLink.stop() }) { Text("Stop") }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Motor / Arduino (serial)", fontWeight = FontWeight.Bold)
                when (state) {
                    is BtLinkState.Connected ->
                        Text("Streaming guidance frames to ${state.deviceName}.")
                    is BtLinkState.Connecting ->
                        Text("Connecting to ${state.deviceName}…")
                    else ->
                        Text(
                            "Connecting automatically to the paired serial module. " +
                                "Pair your HC-05/HC-06 in Android Settings first (PIN is " +
                                "usually 1234 or 0000) — pick it below to override."
                        )
                }
                Spacer(Modifier.height(12.dp))
                if (!link.isBluetoothEnabled) {
                    Text("Bluetooth is off.", fontWeight = FontWeight.Bold)
                    TextButton(onClick = {
                        context.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                    }) { Text("Turn on Bluetooth") }
                } else {
                    val devices = link.bondedDevices()
                    if (devices.isEmpty()) {
                        Text("No paired devices found.")
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                            items(devices) { device ->
                                TextButton(onClick = {
                                    link.connect(device)
                                    onDismiss()
                                }) {
                                    Text("${device.name ?: device.address}  (${device.address})")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (state !is BtLinkState.Disconnected) {
                TextButton(onClick = { link.disconnect() }) { Text("Disconnect") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

/** The big round status light, with a soft halo in its own colour. */
@Composable
private fun HudLight(color: Color, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(84.dp)
            .background(color.copy(alpha = 0.18f), CircleShape)
            .padding(6.dp)
            .background(color, CircleShape),
        contentAlignment = Alignment.Center,
        content = { content() },
    )
}

/** Small pill under the HUD: a coloured status dot plus a label. */
@Composable
private fun HudChip(text: String, dotColor: Color, highlighted: Boolean = false) {
    Card(
        shape = RoundedCornerShape(50),
        colors = CardDefaults.cardColors(
            containerColor = if (highlighted) HudAlertSurface else HudSurface,
            contentColor = HudInk,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .background(dotColor, CircleShape)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = HudInk,
            )
        }
    }
}

@Composable
private fun StatusText(message: String) {
    Text(
        text = message,
        modifier = Modifier.padding(20.dp),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = HudInk,
    )
}

@Composable
private fun StatusRow(message: String) {
    Row(
        modifier = Modifier.padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(22.dp),
            strokeWidth = 3.dp,
            color = HudBlue,
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = HudInk,
        )
    }
}
