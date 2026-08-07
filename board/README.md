# qhackcane

Arduino UNO Q project: **Distance Watch** — a live web dashboard **and BLE
phone link** for a Modulino chain: **Motors** (steering), **Vibro** (haptic
alert), and **Distance** (VL53L4CD/VL53L4ED time-of-flight, on the Qwiic
connector).

- Big **red lamp** on the page while an object is closer than the presence
  threshold (default 300 mm).
- **Vibro buzz** (250 ms pulse every 500 ms) while that object is present —
  haptic obstacle alert straight on the cane.
- **Steering motor** on the Modulino Motors spins **left/right on phone
  command** over BLE, and stops on `clear`/`stop` or when the phone drops.
- **QCane Link dashboard** (port 7000): per-Modulino informatics cards —
  distance readings (mm/cm, rate, age), motor module telemetry (sensed
  current per channel in mA, commanded vs applied direction, busy flag),
  vibro state, Bluetooth link state, and a rolling event log — plus **manual
  controls**: spin left / stop / spin right buttons and a one-shot vibro
  buzz. (VM voltage is not readable from the module firmware; the yellow VM
  LED means motor power is present.)
- **Two-way Bluetooth**: a phone receives live distance + obstruction
  notifications and can write back (steer the motor, set the threshold, or
  send a message that appears on the web page).

## Hardware

Modulinos are daisy-chained on the Qwiic connector (`Wire1`), in wiring order:

```
UNO Q ──▶ Modulino Motors ──▶ Modulino Vibro ──▶ Modulino Distance
```

Chain order does not matter on I²C — each node answers on its own address.

**Modulino Motors wiring**: the DC motor is on **channel A, screw terminals
`1A` + `2A`** (`1B`/`2B` are the unused channel B). Motor power must be fed
into the `VM` + `GND` screw terminals (5–24 V); the yellow VM LED confirms
power. If left/right spin the wrong way, swap the `1A`/`2A` wires.

## How it works

```
Modulino Distance ──I²C (Wire1)──▶ MCU sketch ──Bridge.notify──▶ Python ──WebSocket──▶ browser
Modulino Motors + Vibro ◀──I²C──── MCU sketch ◀──Bridge "set_motor"/"set_vibro"── Python
                                                                   ▲
                                                     HTTP :7000 (poll + POST)
                                                                   ▼
phone (BLE central) ◀──Nordic UART Service──▶ ble-bridge/ble_bridge.py (host daemon)
```

- `distance-watch/sketch/sketch.ino` — reads the sensor (~50 Hz) and streams
  each valid measurement with `Bridge.notify("distance_reading", mm)`, plus
  1 Hz `sensor_status` / `actuator_status` heartbeats so the UI can tell
  *no object* apart from *no sensor*. It applies actuator commands received
  via `Bridge.provide("set_motor")` / `("set_vibro")`: motor channel A spins
  left/right/stops, vibro pulses while commanded on. **Failsafe**: if the
  Linux side stops refreshing commands for 2 s, everything stops.
- `distance-watch/python/main.py` — receives readings, tracks presence
  (`mm <= threshold`), expires stale readings after 1 s, and pushes state
  snapshots to browsers over the `web_ui` brick (WebSocket event `distance`,
  plus REST `GET /api/state`). It owns the actuator policy: **vibro follows
  presence**, **motor follows the last phone steering command**, and both are
  re-sent to the sketch 4×/s as the failsafe heartbeat. The motor is zeroed
  when the phone disconnects or the BLE bridge dies.
- `distance-watch/assets/` — the dashboard (vanilla HTML/CSS/JS, served on
  port 7000).

## Run

```bash
arduino-app-cli app start /home/arduino/dev/qhackcane/distance-watch
arduino-app-cli app logs  /home/arduino/dev/qhackcane/distance-watch --follow
```

Then open `http://<board-ip>:7000`. Quick check without a browser:

```bash
curl -s http://localhost:7000/api/state
```

MCU serial debug (sensor init status): `arduino-app-cli monitor`.

## Bluetooth (phone link)

`ble-bridge/ble_bridge.py` runs **on the Linux host** (the app container has
no D-Bus access) as a systemd *user* service. It exposes a standard
**Nordic UART Service** BLE peripheral via BlueZ and shuttles data to/from the
app over `localhost:7000`.

