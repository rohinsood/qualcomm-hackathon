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
    "present": False,    # True while an object is within the presence threshold
    "sensor_ok": False,  # True once the sketch reports the Modulino is detected
    "count": 0,          # valid readings received since app start
    "hz": 0.0,           # measured reading rate
    "threshold_mm": PRESENCE_MAX_MM,
    "phone_msg": None,   # last freeform text received from the phone over BLE
    "motor": 0,          # commanded steering motor: -1 left, 0 stop, +1 right
    "motors_ok": False,  # True once the sketch reports the Modulino Motors is detected
    "vibro_ok": False,   # True once the sketch reports the Modulino Vibro is detected
    "bt": {"advertising": False, "connected": False, "device": None},
}
_last_reading_t = 0.0
_bt_last_t = 0.0
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


def _push_actuators():
    """Send the desired actuator state to the sketch: steering motor direction
    and vibro (= presence). Also re-sent from the watchdog as a heartbeat — the
    sketch stops every output if these stop arriving (failsafe), and a rebooted
    MCU re-converges on the next beat."""
    with _lock:
        motor = int(_state["motor"])
        vibro = 1 if _state["present"] else 0
    try:
        Bridge.notify("set_motor", motor)
        Bridge.notify("set_vibro", vibro)
    except Exception:
        logger.debug("Failed to push actuator state to the sketch")


def _steer_command(text: str):
    """Map phone text to a motor command: -1 left, +1 right, 0 stop, None no-op.
    Substring match, so qhackGPS's "AVOID LEFT"/"AVOID RIGHT"/"CLEAR" work."""
    t = text.upper()
    if "LEFT" in t:
        return -1
    if "RIGHT" in t:
        return 1
    if "CLEAR" in t or "STOP" in t or "CENTER" in t:
        return 0
    return None


def _set_motor(direction: int, reason: str):
    with _lock:
        changed = _state["motor"] != direction
        _state["motor"] = direction
    if changed:
        word = "left" if direction < 0 else ("right" if direction > 0 else "stop")
        logger.info(f"Steering motor {word} ({reason})")
        _push_actuators()
        _emit(force=True)


def on_distance_reading(mm: float):
    """Bridge handler: one valid measurement (mm) pushed by the sketch."""
    global _last_reading_t, _win_start_t, _win_count
    now = time.monotonic()
    _last_reading_t = now
    with _lock:
        was_present = _state["present"]
        _state["mm"] = float(mm)
        _state["present"] = float(mm) <= _state["threshold_mm"]
        _state["sensor_ok"] = True
        _state["count"] += 1
        present = _state["present"]
        _win_count += 1
        if now - _win_start_t >= 1.0:
            _state["hz"] = round(_win_count / (now - _win_start_t), 1)
            _win_start_t = now
            _win_count = 0
    if present != was_present:
        # React to obstacles immediately; steady state rides the heartbeat.
        _push_actuators()
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


def on_actuator_status(mask):
    """Bridge handler: 1 Hz heartbeat with the actuator init states
    (bit 0 = Modulino Motors, bit 1 = Modulino Vibro)."""
    mask = int(mask)
    motors_ok = bool(mask & 1)
    vibro_ok = bool(mask & 2)
    with _lock:
        changed = (_state["motors_ok"] != motors_ok
                   or _state["vibro_ok"] != vibro_ok)
        _state["motors_ok"] = motors_ok
        _state["vibro_ok"] = vibro_ok
    if changed:
        logger.info(f"Modulino Motors {'detected' if motors_ok else 'not detected'}, "
                    f"Modulino Vibro {'detected' if vibro_ok else 'not detected'}")
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
    # The BLE bridge heartbeats every 5 s; if it stops, show Bluetooth as off
    # and stop steering (the phone is the only source of motor commands).
    if _bt_last_t and (now - _bt_last_t) > 12.0:
        with _lock:
            _state["bt"] = {"advertising": False, "connected": False, "device": None}
        _set_motor(0, "bluetooth bridge lost")
    _push_actuators()
    _emit(force=True)


def set_threshold(payload: dict):
    """POST /api/threshold {"mm": <number>} — from the BLE bridge (phone write)."""
    try:
        mm = float(payload.get("mm"))
    except (TypeError, ValueError):
        return {"error": 'expected {"mm": <number>}'}
    mm = max(0.0, min(1500.0, mm))
    with _lock:
        _state["threshold_mm"] = mm
        if _state["mm"] is not None:
            _state["present"] = _state["mm"] <= mm
    logger.info(f"Presence threshold set to {mm:.0f} mm")
    _emit(force=True)
    return {"threshold_mm": mm}


def set_phone_msg(payload: dict):
    """POST /api/phone {"text": "..."} — freeform message from the phone.
    Text containing a steering keyword (left/right/clear/stop) also drives
    the Modulino Motors; anything else is display-only."""
    text = str(payload.get("text", "")).strip()[:200]
    with _lock:
        _state["phone_msg"] = text or None
    logger.info(f"Message from phone: {text!r}")
    if text:
        command = _steer_command(text)
        if command is not None:
            _set_motor(command, f"phone said {text!r}")
    _emit(force=True)
    return {"ok": True}


def set_bt(payload: dict):
    """POST /api/bt — advertising/connection status heartbeat from the BLE bridge."""
    global _bt_last_t
    _bt_last_t = time.monotonic()
    with _lock:
        bt = _state["bt"]
        was_connected = bt["connected"]
        for key in ("advertising", "connected"):
            if key in payload:
                bt[key] = bool(payload[key])
        if "device" in payload:
            bt["device"] = payload["device"] or None
        lost_phone = was_connected and not bt["connected"]
    if lost_phone:
        _set_motor(0, "phone disconnected")
    _emit(force=True)
    return {"ok": True}


for _name, _handler in (("distance_reading", on_distance_reading),
                        ("sensor_status", on_sensor_status),
                        ("actuator_status", on_actuator_status)):
    try:
        Bridge.provide(_name, _handler)
    except RuntimeError:
        logger.debug(f"'{_name}' already registered")

ui.on_connect(lambda sid: ui.send_message("distance", _snapshot(), sid))
ui.expose_api("GET", "/api/state", _snapshot)
ui.expose_api("POST", "/api/threshold", set_threshold)
ui.expose_api("POST", "/api/phone", set_phone_msg)
ui.expose_api("POST", "/api/bt", set_bt)

logger.info(f"Distance Watch starting; presence threshold = {PRESENCE_MAX_MM:.0f} mm; "
            "vibro on presence, motor on phone left/right")
App.run(user_loop=watchdog)
