# Assembly Instructions

Three stages. **Stage 1 (electronics) is verified working** and needs no
mechanical assembly at all — do it first on a desk. Stages 2 and 3 print and
assemble the cane.

Parts and costs: [`Bill of Materials.md`](Bill%20of%20Materials.md)

![Full assembly](CAD/Assembly.png)

*Complete assembly — phone mount at the top, battery and electronics housings
mid-shaft, drive assembly with the omni wheel at the tip.*

---

## Stage 1 — Electronics bring-up (verified)

Complete this entire stage with the parts loose on a desk before committing to a
print. If the wheel spins from a phone command here, the hard part is done.

### 1.1 Chain the Modulinos

```
UNO Q ──▶ Modulino Motors ──▶ Modulino Vibro ──▶ Modulino Distance
```

Order doesn't matter on I²C — each node answers on its own address. On the UNO Q
the Qwiic connector is `Wire1`.

### 1.2 Power the motor from 12 V

Set the USB-C PD trigger board to **12 V**, feed it from the power bank, and wire
its output to the Modulino Motors **`VM` + `GND`** screw terminals.

> The Qwiic cable is **3.3 V logic only**. Skip this and the H-bridge will accept
> every command and drive nothing — a confusing failure, because every software
> layer reports success. The yellow `VM` LED confirms power.

### 1.3 Wire the JGA25 across `1A` + `2A`

