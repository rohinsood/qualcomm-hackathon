# Lighthouse — Cane Board & BLE Protocol Reference

The cane hardware is an **Arduino UNO Q** driving three Modulinos, steered
over Bluetooth by the phone. `QCane` is the real on-the-wire BLE identity —
that name is not an alias, it is what the board advertises.

The phone-side counterpart is
`app/src/main/java/dev/quad/shepherd/bt/CaneBleLink.kt`.

> **Read [Protocol mismatches](#protocol-mismatches-phone-vs-board) before
> wiring anything up.** The phone and board implementations disagree on the
> command vocabulary and on which BLE service is actually in use. As
> committed on `origin/v3`, the phone's motor letters are rejected by the
> board.

---

## 1. Board architecture

### Hardware

Modulinos daisy-chained on the Qwiic connector (`Wire1`), in wiring order:

```
UNO Q ──▶ Modulino Motors ──▶ Modulino Vibro ──▶ Modulino Distance
```

Chain order does not matter on I²C — each node answers on its own address.

| Module | Part | Bus | Address | Role |
|---|---|---|---|---|
| Modulino Motors | MAX22211 dual H-bridge, up to 3.8 A/channel | Qwiic / `Wire1` | `0x48` | Drives the wheel |
| Modulino Vibro | haptic vibration motor | Qwiic / `Wire1` | — | Obstacle buzz |
| Modulino Distance | VL53L4CD / VL53L4ED time-of-flight | Qwiic / `Wire1` | — | Presence sensing |

Plus the UNO Q's onboard **13x8 LED matrix** (104 pixels, 3-bit grayscale)
mirroring wheel state.

#### Power — the most common failure

The Qwiic cable carries **3.3 V logic only**. The motor needs its **own
5-24 V supply** on the Modulino's `VM` + `GND` screw terminals (5 V is used
here; the yellow VM LED confirms power). Without it the H-bridge accepts every
command and drives nothing.

#### Wiring — the motor is across `1A` + `2A`

That is one half-bridge from **channel A** and one from **channel B**, not a
single channel's own pair. So the two channels must be driven in **opposite
phase**:

```cpp
motors.setDcSpeedRaw(+raw, -raw);   // one way
motors.setDcSpeedRaw(-raw, +raw);   // the other
```

Both channels show current while it spins. This also works unchanged if the
motor is moved onto a single channel's pair (`1A`/`1B`), so it is a safe
default either way. If left and right come out swapped, flip the sign in
`driveMotor()`.

### The three-layer software split

```
Phone  ──BLE GATT write──┐
       ──SPP  RFCOMM  ───┤
                         ▼
   host/qcane_btd.py            Linux side, runs on the HOST (not in Docker)
     owns BlueZ over D-Bus
                         │  Unix socket  .run/qcane-bt.sock
                         ▼
   python/main.py               Linux side, runs in the app container
     Bridge.notify("set_wheel", dir, speed)
                         │  Router Bridge
                         ▼
   sketch/sketch.ino            MCU — drives the motor, mirrors it on the matrix
     Bridge.notify("wheel_applied", dir, speed, motorReady)  ──► up to the phone
                         │  Qwiic / Wire1, I²C 0x48
                         ▼
   Modulino Motors              MAX22211 dual H-bridge
```

| Layer | File | Responsibility |
|---|---|---|
| MCU sketch | `board/qcane-wheel/sketch/sketch.ino` | Owns all three Modulinos. Streams ToF measurements, applies wheel commands (opposite-phase drive + matrix animation + acks), pulses the vibro, sends 2 Hz motor telemetry and 1 Hz status heartbeats, runs the self-test |
| Linux app (container) | `board/qcane-wheel/python/main.py` | **Policy layer.** Presence → vibro; last wheel command wins (phone or dashboard); re-sends both 4x/s as the failsafe heartbeat. Serves the dashboard, REST API, event log, graph history. Bridges the BT daemon socket |
| Host daemon | `board/qcane-wheel/host/qcane_btd.py` | Owns the radio. BLE GATT + Classic SPP over BlueZ D-Bus |

### Why the Bluetooth daemon must run on the host

The Arduino App's Python runs in a **container on a Docker bridge network with
no `/run/dbus` mount**. There, `AF_BLUETOOTH` fails with **`EAFNOSUPPORT`**.
The container cannot reach BlueZ at all, and Bluetooth is not an Arduino brick,
so there is no supported in-container path either.

So the radio is driven from the host, and the two halves meet on a **Unix
socket inside the app folder**, which is already bind-mounted into the
container at `/app`:

```
QCANE_BT_SOCKET  (env override)
default: /app/.run/qcane-bt.sock
```

The daemon needs **no root and no pip packages** — BlueZ is driven over D-Bus
through the system `gi` (PyGObject) install.

A second benefit: keeping Bluetooth in a long-lived host process means a
connected phone survives `arduino-app-cli app restart` while you iterate.

### Sketch build requirements

`board/qcane-wheel/sketch/sketch.yaml`, platform `arduino:zephyr`:

| Library | Version | Why |
|---|---|---|
| `Arduino_Modulino` | **0.9.0** | First version with `ModulinoMotors`; the 0.7.0 bundled with the core lacks it |
| `STM32duino VL53L4CD` | 1.0.5 | Pulled in by `Modulino.h` |
| `STM32duino VL53L4ED` | 1.0.1 | Pulled in by `Modulino.h` |
| `Arduino_LSM6DSOX` | 1.1.2 | Pulled in by `Modulino.h` |
| `Arduino_LPS22HB` | 1.0.2 | Pulled in by `Modulino.h` |
| `Arduino_HS300x` | 1.0.0 | Pulled in by `Modulino.h` |
| `ArduinoGraphics` | 1.1.4 | Pulled in by `Modulino.h` |
| `Arduino_LTR381RGB` | 1.0.0 | Pulled in by `Modulino.h` |

