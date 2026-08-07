#!/usr/bin/env python3
"""qcane-btd — host-side Bluetooth front end for the qcane-wheel Arduino App.

This runs on the board's Linux side, NOT inside the app container: the app
container sits on a Docker bridge network with no D-Bus socket and no
AF_BLUETOOTH support, so it cannot talk to BlueZ. This daemon owns the radio
and forwards commands to the app over a Unix socket in the app folder (which is
bind-mounted into the container at /app).

It exposes the same wheel commands over two transports at once, so the phone
side can use whichever is easier:

  * BLE GATT   — write "left" / "right" / "stop" to the command characteristic.
  * Classic SPP — pair, open an RFCOMM socket, write "left\\n".

Needs no root and no extra Python packages: BlueZ is driven over D-Bus through
the system PyGObject (gi) install.
"""

from __future__ import annotations

import argparse
import json
import logging
import os
import socket
import sys
import time

import gi

gi.require_version("Gio", "2.0")
from gi.repository import Gio, GLib  # noqa: E402

log = logging.getLogger("qcane-btd")

# ---------------------------------------------------------------------------
# Protocol constants — keep these in sync with the Android app.
# ---------------------------------------------------------------------------

SERVICE_UUID = "bcf2f193-f22b-4695-af5e-fd3b9caf4977"
CMD_CHAR_UUID = "bcf2f194-f22b-4695-af5e-fd3b9caf4977"
STATE_CHAR_UUID = "bcf2f195-f22b-4695-af5e-fd3b9caf4977"

# Standard Serial Port Profile: what Android's
# createRfcommSocketToServiceRecord() looks for.
SPP_UUID = "00001101-0000-1000-8000-00805f9b34fb"

BLUEZ = "org.bluez"
APP_PATH = "/qcane/wheel"
SERVICE_PATH = APP_PATH + "/service0"
CMD_CHAR_PATH = SERVICE_PATH + "/char0"
STATE_CHAR_PATH = SERVICE_PATH + "/char1"
ADV_PATH = "/qcane/adv0"
PROFILE_PATH = "/qcane/spp"
AGENT_PATH = "/qcane/agent"

GATT_CHRC_IFACE = "org.bluez.GattCharacteristic1"
PROPS_IFACE = "org.freedesktop.DBus.Properties"

# Full scale: bare letters from the phone (v3 sends no speed suffix) must
# still drive the wheel hard enough to feel through a cane grip.
DEFAULT_SPEED = 5
MIN_SPEED, MAX_SPEED = 1, 5
MAX_LINE = 4096

# A legacy advertisement carries 31 bytes: 3 for flags, 18 for one 128-bit
# service UUID, 2 for the name header — leaving this much for the name itself.
MAX_ADV_NAME = 31 - 3 - (2 + 16) - 2

ACTIONS = {
    "left": "left", "l": "left", "ccw": "left",
    "turn_left": "left", "turn-left": "left", "turnleft": "left",
    "right": "right", "r": "right", "cw": "right",
    "turn_right": "right", "turn-right": "right", "turnright": "right",
    "stop": "stop", "s": "stop", "x": "stop", "halt": "stop", "brake": "stop", "0": "stop",
}


def parse_command(raw: bytes):
    """Parse one wire message into (action, speed), or None if unrecognised.

    Deliberately permissive so the phone side can stay trivial. All of these
    mean the same thing:  b"LEFT"  b"left 4"  b"left:4"  b'{"action":"left"}'
    """
    text = raw.decode("utf-8", "replace").strip()
    if not text:
        return None

    speed = DEFAULT_SPEED
    if text.startswith("{"):
        try:
            obj = json.loads(text)
        except ValueError:
            return None
        action = str(obj.get("action", obj.get("cmd", ""))).strip().lower()
        speed = obj.get("speed", DEFAULT_SPEED)
    else:
        for sep in (":", "=", ",", " "):
            if sep in text:
                action, _, rest = text.partition(sep)
                speed = rest.strip() or DEFAULT_SPEED
                break
        else:
            action = text
        action = action.strip().lower()

    action = ACTIONS.get(action)
    if action is None:
        return None

    try:
        speed = int(speed)
    except (TypeError, ValueError):
        speed = DEFAULT_SPEED

    return action, max(MIN_SPEED, min(MAX_SPEED, speed))


# ---------------------------------------------------------------------------
# Unix socket hub: daemon <-> Arduino App
# ---------------------------------------------------------------------------


