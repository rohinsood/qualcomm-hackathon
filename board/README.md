# Cane board (Arduino UNO Q)

One app runs the whole cane: **`distance-watch/`** — phone-steered wheel,
obstacle haptics, LED-matrix status, and a live web dashboard. This is the
merged v3 firmware (wheel + three Modulinos + dashboard) carried onto the
qhackGPS/qhackcane pairing: the phone side here is **qhackGPS** (repo root),
talking over the Nordic UART bridge.

- **Wheel**: spins left/right on phone command or the dashboard buttons
  (dashboard always drives full scale). The 13×8 LED matrix mirrors the
  motion.
- **Vibro buzz**, parking-sensor style: pulses start at a 700 ms rhythm at
  the presence threshold (default 300 mm) and tighten to 250 ms as the
  obstacle closes; at/below 250 mm the buzz goes **continuous** ("stop now").
- **QCane Link dashboard** (port 7000): per-Modulino cards — distance
  readings (mm/cm, rate, age), motor telemetry with **live graphs** of
  sensed current (mA) and applied voltage (duty × VM, VM = 5 V), vibro
  state, Bluetooth links, rolling event log — plus manual spin/stop/buzz
  and clear-graphs buttons. Laptop arrow keys drive too: hold ← / → to
  spin (release stops), ↓/Space stops, ↑ buzzes.
- **Failsafe**: the Linux side re-sends the desired state 4×/s; if that
  stream stops for 2 s, the sketch stops the wheel and vibro on its own.

## Hardware

Modulinos daisy-chained on the Qwiic connector (`Wire1`), in wiring order:

```
UNO Q ──▶ Modulino Motors ──▶ Modulino Vibro ──▶ Modulino Distance
```

Chain order does not matter on I²C — each node answers on its own address.

**Motor wiring (important):** the motor sits across screw terminals
**`1A` + `2A`** — one half-bridge from **channel A** and one from
**channel B**, not a single channel's own pair, so the sketch drives the two
channels in opposite phase (`driveMotor()`); both channels show current
while it spins. `motor_selftest` re-measures every drive option if the
wiring is ever in doubt. Motor power goes into the `VM` + `GND` screw
terminals (5 V here); the yellow VM LED confirms power.

## How it works

```
Modulino Distance ──I²C──▶ MCU sketch ──"distance_reading"──▶ Python ──WebSocket──▶ dashboard
Modulino Motors + Vibro ◀──I²C── MCU sketch ◀──"set_wheel"/"set_vibro" heartbeat── Python
                                                                 ▲
                                                   HTTP :7000 (poll + POST)
                                                                 ▼
qhackGPS phone (BLE central) ◀──Nordic UART──▶ ble-bridge/ble_bridge.py (host daemon)
```

- `distance-watch/sketch/sketch.ino` — owns all three Modulinos: streams
  each valid ToF measurement, applies wheel commands (opposite-phase drive +
  matrix animation + `wheel_applied` acks), pulses the vibro, sends 2 Hz
  `motor_telemetry` and 1 Hz status heartbeats, and runs the motor
  self-test on request.
- `distance-watch/python/main.py` — the policy layer: presence
  (`mm <= threshold`) drives the vibro; the last wheel command (phone or
  dashboard, last writer wins) drives the motor; both re-sent 4×/s as the
  failsafe heartbeat. Serves the dashboard, REST API, event log, and graph
  history.
- `ble-bridge/` — host-side Nordic UART bridge + auto-accept pairing agent
  (systemd user services). The phone's texts steer the wheel (table below)
  and its 5 s heartbeat feeds the dashboard's Bluetooth card.
- `distance-watch/host/qcane_btd.py` — optional QCane GATT/SPP daemon from
  the v3 wheel board (speeds 1–5, `left`/`right 4`/JSON). Not needed for
  qhackGPS; the app's socket client simply reports "not running" until the
  daemon is started. Contract: `distance-watch/README.md`.

## Bluetooth (qhackGPS phone link)

`ble-bridge/ble_bridge.py` runs **on the Linux host** as a systemd *user*
service and exposes a standard **Nordic UART Service** peripheral named
**"Distance Watch"**:

