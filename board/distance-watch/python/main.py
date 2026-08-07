"""qcane-wheel — Linux (MPU) side (merged: wheel + distance + vibro + dashboard).

Bluetooth itself lives in host/qcane_btd.py, which runs on the host rather than
in this container: the app container is on a Docker bridge network with no
D-Bus and no AF_BLUETOOTH, so it cannot reach BlueZ. The daemon owns the radio
and hands phone commands over a Unix socket in the app folder (bind-mounted at
/app); this module folds them into the same policy pipeline as the dashboard.

Policy lives here: presence (mm <= threshold) drives the vibro, the last
wheel command (phone or dashboard, last writer wins) drives the motor, and
both are re-sent to the sketch 4x/s as the failsafe heartbeat. The web
dashboard (port 7000) shows per-Modulino informatics and manual controls.
"""

import json
import os
import re
import select
import socket
import threading
import time
from collections import deque

from arduino.app_utils import App, Bridge, Logger
from arduino.app_bricks.web_ui import WebUI

logger = Logger("qcane-wheel")

# An object closer than this counts as "in front" and lights the red lamp.
PRESENCE_MAX_MM = 300.0
# Hysteresis + debounce so an object hovering at the boundary cannot chatter
# the vibro or the avoidance state machine: presence enters at the threshold
# but releases only beyond threshold + margin, and either flip needs this many
# consecutive qualifying readings (~40 ms at the sensor's ~50 Hz).
PRESENCE_EXIT_MARGIN_MM = 100.0
PRESENCE_DEBOUNCE_N = 2
# No valid reading for this long -> report "nothing in range".
STALE_AFTER_S = 1.0
# Cap reading-driven websocket pushes (the sensor streams at up to ~50 Hz).
EMIT_MIN_INTERVAL_S = 0.05
# Dashboard event log depth.
EVENT_LOG_LEN = 30
# Motor telemetry history depth for the dashboard graphs (2 Hz -> 2 minutes).
MOTOR_HISTORY_LEN = 240
# Motor supply voltage wired into the Modulino Motors VM terminals. The module
# cannot measure VM, so the dashboard computes applied volts as duty x VM.
MOTOR_VM_V = 5.0

# Parking-sensor vibro rhythm: the pulse period tightens linearly as the
# obstacle closes from the presence threshold down to the stop tier, where
# the buzz goes continuous ("stop now"). Period 0 is the continuous sentinel.
VIBRO_PERIOD_FAR_MS = 700   # rhythm right at the threshold edge
VIBRO_PERIOD_NEAR_MS = 250  # rhythm just above the stop tier
VIBRO_STOP_TIER_MM = 250.0  # at/below this distance: continuous buzz

# Bluetooth daemon (QCane GATT) unix socket, served by host/qcane_btd.py.
SOCKET_PATH = os.environ.get("QCANE_BT_SOCKET", "/app/.run/qcane-bt.sock")
SOCKET_RETRY_S = 2.0
MAX_BUFFER = 64 * 1024

# Sketch-side convention for set_wheel(dir, speed).
DIRECTIONS = {"left": -1, "stop": 0, "right": 1}
ACTION_NAMES = {value: name for name, value in DIRECTIONS.items()}
# Every spin runs at full scale (100% duty) — no graded speeds; phone/daemon
# speed hints are ignored, and the sketch also pins duty to 100% no matter
# what arrives on the wire (no ramp: commands step straight to full).
WHEEL_SPEED = 5

# The board NEVER steers itself. Obstacle presence is streamed to the phone
# ({"mm","p"} over the NUS bridge) and the phone alone decides — it answers
# with AVOID/TURN/CLEAR texts that drive the wheel. Without a phone the board
# only buzzes the vibro.

ui = WebUI()