class CommandBus:
    """Broadcasts commands down to the app and collects state back from it.

    Also accepts {"type": "inject", ...} from any client, which lets
    `qcane_btd.py --send left` exercise the whole chain without a phone.
    """

    def __init__(self, path: str):
        self.path = path
        self.state = {"action": "stop", "speed": DEFAULT_SPEED}
        self.on_state = None  # set by the transports
        self._clients = {}  # fd -> {"sock":..., "buf":...}

        parent = os.path.dirname(path)
        if parent:
            os.makedirs(parent, exist_ok=True)
        if os.path.exists(path):
            os.unlink(path)

        self._srv = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self._srv.bind(path)
        self._srv.listen(8)
        self._srv.setblocking(False)
        os.chmod(path, 0o660)

        GLib.unix_fd_add_full(
            GLib.PRIORITY_DEFAULT, self._srv.fileno(),
            GLib.IOCondition.IN, self._on_accept)
        log.info("command socket listening on %s", path)

    # -- plumbing ----------------------------------------------------------

    def _on_accept(self, _fd, _cond):
        try:
            conn, _ = self._srv.accept()
        except OSError as err:
            log.warning("accept failed: %s", err)
            return True

        conn.setblocking(False)
        fd = conn.fileno()
        self._clients[fd] = {"sock": conn, "buf": b""}
        GLib.unix_fd_add_full(
            GLib.PRIORITY_DEFAULT, fd,
            GLib.IOCondition.IN | GLib.IOCondition.HUP | GLib.IOCondition.ERR,
            self._on_client)
        log.info("app connected (fd %d), %d client(s)", fd, len(self._clients))
        self._send_to(fd, {"type": "state", **self.state})
        return True

    def _on_client(self, fd, cond):
        client = self._clients.get(fd)
        if client is None:
            return False

        if cond & (GLib.IOCondition.HUP | GLib.IOCondition.ERR):
            self._drop(fd)
            return False

        try:
            chunk = client["sock"].recv(4096)
        except BlockingIOError:
            return True
        except OSError as err:
            log.warning("client %d read failed: %s", fd, err)
            self._drop(fd)
            return False

        if not chunk:
            self._drop(fd)
            return False

        client["buf"] += chunk
        while b"\n" in client["buf"]:
            line, client["buf"] = client["buf"].split(b"\n", 1)
            if line.strip():
                self._on_message(line, fd)
        if len(client["buf"]) > MAX_LINE:
            client["buf"] = b""
        return True

    def _drop(self, fd):
        client = self._clients.pop(fd, None)
        if client is None:
            return
        try:
            client["sock"].close()
        except OSError:
            pass
        log.info("app disconnected (fd %d), %d client(s)", fd, len(self._clients))

    def _send_to(self, fd, payload):
        client = self._clients.get(fd)
        if client is None:
            return
        try:
            client["sock"].sendall((json.dumps(payload) + "\n").encode("utf-8"))
        except OSError:
            self._drop(fd)

    def _on_message(self, line: bytes, sender_fd):
        try:
            message = json.loads(line)
        except ValueError:
            log.warning("ignoring malformed client message: %r", line[:120])
            return

        kind = message.get("type")
        if kind == "state":
            action = str(message.get("action", "stop")).lower()
            if action in ("left", "right", "stop"):
                self.state = {"action": action,
                              "speed": int(message.get("speed", DEFAULT_SPEED))}
                log.info("wheel is now %s (speed %d)",
                         self.state["action"], self.state["speed"])
                # Out to the phone, and to any other local listener such as
                # `--send`, which waits for this to confirm the round trip.
                if self.on_state:
                    self.on_state(self.state)
                self._broadcast({"type": "state", **self.state}, exclude=sender_fd)
        elif kind == "inject":
            parsed = parse_command(json.dumps(message).encode())
            if parsed:
                self.publish(*parsed, source="inject")
        elif kind == "selftest":
            # Diagnostic, not part of the wheel vocabulary: pass it to the app.
            log.info("motor self-test requested at %s%%", message.get("percent"))
            self._broadcast(message, exclude=sender_fd)
        elif kind == "telemetry":
            log.info("selftest %-18s A=%7.1f mA  B=%7.1f mA",
                     message.get("stage"), message.get("mA_a", -1),
                     message.get("mA_b", -1))
            self._broadcast(message, exclude=sender_fd)
        elif kind == "error":
            log.error("app reported: %s", message.get("detail"))
            self._broadcast(message, exclude=sender_fd)

    def _broadcast(self, payload, exclude=None):
        line = (json.dumps(payload) + "\n").encode("utf-8")
        for fd in list(self._clients):
            if fd == exclude:
                continue
            try:
                self._clients[fd]["sock"].sendall(line)
            except OSError:
                self._drop(fd)

    # -- public ------------------------------------------------------------

    def publish(self, action: str, speed: int, source: str):
        if not self._clients:
            log.warning("%s command %r dropped: the Arduino App is not connected",
                        source, action)
            return False

        self._broadcast({"type": "command", "action": action,
                         "speed": speed, "source": source})
        log.info("%s -> %s (speed %d)", source, action, speed)
        return True

    def close(self):
        for fd in list(self._clients):
            self._drop(fd)
        try:
            self._srv.close()
        except OSError:
            pass
        if os.path.exists(self.path):
            os.unlink(self.path)


