#!/usr/bin/env python3
"""BLE bridge for the Distance Watch app (UNO Q).

Runs on the Linux host (the app container has no D-Bus access) and exposes a
Nordic UART Service (NUS) BLE peripheral via BlueZ, so any off-the-shelf BLE
terminal app on a phone can talk to the board:

  phone <--BLE/NUS--> this bridge <--HTTP localhost:7000--> Distance Watch app

Board -> phone (TX notify, ~2 Hz + instantly on presence change):
  {"mm":123,"p":1}        latest distance in mm; p=1 while an object is within
                          the presence threshold; mm=null when nothing in range

Phone -> board (RX write, UTF-8 text):
  <number>   set the presence threshold in mm (e.g. "250"), ack {"thr":250}
  get        full state snapshot as JSON
  <text>     anything else is shown on the web UI as "message from phone"

Requires only python3-dbus + PyGObject (both preinstalled on the board).
"""

import json
import signal
import sys
import time
import urllib.error
import urllib.request

import dbus
import dbus.mainloop.glib
import dbus.service
from gi.repository import GLib

APP_URL = "http://127.0.0.1:7000"
LOCAL_NAME = "Distance Watch"

NUS_SERVICE_UUID = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
NUS_RX_UUID = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"  # phone writes here
NUS_TX_UUID = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"  # board notifies here

POLL_INTERVAL_MS = 250     # how often we read /api/state
PUSH_MIN_INTERVAL_S = 0.5  # steady-state notify rate (presence flips push at once)
BT_HEARTBEAT_S = 5         # /api/bt heartbeat (app zeroes BT status if it stops)
CHUNK = 20                 # fits the minimum BLE ATT payload

BLUEZ = "org.bluez"
OM_IFACE = "org.freedesktop.DBus.ObjectManager"
PROP_IFACE = "org.freedesktop.DBus.Properties"
ADAPTER_IFACE = "org.bluez.Adapter1"
DEVICE_IFACE = "org.bluez.Device1"
GATT_MGR_IFACE = "org.bluez.GattManager1"
ADV_MGR_IFACE = "org.bluez.LEAdvertisingManager1"
SERVICE_IFACE = "org.bluez.GattService1"
CHRC_IFACE = "org.bluez.GattCharacteristic1"
ADV_IFACE = "org.bluez.LEAdvertisement1"


def log(msg):
    print(msg, flush=True)


# --- tiny HTTP helpers -------------------------------------------------------

def http_get_json(path, timeout=0.6):
    try:
        with urllib.request.urlopen(APP_URL + path, timeout=timeout) as r:
            return json.load(r)
    except (urllib.error.URLError, OSError, ValueError):
        return None


