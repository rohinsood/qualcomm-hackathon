import threading
import time

from arduino.app_utils import App, Bridge, Logger
from arduino.app_bricks.web_ui import WebUI

logger = Logger("distance-watch")

# An object closer than this counts as "in front" and lights the red lamp.
PRESENCE_MAX_MM = 300.0
# No valid reading for this long -> report "nothing in range".
STALE_AFTER_S = 1.0
# Cap reading-driven websocket pushes (the sensor streams at up to ~50 Hz).
EMIT_MIN_INTERVAL_S = 0.05

ui = WebUI()

_lock = threading.Lock()
_state = {
    "mm": None,          # latest valid reading from the sensor, millimeters
    "present": False,    # True while an object is within PRESENCE_MAX_MM
    "sensor_ok": False,  # True once the sketch reports the Modulino is detected
    "count": 0,          # valid readings received since app start
    "hz": 0.0,           # measured reading rate
    "threshold_mm": PRESENCE_MAX_MM,
}
_last_reading_t = 0.0
_last_emit_t = 0.0
_win_start_t = time.monotonic()
_win_count = 0


def _snapshot() -> dict:
    with _lock:
        snap = dict(_state)
    snap["age_ms"] = int((time.monotonic() - _last_reading_t) * 1000) if _last_reading_t else None
    return snap


def _emit(force: bool = False):
    global _last_emit_t
    now = time.monotonic()
    if not force and (now - _last_emit_t) < EMIT_MIN_INTERVAL_S:
        return
    _last_emit_t = now
    try:
        ui.send_message("distance", _snapshot())
    except Exception:
        logger.debug("Failed to emit 'distance' websocket message")


def on_distance_reading(mm: float):
    """Bridge handler: one valid measurement (mm) pushed by the sketch."""
    global _last_reading_t, _win_start_t, _win_count
    now = time.monotonic()
    _last_reading_t = now
    with _lock:
        _state["mm"] = float(mm)
        _state["present"] = float(mm) <= PRESENCE_MAX_MM
        _state["sensor_ok"] = True
        _state["count"] += 1
        _win_count += 1
        if now - _win_start_t >= 1.0:
            _state["hz"] = round(_win_count / (now - _win_start_t), 1)
            _win_start_t = now
            _win_count = 0
    _emit()


def on_sensor_status(ok):
    """Bridge handler: 1 Hz heartbeat from the sketch with the sensor init state."""
    ok = bool(ok)
    with _lock:
        changed = _state["sensor_ok"] != ok
        _state["sensor_ok"] = ok
    if changed:
        logger.info(f"Modulino Distance {'detected' if ok else 'not detected'}")
        _emit(force=True)


def watchdog():
    """Repeating loop: expire stale readings and keep clients refreshed."""
    global _win_start_t, _win_count
    time.sleep(0.25)
    now = time.monotonic()
    if _last_reading_t and (now - _last_reading_t) > STALE_AFTER_S:
        with _lock:
            _state["mm"] = None
            _state["present"] = False
            _state["hz"] = 0.0
            _win_start_t = now
            _win_count = 0
    _emit(force=True)


for _name, _handler in (("distance_reading", on_distance_reading),
                        ("sensor_status", on_sensor_status)):
    try:
        Bridge.provide(_name, _handler)
    except RuntimeError:
        logger.debug(f"'{_name}' already registered")

ui.on_connect(lambda sid: ui.send_message("distance", _snapshot(), sid))
ui.expose_api("GET", "/api/state", _snapshot)

logger.info(f"Distance Watch starting; presence threshold = {PRESENCE_MAX_MM:.0f} mm")
App.run(user_loop=watchdog)