# ---------------------------------------------------------------------------
# D-Bus helpers
# ---------------------------------------------------------------------------


def iface_info(xml: str):
    return Gio.DBusNodeInfo.new_for_xml(xml).interfaces[0]


def proxy(bus, path, interface):
    return Gio.DBusProxy.new_sync(
        bus, Gio.DBusProxyFlags.NONE, None, BLUEZ, path, interface, None)


def call_async(prox, method, params, on_ok=None, on_error=None, timeout=20000):
    """Call a BlueZ method without blocking the main loop.

    Registration calls must not be synchronous: BlueZ calls straight back into
    us (GetManagedObjects, GetAll) while the call is in flight, and a blocked
    main loop cannot answer, so call_sync deadlocks until it times out.
    """

    def done(prox_, result):
        try:
            prox_.call_finish(result)
        except GLib.Error as err:
            if on_error:
                on_error(err)
            else:
                log.error("%s failed: %s", method, err.message)
            return
        if on_ok:
            on_ok()

    prox.call(method, params, Gio.DBusCallFlags.NONE, timeout, None, done)


def call_best_effort(prox, method, params, timeout=3000):
    """Fire-and-forget cleanup call; failures are not worth surfacing."""
    try:
        prox.call(method, params, Gio.DBusCallFlags.NONE, timeout, None, None)
    except GLib.Error as err:
        log.debug("%s: %s", method, err.message)


# ---------------------------------------------------------------------------
# BLE GATT transport
# ---------------------------------------------------------------------------

APP_XML = """
<node><interface name='org.freedesktop.DBus.ObjectManager'>
  <method name='GetManagedObjects'>
    <arg type='a{oa{sa{sv}}}' name='objects' direction='out'/>
  </method>
</interface></node>
"""

CHRC_XML = """
<node><interface name='org.bluez.GattCharacteristic1'>
  <method name='ReadValue'>
    <arg type='a{sv}' name='options' direction='in'/>
    <arg type='ay' name='value' direction='out'/>
  </method>
  <method name='WriteValue'>
    <arg type='ay' name='value' direction='in'/>
    <arg type='a{sv}' name='options' direction='in'/>
  </method>
  <method name='StartNotify'/>
  <method name='StopNotify'/>
  <property name='UUID' type='s' access='read'/>
  <property name='Service' type='o' access='read'/>
  <property name='Flags' type='as' access='read'/>
  <property name='Notifying' type='b' access='read'/>
  <property name='Value' type='ay' access='read'/>
</interface></node>
"""

SERVICE_XML = """
<node><interface name='org.bluez.GattService1'>
  <property name='UUID' type='s' access='read'/>
  <property name='Primary' type='b' access='read'/>
</interface></node>
"""

ADV_XML = """
<node><interface name='org.bluez.LEAdvertisement1'>
  <method name='Release'/>
  <property name='Type' type='s' access='read'/>
  <property name='ServiceUUIDs' type='as' access='read'/>
  <property name='LocalName' type='s' access='read'/>
</interface></node>
"""


