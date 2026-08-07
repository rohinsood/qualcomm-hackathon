#!/usr/bin/env python3
"""Auto-accepting BlueZ pairing agent for the Distance Watch board.

The board is headless, so nothing is there to answer a pairing prompt: BlueZ
refuses the request and the phone reports "the device is not in pairing mode".
This registers a NoInputNoOutput ("just works") agent that accepts every
request and marks the peer Trusted, so it can reconnect on its own afterwards.

qhackGPS itself does not need this — it talks to the open NUS peripheral
without bonding — but pairing from the phone's Bluetooth settings does.

Requires only python3-dbus + PyGObject (both preinstalled on the board).
"""

import sys

import dbus
import dbus.mainloop.glib
import dbus.service
from gi.repository import GLib

BLUEZ = "org.bluez"
AGENT_PATH = "/qhack/agent"
AGENT_IFACE = "org.bluez.Agent1"
AGENT_MGR_IFACE = "org.bluez.AgentManager1"
DEVICE_IFACE = "org.bluez.Device1"
PROP_IFACE = "org.freedesktop.DBus.Properties"

# "Just works" pairing: no PIN can be shown or typed on this board.
CAPABILITY = "NoInputNoOutput"


def log(msg):
    print(msg, flush=True)


class Agent(dbus.service.Object):
    def __init__(self, bus, path):
        self.bus = bus
        super().__init__(bus, path)

    def _trust(self, device):
        """A trusted device may reconnect later without asking again."""
        try:
            props = dbus.Interface(self.bus.get_object(BLUEZ, device), PROP_IFACE)
            props.Set(DEVICE_IFACE, "Trusted", dbus.Boolean(True))
        except dbus.exceptions.DBusException as e:
            log(f"could not trust {device}: {e}")

    @dbus.service.method(AGENT_IFACE, in_signature="", out_signature="")
    def Release(self):
        log("agent released")

    @dbus.service.method(AGENT_IFACE, in_signature="os", out_signature="")
    def AuthorizeService(self, device, uuid):
        log(f"authorizing service {uuid} for {device}")
        self._trust(device)

    @dbus.service.method(AGENT_IFACE, in_signature="o", out_signature="s")
    def RequestPinCode(self, device):
        log(f"pin code requested by {device} -> 0000")
        self._trust(device)
        return "0000"

    @dbus.service.method(AGENT_IFACE, in_signature="o", out_signature="u")
    def RequestPasskey(self, device):
        log(f"passkey requested by {device} -> 0")
        self._trust(device)
        return dbus.UInt32(0)

    @dbus.service.method(AGENT_IFACE, in_signature="ouq", out_signature="")
    def DisplayPasskey(self, device, passkey, entered):
        log(f"passkey for {device}: {passkey:06d} ({entered} entered)")

    @dbus.service.method(AGENT_IFACE, in_signature="os", out_signature="")
    def DisplayPinCode(self, device, pincode):
        log(f"pin code for {device}: {pincode}")

    @dbus.service.method(AGENT_IFACE, in_signature="ou", out_signature="")
    def RequestConfirmation(self, device, passkey):
        log(f"confirming passkey {passkey:06d} for {device}")
        self._trust(device)

    @dbus.service.method(AGENT_IFACE, in_signature="o", out_signature="")
    def RequestAuthorization(self, device):
        log(f"authorizing {device}")
        self._trust(device)

    @dbus.service.method(AGENT_IFACE, in_signature="", out_signature="")
    def Cancel(self):
        log("request cancelled by the remote end")


def main():
    dbus.mainloop.glib.DBusGMainLoop(set_as_default=True)
    bus = dbus.SystemBus()
    Agent(bus, AGENT_PATH)
    try:
        mgr = dbus.Interface(bus.get_object(BLUEZ, "/org/bluez"), AGENT_MGR_IFACE)
        mgr.RegisterAgent(AGENT_PATH, CAPABILITY)
        mgr.RequestDefaultAgent(AGENT_PATH)
    except dbus.exceptions.DBusException as e:
        log(f"ERROR: could not register pairing agent: {e}")
        sys.exit(1)
    log(f"pairing agent registered ({CAPABILITY}); accepting all requests")
    GLib.MainLoop().run()


if __name__ == "__main__":
    main()
