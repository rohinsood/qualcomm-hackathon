# qhackGPS

Compass-guided navigation component for an assured-navigation system. Point the
phone's back camera where you're heading; the app shows a **green light** when
you're pointing toward your destination and **left/right turn arrows** (with
degrees) when you're not — and exports that guidance live to an Arduino over
Bluetooth and to sibling apps via broadcasts.

## How it works

- Tap the map to set a destination. The app fetches a walking route from the
  Google Directions API (falls back to a straight line if unavailable).
- Hold the phone upright in landscape, screen facing you: heading = where the
  **back camera** looks (rotation-vector sensor remapped with
  `remapCoordinateSystem(AXIS_X, AXIS_Z)`, corrected to true north).
- Guidance aims at the route point ~25 m ahead, so it follows turns.
- Green light within 15° of the target bearing (stays green until 25° —
  hysteresis stops flicker).

## Component architecture

`NavigatorScreen` (map UI) publishes every guidance change into an in-process
bus, and two exporters consume it. Future components (camera, object detection)
can observe the same bus.

```
qhackcane (UNO Q + Modulino Distance)
        │ BLE / Nordic UART: {"mm":842,"p":1}
        ▼
sensors/GPS ─> NavigatorScreen ─> GuidanceBus (StateFlow<GuidanceUpdate>)
        ▲                            ├─> BluetoothGuidanceLink  ─ SPP ─> Arduino ─> motor
   CaneBleLink                       ├─> Broadcast exporter     ─────> other apps
                                     └─> (your future in-app components)
```

- `guidance/Guidance.kt` — `GuidanceUpdate` (the data contract), `GuidanceBus`
- `bt/BluetoothGuidanceLink.kt` — Bluetooth Classic SPP client, auto-connect +
  auto-reconnect to the Arduino
- `bt/CaneBleLink.kt` — BLE central for the qhackcane obstacle sensor
- `haptics/ObstacleHaptics.kt` — the "stop!" vibration while an object is ahead
- `NavUtils.kt` — bearing math, polyline decode, Directions fetch

## Bluetooth protocol (Arduino)

Classic Bluetooth SPP (RFCOMM, UUID `00001101-...`), i.e. HC-05/HC-06/ESP32
serial. Pair the module in Android settings once (PIN usually `1234`) — the app
then **connects on its own at launch** and keeps the link up: it prefers the
device that worked last time, otherwise a paired device whose name looks like a
serial module (`HC-05`, `ESP32`, `JDY-…`, …), rotating through candidates every
4 s until one answers. Tap **BT** only to override the pick or disconnect.
The app streams one ASCII line ~5x/second:

```
QG,<dir>,<deltaDeg>,<distanceM>,<headingDeg>,<bearingDeg>,<aligned>,<obst>,<obstMM>\n
```

| Field        | Meaning                                                   |
| ------------ | --------------------------------------------------------- |
| `dir`        | `S` straight (green light) · `L` left · `R` right · `N` no destination |
| `deltaDeg`   | 0–180, degrees off target (45 nominal while dodging an obstacle) |
| `distanceM`  | straight-line meters to destination, `-1` if none         |
| `headingDeg` | camera heading, 0–359 true north, `-1` unknown            |
| `bearingDeg` | target bearing, 0–359 true north, `-1` none               |
| `aligned`    | `1` green light, `0` not                                  |
| `obst`       | `1` while the smart cane reports an object in the way (`dir` already carries the dodge) |
| `obstMM`     | cane distance to the object in mm, `-1` unknown/none      |

Example: `QG,L,37,171,147,183,0,0,-1` → turn left 37°, 171 m to go, no obstacle.
Old 6-field parsers keep working — the two extra fields append at the end.

The steady cadence is a heartbeat: **if the Arduino sees no frame for 1 s it
should stop/center the motor.** A ready-to-flash sketch (servo example +
failsafe + wiring notes) is in
[`arduino/qhack_guidance_motor/`](arduino/qhack_guidance_motor/qhack_guidance_motor.ino).

## Broadcast contract (other apps / components)

Sent whenever the actionable payload changes:

- **Action:** `com.example.qhackgps.GUIDANCE`
- **Extras:** `direction` (String: `NONE|STRAIGHT|LEFT|RIGHT`), `deltaDeg` (Int),
  `aligned` (Boolean), `distanceM` (Int), `headingDeg` (Int), `bearingDeg` (Int),
  `lat`/`lng`/`destLat`/`destLng` (Double, `NaN` when unknown),
  `obstacle` (Boolean), `obstacleMm` (Int, `-1` unknown), `timestampMs` (Long)

Consume with a runtime-registered receiver (manifest receivers won't get
implicit broadcasts since Android 8):

```kotlin
val receiver = object : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val dir = i.getStringExtra("direction")      // "LEFT"
        val delta = i.getIntExtra("deltaDeg", 0)     // 37
        val aligned = i.getBooleanExtra("aligned", false)
    }
}
// On Android 13+, pass RECEIVER_EXPORTED when registering.
ContextCompat.registerReceiver(context, receiver,
    IntentFilter("com.example.qhackgps.GUIDANCE"),
    ContextCompat.RECEIVER_EXPORTED)
```

## Smart cane input ([qhackcane](https://github.com/iujab/qhackcane), BLE)

The app is also a BLE central for the qhackcane board (Arduino UNO Q + Modulino
Distance). It scans for the cane's Nordic UART Service (advertised as
**"Distance Watch"**, no pairing needed), subscribes to the distance stream
(`{"mm":842,"p":1}` JSON lines), and on connect widens the cane's presence
threshold to **1200 mm** so obstacles register early enough to walk around.

- The HUD shows a live cane chip: `Cane ✓ 0.84 m` / `Cane ✓ clear`.
- While the cane reports an object inside the threshold, the app shows
  **"Stop — object ahead"** with the measured distance and the side to step
  around to, and overrides the exported guidance with that dodge (the side is
  chosen toward the route target, latched until the path clears so the arrow
  doesn't flip mid-turn).
- **The phone vibrates** the whole time an object is in the way — three sharp
  pulses, a gap, repeat, at alarm priority so it fires through silent mode. You
  are walking and probably not looking at the screen, so the buzz is the stop
  signal; it ends the moment the path is clear (and is muted while the app is in
  the background). See `haptics/ObstacleHaptics.kt`.
- The avoidance state is mirrored back to the cane's web dashboard as
  `AVOID LEFT` / `AVOID RIGHT` / `CLEAR` ("message from phone").

The link is automatic: power the cane and the phone finds it within seconds
(rescans every few seconds while disconnected). The BT dialog shows its status.

## Building

1. Put your Google Maps key in `local.properties`:
   `MAPS_API_KEY=AIza...` (enable *Maps SDK for Android*; also *Directions API*
   for street routes).
2. `./gradlew :app:assembleDebug` and install, or run from Android Studio.

Min SDK 24 · tested on a Pixel 2 (Android 10) · Compose + Maps Compose.
