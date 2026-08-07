# 🦯 qcane-wheel

Spins a wheel motor left or right from a Bluetooth command. The motor is a
**Modulino Motors** board on the Qwiic bus; the 13×8 LED matrix mirrors what it
is doing — a wheel spinning clockwise, counter-clockwise, or sitting still,
with a bar on the side it is turning towards.

**This is now the merged cane app** (absorbed the former `distance-watch`):
on top of the wheel it reads the **Modulino Distance** sensor, pulses the
**Modulino Vibro** while an object is inside the presence threshold, streams
telemetry to the **QCane Link dashboard** on port 7000 (per-module cards,
live current/voltage graphs, manual spin + buzz buttons), and stops every
output within 2 s if the Linux side goes quiet (failsafe). See
[`../README.md`](../README.md) for the full-system picture; everything below
documents the Bluetooth contract, which is unchanged.

Two Bluetooth transports are exposed at the same time — pick whichever is
easier on the phone side.

---

## Bluetooth contract

Board identity:

| | |
|---|---|
| Bluetooth name (pairing / SPP) | `QCane-Wheel` |
| BLE advertised name | `QCane` (short, so it fits beside the 128-bit UUID) |
| Adapter address | `14:B5:CD:EA:B7:99` |

### Commands

Case-insensitive, and the parser is deliberately forgiving. All of these mean
the same thing:

```
left            LEFT            l           turn_left       turn-left
right           RIGHT           r           turn_right      turn-right
stop            STOP            s           halt            brake
```

An optional speed of `1`–`5` may follow, separated by `:`, `=`, `,` or a
space — it is accepted for compatibility and **ignored**: every spin runs
at full scale (100 % duty). JSON works too:

```
left            left:5          left 5          {"action":"left","speed":5}
```

Unknown words are rejected and logged.

### Option A — BLE GATT (no pairing needed)

| | |
|---|---|
| Service | `bcf2f193-f22b-4695-af5e-fd3b9caf4977` |
| Command characteristic | `bcf2f194-f22b-4695-af5e-fd3b9caf4977` — `write`, `write-without-response` |
| State characteristic | `bcf2f195-f22b-4695-af5e-fd3b9caf4977` — `read`, `notify` |

Write the command as plain UTF-8 bytes. The state characteristic reads back
`"<action>:<speed>"` (e.g. `left:5`) and notifies on every change — note this
reflects what the **MCU actually applied**, not merely what was requested.

Scan by service UUID rather than by name; the name is only advertised as a
convenience.

```kotlin
val SERVICE = UUID.fromString("bcf2f193-f22b-4695-af5e-fd3b9caf4977")
val CMD     = UUID.fromString("bcf2f194-f22b-4695-af5e-fd3b9caf4977")
val STATE   = UUID.fromString("bcf2f195-f22b-4695-af5e-fd3b9caf4977")

// Scan with ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build(),
// connectGatt(...), discoverServices(), then:
val chr = gatt.getService(SERVICE).getCharacteristic(CMD)

// Android 13+
gatt.writeCharacteristic(chr, "left".toByteArray(), WRITE_TYPE_DEFAULT)

// Android 12 and older
chr.value = "left".toByteArray()
gatt.writeCharacteristic(chr)
```

### Option B — Classic SPP / RFCOMM (simplest phone code)

| | |
|---|---|
| Service UUID | `00001101-0000-1000-8000-00805f9b34fb` (standard SPP) |
| Channel | assigned by BlueZ, found via SDP |

Pair with `QCane-Wheel` once from Android's Bluetooth settings — the board runs
a `NoInputNoOutput` agent that auto-accepts, so there is no PIN prompt. Then
it is an ordinary socket. Send one command per line.

```kotlin
val SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
val device = adapter.bondedDevices.first { it.name == "QCane-Wheel" }
val socket = device.createRfcommSocketToServiceRecord(SPP)
socket.connect()
socket.outputStream.write("left\n".toByteArray())
```

State comes back on the same socket as `"<action>:<speed>\n"`.

### Android permissions

`BLUETOOTH_CONNECT` for both transports, plus `BLUETOOTH_SCAN` for BLE
(Android 12+). On Android 11 and older, BLE scanning also needs
`ACCESS_FINE_LOCATION`. All are runtime permissions.

---

## How it fits together

```
Phone  ──BLE GATT write──┐
       ──SPP  RFCOMM  ───┤
                         ▼
   host/qcane_btd.py            Linux side, runs on the host (not in Docker)
     owns BlueZ over D-Bus
                         │  Unix socket  .run/qcane-bt.sock
                         ▼
   python/main.py               Linux side, runs in the app container
     Bridge.call("set_wheel", dir, speed)
                         │  Router Bridge
                         ▼
   sketch/sketch.ino            MCU — drives the motor, mirrors it on the matrix
     Bridge.notify("wheel_applied", dir, speed, motorReady)  ──► up to the phone
                         │  Qwiic / Wire1, I²C 0x48
                         ▼
   Modulino Motors              MAX22211 dual H-bridge
```

