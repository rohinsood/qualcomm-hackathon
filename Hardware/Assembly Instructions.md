# Assembly Instructions

Two stages: **bring up the electronics** (verified — do this first, it works on a
bench with no mechanical assembly at all), then **mount it on a cane**
(not yet fabricated for this build).

Parts list: [`Bill of Materials.md`](Bill%20of%20Materials.md).

---

## Stage 1 — Electronics bring-up (verified)

You can complete this entire stage with the parts loose on a desk. Do that
before committing to any mechanical build.

### 1.1 Wire the Qwiic chain

```
UNO Q ──▶ Modulino Motors ──▶ Modulino Vibro ──▶ Modulino Distance
```

Chain order does not matter on I²C — each node answers on its own address. On
the UNO Q the Qwiic connector is `Wire1`.

### 1.2 Power the motor separately

Connect a **5 V supply** to the Modulino Motors `VM` + `GND` screw terminals.

> The Qwiic cable carries 3.3 V logic only. Skip this and the H-bridge will
> acknowledge every command and drive nothing — a confusing failure, because the
> software all reports success. The yellow `VM` LED confirms power.

### 1.3 Attach the motor across `1A` + `2A`

See the wiring note in [`Bill of Materials.md`](Bill%20of%20Materials.md#motor-wiring--read-this-before-powering-up).
Terminals `1A` and `2A` are one half-bridge from each channel, which is why the
sketch drives them in opposite phase.

### 1.4 Flash and start the board app

```bash
arduino-app-cli app start ~/lighthouse/board/qcane-wheel
arduino-app-cli app logs  ~/lighthouse/board/qcane-wheel --follow
```

Open `http://<board-ip>:7000` — the QCane Link dashboard. You should see live
distance readings, motor telemetry with current/voltage graphs, and vibro state.

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

`--send` prints `left (speed 5) acknowledged by the app` **only once the MCU has
reported back**, so a success there means the whole path worked. If the wheel
spins on `--send left`, the electronics are done.

### 1.6 Install the Bluetooth daemon as a service

```bash
mkdir -p ~/.config/systemd/user
cp ~/lighthouse/board/qcane-wheel/host/qcane-btd.service ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now qcane-btd
loginctl enable-linger arduino     # survives logout, starts at boot
```

The unit must point at the **same app folder the app runs from** — the Unix
socket lives in that folder's `.run/`.

Why the daemon is a separate host process: the app's Python runs in a container
with no `/run/dbus` mount, where `AF_BLUETOOTH` fails outright. It cannot reach
BlueZ at all. So the radio is driven from the host and the two halves meet on a
Unix socket inside the app folder, which is already bind-mounted into the
container. A side benefit: a connected phone survives
`arduino-app-cli app restart` while you iterate.

### 1.7 Connect the phone

Install the app (see [`docs/SETUP.md`](../docs/SETUP.md)) and power the board.
The phone scans by **GATT service UUID**, not by name, and connects within
seconds. No pairing required for the BLE path.

Protocol reference: [`docs/BOARD.md`](../docs/BOARD.md).

---

## Stage 2 — Mechanical assembly `[NOT YET BUILT]`

> **This stage has not been fabricated for this build.** What follows is the
> design intent, derived from the reference research design. Treat it as a plan
> to validate, not instructions known to produce a working cane.
>
> CAD files: see [`CAD/README.md`](CAD/README.md) — the directory is currently a
> placeholder. The upstream research design publishes STLs and solder-at-home
> instructions and is the best starting point:
> [pslade2/AugmentedCane](https://github.com/pslade2/AugmentedCane).

### 2.1 Motor and wheel at the tip

Mount the geared motor so the **omni wheel contacts the ground** at the cane's
bottom, with its roll axis aligned fore-aft. The omni is essential: it resists
lateral motion (that is the steering force the user feels) while rolling freely
forward, so the cane never feels like it is dragging.

### 2.2 Clamp to the shaft

Clamp the motor assembly to the bottom of the 1.25 in shaft. This joint takes
the full steering reaction torque — it is the most likely thing to work loose in
field testing. Check it before every session.

### 2.3 Handle enclosure

House the UNO Q, the three Modulinos, and the battery in the handle. Route the
Qwiic chain so the **Distance module has clear line of sight forward and down**
toward where the tip is about to be.

### 2.4 Phone mount — the one that matters for accuracy

Mount the phone with the **rear camera facing forward** along the direction of
travel, at a stable pitch, roughly chest height.

The grid self-calibrates for camera height (a 12th-percentile ground estimate,
EMA-smoothed) and reads pitch and roll live from the gravity sensor, so it
tolerates a fair amount of mounting slop and hand movement. What it cannot
recover from is a camera that sees mostly sky or mostly ground, or a mount that
lets the phone swing freely — the ground latch will chase the motion.

### 2.5 Power

Battery + BMS in the handle, feeding the motor's `VM` terminals and the UNO Q.
The reference design used 3S LiPo with a 12 V→5 V step-down that also charged the
phone; that is a good pattern, since the phone is doing all the AI work and is
the first thing to run flat.

---

## Field-test protocol

Once assembled, **always test with a sighted companion.** Suggested progression:

1. **Static** — hold the cane, put an obstacle 1.5 m ahead. The wheel should
   pick a side and commit, not oscillate.
2. **Corridor** — walk a clear hallway. The wheel should stay near-neutral; if it
   hunts, the ground latch or pitch is off.
3. **Single obstacle** — a box on a clear sidewalk. Expect a smooth deviation and
   a return to the original heading after passing. The planner logs
   `deviate`/`return` transitions — watch them.
4. **Dead end** — walk at a wall. Expect a **STOP** (`X`), not a guess.
5. **Route following** — set an outdoor destination and confirm the wheel biases
   toward the route while ignoring obstacles that are off the path.

Reproducible latency measurement: [`docs/PERFORMANCE.md`](../docs/PERFORMANCE.md).
