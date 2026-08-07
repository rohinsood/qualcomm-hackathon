"""qcane-wheel — Linux (MPU) side.

Bluetooth itself lives in host/qcane_btd.py, which runs on the host rather than
in this container: the app container is on a Docker bridge network with no
D-Bus and no AF_BLUETOOTH, so it cannot reach BlueZ. The daemon owns the radio
and hands commands over a Unix socket in the app folder, which is bind-mounted
here at /app.

This module only translates those commands into Router Bridge calls to the
sketch, which drives the wheel (currently mocked on the LED matrix).
"""

import json
import os
import select
import socket
import time

from arduino.app_utils import App, Bridge, Logger

logger = Logger("qcane-wheel")

SOCKET_PATH = os.environ.get("QCANE_BT_SOCKET", "/app/.run/qcane-bt.sock")
RETRY_DELAY_S = 2.0
POLL_TIMEOUT_S = 1.0
MAX_BUFFER = 64 * 1024

# Sketch-side convention for set_wheel(dir, speed).
DIRECTIONS = {"left": -1, "stop": 0, "right": 1}
ACTION_NAMES = {value: name for name, value in DIRECTIONS.items()}
DEFAULT_SPEED = 3

_sock = None
_buffer = b""
_last_warned = 0.0


def _disconnect():
    global _sock, _buffer
    if _sock is not None:
        try:
            _sock.close()
        except OSError:
            pass
    _sock = None
    _buffer = b""


def _send(payload):
    """Report back to the daemon so it can push state to the phone.

    Also called from the Bridge thread, so take a local reference: the main
    loop may drop the connection concurrently.
    """
    sock = _sock
    if sock is None:
        return
    try:
        sock.sendall((json.dumps(payload) + "\n").encode("utf-8"))
    except OSError as err:
        logger.warning("could not send %s upstream: %s", payload.get("type"), err)
        _disconnect()


def _connect():
    global _sock, _last_warned
    try:
        sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        sock.connect(SOCKET_PATH)
    except OSError as err:
        now = time.monotonic()
        if now - _last_warned > 30:
            _last_warned = now
            logger.warning(
                "Bluetooth daemon not reachable on %s (%s). Start it on the host: "
                "python3 ~/ArduinoApps/qcane-wheel/host/qcane_btd.py",
                SOCKET_PATH,
                err,
            )
        return False

    _sock = sock
    logger.info("connected to the Bluetooth daemon on %s", SOCKET_PATH)
    _send({"type": "hello", "client": "arduino-app"})
    return True


def _apply(action, speed):
    """Forward one command to the MCU."""
    direction = DIRECTIONS[action]
    try:
        # Raises ValueError if the sketch never registered set_wheel, or
        # TimeoutError if the MCU stopped answering.
        Bridge.call("set_wheel", direction, speed)
    except Exception as err:
        logger.error("set_wheel(%s, %s) failed: %s", direction, speed, err)
        _send({"type": "error", "action": action, "detail": str(err)})
        return

    logger.info("wheel -> %s (speed %s)", action, speed)
    # The authoritative state comes back from the sketch via wheel_applied.


def _on_wheel_applied(direction, speed, motor_ready=True):
    """Called by the sketch once it has actually applied a command.

    Reporting from the MCU rather than from _apply() means the state the phone
    sees is what the hardware is doing, not merely what was requested.
    """
    action = ACTION_NAMES.get(int(direction), "stop")
    if motor_ready:
        logger.info("MCU applied %s (speed %s)", action, speed)
    else:
        logger.warning(
            "MCU applied %s (speed %s) but no Modulino Motors answered on the "
            "Qwiic bus — check the cable and the motor power supply", action, speed)
    _send({"type": "state", "action": action, "speed": int(speed),
           "motor": bool(motor_ready)})


def _on_motor_telemetry(stage, milliamps_a, milliamps_b, mode="?", busy=False):
    """Current-sense readings streamed by the sketch during a self-test."""
    logger.info("selftest %-18s A=%7.1f mA  B=%7.1f mA  mode=%s busy=%s",
                stage, milliamps_a, milliamps_b, mode, busy)
    _send({"type": "telemetry", "stage": stage,
           "mA_a": round(float(milliamps_a), 1),
           "mA_b": round(float(milliamps_b), 1),
           "mode": str(mode), "busy": bool(busy)})


def _handle(line):
    try:
        message = json.loads(line)
    except ValueError:
        logger.warning("ignoring malformed message: %r", line[:120])
        return

    if message.get("type") == "selftest":
        try:
            percent = int(message.get("percent", 40))
        except (TypeError, ValueError):
            percent = 40
        logger.info("running motor self-test at %d%%", percent)
        try:
            Bridge.call("motor_selftest", max(10, min(100, percent)))
        except Exception as err:
            logger.error("motor_selftest failed: %s", err)
            _send({"type": "error", "action": "selftest", "detail": str(err)})
        return

    if message.get("type") != "command":
        return

    action = str(message.get("action", "")).lower()
    if action not in DIRECTIONS:
        logger.warning("ignoring unknown action %r", action)
        return

    try:
        speed = int(message.get("speed", DEFAULT_SPEED))
    except (TypeError, ValueError):
        speed = DEFAULT_SPEED

    _apply(action, max(1, min(5, speed)))


def loop():
    global _buffer

    if _sock is None:
        if not _connect():
            time.sleep(RETRY_DELAY_S)
        return

    # Poll rather than block on a read, so shutdown signals are handled promptly.
    readable, _, _ = select.select([_sock], [], [], POLL_TIMEOUT_S)
    if not readable:
        return

    try:
        chunk = _sock.recv(4096)
    except OSError as err:
        logger.warning("read failed: %s", err)
        _disconnect()
        return

    if not chunk:
        logger.warning("Bluetooth daemon closed the connection, will retry")
        _disconnect()
        time.sleep(RETRY_DELAY_S)
        return

    _buffer += chunk
    while b"\n" in _buffer:
        line, _buffer = _buffer.split(b"\n", 1)
        if line.strip():
            _handle(line)

    if len(_buffer) > MAX_BUFFER:
        logger.warning("dropping %d bytes of unterminated input", len(_buffer))
        _buffer = b""


Bridge.provide("wheel_applied", _on_wheel_applied)
Bridge.provide("motor_telemetry", _on_motor_telemetry)

logger.info("qcane-wheel starting, waiting for Bluetooth commands on %s", SOCKET_PATH)

App.run(user_loop=loop)