def http_post_json(path, payload, timeout=0.6):
    try:
        req = urllib.request.Request(
            APP_URL + path,
            data=json.dumps(payload).encode(),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.load(r)
    except (urllib.error.URLError, OSError, ValueError):
        return None


# --- D-Bus errors BlueZ understands ------------------------------------------

class NotSupportedException(dbus.exceptions.DBusException):
    _dbus_error_name = "org.bluez.Error.NotSupported"


class InvalidArgsException(dbus.exceptions.DBusException):
    _dbus_error_name = "org.freedesktop.DBus.Error.InvalidArgs"


# --- GATT server objects ------------------------------------------------------

class Application(dbus.service.Object):
    PATH = "/qhack/app"

    def __init__(self, bus):
        self.services = []
        super().__init__(bus, self.PATH)

    @dbus.service.method(OM_IFACE, out_signature="a{oa{sa{sv}}}")
    def GetManagedObjects(self):
        resp = {}
        for svc in self.services:
            resp[svc.get_path()] = svc.get_properties()
            for chrc in svc.characteristics:
                resp[chrc.get_path()] = chrc.get_properties()
        return resp


class Service(dbus.service.Object):
    def __init__(self, bus, index, uuid):
        self.path = f"{Application.PATH}/service{index}"
        self.uuid = uuid
        self.characteristics = []
        super().__init__(bus, self.path)

    def get_path(self):
        return dbus.ObjectPath(self.path)

    def get_properties(self):
        return {
            SERVICE_IFACE: {
                "UUID": self.uuid,
                "Primary": dbus.Boolean(True),
                "Characteristics": dbus.Array(
                    [c.get_path() for c in self.characteristics], signature="o"
                ),
            }
        }


class Characteristic(dbus.service.Object):
    def __init__(self, bus, index, uuid, flags, service):
        self.path = f"{service.path}/char{index}"
        self.uuid = uuid
        self.flags = flags
        self.service = service
        self.value = []
        super().__init__(bus, self.path)

    def get_path(self):
        return dbus.ObjectPath(self.path)

    def get_properties(self):
        return {
            CHRC_IFACE: {
                "Service": self.service.get_path(),
                "UUID": self.uuid,
                "Flags": dbus.Array(self.flags, signature="s"),
            }
        }

    @dbus.service.method(CHRC_IFACE, in_signature="a{sv}", out_signature="ay")
    def ReadValue(self, options):
        return dbus.Array(self.value, signature="y")

    @dbus.service.method(CHRC_IFACE, in_signature="aya{sv}")
    def WriteValue(self, value, options):
        raise NotSupportedException()

    @dbus.service.method(CHRC_IFACE)
    def StartNotify(self):
        raise NotSupportedException()

    @dbus.service.method(CHRC_IFACE)
    def StopNotify(self):
        raise NotSupportedException()

    @dbus.service.signal(PROP_IFACE, signature="sa{sv}as")
    def PropertiesChanged(self, interface, changed, invalidated):
        pass


class TxCharacteristic(Characteristic):
    """Board -> phone stream (notify)."""

    def __init__(self, bus, service):
        super().__init__(bus, 0, NUS_TX_UUID, ["read", "notify"], service)
        self.notifying = False

    @dbus.service.method(CHRC_IFACE)
    def StartNotify(self):
        self.notifying = True
        log("phone subscribed to notifications")

    @dbus.service.method(CHRC_IFACE)
    def StopNotify(self):
        self.notifying = False
        log("phone unsubscribed from notifications")

    def send_line(self, text):
        """Send one newline-terminated line, chunked to fit small ATT payloads."""
        data = (text + "\n").encode()
        for i in range(0, len(data), CHUNK):
            chunk = list(data[i : i + CHUNK])
            self.value = chunk
            if self.notifying:
                self.PropertiesChanged(
                    CHRC_IFACE, {"Value": dbus.Array(chunk, signature="y")}, []
                )


class RxCharacteristic(Characteristic):
    """Phone -> board writes."""

    def __init__(self, bus, service, on_text):
        super().__init__(bus, 1, NUS_RX_UUID, ["write", "write-without-response"], service)
        self.on_text = on_text

    @dbus.service.method(CHRC_IFACE, in_signature="aya{sv}")
    def WriteValue(self, value, options):
        text = bytes(value).decode("utf-8", "replace").strip()
        if text:
            self.on_text(text)


class Advertisement(dbus.service.Object):
    PATH = "/qhack/advertisement0"

    def __init__(self, bus):
        super().__init__(bus, self.PATH)

    def get_path(self):
        return dbus.ObjectPath(self.PATH)

    @dbus.service.method(PROP_IFACE, in_signature="s", out_signature="a{sv}")
    def GetAll(self, interface):
        if interface != ADV_IFACE:
            raise InvalidArgsException()
        return {
            "Type": dbus.String("peripheral"),
            "ServiceUUIDs": dbus.Array([NUS_SERVICE_UUID], signature="s"),
            "LocalName": dbus.String(LOCAL_NAME),
        }

    @dbus.service.method(ADV_IFACE)
    def Release(self):
        log("advertisement released by BlueZ")


# --- the bridge ---------------------------------------------------------------

class Bridge:
    def __init__(self, bus):
        self.bus = bus
        self.adapter_path = self._find_adapter()
        if not self.adapter_path:
            log("ERROR: no Bluetooth adapter with GATT support found")
            sys.exit(1)
        log(f"using adapter {self.adapter_path}")

        self.app = Application(bus)
        service = Service(bus, 0, NUS_SERVICE_UUID)
        self.tx = TxCharacteristic(bus, service)
        self.rx = RxCharacteristic(bus, service, self.on_phone_text)
        service.characteristics = [self.tx, self.rx]
        self.app.services = [service]
        self.adv = Advertisement(bus)

        self.advertising = False
        self.connected_devices = {}  # object path -> alias
        self.last_pushed = None      # (mm, present) of the last notify
        self.last_push_t = 0.0
        self.app_down_logged = False

    # -- adapter / registration

    def _find_adapter(self):
        om = dbus.Interface(self.bus.get_object(BLUEZ, "/"), OM_IFACE)
        for path, ifaces in om.GetManagedObjects().items():
            if GATT_MGR_IFACE in ifaces and ADV_MGR_IFACE in ifaces:
                return path
        return None

    def register(self):
        gatt_mgr = dbus.Interface(
            self.bus.get_object(BLUEZ, self.adapter_path), GATT_MGR_IFACE
        )
        adv_mgr = dbus.Interface(
            self.bus.get_object(BLUEZ, self.adapter_path), ADV_MGR_IFACE
        )
        gatt_mgr.RegisterApplication(
            self.app.PATH, {},
            reply_handler=lambda: log("GATT application registered (NUS)"),
            error_handler=self._fatal("RegisterApplication"),
        )
        adv_mgr.RegisterAdvertisement(
            self.adv.get_path(), {},
            reply_handler=self._on_adv_registered,
            error_handler=self._fatal("RegisterAdvertisement"),
        )

    def _fatal(self, what):
        def handler(error):
            log(f"ERROR: {what} failed: {error}")
            sys.exit(1)
        return handler

    def _on_adv_registered(self):
        self.advertising = True
        log(f"advertising as '{LOCAL_NAME}'")
        self.post_bt_status()

    # -- connection tracking

    def watch_connections(self):
        self.bus.add_signal_receiver(
            self._on_device_props,
            dbus_interface=PROP_IFACE,
            signal_name="PropertiesChanged",
            arg0=DEVICE_IFACE,
            path_keyword="path",
        )
        om = dbus.Interface(self.bus.get_object(BLUEZ, "/"), OM_IFACE)
        for path, ifaces in om.GetManagedObjects().items():
            dev = ifaces.get(DEVICE_IFACE)
            if dev and dev.get("Connected"):
                self.connected_devices[path] = str(dev.get("Alias", "?"))

    def _on_device_props(self, iface, changed, invalidated, path=None):
        if "Connected" not in changed or not path.startswith(self.adapter_path):
            return
        if changed["Connected"]:
            alias = "?"
            try:
                props = dbus.Interface(
                    self.bus.get_object(BLUEZ, path), PROP_IFACE
                )
                alias = str(props.Get(DEVICE_IFACE, "Alias"))
            except dbus.exceptions.DBusException:
                pass
            self.connected_devices[path] = alias
            log(f"phone connected: {alias}")
        else:
            alias = self.connected_devices.pop(path, "?")
            log(f"phone disconnected: {alias}")
        self.post_bt_status()

    # -- app I/O

    def post_bt_status(self):
        device = next(iter(self.connected_devices.values()), None)
        http_post_json("/api/bt", {
            "advertising": self.advertising,
            "connected": bool(self.connected_devices),
            "device": device,
        })
        return True  # keep the heartbeat timer alive

    def poll_state(self):
        try:
            self._poll_state()
        except Exception as e:  # never let the timer die
            log(f"poll error: {e}")
        return True

    def _poll_state(self):
        state = http_get_json("/api/state")
        if state is None:
            if not self.app_down_logged:
                log("Distance Watch app unreachable on :7000 (will keep trying)")
                self.app_down_logged = True
            return
        if self.app_down_logged:
            log("Distance Watch app reachable again")
            self.app_down_logged = False

        mm = state.get("mm")
        present = 1 if state.get("present") else 0
        mm_int = None if mm is None else int(round(mm))
        now = time.monotonic()

        presence_flipped = (
            self.last_pushed is not None and self.last_pushed[1] != present
        )
        changed = self.last_pushed != (mm_int, present)
        due = (now - self.last_push_t) >= PUSH_MIN_INTERVAL_S

        if presence_flipped or (changed and due) or self.last_pushed is None:
            mm_json = "null" if mm_int is None else str(mm_int)
            self.tx.send_line(f'{{"mm":{mm_json},"p":{present}}}')
            self.last_pushed = (mm_int, present)
            self.last_push_t = now

    # -- phone -> board

    def on_phone_text(self, text):
        log(f"phone wrote: {text!r}")
        try:
            threshold = float(text)
        except ValueError:
            threshold = None

        if threshold is not None:
            resp = http_post_json("/api/threshold", {"mm": threshold})
            if resp and "threshold_mm" in resp:
                self.tx.send_line(f'{{"thr":{resp["threshold_mm"]:.0f}}}')
            else:
                self.tx.send_line('{"err":"app unreachable"}')
        elif text.lower() == "get":
            state = http_get_json("/api/state")
            if state is None:
                self.tx.send_line('{"err":"app unreachable"}')
            else:
                keep = ("mm", "present", "threshold_mm", "sensor_ok", "hz", "phone_msg")
                self.tx.send_line(json.dumps(
                    {k: state.get(k) for k in keep}, separators=(",", ":")
                ))
        else:
            resp = http_post_json("/api/phone", {"text": text})
            self.tx.send_line('{"ok":1}' if resp else '{"err":"app unreachable"}')


def main():
    dbus.mainloop.glib.DBusGMainLoop(set_as_default=True)
    bus = dbus.SystemBus()

    bridge = Bridge(bus)
    bridge.register()
    bridge.watch_connections()

    GLib.timeout_add(POLL_INTERVAL_MS, bridge.poll_state)
    GLib.timeout_add_seconds(BT_HEARTBEAT_S, bridge.post_bt_status)

    loop = GLib.MainLoop()

    def shutdown(*_):
        log("shutting down")
        bridge.advertising = False
        bridge.connected_devices.clear()
        bridge.post_bt_status()
        loop.quit()
        return False

    GLib.unix_signal_add(GLib.PRIORITY_DEFAULT, signal.SIGTERM, shutdown)
    GLib.unix_signal_add(GLib.PRIORITY_DEFAULT, signal.SIGINT, shutdown)

    log("BLE bridge running")
    loop.run()


if __name__ == "__main__":
    main()