_lock = threading.Lock()
_state = {
    "mm": None,          # latest valid reading from the sensor, millimeters
    "present": False,    # True while an object is within the presence threshold
    "sensor_ok": False,  # True once the sketch reports the Modulino is detected
    "count": 0,          # valid readings received since app start
    "hz": 0.0,           # measured reading rate
    "threshold_mm": PRESENCE_MAX_MM,
    "phone_msg": None,   # last freeform text received over the NUS side channel
    "motor": 0,          # commanded wheel direction: -1 left, 0 stop, +1 right
    "wheel_speed": WHEEL_SPEED,  # always full scale
    "motors_ok": False,  # True once the sketch reports the Modulino Motors is detected
    "vibro_ok": False,   # True once the sketch reports the Modulino Vibro is detected
    "motor_ma_a": None,  # sensed current on channel A, mA
    "motor_ma_b": None,  # sensed current on channel B, mA
    "motor_applied": 0,  # direction the sketch is actually applying right now
    "speed_applied": 0,  # speed the sketch is actually applying right now
    "motor_duty_pct": 0, # signed duty the sketch applies = voltage as % of VM
    "motor_t": None,     # epoch seconds of the latest motor telemetry sample
    "vm_v": MOTOR_VM_V,  # configured VM supply voltage (volts)
    "graph_epoch": 0,    # bumped when the graphs are cleared; clients drop buffers
    "motor_busy": False, # driver busy flag from module telemetry
    "vibro_active": False,  # True while the sketch is running the pulse rhythm
    "cane_daemon": False,   # True while the QCane BT daemon socket is connected
    "bt": {"advertising": False, "connected": False, "device": None},
    "say": {"n": 0, "text": None},  # TTS line for the phone; n bumps per line
    "events": [],        # rolling event log, newest last
}
_say_n = 0
_events = deque(maxlen=EVENT_LOG_LEN)
_motor_history = deque(maxlen=MOTOR_HISTORY_LEN)
_presence_streak = 0  # +n consecutive "in" readings, -n "out"; 0 = nothing pending
_last_reading_t = 0.0
_bt_last_t = 0.0
_last_emit_t = 0.0
_win_start_t = time.monotonic()
_win_count = 0

_sock = None
_sock_buffer = b""
_sock_last_try = 0.0
_sock_last_warn = 0.0


def _snapshot(history: bool = False) -> dict:
    """State snapshot. The motor graph history rides along only on demand
    (initial websocket push + GET /api/state) — live updates append
    client-side from the regular state stream."""
    with _lock:
        snap = dict(_state)
        snap["events"] = list(_events)
        if history:
            snap["motor_history"] = list(_motor_history)
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


def _log_event(msg: str):
    """Append to the dashboard event log (and the app log), then push."""
    with _lock:
        _events.append({"t": time.strftime("%H:%M:%S"), "msg": msg})
    logger.info(msg)
    _emit(force=True)


def _say_to_phone(text: str):
    """Queue one line for the phone to speak. The NUS bridge polls /api/state,
    forwards a changed line as {"say": ...}, and qhackGPS reads it aloud."""
    global _say_n
    with _lock:
        _say_n += 1
        _state["say"] = {"n": _say_n, "text": text}


# ---- Bluetooth daemon (QCane GATT) socket -----------------------------------

def _sock_disconnect():
    global _sock, _sock_buffer
    sock = _sock
    if sock is not None:
        try:
            sock.close()
        except OSError:
            pass
    _sock = None
    _sock_buffer = b""
    with _lock:
        changed = _state["cane_daemon"]
        _state["cane_daemon"] = False
    if changed:
        _log_event("QCane Bluetooth daemon disconnected")


def _sock_send(payload: dict):
    """Report back to the daemon so it can push state to the phone.

    Also called from the Bridge thread, so take a local reference: the main
    loop may drop the connection concurrently."""
    sock = _sock
    if sock is None:
        return
    try:
        sock.sendall((json.dumps(payload) + "\n").encode("utf-8"))
    except OSError as err:
        logger.warning(f"could not send {payload.get('type')} upstream: {err}")
        _sock_disconnect()


def _sock_connect() -> bool:
    global _sock, _sock_last_warn
    try:
        sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        sock.connect(SOCKET_PATH)
    except OSError as err:
        now = time.monotonic()
        if now - _sock_last_warn > 30:
            _sock_last_warn = now
            logger.warning(
                f"Bluetooth daemon not reachable on {SOCKET_PATH} ({err}). "
                "Start it on the host: python3 host/qcane_btd.py")
        return False

    _sock = sock
    _sock_send({"type": "hello", "client": "arduino-app"})
    with _lock:
        _state["cane_daemon"] = True
    _log_event("QCane Bluetooth daemon connected")
    return True