class BleTransport:
    def __init__(self, bus, adapter_path, local_name, bus_hub: CommandBus):
        self.bus = bus
        self.adapter_path = adapter_path
        if len(local_name.encode("utf-8")) > MAX_ADV_NAME:
            log.warning("BLE name %r is too long to advertise next to the service "
                        "UUID, truncating to %d bytes", local_name, MAX_ADV_NAME)
            local_name = local_name.encode("utf-8")[:MAX_ADV_NAME].decode(
                "utf-8", "ignore")
        self.local_name = local_name
        self.hub = bus_hub
        self.notifying = False
        self.state_value = self._encode_state(bus_hub.state)
        self._adv_registered = False

        bus.register_object(APP_PATH, iface_info(APP_XML), self._app_method, None, None)
        bus.register_object(SERVICE_PATH, iface_info(SERVICE_XML), None,
                            self._service_prop, None)
        bus.register_object(CMD_CHAR_PATH, iface_info(CHRC_XML), self._chrc_method,
                            self._chrc_prop, None)
        bus.register_object(STATE_CHAR_PATH, iface_info(CHRC_XML), self._chrc_method,
                            self._chrc_prop, None)
        bus.register_object(ADV_PATH, iface_info(ADV_XML), self._adv_method,
                            self._adv_prop, None)

    # -- properties --------------------------------------------------------

    @staticmethod
    def _encode_state(state):
        return f"{state['action']}:{state['speed']}".encode("utf-8")

    def _service_prop(self, _conn, _sender, _path, _iface, prop):
        return {"UUID": GLib.Variant("s", SERVICE_UUID),
                "Primary": GLib.Variant("b", True)}.get(prop)

    def _chrc_props(self, path):
        if path == CMD_CHAR_PATH:
            return {
                "UUID": GLib.Variant("s", CMD_CHAR_UUID),
                "Service": GLib.Variant("o", SERVICE_PATH),
                "Flags": GLib.Variant("as", ["write", "write-without-response"]),
                "Notifying": GLib.Variant("b", False),
                "Value": GLib.Variant("ay", b""),
            }
        return {
            "UUID": GLib.Variant("s", STATE_CHAR_UUID),
            "Service": GLib.Variant("o", SERVICE_PATH),
            "Flags": GLib.Variant("as", ["read", "notify"]),
            "Notifying": GLib.Variant("b", self.notifying),
            "Value": GLib.Variant("ay", self.state_value),
        }

    def _chrc_prop(self, _conn, _sender, path, _iface, prop):
        return self._chrc_props(path).get(prop)

    def _adv_prop(self, _conn, _sender, _path, _iface, prop):
        return {
            "Type": GLib.Variant("s", "peripheral"),
            "ServiceUUIDs": GLib.Variant("as", [SERVICE_UUID]),
            "LocalName": GLib.Variant("s", self.local_name),
        }.get(prop)

    # -- methods -----------------------------------------------------------

    def _app_method(self, _conn, _sender, _path, _iface, method, _params, invocation):
        if method != "GetManagedObjects":
            invocation.return_error_literal(
                Gio.dbus_error_quark(), Gio.DBusError.UNKNOWN_METHOD, method)
            return
        objects = {
            SERVICE_PATH: {"org.bluez.GattService1": {
                "UUID": GLib.Variant("s", SERVICE_UUID),
                "Primary": GLib.Variant("b", True)}},
            CMD_CHAR_PATH: {GATT_CHRC_IFACE: self._chrc_props(CMD_CHAR_PATH)},
            STATE_CHAR_PATH: {GATT_CHRC_IFACE: self._chrc_props(STATE_CHAR_PATH)},
        }
        invocation.return_value(GLib.Variant("(a{oa{sa{sv}}})", (objects,)))

    def _chrc_method(self, _conn, _sender, path, _iface, method, params, invocation):
        if method == "ReadValue":
            invocation.return_value(GLib.Variant("(ay)", (self.state_value,)))
            return

        if method == "WriteValue":
            raw = bytes(params.unpack()[0])
            parsed = parse_command(raw)
            if parsed is None:
                log.warning("BLE write ignored, unrecognised payload: %r", raw[:64])
                invocation.return_error_literal(
                    Gio.dbus_error_quark(), Gio.DBusError.INVALID_ARGS,
                    "expected left, right or stop")
                return
            self.hub.publish(*parsed, source="ble")
            invocation.return_value(None)
            return

        if method in ("StartNotify", "StopNotify"):
            self.notifying = method == "StartNotify"
            log.info("BLE notifications %s", "on" if self.notifying else "off")
            invocation.return_value(None)
            return

        invocation.return_error_literal(
            Gio.dbus_error_quark(), Gio.DBusError.UNKNOWN_METHOD, method)

    def _adv_method(self, _conn, _sender, _path, _iface, method, _params, invocation):
        if method == "Release":
            self._adv_registered = False
            log.info("advertisement released by BlueZ")
        invocation.return_value(None)

    # -- state push --------------------------------------------------------

    def push_state(self, state):
        self.state_value = self._encode_state(state)
        if not self.notifying:
            return
        self.bus.emit_signal(
            None, STATE_CHAR_PATH, PROPS_IFACE, "PropertiesChanged",
            GLib.Variant("(sa{sv}as)",
                         (GATT_CHRC_IFACE,
                          {"Value": GLib.Variant("ay", self.state_value)}, [])))

    # -- registration ------------------------------------------------------

    def register(self):
        gatt = proxy(self.bus, self.adapter_path, "org.bluez.GattManager1")
        call_async(
            gatt, "RegisterApplication", GLib.Variant("(oa{sv})", (APP_PATH, {})),
            on_ok=lambda: log.info("GATT application registered (service %s)",
                                   SERVICE_UUID))

        adv = proxy(self.bus, self.adapter_path, "org.bluez.LEAdvertisingManager1")

        def advertised():
            self._adv_registered = True
            log.info("advertising as %r with service UUID %s",
                     self.local_name, SERVICE_UUID)

        def retry_without_name(err):
            # A 128-bit UUID plus a long name overflows the 31-byte advertising
            # payload. The UUID matters more: it is what the phone scans for.
            log.warning("advertisement rejected (%s); retrying with a shorter name",
                        err.message)
            self.local_name = self.local_name[:4] or "QC"
            call_async(adv, "RegisterAdvertisement",
                       GLib.Variant("(oa{sv})", (ADV_PATH, {})),
                       on_ok=advertised)

        call_async(adv, "RegisterAdvertisement",
                   GLib.Variant("(oa{sv})", (ADV_PATH, {})),
                   on_ok=advertised, on_error=retry_without_name)

    def unregister(self):
        if self._adv_registered:
            call_best_effort(
                proxy(self.bus, self.adapter_path, "org.bluez.LEAdvertisingManager1"),
                "UnregisterAdvertisement", GLib.Variant("(o)", (ADV_PATH,)))
        call_best_effort(
            proxy(self.bus, self.adapter_path, "org.bluez.GattManager1"),
            "UnregisterApplication", GLib.Variant("(o)", (APP_PATH,)))