| | UUID |
|---|---|
| Service (NUS) | `6E400001-B5A3-F393-E0A9-E50E24DCCA9E` |
| RX — phone **writes** | `6E400002-B5A3-F393-E0A9-E50E24DCCA9E` |
| TX — board **notifies** | `6E400003-B5A3-F393-E0A9-E50E24DCCA9E` |

**Board → phone** (subscribe to TX): `{"mm":123,"p":1}` — latest distance
and whether an obstruction is inside the threshold (`"mm":null` when nothing
is in range); `{"say":"..."}` — a line for the phone to read aloud over TTS,
sent whenever a dashboard button (spin/stop/buzz) is pressed.

**Phone → board** (write UTF-8 text to RX):

| you send | effect |
|---|---|
| a number, e.g. `1200` | sets the presence threshold (mm); ack `{"thr":1200}` |
| `get` | state snapshot as JSON |
| `TURN LEFT 90` / `TURN RIGHT 35` | route turn: wheel turns that way at full speed |
| other text containing `left` / `right` | full-speed dodge (e.g. `AVOID LEFT`) |
| text containing `clear` / `stop` / `straight` | stops the wheel |
| any other text | shown on the dashboard as "message from phone" |

**qhackGPS** (repo root) is the primary client: it auto-discovers "Distance
Watch", subscribes to the distance stream, widens the threshold to 1200 mm
on connect, and streams its steering back — `AVOID LEFT`/`AVOID RIGHT`
while dodging an obstacle, `TURN LEFT/RIGHT <deg>` when the route itself
bends (a 90° corner starts the wheel turning the moment guidance calls it),
`CLEAR` when aligned — all of which physically drive the wheel. The wheel
also stops when the phone disconnects or the bridge dies.

## Steering authority: the phone, only the phone

The board never decides a turn on its own. An obstacle is only *sensed*
here — it streams to the phone as `{"mm","p"}` — and qhackGPS makes the
call, answering with `AVOID`/`TURN`/`CLEAR` texts that drive the wheel
(and speaking every turn it commands). With no phone connected the board
buzzes the vibro but the wheel stays put; dashboard buttons still work,
and the 2 s failsafe still stops everything if the link dies.

Service install (first time on a board; fix the path in the unit to match
where this repo lives):

```bash
sed "s|/home/arduino/dev/qhackcane/ble-bridge|$(pwd)/ble-bridge|" ble-bridge/qhack-ble-bridge.service > ~/.config/systemd/user/qhack-ble-bridge.service
sed "s|/home/arduino/dev/qhackcane/ble-bridge|$(pwd)/ble-bridge|" ble-bridge/qhack-bt-agent.service  > ~/.config/systemd/user/qhack-bt-agent.service
systemctl --user daemon-reload && systemctl --user enable --now qhack-ble-bridge qhack-bt-agent
```

(Needs `python3-dbus` + PyGObject: `sudo apt-get install -y python3-dbus`.)

## Run

```bash
arduino-app-cli app start <repo>/board/distance-watch
arduino-app-cli app logs  <repo>/board/distance-watch --follow
```

Then open `http://<board-ip>:7000`.

## API quick reference (dashboard buttons use these)

```bash
curl -s localhost:7000/api/state                                                            # everything
curl -X POST localhost:7000/api/motor  -H 'Content-Type: application/json' -d '{"dir":-1}'  # -1 left, 0 stop, 1 right (full speed)
curl -X POST localhost:7000/api/vibro  -H 'Content-Type: application/json' -d '{"ms":600}'  # one-shot buzz, 50–3000 ms
curl -X POST localhost:7000/api/threshold -H 'Content-Type: application/json' -d '{"mm":250}'
curl -X POST localhost:7000/api/graphs/clear                                                # wipe the motor graphs
```

## Tuning

- `distance-watch/sketch/sketch.ino`: `COMMAND_TIMEOUT_MS` (failsafe, 2 s).
  There is no speed knob — every spin is pinned to 100% duty in the
  firmware itself (speed arguments on the wire are ignored).
- `distance-watch/python/main.py`: `PRESENCE_MAX_MM` (threshold, also
  settable live from the phone or `/api/threshold`),
  `VIBRO_PERIOD_FAR_MS` / `VIBRO_PERIOD_NEAR_MS` / `VIBRO_STOP_TIER_MM`
  (haptic rhythm grading), `MOTOR_VM_V` (5 V — scales the dashboard
  voltage graph).