| | UUID |
|---|---|
| Service (NUS) | `6E400001-B5A3-F393-E0A9-E50E24DCCA9E` |
| RX — phone **writes** | `6E400002-B5A3-F393-E0A9-E50E24DCCA9E` |
| TX — board **notifies** | `6E400003-B5A3-F393-E0A9-E50E24DCCA9E` |

**Board → phone** (subscribe to TX): `{"mm":123,"p":1}` — latest distance and
whether an obstruction is inside the threshold; `"mm":null` when nothing is in
range. Sent ~2×/s while values change, instantly when presence flips.

**Phone → board** (write UTF-8 text to RX):

| you send | effect |
|---|---|
| a number, e.g. `250` | sets the presence threshold (mm); ack `{"thr":250}` |
| `get` | full state snapshot as JSON |
| text containing `left` / `right` | spins the steering motor that way (e.g. `left`, `AVOID RIGHT`) |
| text containing `clear` / `stop` | stops the steering motor |
| any other text | shown on the web page as "Message from phone" |

Steering keywords are case-insensitive substring matches, and the text still
shows on the dashboard as the "message from phone".

**Try it from a phone**: install *nRF Connect* (or *Serial Bluetooth
Terminal* in "Bluetooth LE" mode), scan, connect to **“Distance Watch”**,
enable notifications on TX, write text to RX. No pairing required (the link
is intentionally open — demo hardware).

### qhackGPS companion app

The [qhackGPS](https://github.com/iujab/qhackGPS) Android navigator is the
primary client: it auto-discovers this peripheral, subscribes to TX, and on
connect writes `1200` to widen the presence threshold to walking range. While
an object is inside the threshold the app shows the live distance, steers the
user around it (left/right), and mirrors that state back here — you'll see
`AVOID LEFT` / `AVOID RIGHT` / `CLEAR` as the "message from phone" on the
dashboard, and those same messages now physically spin/stop the steering
motor on the Modulino Motors. On subscribe, the bridge now pushes the current state immediately
(no need to wait for the next change) — restart the service after pulling
that change: `systemctl --user restart qhack-ble-bridge`.

**Service management** (installed, enabled, and linger is on, so it starts at
boot):

```bash
systemctl --user status  qhack-ble-bridge   # logs: journalctl --user -u qhack-ble-bridge -f
systemctl --user restart qhack-ble-bridge   # after editing ble_bridge.py
# install from scratch:
cp ble-bridge/qhack-ble-bridge.service ~/.config/systemd/user/ && \
  systemctl --user daemon-reload && systemctl --user enable --now qhack-ble-bridge
```

The web page's raw-data table shows the Bluetooth state (advertising /
connected + device name), fed by a 5 s heartbeat from the bridge
(`POST /api/bt`); if the bridge stops, the app shows Bluetooth as off within
12 s.

## Tuning

Presence threshold and staleness live at the top of
`distance-watch/python/main.py` (`PRESENCE_MAX_MM`, `STALE_AFTER_S`) — and the
threshold can be changed live from the phone (write a number) or via
`curl -X POST localhost:7000/api/threshold -H 'Content-Type: application/json' -d '{"mm":250}'`.

Dashboard control endpoints (what the buttons call):

```bash
curl -X POST localhost:7000/api/motor -H 'Content-Type: application/json' -d '{"dir":-1}'  # -1 left, 0 stop, 1 right
curl -X POST localhost:7000/api/vibro -H 'Content-Type: application/json' -d '{"ms":600}'  # one-shot buzz, 50–3000 ms
```

Actuator behavior lives at the top of `distance-watch/sketch/sketch.ino`:
`MOTOR_SPEED_PCT` (steering spin speed, default 100 % = full VM),
`VIBRO_PULSE_MS` / `VIBRO_PERIOD_MS` (haptic rhythm), and
`COMMAND_TIMEOUT_MS` (failsafe stop when the Linux side goes quiet, default
2 s). The dashboard's applied-voltage graph scales by `MOTOR_VM_V` in
`distance-watch/python/main.py` (5 V — the supply wired into the module's VM
terminals; the module cannot measure VM itself).

Quick motor test without a phone:
`curl -X POST localhost:7000/api/phone -H 'Content-Type: application/json' -d '{"text":"left"}'`
(then `right` / `stop`).