# ---------------------------------------------------------------------------
# Classic SPP transport
# ---------------------------------------------------------------------------

PROFILE_XML = """
<node><interface name='org.bluez.Profile1'>
  <method name='Release'/>
  <method name='NewConnection'>
    <arg type='o' name='device' direction='in'/>
    <arg type='h' name='fd' direction='in'/>
    <arg type='a{sv}' name='fd_properties' direction='in'/>
  </method>
  <method name='RequestDisconnection'>
    <arg type='o' name='device' direction='in'/>
  </method>
</interface></node>
"""

AGENT_XML = """
<node><interface name='org.bluez.Agent1'>
  <method name='Release'/>
  <method name='RequestPinCode'>
    <arg type='o' name='device' direction='in'/>
    <arg type='s' name='pincode' direction='out'/>
  </method>
  <method name='DisplayPinCode'>
    <arg type='o' name='device' direction='in'/>
    <arg type='s' name='pincode' direction='in'/>
  </method>
  <method name='RequestPasskey'>
    <arg type='o' name='device' direction='in'/>
    <arg type='u' name='passkey' direction='out'/>
  </method>
  <method name='DisplayPasskey'>
    <arg type='o' name='device' direction='in'/>
    <arg type='u' name='passkey' direction='in'/>
    <arg type='q' name='entered' direction='in'/>
  </method>
  <method name='RequestConfirmation'>
    <arg type='o' name='device' direction='in'/>
    <arg type='u' name='passkey' direction='in'/>
  </method>
  <method name='RequestAuthorization'>
    <arg type='o' name='device' direction='in'/>
  </method>
  <method name='AuthorizeService'>
    <arg type='o' name='device' direction='in'/>
    <arg type='s' name='uuid' direction='in'/>
  </method>
  <method name='Cancel'/>
</interface></node>
"""