A sketch profile does **not** resolve transitive dependencies, so
`Modulino.h`'s whole sensor-driver set is listed explicitly. Dropping any of
them breaks the build.

---

## 2. The full BLE contract

### Board identity

| | Value |
|---|---|
| Bluetooth name (pairing / SPP) | `QCane-Wheel` (`--name`) |
| BLE advertised name | `QCane` (`--ble-name`) — short so it fits beside the 128-bit UUID |
| Adapter address | `14:B5:CD:EA:B7:99` |
| Adapter | `hci0` (`--adapter`) |
| Pairing PIN (legacy devices) | `0000` (`--pin`) |

The advertising name budget is computed in `qcane_btd.py`:

```python
# 31 bytes: 3 for flags, 18 for one 128-bit service UUID, 2 for the name header
MAX_ADV_NAME = 31 - 3 - (2 + 16) - 2      # = 8
```

A longer `--ble-name` is truncated to 8 bytes with a warning. If BlueZ still
rejects the advertisement, `retry_without_name()` shortens the name to
`local_name[:4] or "QC"` and retries — **the UUID matters more, it is what the
phone scans for.**

While the daemon runs, the adapter is made discoverable/pairable and renamed
to `QCane-Wheel`; both are restored on exit (`Adapter.restore()`).

### Option A — BLE GATT (no pairing needed)

| Role | UUID | Flags |
|---|---|---|
| Service | `bcf2f193-f22b-4695-af5e-fd3b9caf4977` | primary |
| Command characteristic | `bcf2f194-f22b-4695-af5e-fd3b9caf4977` | `write`, `write-without-response` |
| State characteristic | `bcf2f195-f22b-4695-af5e-fd3b9caf4977` | `read`, `notify` |

D-Bus object paths (for `bluetoothctl` / debugging):

| Object | Path |
|---|---|
| GATT application | `/qcane/wheel` |
| Service | `/qcane/wheel/service0` |
| Command char | `/qcane/wheel/service0/char0` |
| State char | `/qcane/wheel/service0/char1` |
| Advertisement | `/qcane/adv0` |
| SPP profile | `/qcane/spp` |
| Pairing agent | `/qcane/agent` |

Write the command as plain UTF-8 bytes. The state characteristic reads back
`"<action>:<speed>"` (e.g. `left:5`) and notifies on every change — this
reflects what the **MCU actually applied**, not merely what was requested.

**Scan by service UUID, not by name** — the name is only advertised as a
convenience.

```kotlin
val SERVICE = UUID.fromString("bcf2f193-f22b-4695-af5e-fd3b9caf4977")
val CMD     = UUID.fromString("bcf2f194-f22b-4695-af5e-fd3b9caf4977")
val STATE   = UUID.fromString("bcf2f195-f22b-4695-af5e-fd3b9caf4977")

// Scan with ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build(),
// connectGatt(...), discoverServices(), then:
val chr = gatt.getService(SERVICE).getCharacteristic(CMD)

// Android 13+
gatt.writeCharacteristic(chr, "left".toByteArray(), WRITE_TYPE_DEFAULT)

// Android 12 and older
chr.value = "left".toByteArray()
gatt.writeCharacteristic(chr)
```

An unrecognised payload is rejected with a D-Bus
`org.freedesktop.DBus.Error.InvalidArgs` carrying the message
`"expected left, right or stop"`, and logged as
`BLE write ignored, unrecognised payload: ...`.

### Option B — Classic SPP / RFCOMM

| | Value |
|---|---|
| Service UUID | `00001101-0000-1000-8000-00805f9b34fb` (standard SPP) |
| Channel | assigned by BlueZ (`Channel: 0`), found via SDP |
| Profile name | `QCane Wheel` |
| Role | `server` |
| `RequireAuthentication` | `false` |
| `RequireAuthorization` | `false` |

Pair with `QCane-Wheel` once from Android's Bluetooth settings — the board
runs a **`NoInputNoOutput`** agent that auto-accepts, so there is no PIN
prompt. Then it is an ordinary socket, one command per line.

```kotlin
val SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
val device = adapter.bondedDevices.first { it.name == "QCane-Wheel" }
val socket = device.createRfcommSocketToServiceRecord(SPP)
socket.connect()
socket.outputStream.write("left\n".toByteArray())
```

State comes back on the same socket as `"<action>:<speed>\n"`.

SPP is newline-tolerant in both directions: `_on_data` splits on `\n` **or**
`\r`, and clients that never send a newline are handled by
`_schedule_flush()` — a 60 ms `GLib.timeout_add` that parses the buffer once
data stops arriving. That exists because `"left"` can arrive as `"l"` +
`"eft"`, and `"l"` on its own is itself a valid command.

### Command grammar

Case-insensitive and deliberately forgiving. `ACTIONS` in `qcane_btd.py`:

| Canonical | Accepted aliases |
|---|---|
| `left` | `left`, `l`, `ccw`, `turn_left`, `turn-left`, `turnleft` |
| `right` | `right`, `r`, `cw`, `turn_right`, `turn-right`, `turnright` |
| `stop` | `stop`, `s`, `x`, `halt`, `brake`, `0` |

Speed is `1`-`5`, default `3` (`DEFAULT_SPEED`), separated by `:`, `=`, `,` or
a space. JSON is also accepted (`action` or `cmd` key). Out-of-range speeds are
clamped via `max(MIN_SPEED, min(MAX_SPEED, speed))`; unknown words are rejected
and logged.