def _sock_handle(line: bytes):
    try:
        message = json.loads(line)
    except ValueError:
        logger.warning(f"ignoring malformed message: {line[:120]!r}")
        return

    kind = message.get("type")
    if kind == "selftest":
        try:
            percent = int(message.get("percent", 40))
        except (TypeError, ValueError):
            percent = 40
        percent = max(10, min(100, percent))
        _log_event(f"Motor self-test at {percent}% (phone)")
        try:
            Bridge.call("motor_selftest", percent)
        except Exception as err:
            logger.error(f"motor_selftest failed: {err}")
            _sock_send({"type": "error", "action": "selftest", "detail": str(err)})
        return

    if kind != "command":
        return

    action = str(message.get("action", "")).lower()
    if action not in DIRECTIONS:
        logger.warning(f"ignoring unknown action {action!r}")
        return
    # Speed hints from the daemon are ignored: every spin is full scale.
    _set_wheel(DIRECTIONS[action], "phone")


def _sock_pump(timeout_s: float):
    """One poll of the daemon socket; sleeps `timeout_s` when disconnected so
    the watchdog cadence stays ~4 Hz either way."""
    global _sock_buffer, _sock_last_try

    if _sock is None:
        now = time.monotonic()
        if now - _sock_last_try >= SOCKET_RETRY_S:
            _sock_last_try = now
            _sock_connect()
        if _sock is None:
            time.sleep(timeout_s)
            return

    try:
        readable, _, _ = select.select([_sock], [], [], timeout_s)
    except OSError:
        _sock_disconnect()
        return
    if not readable:
        return

    try:
        chunk = _sock.recv(4096)
    except OSError as err:
        logger.warning(f"daemon socket read failed: {err}")
        _sock_disconnect()
        return

    if not chunk:
        logger.warning("Bluetooth daemon closed the connection, will retry")
        _sock_disconnect()
        return

    _sock_buffer += chunk
    while b"\n" in _sock_buffer:
        line, _sock_buffer = _sock_buffer.split(b"\n", 1)
        if line.strip():
            _sock_handle(line)
    if len(_sock_buffer) > MAX_BUFFER:
        logger.warning(f"dropping {len(_sock_buffer)} bytes of unterminated input")
        _sock_buffer = b""


# ---- actuator policy ---------------------------------------------------------

def _push_actuators():
    """Send the desired actuator state to the sketch: wheel dir/speed and
    vibro (= presence) with a distance-graded pulse period. Also re-sent from
    the watchdog as a heartbeat — the sketch stops every output if these stop
    arriving (failsafe), and a rebooted MCU re-converges on the next beat."""
    with _lock:
        motor = int(_state["motor"])
        speed = int(_state["wheel_speed"])
        vibro = 1 if _state["present"] else 0
        mm = _state["mm"]
        threshold = _state["threshold_mm"]
    # Parking-sensor grading: 700 ms rhythm at the threshold edge tightening
    # to 250 ms, then continuous (period 0) inside the stop tier.
    period = VIBRO_PERIOD_FAR_MS
    if mm is not None:
        if mm <= VIBRO_STOP_TIER_MM:
            period = 0
        else:
            urgency = (threshold - mm) / max(1.0, threshold - VIBRO_STOP_TIER_MM)
            urgency = max(0.0, min(1.0, urgency))
            period = int(VIBRO_PERIOD_FAR_MS
                         - (VIBRO_PERIOD_FAR_MS - VIBRO_PERIOD_NEAR_MS) * urgency)
    try:
        Bridge.notify("set_wheel", motor, speed)
        Bridge.notify("set_vibro", vibro, period)
    except Exception:
        logger.debug("Failed to push actuator state to the sketch")


_TURN_RE = re.compile(r"TURN\s+(LEFT|RIGHT)\s*(\d+)?")