**Why the daemon is separate.** The app's Python runs in a container on a
Docker bridge network with no `/run/dbus` mount, where `AF_BLUETOOTH` fails
with `EAFNOSUPPORT`. It cannot reach BlueZ at all, and there is no Bluetooth
brick. So the radio is driven from the host and the two halves meet on a Unix
socket inside the app folder, which is already bind-mounted into the container
at `/app`. The daemon needs no root and no pip packages — BlueZ is driven over
D-Bus through the system `gi` (PyGObject) install.

Keeping Bluetooth in a long-lived host process also means a connected phone
survives `arduino-app-cli app restart` while you iterate on the app.

---

## Running it

The Arduino App is managed normally:

```bash
arduino-app-cli app start   ~/ArduinoApps/qcane-wheel
arduino-app-cli app logs    ~/ArduinoApps/qcane-wheel --follow
```

The Bluetooth daemon is a separate host process:

```bash
python3 ~/ArduinoApps/qcane-wheel/host/qcane_btd.py          # foreground
python3 ~/ArduinoApps/qcane-wheel/host/qcane_btd.py -v       # with debug logs
```

To start it automatically, install the bundled **user** service (no root):

```bash
mkdir -p ~/.config/systemd/user
cp ~/ArduinoApps/qcane-wheel/host/qcane-btd.service ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now qcane-btd
loginctl enable-linger arduino        # so it survives logout / starts at boot
```

Useful flags: `--no-ble`, `--no-spp`, `--name`, `--ble-name`, `--adapter`.

While it runs, the adapter is made discoverable and renamed to `QCane-Wheel`;
both are restored when the daemon exits.

## Testing without a phone

The daemon can inject commands into itself, which exercises the whole chain
down to the MCU and waits for the sketch's acknowledgement:

```bash
cd ~/ArduinoApps/qcane-wheel
python3 host/qcane_btd.py --send left --speed 5
python3 host/qcane_btd.py --send right
python3 host/qcane_btd.py --send stop
python3 host/qcane_btd.py --watch          # live view of commands and state
python3 host/qcane_btd.py --selftest       # profile the motor wiring
```

`--send` prints `left (speed 5) acknowledged by the app` only once the MCU has
reported back, so a success there means the full path worked.

MCU serial output goes to `arduino-app-cli monitor`.

---

## The motor

**Modulino Motors** — MAX22211 dual H-bridge, Qwiic bus (`Wire1` on UNO Q),
I²C address `0x48`, up to 3.8 A per channel. Needs the `Arduino_Modulino`
library at **0.9.0 or newer**; the 0.7.0 bundled with the core has no
`ModulinoMotors` class. A sketch profile does not resolve transitive
dependencies, so `sketch/sketch.yaml` lists the whole sensor-driver set that
`Modulino.h` pulls in — dropping any of them breaks the build.

### Power

The Qwiic cable only carries 3.3 V logic. **The motor needs its own 5–24 V
supply on the Modulino's power terminals**, or the H-bridge will accept every
command and drive nothing.

### Wiring

The wheel is across terminals **1A** and **2A** — one half-bridge from channel
A and one from channel B, rather than a single channel's own pair. So the two
channels are driven in opposite phase:

```cpp
motors.setDcSpeedRaw(+raw, -raw);   // one way
motors.setDcSpeedRaw(-raw, +raw);   // the other
```

This also works unchanged if the motor is moved onto a single channel's pair
(1A/1B), so it is a safe default either way.

There is no speed table any more — every spin is pinned to 100 % duty in the
sketch (speed arguments on the wire are ignored). If left and right come out
swapped, flip the sign in `applyMotor()`.

`driveMotor()` is called from `loop()` on every change, never from the Bridge
handler: that runs mid-RPC on the bridge thread, where blocking, I²C traffic
and logging are all unsafe.

### Checking the wiring

`--selftest` drives each channel pair in turn and reports the current each one
draws, which identifies the terminals the motor is actually on — only a channel
with the motor across it draws current.

```bash
python3 host/qcane_btd.py --selftest        # 40% duty
python3 host/qcane_btd.py --selftest 100    # full power
```

```
stage                   channel A    channel B  mode
idle                       0.6 mA       1.3 mA  dc
A+ only (1A/1B)            1.3 mA       1.3 mA  dc
...
```

The `mode` column reads back from the module, so `dc` confirms it is receiving
commands. Readings of 0.6–1.3 mA are the ADC noise floor — i.e. no current at
all. If every row looks like that while `mode` still says `dc`, the module is
listening but nothing is being driven: check the 5–24 V supply first, then that
the motor leads are properly clamped in the screw terminals.

If the Modulino does not answer on the bus at all, the app logs a warning on
every command and `--selftest` reports `no modulino found`.

## Layout

```
qcane-wheel/
├── app.yaml                 no bricks — Bluetooth is not one, and could not run here anyway
├── python/main.py           socket -> Router Bridge
├── sketch/sketch.ino        Modulino Motors drive + LED matrix status
├── sketch/sketch.yaml       pinned libraries (Modulino 0.9.0 + its deps)
├── host/qcane_btd.py        BLE GATT + Classic SPP, driven over BlueZ D-Bus
├── host/qcane-btd.service   optional systemd user unit
└── .run/                    runtime socket (gitignored)
```