class SppTransport:
    """Classic RFCOMM via BlueZ's Profile1, plus an auto-accepting pair agent."""

    def __init__(self, bus, bus_hub: CommandBus, pin: str):
        self.bus = bus
        self.hub = bus_hub
        self.pin = pin
        self.links = {}  # fd -> {"sock":..., "buf":..., "device":...}

        bus.register_object(PROFILE_PATH, iface_info(PROFILE_XML),
                            self._profile_method, None, None)
        bus.register_object(AGENT_PATH, iface_info(AGENT_XML),
                            self._agent_method, None, None)

    # -- pairing agent: accept everything, this is a headless board ---------

    def _agent_method(self, _conn, _sender, _path, _iface, method, params, invocation):
        if method == "RequestPinCode":
            log.info("pairing: supplying PIN for %s", params.unpack()[0])
            invocation.return_value(GLib.Variant("(s)", (self.pin,)))
        elif method == "RequestPasskey":
            log.info("pairing: supplying passkey for %s", params.unpack()[0])
            invocation.return_value(GLib.Variant("(u)", (int(self.pin),)))
        elif method in ("RequestConfirmation", "RequestAuthorization",
                        "AuthorizeService"):
            log.info("pairing: auto-accepting %s for %s", method, params.unpack()[0])
            invocation.return_value(None)
        else:
            invocation.return_value(None)

    # -- RFCOMM links ------------------------------------------------------

    def _profile_method(self, _conn, _sender, _path, _iface, method, params, invocation):
        if method == "NewConnection":
            device, fd_index, _props = params.unpack()
            fd_list = invocation.get_message().get_unix_fd_list()
            try:
                fd = fd_list.get(fd_index)
            except Exception as err:  # GLib.Error or IndexError
                log.error("SPP: could not take the connection fd: %s", err)
                invocation.return_value(None)
                return
            self._attach(fd, device)
            invocation.return_value(None)
            return

        if method == "RequestDisconnection":
            device = params.unpack()[0]
            for fd, link in list(self.links.items()):
                if link["device"] == device:
                    self._drop(fd)
        invocation.return_value(None)

    def _attach(self, fd, device):
        sock = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM,
                             socket.BTPROTO_RFCOMM, fileno=fd)
        sock.setblocking(False)
        self.links[sock.fileno()] = {"sock": sock, "buf": b"",
                                     "device": device, "flush_id": None}
        GLib.unix_fd_add_full(
            GLib.PRIORITY_DEFAULT, sock.fileno(),
            GLib.IOCondition.IN | GLib.IOCondition.HUP | GLib.IOCondition.ERR,
            self._on_data)
        log.info("SPP client connected: %s", device)
        self._write(sock.fileno(), self.hub.state)

    def _on_data(self, fd, cond):
        link = self.links.get(fd)
        if link is None:
            return False
        if cond & (GLib.IOCondition.HUP | GLib.IOCondition.ERR):
            self._drop(fd)
            return False

        try:
            chunk = link["sock"].recv(1024)
        except BlockingIOError:
            return True
        except OSError as err:
            log.warning("SPP read failed: %s", err)
            self._drop(fd)
            return False

        if not chunk:
            self._drop(fd)
            return False

        link["buf"] += chunk
        while b"\n" in link["buf"] or b"\r" in link["buf"]:
            line, _, rest = link["buf"].replace(b"\r", b"\n").partition(b"\n")
            link["buf"] = rest
            self._dispatch(line)

        if len(link["buf"]) > MAX_LINE:
            link["buf"] = b""
        elif link["buf"]:
            # Tolerate clients that never send a newline, but only once the
            # data has stopped arriving: "left" can turn up as "l" + "eft",
            # and "l" on its own is a valid command.
            self._schedule_flush(fd)
        return True

    def _schedule_flush(self, fd):
        link = self.links.get(fd)
        if link is None or link.get("flush_id"):
            return
        link["flush_id"] = GLib.timeout_add(60, self._flush, fd)

    def _flush(self, fd):
        link = self.links.get(fd)
        if link is None:
            return False
        link["flush_id"] = None
        if link["buf"] and parse_command(link["buf"]):
            self._dispatch(link["buf"])
            link["buf"] = b""
        return False

    def _dispatch(self, raw: bytes):
        if not raw.strip():
            return
        parsed = parse_command(raw)
        if parsed is None:
            log.warning("SPP message ignored, unrecognised payload: %r", raw[:64])
            return
        self.hub.publish(*parsed, source="spp")

    def _write(self, fd, state):
        link = self.links.get(fd)
        if link is None:
            return
        try:
            link["sock"].sendall(
                f"{state['action']}:{state['speed']}\n".encode("utf-8"))
        except OSError:
            self._drop(fd)

    def _drop(self, fd):
        link = self.links.pop(fd, None)
        if link is None:
            return
        if link.get("flush_id"):
            GLib.source_remove(link["flush_id"])
        try:
            link["sock"].close()
        except OSError:
            pass
        log.info("SPP client disconnected: %s", link["device"])

    def push_state(self, state):
        for fd in list(self.links):
            self._write(fd, state)

    # -- registration ------------------------------------------------------

    def register(self):
        agents = proxy(self.bus, "/org/bluez", "org.bluez.AgentManager1")

        def agent_is_default():
            log.info("pairing agent registered (auto-accept)")

        def agent_registered():
            call_async(agents, "RequestDefaultAgent",
                       GLib.Variant("(o)", (AGENT_PATH,)),
                       on_ok=agent_is_default,
                       on_error=lambda err: log.warning(
                           "could not become the default pairing agent: %s. "
                           "Pairing may need confirming with bluetoothctl.",
                           err.message))

        call_async(agents, "RegisterAgent",
                   GLib.Variant("(os)", (AGENT_PATH, "NoInputNoOutput")),
                   on_ok=agent_registered,
                   on_error=lambda err: log.warning(
                       "pairing agent not registered: %s", err.message))

        options = {
            "Name": GLib.Variant("s", "QCane Wheel"),
            "Role": GLib.Variant("s", "server"),
            "Channel": GLib.Variant("q", 0),
            "RequireAuthentication": GLib.Variant("b", False),
            "RequireAuthorization": GLib.Variant("b", False),
        }
        call_async(
            proxy(self.bus, "/org/bluez", "org.bluez.ProfileManager1"),
            "RegisterProfile",
            GLib.Variant("(osa{sv})", (PROFILE_PATH, SPP_UUID, options)),
            on_ok=lambda: log.info("SPP profile registered (UUID %s)", SPP_UUID))

    def unregister(self):
        for fd in list(self.links):
            self._drop(fd)
        for path, iface, method in (
                (PROFILE_PATH, "org.bluez.ProfileManager1", "UnregisterProfile"),
                (AGENT_PATH, "org.bluez.AgentManager1", "UnregisterAgent")):
            call_best_effort(proxy(self.bus, "/org/bluez", iface), method,
                             GLib.Variant("(o)", (path,)))