def _steer_from_text(text: str):
    """Map phone text to (dir, kind), or None for display-only text.

    "TURN LEFT 90" / "TURN RIGHT 35"  -> ("turn")  route turn, full speed
    "AVOID LEFT" / anything with LEFT -> ("dodge") obstacle dodge, full speed
    "CLEAR" / "STOP" / "STRAIGHT"     -> ("stop")  stop the wheel
    """
    t = text.upper()
    m = _TURN_RE.search(t)
    if m:
        deg = int(m.group(2)) if m.group(2) else 45
        direction = -1 if m.group(1) == "LEFT" else 1
        return direction, "turn"
    if "LEFT" in t:
        return -1, "dodge"
    if "RIGHT" in t:
        return 1, "dodge"
    if any(word in t for word in ("CLEAR", "STOP", "CENTER", "STRAIGHT")):
        return 0, "stop"
    return None


def _set_wheel(direction: int, reason: str):
    with _lock:
        changed = _state["motor"] != direction
        _state["motor"] = direction
        _state["wheel_speed"] = WHEEL_SPEED
    if changed:
        _push_actuators()
        word = ACTION_NAMES.get(direction, "stop")
        _log_event(f"Wheel -> {word} ({reason})")


# ---- Bridge handlers (sketch -> app) ----------------------------------------

def on_distance_reading(mm: float):
    """Bridge handler: one valid measurement (mm) pushed by the sketch."""
    global _last_reading_t, _win_start_t, _win_count, _presence_streak
    now = time.monotonic()
    _last_reading_t = now
    with _lock:
        was_present = _state["present"]
        value = float(mm)
        _state["mm"] = value
        # Hysteresis + debounce: "in" at the threshold, "out" only beyond
        # threshold + margin; the dead band between them cancels any pending
        # flip. A flip lands after PRESENCE_DEBOUNCE_N consecutive qualifying
        # readings, so boundary noise cannot chatter presence. (The watchdog's
        # stale-expiry still clears presence unconditionally; the streak logic
        # then demands a fresh debounce before re-entering.)
        if value <= _state["threshold_mm"]:
            if was_present:
                _presence_streak = 0
            else:
                _presence_streak = _presence_streak + 1 if _presence_streak > 0 else 1
                if _presence_streak >= PRESENCE_DEBOUNCE_N:
                    _state["present"] = True
                    _presence_streak = 0
        elif value > _state["threshold_mm"] + PRESENCE_EXIT_MARGIN_MM:
            if not was_present:
                _presence_streak = 0
            else:
                _presence_streak = _presence_streak - 1 if _presence_streak < 0 else -1
                if _presence_streak <= -PRESENCE_DEBOUNCE_N:
                    _state["present"] = False
                    _presence_streak = 0
        else:
            _presence_streak = 0
        _state["sensor_ok"] = True
        _state["count"] += 1
        present = _state["present"]
        _win_count += 1
        if now - _win_start_t >= 1.0:
            _state["hz"] = round(_win_count / (now - _win_start_t), 1)
            _win_start_t = now
            _win_count = 0
    if present != was_present:
        # React immediately: vibro + the {"mm","p"} stream the phone decides
        # from (the bridge pushes presence flips to the phone at once).
        _push_actuators()
        if present:
            _log_event(f"Obstacle at {mm:.0f} mm — vibro on")
        else:
            _log_event("Path clear — vibro off")
    _emit()


def on_sensor_status(ok):
    """Bridge handler: 1 Hz heartbeat from the sketch with the sensor init state."""
    ok = bool(ok)
    with _lock:
        changed = _state["sensor_ok"] != ok
        _state["sensor_ok"] = ok
    if changed:
        _log_event(f"Modulino Distance {'detected' if ok else 'not detected'}")


def on_actuator_status(mask):
    """Bridge handler: 1 Hz heartbeat with the actuator init states
    (bit 0 = Modulino Motors, bit 1 = Modulino Vibro)."""
    mask = int(mask)
    motors_ok = bool(mask & 1)
    vibro_ok = bool(mask & 2)
    with _lock:
        motors_changed = _state["motors_ok"] != motors_ok
        vibro_changed = _state["vibro_ok"] != vibro_ok
        _state["motors_ok"] = motors_ok
        _state["vibro_ok"] = vibro_ok
        if motors_changed and not motors_ok:
            _state["motor_ma_a"] = None
            _state["motor_ma_b"] = None
            _state["motor_busy"] = False
    if motors_changed:
        _log_event(f"Modulino Motors {'detected' if motors_ok else 'not detected'}")
    if vibro_changed:
        _log_event(f"Modulino Vibro {'detected' if vibro_ok else 'not detected'}")


