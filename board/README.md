# qhackcane

Arduino UNO Q project: **Distance Watch** — a live web dashboard **and BLE
phone link** for the **Modulino Distance** time-of-flight sensor
(VL53L4CD/VL53L4ED on the Qwiic connector).

- Big **red lamp** on the page while an object is closer than the presence
  threshold (default 300 mm).
- Live **distance readout** (mm / cm) plus the raw data the sensor returns:
  last reading, reading count, update rate, reading age, sensor status.
- **Two-way Bluetooth**: a phone receives live distance + obstruction
  notifications and can write back (set the threshold, or send a message that
  appears on the web page).

## How it works

```
Modulino Distance ──I²C (Wire1)──▶ MCU sketch ──Bridge.notify──▶ Python ──WebSocket──▶ browser
                                                                   ▲
                                                     HTTP :7000 (poll + POST)
                                                                   ▼
phone (BLE central) ◀──Nordic UART Service──▶ ble-bridge/ble_bridge.py (host daemon)
```

- `distance-watch/sketch/sketch.ino` — reads the sensor (~50 Hz) and streams
  each valid measurement with `Bridge.notify("distance_reading", mm)`, plus a
  1 Hz `sensor_status` heartbeat so the UI can tell *no object* apart from
  *no sensor*. The sensor reports nothing at all when no target is in range.
- `distance-watch/python/main.py` — receives readings, tracks presence
  (`mm <= 300`), expires stale readings after 1 s, and pushes state snapshots
  to browsers over the `web_ui` brick (WebSocket event `distance`, plus REST
  `GET /api/state`).
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
| any other text | shown on the web page as "Message from phone" |

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
dashboard. On subscribe, the bridge now pushes the current state immediately
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