# ---------------------------------------------------------------------------
# Adapter
# ---------------------------------------------------------------------------


class Adapter:
    def __init__(self, bus, path):
        self.bus = bus
        self.path = path
        self.props = proxy(bus, path, PROPS_IFACE)
        self._previous = {}

    def get(self, name):
        return self.props.call_sync(
            "Get", GLib.Variant("(ss)", ("org.bluez.Adapter1", name)),
            Gio.DBusCallFlags.NONE, 5000, None).unpack()[0]

    def set(self, name, variant, remember=True):
        if remember and name not in self._previous:
            try:
                self._previous[name] = self.get(name)
            except GLib.Error:
                pass
        self.props.call_sync(
            "Set", GLib.Variant("(ssv)", ("org.bluez.Adapter1", name, variant)),
            Gio.DBusCallFlags.NONE, 5000, None)

    def prepare(self, alias):
        self.set("Powered", GLib.Variant("b", True))
        self.set("Alias", GLib.Variant("s", alias))
        self.set("DiscoverableTimeout", GLib.Variant("u", 0))
        self.set("PairableTimeout", GLib.Variant("u", 0))
        self.set("Discoverable", GLib.Variant("b", True))
        self.set("Pairable", GLib.Variant("b", True))
        log.info("adapter %s ready: name=%r address=%s",
                 self.path, alias, self.get("Address"))

    def restore(self):
        for name, value in self._previous.items():
            try:
                if isinstance(value, bool):
                    variant = GLib.Variant("b", value)
                elif isinstance(value, int):
                    variant = GLib.Variant("u", value)
                else:
                    variant = GLib.Variant("s", value)
                self.set(name, variant, remember=False)
            except GLib.Error as err:
                log.debug("could not restore %s: %s", name, err)


# ---------------------------------------------------------------------------
# Client helpers (no radio involved — talk to a running daemon)
# ---------------------------------------------------------------------------


def client_send(path, action, speed, timeout=3.0):
    """Inject a command and wait for the app to confirm it reached the MCU."""
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as sock:
        try:
            sock.connect(path)
        except OSError as err:
            print(f"cannot reach the daemon on {path}: {err}", file=sys.stderr)
            return 1

        sock.sendall((json.dumps(
            {"type": "inject", "action": action, "speed": speed}) + "\n").encode())

        deadline = time.monotonic() + timeout
        buffer = b""
        while time.monotonic() < deadline:
            sock.settimeout(max(0.1, deadline - time.monotonic()))
            try:
                chunk = sock.recv(4096)
            except (OSError, socket.timeout):
                break
            if not chunk:
                break
            buffer += chunk
            while b"\n" in buffer:
                line, buffer = buffer.split(b"\n", 1)
                try:
                    message = json.loads(line)
                except ValueError:
                    continue
                if message.get("type") == "error":
                    print(f"the app could not drive the wheel: "
                          f"{message.get('detail')}", file=sys.stderr)
                    return 1
                # Ignore the state snapshot sent on connect; wait for the one
                # produced by this command.
                if (message.get("type") == "state"
                        and message.get("action") == action
                        and int(message.get("speed", -1)) == speed):
                    print(f"{action} (speed {speed}) acknowledged by the app")
                    return 0

    print(f"sent {action}, but the app did not confirm it — is the Arduino App "
          f"running?", file=sys.stderr)
    return 1


def client_selftest(path, percent, timeout=20.0):
    """Ask the sketch to profile the motor wiring and print what it measures."""
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as sock:
        try:
            sock.connect(path)
        except OSError as err:
            print(f"cannot reach the daemon on {path}: {err}", file=sys.stderr)
            return 1

        sock.sendall((json.dumps(
            {"type": "selftest", "percent": percent}) + "\n").encode())

        print(f"driving each channel pair at {percent}% and measuring current;")
        print("only a channel with the motor across it draws current.\n")
        print(f"{'stage':<20} {'channel A':>12} {'channel B':>12}  mode")

        deadline = time.monotonic() + timeout
        buffer = b""
        seen = 0
        while time.monotonic() < deadline:
            sock.settimeout(max(0.1, deadline - time.monotonic()))
            try:
                chunk = sock.recv(4096)
            except (OSError, socket.timeout):
                break
            if not chunk:
                break
            buffer += chunk
            while b"\n" in buffer:
                line, buffer = buffer.split(b"\n", 1)
                try:
                    message = json.loads(line)
                except ValueError:
                    continue
                if message.get("type") == "error":
                    print(f"\nself-test failed: {message.get('detail')}",
                          file=sys.stderr)
                    return 1
                if message.get("type") != "telemetry":
                    continue
                seen += 1
                print(f"{message.get('stage', '?'):<20} "
                      f"{message.get('mA_a', -1):>9.1f} mA "
                      f"{message.get('mA_b', -1):>9.1f} mA  "
                      f"{message.get('mode', '?')}")
                if message.get("stage") == "stopped":
                    return 0

        if seen == 0:
            print("\nno telemetry came back — is the Arduino App running?",
                  file=sys.stderr)
            return 1
    return 0