def on_motor_telemetry(ma_a, ma_b, applied, duty_pct, vibro_active, busy):
    """Bridge handler: 2 Hz motor module telemetry pushed by the sketch."""
    now_t = round(time.time(), 2)
    ma_a = round(float(ma_a), 1)
    with _lock:
        _state["motor_ma_a"] = ma_a
        _state["motor_ma_b"] = round(float(ma_b), 1)
        _state["motor_applied"] = int(applied)
        _state["motor_duty_pct"] = int(duty_pct)
        _state["motor_t"] = now_t
        _state["vibro_active"] = bool(vibro_active)
        _state["motor_busy"] = bool(busy)
        _motor_history.append({"t": now_t, "ma": ma_a, "duty": int(duty_pct)})
    _emit()


def on_wheel_applied(direction, speed, motor_ready=True):
    """Called by the sketch once it has actually applied a command. Reporting
    from the MCU rather than from the request path means the state the phone
    sees is what the hardware is doing, not merely what was requested."""
    direction = int(direction)
    speed = int(speed)
    action = ACTION_NAMES.get(direction, "stop")
    with _lock:
        changed = (_state["motor_applied"] != direction
                   or _state["speed_applied"] != speed)
        _state["motor_applied"] = direction
        _state["speed_applied"] = speed
    if not motor_ready:
        logger.warning(
            f"MCU applied {action} (speed {speed}) but no Modulino Motors "
            "answered on the Qwiic bus — check the cable and motor power")
    elif changed:
        logger.info(f"MCU applied {action} (speed {speed})")
        _emit(force=True)
    _sock_send({"type": "state", "action": action, "speed": speed,
                "motor": bool(motor_ready)})


def on_selftest_telemetry(stage, milliamps_a, milliamps_b, mode="?", busy=False):
    """Current-sense readings streamed by the sketch during a self-test."""
    _log_event(f"Self-test {stage}: A={float(milliamps_a):.1f} mA "
               f"B={float(milliamps_b):.1f} mA")
    _sock_send({"type": "telemetry", "stage": str(stage),
                "mA_a": round(float(milliamps_a), 1),
                "mA_b": round(float(milliamps_b), 1),
                "mode": str(mode), "busy": bool(busy)})


# ---- watchdog / main loop ----------------------------------------------------

def watchdog():
    """Repeating loop: pump the BT daemon socket, expire stale readings, keep
    the failsafe heartbeat flowing, and refresh clients."""
    global _win_start_t, _win_count
    _sock_pump(0.25)
    now = time.monotonic()
    went_clear = False
    if _last_reading_t and (now - _last_reading_t) > STALE_AFTER_S:
        with _lock:
            went_clear = _state["present"]
            _state["mm"] = None
            _state["present"] = False
            _state["hz"] = 0.0
            _win_start_t = now
            _win_count = 0
    if went_clear:
        _log_event("Path clear (reading went stale) — vibro off")
    # The NUS bridge heartbeats every 5 s; if it stops, show Bluetooth as off.
    if _bt_last_t and (now - _bt_last_t) > 12.0:
        with _lock:
            _state["bt"] = {"advertising": False, "connected": False, "device": None}
    _push_actuators()
    _emit(force=True)


# ---- REST API (dashboard + NUS side channel) --------------------------------

def set_threshold(payload: dict):
    """POST /api/threshold {"mm": <number>} — dashboard or NUS phone write."""
    try:
        mm = float(payload.get("mm"))
    except (TypeError, ValueError):
        return {"error": 'expected {"mm": <number>}'}
    mm = max(0.0, min(1500.0, mm))
    with _lock:
        _state["threshold_mm"] = mm
        if _state["mm"] is not None:
            # Plain compare on a manual threshold change; hysteresis/debounce
            # governs the streaming path in on_distance_reading.
            _state["present"] = _state["mm"] <= mm
    _log_event(f"Presence threshold set to {mm:.0f} mm")
    return {"threshold_mm": mm}