See the [wiring note in the BOM](Bill%20of%20Materials.md#motor-wiring--read-before-powering-up).
`1A` and `2A` are one half-bridge from each channel, which is why the sketch
drives them in opposite phase.

### 1.4 Flash and start the board app

```bash
arduino-app-cli app start ~/lighthouse/board/qcane-wheel
arduino-app-cli app logs  ~/lighthouse/board/qcane-wheel --follow
```

Open `http://<board-ip>:7000` — the QCane Link dashboard. You should see live
distance readings, motor telemetry with current and voltage graphs, and vibro
state.

### 1.5 Verify the full chain without a phone

The Bluetooth daemon can inject commands into itself, exercising everything down
to the MCU and waiting for the sketch's acknowledgement:

```bash
cd ~/lighthouse/board/qcane-wheel
python3 host/qcane_btd.py --send left --speed 5
python3 host/qcane_btd.py --send right
python3 host/qcane_btd.py --send stop
python3 host/qcane_btd.py --watch       # live command + state view
python3 host/qcane_btd.py --selftest    # profile the motor wiring
```

`--send` prints `left (speed 5) acknowledged by the app` **only after the MCU
reports back**, so success there means the whole path worked.

### 1.6 Install the Bluetooth daemon as a service

```bash
mkdir -p ~/.config/systemd/user
cp ~/lighthouse/board/qcane-wheel/host/qcane-btd.service ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now qcane-btd
loginctl enable-linger arduino     # survives logout, starts at boot
```

The unit must point at the **same app folder the app runs from** — the Unix socket
lives in that folder's `.run/`.

Why the daemon runs on the host rather than in the app: the app's Python executes
in a container with no `/run/dbus` mount, where `AF_BLUETOOTH` fails outright. It
cannot reach BlueZ at all. So the radio is driven host-side and the two halves
meet on a Unix socket inside the app folder, which is already bind-mounted in. A
useful side effect: a connected phone survives `arduino-app-cli app restart`
while you iterate.

### 1.7 Connect the phone

Install the app ([`../docs/SETUP.md`](../docs/SETUP.md)) and power the board. The
phone scans by **GATT service UUID**, not by name, and connects within seconds. No
pairing needed.

Protocol reference: [`../docs/BOARD.md`](../docs/BOARD.md)

---

## Stage 2 — Print the parts

All STLs are in [`CAD/`](CAD/). Six assemblies, ~400–550 g of filament total.

| Part | File | Notes |
|---|---|---|
| Omni wheel | `Omni Wheel.stl` | **Print-in-place** — see below |
| Main frame | `Main.stl` | Largest part; carries motor, wheel, ToF |
| Hub mount + cover | `Hub Mount - Hub Cover.stl` | Shaft clamp |
| Electronics housing + cover | `Mount & Electronics - Electronics Cover.stl` | Skeletonized |
| Distance mount | `Mount & Electronics - Distance Mount.stl` | ToF aiming bracket |
| Phone mount | `S25 Ultra - Phone Mount.stl` | Sized for S25 Ultra |
| Battery mount | `Anker 737 Power Bank - Battery Mount.stl` | Sized for Anker 737 |

**Suggested settings.** PETG preferred for the structural parts (frame, hub
mount, phone mount) — it survives being dropped and flexed better than PLA, and a
cane gets both. PLA is fine for the covers. 0.2 mm layers, 4 perimeters, 25–30%
infill on structural parts.

> **These parts have not yet been printed and fit-checked.** Expect a clearance
> iteration, especially on the wheel. If you print them, please report what you
> had to change.

### The print-in-place omni wheel

![Omni wheel](CAD/Omni%20Wheel.png)

*Six-roller print-in-place omni wheel, rollers captive in the hub.*

Based on the open-source design at
<https://grabcad.com/library/omni-wheel-print-in-place-1>, adapted for the JGA25's
4 mm D-shaft.

The rollers print **already captured** in the hub — no bearings, no axles, no
assembly. That removes a commercial omni wheel ($15–25), a machined hub adapter,
and an assembly step from the build.

Print it **flat, no supports, and slow** — 30–40 mm/s. The roller clearances are
the tightest tolerance in the whole project and they are printer-dependent. When
it comes off the bed, the rollers will be fused; work each one free by hand until
all six spin. If they won't free up, scale roller clearance up by 0.05 mm and
reprint. Getting this part right is what makes the steering feel like a nudge
rather than a drag, so it's worth a second attempt.

Check the license terms on the GrabCAD page before redistributing derived
geometry.

---

## Stage 3 — Assemble

### 3.1 Drive assembly at the tip

![Hub mount](CAD/Hub%20Mount.png)

Mount the JGA25 in the main frame, press the omni wheel onto the 4 mm D-shaft,
and clamp the frame to the bottom of the shaft with the hub mount and cover.

Orient the wheel so its **roll axis is fore-aft**. That is the entire principle:
the wheel resists *lateral* motion — the force the user feels as steering — while
rolling freely forward, so the cane never drags.

This joint takes the full steering reaction torque. It is the most likely thing to
work loose in field testing; check it before every session.

### 3.2 Distance sensor

Fit the Modulino Distance into its bracket on the main frame, aimed **forward and
slightly down**, toward where the tip is about to be. This catches what the phone
camera cannot see — low obstacles inside the camera's near blind spot.

### 3.3 Electronics housing

![Electronics mount](CAD/Electronics%20Mount.png)

*Skeletonized housing with the UNO Q and Modulino stack.*

Seat the UNO Q and the Modulino chain in the housing and close the cover. Route
the Qwiic chain so no cable crosses a clamp face. The skeletonization is for
airflow as much as weight — the UNO Q runs warm under sustained load.

### 3.4 Battery

![Battery mount](CAD/Battery%20Mount.png)

Clip the Anker 737 into its cradle mid-shaft. Ports stay accessible so you can
charge without disassembly, and so the phone can charge from the same bank on a
long walk.

Mount it **opposite the electronics housing** if you can, to balance the shaft.
An unbalanced cane is tiring in a way that doesn't show up in a ten-minute test.

### 3.5 Phone mount

![Phone mount](CAD/Phone%20Mount.png)

*S25 Ultra cradle on a pivoting hinge — rear cameras face forward.*

Clamp the mount high on the shaft and seat the phone with the **rear cameras
facing forward** along the direction of travel, roughly chest height. Use the
hinge to set pitch, then tighten.

The perception stack tolerates a fair amount of mounting slop: the grid
self-calibrates camera height from a 12th-percentile ground estimate and reads
pitch and roll live from the gravity sensor. What it cannot recover from is a
camera seeing mostly sky or mostly ground, or a mount loose enough to let the
phone swing — the ground latch will chase the motion. Watch `ground=` in the
telemetry; it should settle near 0.

### 3.6 Power-up order

1. Power bank on, PD trigger board showing 12 V
2. UNO Q boots, board app starts (dashboard reachable)
3. Launch the phone app, confirm the cane link connects
4. `--send left` or the dashboard buttons to confirm the wheel drives
5. Walk

---

## Field-test protocol

**Always test with a sighted companion.** Suggested progression:

1. **Static** — obstacle 1.5 m ahead. The wheel should pick a side and commit,
   not oscillate.
2. **Corridor** — walk a clear hallway. Near-neutral. If it hunts, check camera
   pitch and the ground latch.
3. **Single obstacle** — a box on a clear sidewalk. Expect a smooth deviation and
   a return to the original heading. Transitions log as `deviate` / `return`.
4. **Off-path obstacle** — place one *beside* the route. Steering should ignore it
   completely; that's the path-first planner working.
5. **Dead end** — walk at a wall. Expect **STOP** (`X`), not a guess.
6. **Route following** — set an outdoor destination and confirm the wheel biases
   toward the route.

Live telemetry:

```bash
adb logcat -s ShepherdTime
```

Reproducible latency measurement: [`../docs/PERFORMANCE.md`](../docs/PERFORMANCE.md)

Known defects, including two that affect behavior:
[`../docs/KNOWN_ISSUES.md`](../docs/KNOWN_ISSUES.md)
