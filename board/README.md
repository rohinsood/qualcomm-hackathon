# Cane board (Arduino UNO Q)

One app runs the whole cane: **`qcane-wheel/`** — phone-steered wheel,
obstacle haptics, LED-matrix status, and a live web dashboard. (It absorbed
the former `distance-watch` app; that directory is gone.)

- **Wheel**: spins left/right on command from the phone (QCane GATT, speeds
  1–5) or the dashboard buttons (always full scale). The 13×8 LED matrix
  mirrors the motion.
- **Vibro buzz** (250 ms pulse every 500 ms) while an object is closer than
  the presence threshold (default 300 mm) — haptic obstacle alert.
- **QCane Link dashboard** (port 7000): per-Modulino cards — distance
  readings (mm/cm, rate, age), motor telemetry with **live graphs** of sensed
  current (mA) and applied voltage (duty × VM, VM = 5 V), vibro state,
  Bluetooth links, rolling event log — plus manual spin/stop/buzz buttons
  and a clear-graphs button.
- **Failsafe**: the Linux side re-sends the desired state 4×/s; if that
  stream stops for 2 s, the sketch stops the wheel and vibro on its own.

## Hardware

Modulinos daisy-chained on the Qwiic connector (`Wire1`), in wiring order:

```
UNO Q ──▶ Modulino Motors ──▶ Modulino Vibro ──▶ Modulino Distance
```

Chain order does not matter on I²C — each node answers on its own address.

**Motor wiring (important):** the motor sits across screw terminals
**`1A` + `2A`** — that is one half-bridge from **channel A** and one from
**channel B**, not a single channel's own pair, so the sketch drives the two
channels in opposite phase (`driveMotor()`); both channels show current while
it spins. `motor_selftest` re-measures every drive option if the wiring is
ever in doubt. Motor power goes into the `VM` + `GND` screw terminals (5 V
here); the yellow VM LED confirms power.

## How it works

```
Modulino Distance ──I²C──▶ MCU sketch ──"distance_reading"──▶ Python ──WebSocket──▶ dashboard
Modulino Motors + Vibro ◀──I²C── MCU sketch ◀──"set_wheel"/"set_vibro" heartbeat── Python
                                                                 ▲
                                          unix socket .run/qcane-bt.sock
                                                                 ▼
phone (QCane GATT / SPP) ◀──BlueZ──▶ host/qcane_btd.py (host daemon)
phone or nRF (Nordic UART) ◀──BlueZ──▶ ../ble-bridge/ble_bridge.py (side channel, HTTP :7000)
```

- `qcane-wheel/sketch/sketch.ino` — owns all three Modulinos: streams each
  valid ToF measurement, applies wheel commands (opposite-phase drive +
  matrix animation + `wheel_applied` acks), pulses the vibro, sends 2 Hz
  `motor_telemetry` (current sense + signed duty) and 1 Hz status
  heartbeats, and runs the self-test on request.
- `qcane-wheel/python/main.py` — the policy layer: presence (`mm <=
  threshold`) drives the vibro; the last wheel command (phone or dashboard,
  last writer wins) drives the motor; both re-sent 4×/s as the failsafe
  heartbeat. Serves the dashboard, REST API, event log, and graph history,
  and bridges the QCane BT daemon's unix socket.
- `qcane-wheel/host/qcane_btd.py` — host-side Bluetooth daemon the phone
  talks to (QCane GATT + SPP; see `qcane-wheel/README.md` for the full
  contract: `left` / `right 4` / `{"action":"left","speed":5}` …).
- `ble-bridge/` — the Nordic UART "Distance Watch" side channel (host
  systemd services `qhack-ble-bridge` + `qhack-bt-agent`): kept for
  nRF-style debugging and legacy qhackGPS clients; its texts (`AVOID LEFT`
  etc.) also steer the wheel, and it feeds the dashboard's NUS status row.

## Run

```bash
arduino-app-cli app start ~/dev/qualcomm-hackathon/board/qcane-wheel
arduino-app-cli app logs  ~/dev/qualcomm-hackathon/board/qcane-wheel --follow
```

Then open `http://<board-ip>:7000`. The BT services (`qcane-btd`,
`qhack-ble-bridge`, `qhack-bt-agent`) run as systemd *user* services on the
host, independent of the app — enabled + linger, so they survive reboots.
Note: `qcane-btd`'s unit must point at the same app folder the app runs
from (the unix socket lives in the app's `.run/`).

## API quick reference (dashboard buttons use these)

```bash
curl -s localhost:7000/api/state                                                            # everything
curl -X POST localhost:7000/api/motor  -H 'Content-Type: application/json' -d '{"dir":-1}'  # -1 left, 0 stop, 1 right (full speed)
curl -X POST localhost:7000/api/vibro  -H 'Content-Type: application/json' -d '{"ms":600}'  # one-shot buzz, 50–3000 ms
curl -X POST localhost:7000/api/threshold -H 'Content-Type: application/json' -d '{"mm":250}'
curl -X POST localhost:7000/api/graphs/clear                                                # wipe the motor graphs
```

## Tuning

- `qcane-wheel/sketch/sketch.ino`: `SPEED_PERCENT` (the 1–5 speed table,
  {30,45,60,80,100} %), `VIBRO_PULSE_MS` / `VIBRO_PERIOD_MS` (haptic
  rhythm), `COMMAND_TIMEOUT_MS` (failsafe, 2 s).
- `qcane-wheel/python/main.py`: `PRESENCE_MAX_MM` (threshold, also settable
  live via `/api/threshold` or a NUS number write), `MOTOR_VM_V` (5 V —
  scales the dashboard voltage graph), `DASHBOARD_SPEED` (buttons, 5 =
  full).

Known gap: `qcane_btd` does not tell the app when the phone drops off the
GATT, so a wheel command survives a phone disconnect until `stop` arrives or
the app dies (failsafe). The NUS side channel *does* zero nothing here —
send `stop` (either transport) or use the dashboard.