def set_phone_msg(payload: dict):
    """POST /api/phone {"text": "..."} — freeform text from the NUS side
    channel. Steering keywords (left/right/clear/stop) also drive the wheel;
    every spin runs at full speed. Anything else is display-only."""
    text = str(payload.get("text", "")).strip()[:200]
    with _lock:
        _state["phone_msg"] = text or None
    if text:
        _log_event(f"Phone (NUS): {text!r}")
        command = _steer_from_text(text)
        if command is not None:
            direction, _kind = command
            _set_wheel(direction, f"NUS text {text!r}")
    _emit(force=True)
    return {"ok": True}


def set_motor_api(payload: dict):
    """POST /api/motor {"dir": -1|0|1} — dashboard buttons; always full speed."""
    try:
        direction = int(payload.get("dir"))
    except (TypeError, ValueError):
        return {"error": 'expected {"dir": -1|0|1}'}
    if direction not in (-1, 0, 1):
        return {"error": 'expected {"dir": -1|0|1}'}
    _set_wheel(direction, "dashboard")
    _say_to_phone("Dashboard: stop" if direction == 0
                  else f"Dashboard: spin {ACTION_NAMES[direction]}")
    return {"motor": direction}


def pulse_vibro_api(payload: dict):
    """POST /api/vibro {"ms": <50..3000>} — one-shot manual buzz."""
    try:
        ms = int(payload.get("ms", 600))
    except (TypeError, ValueError):
        return {"error": 'expected {"ms": <number>}'}
    ms = max(50, min(3000, ms))
    try:
        Bridge.notify("vibro_pulse", ms)
    except Exception:
        logger.debug("Failed to send vibro pulse to the sketch")
        return {"error": "sketch unreachable"}
    _log_event(f"Manual vibro buzz ({ms} ms, dashboard)")
    _say_to_phone("Dashboard: buzz")
    return {"ms": ms}


def clear_graphs_api(payload: dict):
    """POST /api/graphs/clear — wipe the motor telemetry history; every open
    dashboard drops its local buffer when it sees graph_epoch change."""
    with _lock:
        _motor_history.clear()
        _state["graph_epoch"] += 1
    _log_event("Motor graphs cleared (dashboard)")
    return {"ok": True}


def set_bt(payload: dict):
    """POST /api/bt — advertising/connection heartbeat from the NUS bridge."""
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
        now_connected = bt["connected"]
        device = bt["device"]
    if now_connected and not was_connected:
        _log_event(f"Phone connected over BLE (NUS){': ' + device if device else ''}")
    if was_connected and not now_connected:
        _log_event("Phone disconnected from BLE (NUS)")
        _set_wheel(0, "phone disconnected")
    _emit(force=True)
    return {"ok": True}


for _name, _handler in (("distance_reading", on_distance_reading),
                        ("sensor_status", on_sensor_status),
                        ("actuator_status", on_actuator_status),
                        ("motor_telemetry", on_motor_telemetry),
                        ("wheel_applied", on_wheel_applied),
                        ("selftest_telemetry", on_selftest_telemetry)):
    try:
        Bridge.provide(_name, _handler)
    except RuntimeError:
        logger.debug(f"'{_name}' already registered")

ui.on_connect(lambda sid: ui.send_message("distance", _snapshot(history=True), sid))
ui.expose_api("GET", "/api/state", lambda: _snapshot(history=True))
ui.expose_api("POST", "/api/threshold", set_threshold)
ui.expose_api("POST", "/api/phone", set_phone_msg)
ui.expose_api("POST", "/api/motor", set_motor_api)
ui.expose_api("POST", "/api/vibro", pulse_vibro_api)
ui.expose_api("POST", "/api/graphs/clear", clear_graphs_api)
ui.expose_api("POST", "/api/bt", set_bt)

logger.info(f"QCane Wheel starting; presence threshold = {PRESENCE_MAX_MM:.0f} mm; "
            f"waiting for Bluetooth commands on {SOCKET_PATH}")
App.run(user_loop=watchdog)