```
left            LEFT            l           turn_left       turn-left
right           RIGHT           r           turn_right      turn-right
stop            STOP            s           halt            brake
left:5          left=5          left,5      left 5
{"action":"left","speed":5}     {"cmd":"right"}
```

Note the parser splits on the **first** separator found, in the order
`: = ,` then space.

### Android permissions

| Permission | When |
|---|---|
| `BLUETOOTH_CONNECT` | Both transports (Android 12+) |
| `BLUETOOTH_SCAN` | BLE (Android 12+) |
| `ACCESS_FINE_LOCATION` | BLE scanning on Android 11 and older |

All are runtime permissions. `CaneBleLink` gates on exactly this, in
`hasScanPermission` / `hasConnectPermission`, keyed off
`Build.VERSION_CODES.S`.

---

## 3. Phone → board: the motor-letters protocol

This is what `CaneBleLink.kt` + `ShepherdService.kt` + `CommandAggregator.kt`
**actually implement**, which is *not* the word grammar above.

### The letters

`CommandAggregator` emits one `Char` per period:

| Letter | Meaning | `CaneCommand.Direction` |
|---|---|---|
| `L` | left | `LEFT` |
| `R` | right | `RIGHT` |
| `S` | straight | `STRAIGHT` |
| `X` | stop | `STOP` |

Its KDoc states the intent: *"Wire letters (BLE NUS to the UNO Q, forwarded to
the Motor Modulino): L = left, R = right, S = straight, X = stop."*

### The 200 ms write cadence

`ShepherdService.startMotorLoop()`:

```kotlin
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
```

`CommandAggregator.PERIOD_MS = 200L` → **5 Hz, unconditionally** — the letter
is written every period whether or not it changed, which is what makes it a
heartbeat the board's failsafe can rely on.

### How a letter is decided

Every processed frame calls `aggregator.offer(guidance)`. Each vote is weighted
by severity:

| Severity | Weight |
|---|---|
| `DANGER` | `3f` |
| `CAUTION` | `2f` |
| `CLEAR` | `1f` |

`decide()` closes the window and returns the winning direction's letter, then
clears the votes. Ties break by **safety order**: `STOP` → `LEFT` → `RIGHT` →
`STRAIGHT`.

Empty windows are expected — the ~11 Hz frame cadence beats against the 200 ms
window — so an empty window **repeats the last letter**, and only after
`maxEmptyStreak = 3` consecutive empties does it fail safe to `X`.

Direction itself comes from `CaneCommand.from(guidance)` with
`DEAD_ZONE = 0.2f`: danger inside the dead zone means `STOP`, otherwise steer
toward the safest window once the smoothed steer clears the dead zone.

### The safety hello

On every successful connect, `onReady()` immediately sends `"s"`:

```kotlin
_state.value = CaneLinkState.Connected(name)
// Safety hello: make sure the wheel is stopped until guidance speaks
write("s")
```

