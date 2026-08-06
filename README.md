# qhackcane

Arduino UNO Q project: **Distance Watch** — a live web dashboard for the
**Modulino Distance** time-of-flight sensor (VL53L4CD/VL53L4ED on the Qwiic
connector).

- Big **red lamp** on the page while an object is closer than the presence
  threshold (300 mm).
- Live **distance readout** (mm / cm) plus the raw data the sensor returns:
  last reading, reading count, update rate, reading age, sensor status.

## How it works

```
Modulino Distance ──I²C (Wire1)──▶ MCU sketch ──Bridge.notify──▶ Python ──WebSocket──▶ browser
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

## Tuning

Presence threshold and staleness live at the top of
`distance-watch/python/main.py` (`PRESENCE_MAX_MM`, `STALE_AFTER_S`).