def client_watch(path):
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as sock:
        try:
            sock.connect(path)
        except OSError as err:
            print(f"cannot reach the daemon on {path}: {err}", file=sys.stderr)
            return 1
        print(f"watching {path} (ctrl-c to stop)")
        try:
            while True:
                chunk = sock.recv(4096)
                if not chunk:
                    print("daemon closed the connection")
                    return 0
                sys.stdout.write(chunk.decode("utf-8", "replace"))
                sys.stdout.flush()
        except KeyboardInterrupt:
            return 0


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------


def default_socket_path():
    here = os.path.dirname(os.path.abspath(__file__))
    return os.path.join(os.path.dirname(here), ".run", "qcane-bt.sock")


def main(argv=None):
    ap = argparse.ArgumentParser(
        description="Bluetooth front end for the qcane-wheel Arduino App.")
    ap.add_argument("--socket", default=default_socket_path(),
                    help="Unix socket shared with the Arduino App")
    ap.add_argument("--adapter", default="hci0", help="Bluetooth adapter")
    ap.add_argument("--name", default="QCane-Wheel",
                    help="Bluetooth device name used for pairing / SPP")
    ap.add_argument("--ble-name", default="QCane",
                    help="short name in the BLE advertisement (31-byte budget)")
    ap.add_argument("--pin", default="0000", help="pairing PIN for legacy devices")
    ap.add_argument("--no-ble", action="store_true", help="disable the BLE GATT transport")
    ap.add_argument("--no-spp", action="store_true", help="disable the Classic SPP transport")
    ap.add_argument("-v", "--verbose", action="store_true")
    ap.add_argument("--send", metavar="ACTION",
                    help="inject a command into a running daemon and exit")
    ap.add_argument("--speed", type=int, default=DEFAULT_SPEED, help="speed for --send")
    ap.add_argument("--watch", action="store_true",
                    help="print traffic from a running daemon and exit")
    ap.add_argument("--selftest", nargs="?", type=int, const=40, metavar="PERCENT",
                    help="profile the motor wiring by current draw (default 40%%)")
    args = ap.parse_args(argv)

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)-7s %(message)s",
        datefmt="%H:%M:%S")

    if args.send:
        action = ACTIONS.get(args.send.strip().lower())
        if action is None:
            print(f"unknown action {args.send!r}; use left, right or stop",
                  file=sys.stderr)
            return 2
        return client_send(args.socket, action,
                           max(MIN_SPEED, min(MAX_SPEED, args.speed)))

    if args.selftest is not None:
        return client_selftest(args.socket, max(10, min(100, args.selftest)))

    if args.watch:
        return client_watch(args.socket)

    if args.no_ble and args.no_spp:
        print("nothing to do: both transports are disabled", file=sys.stderr)
        return 2

    bus = Gio.bus_get_sync(Gio.BusType.SYSTEM, None)
    adapter_path = f"/org/bluez/{args.adapter}"

    adapter = Adapter(bus, adapter_path)
    adapter.prepare(args.name)

    hub = CommandBus(args.socket)
    transports = []

    if not args.no_ble:
        ble = BleTransport(bus, adapter_path, args.ble_name, hub)
        ble.register()
        transports.append(ble)

    if not args.no_spp:
        spp = SppTransport(bus, hub, args.pin)
        spp.register()
        transports.append(spp)

    hub.on_state = lambda state: [t.push_state(state) for t in transports]

    loop = GLib.MainLoop()

    def shutdown(*_):
        log.info("shutting down")
        loop.quit()
        return GLib.SOURCE_REMOVE

    GLib.unix_signal_add(GLib.PRIORITY_HIGH, 2, shutdown)   # SIGINT
    GLib.unix_signal_add(GLib.PRIORITY_HIGH, 15, shutdown)  # SIGTERM

    log.info("ready — waiting for phones")
    try:
        loop.run()
    finally:
        for transport in transports:
            transport.unregister()
        hub.close()
        adapter.restore()

    return 0


if __name__ == "__main__":
    sys.exit(main())