Lowercase `s`. This is the one motor letter that happens to be a valid board
alias (`"s"` → `stop`) — see [mismatch #1](#protocol-mismatches-phone-vs-board).

### Write path constraints

| Constraint | Value | Source |
|---|---|---|
| Max payload | `text.take(20)` — single BLE frame, ≤ 20 bytes | `CaneBleLink.write` |
| Queue cap | `8` entries; **oldest is shed** when full | `if (writeQueue.size >= 8) writeQueue.removeFirst()` |
| Write type | `WRITE_TYPE_DEFAULT` (acknowledged) | `pumpWrites` |
| Concurrency | One write in flight; `pumpWrites()` is re-entered from `onCharacteristicWrite` | `writeInFlight` |
| Encoding | UTF-8 | `toByteArray(Charsets.UTF_8)` |

The queue sheds the oldest rather than growing, because *"motor letters are
momentary — on a stalled link, shed the oldest instead of growing the queue
without bound."*

---

## 4. Board → phone: JSON telemetry

The phone parses newline-terminated JSON lines in `CaneBleLink.parseLine`:

| Key | Type | Meaning | Phone handling |
|---|---|---|---|
| `mm` | int or `null` | Latest distance in mm; `null` = nothing in range | → `CaneReading.mm` |
| `p` | `0` / `1` | `1` while an object is within the presence threshold | → `CaneReading.present` |
| `thr` | number | Threshold-set acknowledgement | `Log.d("cane threshold ack: ...")` |
| `err` | string | Error, e.g. `"app unreachable"` | `Log.w("cane error: ...")` |

```json
{"mm":842,"p":0}
{"mm":null,"p":0}
{"thr":1200}
{"err":"app unreachable"}
```

Dispatch is order-sensitive: `mm` **or** `p` is checked first, then `thr`, then
`err`. `mm` is read as `null` when absent *or* `JSONObject.NULL`; `p` defaults
to `0` via `optInt`.

### Reassembly

`onBytes` appends to a `StringBuilder` and splits on `\n`, trimming each line.
A **runaway guard** resets the buffer if it exceeds **512 chars** — necessary
because the board chunks lines to fit small ATT payloads.

### The 1200 mm presence threshold

```kotlin
/** Presence threshold (mm) pushed to the cane on connect. */
const val OBSTACLE_THRESHOLD_MM = 1200
```

Declared in `CaneBleLink.companion`. On the board side, `main.py` starts at
`PRESENCE_MAX_MM = 300.0` and accepts `0..1500` mm via `/api/threshold`, so
1200 mm is in range and represents a deliberate widening — the phone wants
~1.2 m of warning, the board defaults to 30 cm.

> **However:** grep shows `OBSTACLE_THRESHOLD_MM` has **exactly one occurrence
> in the entire tree** — its own declaration. Nothing reads it. `write()` is
> called only from `startMotorLoop()` (motor letters) and `onReady()` (`"s"`).
> See [mismatch #3](#protocol-mismatches-phone-vs-board).

### What the phone does with a reading

`ShepherdService.startCaneLink()`:

```kotlin
if (r?.present == true && r.mm != null) {
    pathPipeline.grid.markNearObstacle(r.mm / 1000f)
    // THE haptic signal: obstacle at the cane -> STOP buzz,
    // repeating (rate-limited) while it stays in view
    if (guidanceEnabled) haptics.caneStop()
    ...
}
```

The cane reading is converted mm → meters and folded into the traversability
grid's near-field ring, and it triggers the phone's own stop haptic.

---

## 5. Reconnection and robustness

### Phone side — zombie-link detection

A "zombie link" is a `gatt` object that still looks connected but no longer
delivers callbacks. `CaneBleLink` handles it because *"Android does not always
deliver `STATE_DISCONNECTED` (seen when the board reflashes its MCU, or after a
Bluetooth toggle)."*

| Constant | Value | Purpose |
|---|---|---|
| watchdog period | `3000L` ms | Retry loop tick |
| `SCAN_WINDOW_MS` | `10_000L` | Scan duty cycle; watchdog restarts a round later |
| `WRITE_STALL_MS` | `4_000L` | In-flight write with no callback this long = zombie |
| `WRITE_FAIL_STREAK` | `8` | Consecutive immediate `writeCharacteristic` failures = dead |
| write queue cap | `8` | Shed oldest |
| line buffer guard | `512` chars | Reset on runaway |

Four independent recovery mechanisms:

1. **Watchdog (3 s)** — if `desired && gatt == null && !scanning`, start a
   scan. Idempotent and self-healing.
2. **Write-stall detection** — `writeInFlightSince` is stamped when a write
   starts and cleared in `onCharacteristicWrite`. If a write has been in flight
   longer than `WRITE_STALL_MS`, tear down and rescan:
   `"cane: write stalled — link presumed dead, reconnecting"`.
3. **Write-failure streak** — `writeFailStreak` counts *immediate*
   `writeCharacteristic` returns of `false`. At `8`, tear down, reset, and
   `startScan()` immediately: `"cane: N straight write failures — reconnecting"`.
   The streak resets on any `GATT_SUCCESS`.
4. **Adapter state receiver** — a `BroadcastReceiver` on
   `BluetoothAdapter.ACTION_STATE_CHANGED` registered `RECEIVER_NOT_EXPORTED`.
   On `STATE_TURNING_OFF` or `STATE_OFF` it drops the link, *"even if no GATT
   callback ever arrives for it."*

`teardownLocked()` is the single cleanup path: closes the gatt, nulls `gatt` and
`rxChar`, clears the write queue, resets `writeInFlight` /
`writeInFlightSince` / `writeFailStreak`, and empties the line buffer.

Link state is exposed as `StateFlow<CaneLinkState>` —
`Disconnected` / `Scanning` / `Connecting(name)` / `Connected(name)` — and
logged under `DebugLog` tag `CANE`. The whole class is a single monitor:
mutable fields are only touched inside `synchronized(this)`, and the class-wide
log tag is `"qhackGPS"`.

Notification setup writes the CCCD explicitly
(`00002902-0000-1000-8000-00805f9b34fb`,
`ENABLE_NOTIFICATION_VALUE`) and only calls `onReady` from
`onDescriptorWrite` — or directly, if the descriptor is absent. If the service
or either characteristic is missing after discovery, it logs
`"cane: NUS service/characteristics missing"` and disconnects.

### Board side — the 2 s failsafe

Two cooperating halves.

**Linux re-sends desired state 4x/s.** `main.py`'s `watchdog()` calls
`_push_actuators()` every pass, which issues
`Bridge.notify("set_wheel", motor, speed)` and
`Bridge.notify("set_vibro", vibro)`. `_sock_pump(0.25)` keeps the cadence at
~4 Hz whether or not the daemon socket is connected.

**The MCU stops everything if that stream dies.**

```cpp
constexpr unsigned long COMMAND_TIMEOUT_MS = 2000;
...
if (lastCommandMs == 0 || (now - lastCommandMs) > COMMAND_TIMEOUT_MS) {
    effDir = 0;
    vibroTarget = false;
}
```

`lastCommandMs` is stamped by `set_wheel()` and `set_vibro()`. Note it starts
at `0`, so the wheel stays stopped until the first real command — no
run-away-on-boot.

Also on the MCU:

- **Bridge handlers only set flags.** `set_wheel`, `set_vibro`, `vibro_pulse`,
  and `motor_selftest` run on the bridge thread mid-RPC, where blocking, I²C
  traffic, and logging (a nested RPC via `Monitor.write()`) are all unsafe.
  `driveMotor()` is called from `loop()` instead, once per change.
- **Self-terminating vibro pulses.** `vibro.on(VIBRO_PULSE_MS)` self-expires,
  so *"a wedged loop cannot leave the vibro buzzing."*
- **Hot-plug retry.** Any missing module is re-`begin()`-ed every
  `MODULE_RETRY_MS = 3000` ms.
- **Ack from the hardware, not the request path.**
  `Bridge.notify("wheel_applied", effDir, effSpeed, motorReady)` is
  fire-and-forget, *"so a Python side that is not listening cannot stall us."*
  `main.py`'s comment: *"Reporting from the MCU rather than from the request
  path means the state the phone sees is what the hardware is doing, not merely
  what was requested."*
- **`appliedDir` / `appliedSpeed` seeded to `-99`**, an impossible value, so
  the current state is applied and reported exactly once at boot.

`beginMotors()` also re-seeds those to `-99`, forcing re-apply and re-report
after a module reconnect.

### Linux side — socket resilience

| Constant | Value | Purpose |
|---|---|---|
| `SOCKET_RETRY_S` | `2.0` | Reconnect interval to the BT daemon |
| `MAX_BUFFER` | `64 * 1024` | Unterminated-input cap; buffer dropped past this |
| `STALE_AFTER_S` | `1.0` | No valid reading this long → report "nothing in range" |
| `EMIT_MIN_INTERVAL_S` | `0.05` | Websocket push cap (sensor streams up to ~50 Hz) |
| NUS heartbeat timeout | `12.0` s | `/api/bt` silent this long → show Bluetooth off |

A warning about an unreachable daemon is rate-limited to once per 30 s:
`"Bluetooth daemon not reachable on <path> (...). Start it on the host: python3 host/qcane_btd.py"`.

`_sock_send` takes a local reference to `_sock` because it is also called from
the Bridge thread while the main loop may drop the connection concurrently.

### Daemon side

`CommandBus.publish()` refuses to drop commands silently — with no app
connected it logs `"<source> command '<action>' dropped: the Arduino App is not
connected"` and returns `False`. New clients get an immediate
`{"type": "state", ...}` snapshot on accept. `MAX_LINE = 4096` caps per-client
buffering. `SIGINT` and `SIGTERM` both unregister the transports, close the
hub (unlinking the socket), and restore the adapter.

Registration calls use `call_async`, never `call_sync`, and the reason is
documented:

> Registration calls must not be synchronous: BlueZ calls straight back into us
> (GetManagedObjects, GetAll) while the call is in flight, and a blocked main
> loop cannot answer, so call_sync deadlocks until it times out.

---

## 6. Dashboard and REST API (port 7000)

The **QCane Link dashboard** is served by the `arduino:web_ui` brick from
`python/main.py`. Open `http://<board-ip>:7000`.

It shows per-Modulino cards — distance readings (mm/cm, rate, age), motor
telemetry with **live graphs** of sensed current (mA) and applied voltage
(duty x VM, VM = 5 V), vibro state, Bluetooth links, and a rolling event log —
plus manual spin/stop/buzz buttons and a clear-graphs button.

### Endpoints

| Method | Path | Body | Notes |
|---|---|---|---|
| `GET` | `/api/state` | — | Everything, including `motor_history` |
| `POST` | `/api/motor` | `{"dir": -1\|0\|1}` | -1 left, 0 stop, 1 right. Always `DASHBOARD_SPEED` (5) |
| `POST` | `/api/vibro` | `{"ms": 50..3000}` | One-shot buzz, default 600 |
| `POST` | `/api/threshold` | `{"mm": 0..1500}` | Presence threshold |
| `POST` | `/api/graphs/clear` | — | Wipes motor graphs, bumps `graph_epoch` |
| `POST` | `/api/phone` | `{"text": "..."}` | Freeform text from the NUS side channel |
| `POST` | `/api/bt` | `{"advertising":bool,"connected":bool,"device":str}` | NUS bridge heartbeat |

```bash
curl -s localhost:7000/api/state
curl -X POST localhost:7000/api/motor     -H 'Content-Type: application/json' -d '{"dir":-1}'
curl -X POST localhost:7000/api/motor     -H 'Content-Type: application/json' -d '{"dir":0}'
curl -X POST localhost:7000/api/vibro     -H 'Content-Type: application/json' -d '{"ms":600}'
curl -X POST localhost:7000/api/threshold -H 'Content-Type: application/json' -d '{"mm":250}'
curl -X POST localhost:7000/api/graphs/clear
curl -X POST localhost:7000/api/phone     -H 'Content-Type: application/json' -d '{"text":"AVOID LEFT"}'
```

Invalid bodies return `{"error": "expected {\"dir\": -1|0|1}"}` and similar
rather than an HTTP error.

### Text steering via `/api/phone`

`_steer_command()` does a **substring, uppercase** match so qhackGPS's
`"AVOID LEFT"` / `"AVOID RIGHT"` / `"CLEAR"` work:

| Contains | Direction |
|---|---|
| `LEFT` | `-1` |
| `RIGHT` | `+1` |
| `CLEAR`, `STOP`, or `CENTER` | `0` |
| anything else | `None` — display only |

Checks run in that order, so a string containing both `LEFT` and `RIGHT`
resolves to left. Matched text steers at `DASHBOARD_SPEED` (5); text is
truncated to 200 chars.

### Bridge notification names (sketch ↔ Python)

| Direction | Name | Payload |
|---|---|---|
| MCU → Python | `distance_reading` | `mm` (float) — every valid ToF measurement |
| MCU → Python | `sensor_status` | `ok` (bool) — 1 Hz |
| MCU → Python | `actuator_status` | `mask` — 1 Hz; bit 0 = Motors, bit 1 = Vibro |
| MCU → Python | `motor_telemetry` | `mA_a, mA_b, applied, duty_pct, vibro_active, busy` — 2 Hz |
| MCU → Python | `wheel_applied` | `dir, speed, motorReady` — on change |
| MCU → Python | `selftest_telemetry` | `stage, mA_a, mA_b, mode, busy` |
| Python → MCU | `set_wheel` | `dir, speed` — 4 Hz heartbeat |
| Python → MCU | `set_vibro` | `on` (0/1) — 4 Hz heartbeat |
| Python → MCU | `vibro_pulse` | `ms` |
| Python → MCU | `motor_selftest` | `percent` |

### Daemon socket message types

Newline-delimited JSON on `.run/qcane-bt.sock`:

| Type | Direction | Fields |
|---|---|---|
| `hello` | app → daemon | `client` |
| `command` | daemon → app | `action`, `speed`, `source` (`ble` / `spp` / `inject`) |
| `state` | app → daemon | `action`, `speed`, `motor` (bool) |
| `inject` | client → daemon | `action`, `speed` — used by `--send` |
| `selftest` | client → daemon → app | `percent` |
| `telemetry` | app → daemon | `stage`, `mA_a`, `mA_b`, `mode`, `busy` |
| `error` | app → daemon | `action`, `detail` |

---

## 7. Running it

### The Arduino App

```bash
arduino-app-cli app start ~/dev/qualcomm-hackathon/board/qcane-wheel
arduino-app-cli app logs  ~/dev/qualcomm-hackathon/board/qcane-wheel --follow
arduino-app-cli monitor                 # MCU serial output
```

### The Bluetooth daemon (separate host process)

```bash
python3 ~/ArduinoApps/qcane-wheel/host/qcane_btd.py          # foreground
python3 ~/ArduinoApps/qcane-wheel/host/qcane_btd.py -v       # debug logs
```

As a **user** service (no root):

```bash
mkdir -p ~/.config/systemd/user
cp ~/ArduinoApps/qcane-wheel/host/qcane-btd.service ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now qcane-btd
loginctl enable-linger arduino        # survives logout / starts at boot
journalctl --user -u qcane-btd -f
```

The unit is `Type=simple`, `Restart=on-failure`, `RestartSec=3`,
`After=bluetooth.target`, `ExecStart=/usr/bin/python3 %h/ArduinoApps/qcane-wheel/host/qcane_btd.py`.

> **The unit must point at the same app folder the app runs from** — the Unix
> socket lives in that folder's `.run/`.

Related host services: `qhack-ble-bridge` and `qhack-bt-agent` (the Nordic UART
side channel), also systemd **user** services with linger enabled.

Useful daemon flags: `--no-ble`, `--no-spp`, `--name`, `--ble-name`,
`--adapter`, `--pin`, `--socket`, `-v`. Disabling both transports exits with
code 2.

### Testing without a phone

The daemon injects commands into itself, exercising the whole chain down to the
MCU and waiting for the sketch's acknowledgement:

```bash
cd ~/ArduinoApps/qcane-wheel
python3 host/qcane_btd.py --send left --speed 5
python3 host/qcane_btd.py --send right
python3 host/qcane_btd.py --send stop
python3 host/qcane_btd.py --watch          # live view of commands and state
python3 host/qcane_btd.py --selftest       # profile the motor wiring (40%)
python3 host/qcane_btd.py --selftest 100   # full power
```

`--send` prints `left (speed 5) acknowledged by the app` only once the MCU has
reported back (3 s timeout), so success there means the full path worked. It
deliberately ignores the connect-time state snapshot and waits for the one
matching both the action *and* the speed it sent.

### Reading a self-test

`--selftest` drives each channel pair in turn and reports the current each
draws — only a channel with the motor across it draws current. The MCU enables
`setHalfFullScaleEnabled(true)` first, doubling current-sense resolution
(~0.65 mA/count instead of ~1.3), and restores the previous wheel state
afterwards. Each stage waits 500 ms to settle before sampling.

```
stage                   channel A    channel B  mode
idle                       0.6 mA       1.3 mA  dc
A+ only (1A/1B)            1.3 mA       1.3 mA  dc
B+ only (2A/2B)              ...          ...   dc
A+/B- (1A -> 2A)             ...          ...   dc
A-/B+ (2A -> 1A)             ...          ...   dc
stopped                      ...          ...   dc
```

The `mode` column reads back **from the module**, so `dc` confirms it is
receiving commands. That separates *"not listening"* from *"listening but
unpowered"*.

| Symptom | Diagnosis |
|---|---|
| `0.6-1.3 mA` everywhere, `mode` = `dc` | ADC noise floor — nothing driven. Check the 5-24 V supply, then the screw terminals |
| `no modulino found` | Module not answering on I²C at all; the app also warns on every command |
| Current on both A and B for `A+/B-` | Correct — motor is across `1A`/`2A` |
| `--selftest` prints nothing | Arduino App not running (no telemetry came back) |

---

## 8. Tuning constants

### `board/qcane-wheel/sketch/sketch.ino` (MCU)

| Constant | Value | Effect |
|---|---|---|
| `SPEED_PERCENT[5]` | `{30, 45, 60, 80, 100}` | Speeds 1-5 as % of full scale. Starts high enough that a loaded motor turns instead of buzzing |
| `COMMAND_TIMEOUT_MS` | `2000` | Failsafe window |
| `VIBRO_PULSE_MS` | `250` | Buzz length |
| `VIBRO_PERIOD_MS` | `500` | Buzz repeat period |
| `MODULE_RETRY_MS` | `3000` | Hot-plug re-`begin()` interval |
| `TELEMETRY_MS` | `500` | Motor telemetry cadence (2 Hz) |
| `DISTANCE_POLL_MS` | `20` | ToF poll gate (≤50 Hz) |
| `MATRIX_W` / `MATRIX_H` | `13` / `8` | LED matrix, 104 pixels |
| `RING_LEN` | `22` | Wheel perimeter pixels, clockwise |
| `COMET[]` | `{7, 5, 3, 1}` | Marker + trailing brightness (3-bit, 0..7) |
| `stepIntervalMs(speed)` | `220/150/100/70/45` ms | Animation speed for 1-5 |

Two markers half a turn apart are drawn because that *"reads as rotation much
better than one."* A faint rim (level 1) keeps the wheel visible when stopped,
and a bar (level 6) lights the panel edge on the side being turned toward.

### `board/qcane-wheel/python/main.py` (Linux policy)

| Constant | Value | Effect |
|---|---|---|
| `PRESENCE_MAX_MM` | `300.0` | Presence threshold; live-settable via `/api/threshold` or a NUS number write |
| `MOTOR_VM_V` | `5.0` | VM supply; scales the dashboard voltage graph (module cannot measure VM) |
| `DASHBOARD_SPEED` | `5` | Dashboard buttons — always full scale |
| `DEFAULT_SPEED` | `3` | Phone commands without a speed |
| `STALE_AFTER_S` | `1.0` | Reading staleness |
| `EMIT_MIN_INTERVAL_S` | `0.05` | Websocket push cap |
| `EVENT_LOG_LEN` | `30` | Dashboard event log depth |
| `MOTOR_HISTORY_LEN` | `240` | Graph history (2 Hz → 2 minutes) |
| `SOCKET_PATH` | `$QCANE_BT_SOCKET` or `/app/.run/qcane-bt.sock` | Daemon socket |

### `board/qcane-wheel/host/qcane_btd.py` (daemon)

| Constant | Value |
|---|---|
| `SERVICE_UUID` | `bcf2f193-f22b-4695-af5e-fd3b9caf4977` |
| `CMD_CHAR_UUID` | `bcf2f194-f22b-4695-af5e-fd3b9caf4977` |
| `STATE_CHAR_UUID` | `bcf2f195-f22b-4695-af5e-fd3b9caf4977` |
| `SPP_UUID` | `00001101-0000-1000-8000-00805f9b34fb` |
| `DEFAULT_SPEED` | `3` |
| `MIN_SPEED` / `MAX_SPEED` | `1` / `5` |
| `MAX_LINE` | `4096` |
| `MAX_ADV_NAME` | `8` (computed) |

### `app/src/main/java/dev/quad/shepherd/bt/CaneBleLink.kt` (phone)

| Constant | Value |
|---|---|
| `SCAN_WINDOW_MS` | `10_000L` |
| `WRITE_STALL_MS` | `4_000L` |
| `WRITE_FAIL_STREAK` | `8` |
| `OBSTACLE_THRESHOLD_MM` | `1200` |
| `NUS_SERVICE_UUID` | `bcf2f193-f22b-4695-af5e-fd3b9caf4977` |
| `NUS_RX_UUID` | `bcf2f194-f22b-4695-af5e-fd3b9caf4977` |
| `NUS_TX_UUID` | `bcf2f195-f22b-4695-af5e-fd3b9caf4977` |
| `CCCD_UUID` | `00002902-0000-1000-8000-00805f9b34fb` |
| `CommandAggregator.PERIOD_MS` | `200L` |
| `CaneCommand.DEAD_ZONE` | `0.2f` |

**Constants that must be changed together:** `SERVICE_UUID` /
`CMD_CHAR_UUID` / `STATE_CHAR_UUID` in `qcane_btd.py` and
`NUS_SERVICE_UUID` / `NUS_RX_UUID` / `NUS_TX_UUID` in `CaneBleLink.kt`. The
daemon's own comment says so: *"Protocol constants — keep these in sync with
the Android app."*

---

## Protocol mismatches: phone vs board

Verified against `origin/v3`. These are real inconsistencies in the committed
code, not documentation drift.

### Mismatch 1 — `S` means STRAIGHT to the phone and STOP to the board (BLOCKING)

The phone writes `L`, `R`, `S`, `X`. `parse_command` does
`action.strip().lower()`, so case is not the issue — but only three of the four
letters mean what the phone intends:

| Letter | Phone intent | Lowercased | In board `ACTIONS`? | Board applies |
|---|---|---|---|---|
| `L` | left | `l` | yes | `left` ✓ |
| `R` | right | `r` | yes | `right` ✓ |
| `X` | stop | `x` | yes | `stop` ✓ |
| `S` | **straight** | `s` | yes | **`stop`** ✗ |

**`S` is the bug.** There is no `straight` action anywhere on the board — the
vocabulary is only `left` / `right` / `stop`, and `main.py`'s `DIRECTIONS` dict
confirms it (`{"left": -1, "stop": 0, "right": 1}`).

So a clear path ahead — the aggregator's `STRAIGHT` verdict — brakes the wheel
every 200 ms. Because `S` is also the safety hello, the *intended*
stop-on-connect happens to work, which masks the defect.

**Also:** every letter carries no speed, so the board applies `DEFAULT_SPEED`
= `3` (60% of full scale). The phone has no way to express speeds 1-5.

### Mismatch 2 — "NUS" naming vs the actual service

`CaneBleLink`'s constants are named `NUS_SERVICE_UUID`, `NUS_RX_UUID`,
`NUS_TX_UUID`, and its KDoc describes the Nordic UART Service on a peripheral
named `"Distance Watch"`, reassembling `{"mm":842,"p":0}`.

But the UUID **values** are the QCane wheel GATT
(`bcf2f193-…`/`bcf2f194-…`/`bcf2f195-…`), not Nordic UART
(`6e400001-b5a3-f393-e0a9-e50e24dcca9e` et al., which is what
`board/ble-bridge/ble_bridge.py` actually implements). A trailing comment in
the companion object records the switch:

> QCane wheel board GATT (board/qcane-wheel/README.md). RX = the command
> characteristic we write motor letters to; TX = the state characteristic
> ("<action>:<speed>") the board notifies on.

The class scans for, and connects to, the **QCane wheel service**. The KDoc,
the constant names, the `"Distance Watch"` reference, and the log tag
`"qhackGPS"` are all stale leftovers from the NUS side channel.

### Mismatch 3 — the phone parses telemetry the QCane service never sends

This is the most consequential mismatch. Two different boards' protocols have
been spliced:

| | `qcane_btd.py` STATE char (what the phone subscribes to) | `ble_bridge.py` NUS TX (what the phone parses) |
|---|---|---|
| Service UUID | `bcf2f193-…` — **the phone connects here** | `6e400001-b5a3-f393-e0a9-e50e24dcca9e` |
| Payload | `"<action>:<speed>"`, e.g. `left:5` | `{"mm":842,"p":0}`, `{"thr":250}`, `{"err":"..."}` |
| Newline? | **No** — `_encode_state` emits no `\n` | Yes — `send_line` appends `\n` |

Consequences, all following directly from the code:

1. **No distance readings will ever arrive.** `parseLine`'s `mm`/`p`/`thr`/`err`
   keys are produced only by `ble_bridge.py`, on the NUS service the phone no
   longer connects to. `CaneBleLink.reading` therefore stays `null` forever, so
   `pathPipeline.grid.markNearObstacle(...)` and `haptics.caneStop()` in
   `ShepherdService.startCaneLink()` are dead code.
2. **`OBSTACLE_THRESHOLD_MM = 1200` is never sent.** It has exactly one
   occurrence in the tree — its declaration. The threshold-push-on-connect
   described in the KDoc is not implemented; `onReady()` writes only `"s"`. The
   board keeps its own `PRESENCE_MAX_MM = 300.0`. **The documented 1200 mm
   threshold is aspirational, not live.**
3. **The state notifications that *do* arrive are logged as garbage.**
   `left:5` is not JSON, so `parseLine` throws and logs
   `"cane: unparseable line: left:5"` on every wheel state change.
4. **No newline means no line ever completes.** Even if the payload were JSON,
   `onBytes` only emits on `\n`, and the GATT state char never sends one. The
   512-char runaway guard would eventually reset the buffer.

Note the two boards' JSON *does* agree where it overlaps —
`ble_bridge.py` emits exactly `{"mm":<int|null>,"p":<0|1>}`, `{"thr":<int>}`,
and `{"err":"app unreachable"}`, which is precisely what `parseLine` handles.
The phone's parser is correct **for the NUS bridge**; it is pointed at the
wrong service.

### Mismatch 4 — failsafe timing comment is wrong

`CommandAggregator.decide()` KDoc says:

> the Arduino's own **1 s** failsafe backstops the link itself

The actual value is `COMMAND_TIMEOUT_MS = 2000` — **2 s**. `board/README.md`
and `qcane-wheel/README.md` both correctly say 2 s. Only the Kotlin comment is
wrong. The margin is still ample: 200 ms writes against a 2 s timeout is 10x.

### Mismatch 5 — the phone never uses speeds, self-test, or `wheel_applied`

The board supports speeds 1-5, a `selftest` message type, and reports
`wheel_applied` back up through `_sock_send({"type": "state", ...})`. The phone
sends bare letters (always speed 3), never requests a self-test, and — per
mismatch 3 — cannot read the state channel. The richer half of the contract is
unexercised from the app.

### Known gap (documented in `board/README.md`)

> `qcane_btd` does not tell the app when the phone drops off the GATT, so a
> wheel command survives a phone disconnect until `stop` arrives or the app
> dies (failsafe).

Confirmed in source: `BleTransport` implements `StartNotify` / `StopNotify` /
`WriteValue` / `ReadValue` but subscribes to **no** BlueZ device-property
signal, so nothing detects a GATT disconnect and nothing publishes a synthetic
`stop`.

The mitigations that do exist:

| Layer | Covers | Does not cover |
|---|---|---|
| MCU `COMMAND_TIMEOUT_MS` (2 s) | Linux/bridge death | A live Linux side happily re-sending the last phone command 4x/s |
| Phone watchdog + zombie detection | Phone-side link loss | Anything after the phone process dies |
| `stop` from either transport, or the dashboard | Manual recovery | Automatic recovery |

**So: a phone that walks out of range mid-turn leaves the wheel turning.** The
2 s failsafe does not help, because `main.py` keeps heartbeating the stale
command. Recovery requires an explicit `stop`, `POST /api/motor {"dir":0}`, or
killing the app.

`board/README.md` also notes the NUS side channel *"does zero nothing here"* —
i.e. it provides no automatic zeroing either.

### Suggested fixes

| # | Fix | Where |
|---|---|---|
| 1 | Map `STRAIGHT` to something that is not `stop` — add a `straight` action to `ACTIONS` + `DIRECTIONS`, or stop sending `S` and hold the last turn | `qcane_btd.py` + `main.py`, or `CommandAggregator` |
| 3 | Either point the phone back at the NUS service for telemetry (a second link), or have `qcane_btd` emit newline-terminated JSON on the STATE char | `CaneBleLink` or `qcane_btd.py` |
| 3b | Actually send `OBSTACLE_THRESHOLD_MM` on connect, or delete the constant and the KDoc claim | `CaneBleLink.onReady` |
| 4 | Correct the "1 s failsafe" comment to 2 s | `CommandAggregator.kt` |
| gap | Subscribe to BlueZ device `PropertiesChanged` for `Connected == false` and publish `stop` | `qcane_btd.py` `BleTransport` |
| naming | Rename `NUS_*` → `QCANE_*`, drop the "Distance Watch" KDoc, retire the `qhackGPS` tag | `CaneBleLink.kt` |

---

## Safety

The 2 s MCU failsafe is the only automatic guard between a software fault and a
motorized cane pulling a blind pedestrian off course — and per the known gap
above, it does **not** cover a phone disconnect. Test with a sighted companion,
keep the dashboard's `stop` button reachable, and treat all guidance as
advisory.
