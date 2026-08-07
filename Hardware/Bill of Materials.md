# Bill of Materials

Total build cost: **~$196** excluding the phone and the 3D printer.

Everything here is either verified working on the bench or modeled and
print-ready. Where a part is designed but not yet printed, it says so.

For context on why this number matters: commercial smart canes run
$800–$1,150, AI wearables $2,000–$5,000, and a guide dog around $50,000 with a
multi-year waitlist. Most people with impaired vision live in low- and
middle-income countries where any of those exceeds an annual income. A cane you
can print and assemble for under $200 — reusing a phone the user already owns —
is the point of the project, not a side effect.

---

## Electronics — verified working

| # | Part | Qty | Unit | Notes |
|---|---|---|---|---|
| 1 | **Arduino UNO Q** (Dragonwing QRB2210) | 1 | ~$54 | Quad-core Cortex-A53 + STM32 MCU + Adreno GPU, 4 GB RAM. Runs the cane app, the policy layer, and the Bluetooth daemon. |
| 2 | **Modulino Motors** | 1 | ~$12 | MAX22211 dual H-bridge, Qwiic, I²C `0x48`, ≤3.8 A per channel. Drives the steering wheel. |
| 3 | **Modulino Distance** | 1 | ~$11 | VL53L4CD time-of-flight. Near-field sensing at the cane tip. |
| 4 | **Modulino Vibro** | 1 | ~$9 | Local haptic alert. |
| 5 | **JGA25-370 geared DC motor, 12 V** | 1 | ~$14 | Standard JGA25 form factor with a 4 mm D-shaft. Drives the omni wheel. |
| 6 | **Qwiic / JST-SH 4-pin cables** | 3 | ~$2 ea | Daisy-chain. Bus order is irrelevant — each node has its own address. |
| 7 | **Anker 737 Power Bank** (24 000 mAh, 140 W) | 1 | ~$100 | Powers everything and charges the phone. A dedicated mount is included in the CAD. |
| 8 | **USB-C PD trigger board, 12 V** | 1 | ~$8 | Negotiates 12 V PD from the Anker for the motor's `VM` terminals. |
| 9 | **Hook-up wire, heat-shrink, JST pigtails** | — | ~$5 | Motor leads and power distribution. |

**Electronics subtotal: ~$219** at single-unit retail, or **~$115 without the
power bank** if you already own a USB-C PD bank — any 12 V-capable PD source
works, the mount is just sized for the 737.

### Phone

Developed on a **Galaxy S25 Ultra** (Snapdragon 8 Elite). Not counted in the
total — the design assumes the user already has a phone, which is the reason
this build has no LiDAR line item. Any Snapdragon 8-series device with an NPU
should work; QNN degrades gracefully if the config doesn't match.

---

## 3D-printed parts — designed, print-ready

All models are in [`CAD/`](CAD/) as STL, with renders. The editable source is a
live Onshape document — [open it here](https://cad.onshape.com/documents/c0b905e8d1b94a52d9e9ca97/w/f6521fb190a4be4abaa604ca/e/e523a5a009f76747b4bb6591)
— and the committed STLs are exports of it. Six printed assemblies:

| # | Part | STL | Purpose |
|---|---|---|---|
| 10 | **Omni wheel** (print-in-place) | `Omni Wheel.stl` | Six-roller omni wheel. Rollers print already captured in the hub — no assembly, no bearings, no separate axles. |
| 11 | **Main frame** | `Main.stl` | Lower assembly: carries the motor, wheel, and Distance module at the cane tip. |
| 12 | **Hub mount + cover** | `Hub Mount - Hub Cover.stl` | Clamps the assembly to the cane shaft. |
| 13 | **Electronics housing + cover** | `Mount & Electronics - Electronics Cover.stl` | Skeletonized enclosure for the UNO Q and Modulinos. |
| 14 | **Distance sensor mount** | `Mount & Electronics - Distance Mount.stl` | Aims the ToF sensor forward and down toward the tip's path. |
| 15 | **Phone mount** | `S25 Ultra - Phone Mount.stl` | S25 Ultra cradle on a pivoting hinge; holds the rear cameras facing forward. |
| 16 | **Battery mount** | `Anker 737 Power Bank - Battery Mount.stl` | Anker 737 cradle with the ports left accessible. |

**Filament cost: ~$12–18** for the full set in PLA or PETG (roughly 400–550 g
depending on infill). At a print-service rate, budget $60–90 instead.

> **Print status.** The models are complete and print-ready, but this parts set
> has **not yet been printed and fit-checked** on hardware. Expect to iterate on
> clearances — particularly the print-in-place wheel, where roller clearance is
> printer-dependent. Report what you find.

### Omni wheel provenance

The wheel is based on the open-source print-in-place omni wheel published on
GrabCAD: <https://grabcad.com/library/omni-wheel-print-in-place-1> — adapted here
for the JGA25's 4 mm D-shaft and this cane's tip geometry.

The print-in-place approach is deliberate and it is what makes the cost figure
real. A commercial omni wheel is $15–25 plus a hub adapter, and the reference
research design used a machined wheel-to-hub adapter. Printing the rollers
captive removes the wheel, the adapter, the bearings, and the assembly step in
one move. Verify the license terms on the GrabCAD page before redistributing the
derived geometry.

## Fasteners and shaft

| # | Part | Qty | Unit | Notes |
|---|---|---|---|---|
| 17 | **White cane shaft or 1.25 in PVC** | 1 | ~$10–20 | Cut to user height. A real white cane is preferable — the familiar object is part of why users trust the device. |
| 18 | M3 / M4 bolts, assorted | ~20 | ~$6 | Frame, covers, clamps. |
| 19 | M3 / M4 heat-set inserts | ~12 | ~$5 | Press into the printed parts. |
| 20 | VHB tape | — | ~$4 | Securing the PD trigger board. |

**Fasteners subtotal: ~$25–35**

---

## Cost summary

| Category | Cost |
|---|---|
| Electronics, excluding power bank | ~$115 |
| Anker 737 power bank | ~$100 |
| 3D-printed parts (filament) | ~$15 |
| Shaft and fasteners | ~$30 |
| **Total, with a new power bank** | **~$260** |
| **Total, reusing a USB-C PD bank you own** | **~$160** |
| **Sensing hardware avoided (LiDAR)** | **$0 — the phone camera and NPU replace it** |

Call it **~$196 typical**, and note what dominates: the power bank and the UNO Q.
The perception stack — the part doing metric depth, semantic segmentation, and
object detection — costs nothing beyond a phone the user already carries. That is
the direct consequence of running everything on the Snapdragon NPU instead of
bolting on a laser scanner.

For comparison, the published research design this steering approach builds on
came to roughly $400, with a 2D LiDAR as the single largest line item.

---

## Motor wiring — read before powering up

The wheel motor sits across screw terminals **`1A` + `2A`**. That is one
half-bridge from channel A and one from channel B — *not* a single channel's own
pair. So the sketch drives the two channels in **opposite phase**:

```cpp
motors.setDcSpeedRaw(+raw, -raw);   // one direction
motors.setDcSpeedRaw(-raw, +raw);   // the other
```

Both channels draw current while it spins. This also works unchanged if the motor
is moved to a single channel's pair (`1A`/`1B`), so it is a safe default either
way. If left and right come out reversed, flip the sign in `driveMotor()`.

> **The trap that will cost you an hour.** The Qwiic cable carries **3.3 V logic
> only**. The JGA25 needs **12 V on the `VM` + `GND` screw terminals** from the PD
> trigger board. Without it, the H-bridge accepts every command, reports success
> at every layer, and drives nothing. The yellow `VM` LED is your confirmation.

Measure which terminals actually drive:

```bash
python3 host/qcane_btd.py --selftest        # 40% duty
python3 host/qcane_btd.py --selftest 100    # full power
```

Only a channel with the motor across it draws current. Readings of 0.6–1.3 mA are
the ADC noise floor — nothing is being driven. If every row looks like that while
`mode` still reads `dc`, check the 12 V supply first, then the lead clamping.

---

## What this build does *not* need

- **No LiDAR, no depth camera, no stereo rig.** Metric depth is inferred from a
  single RGB camera on the Hexagon NPU. This is the largest cost and complexity
  reduction in the design.
- **No separate BLE module.** The UNO Q's own radio serves both GATT and SPP.
- **No commercial omni wheel or machined hub adapter.** Both are printed as one
  part.
- **No cloud service** for obstacle avoidance or steering. Street routing is
  optional; everything safety-relevant runs offline.
- **No custom PCB.** Three Modulinos on a Qwiic chain.

## Accessibility notes

Choices made for the user rather than the demo:

- **It stays a white cane.** The familiar form is retained deliberately —
  contact sensing still works if every electronic system fails, and the research
  literature attributes part of the measured confidence gain to keeping the
  white cane as the base.
- **Steering, not beeping.** Feedback is a physical nudge, leaving hearing free.
  Audio is available but never required for obstacle avoidance.
- **Nothing to configure to walk.** Power the board and go; routing is optional.
- **Everything is printable and documented.** No proprietary parts, no vendor
  lock-in, no service contract. If a part breaks, print another.

## Next steps

1. Print the full set and fit-check, particularly print-in-place roller clearance.
2. Move the motor from bench supply to the PD trigger board and re-run
   `--selftest` under load.
3. Measure end-to-end steering latency with the wheel on a real shaft —
   mechanical inertia is not captured by the bench numbers in
   [`../docs/PERFORMANCE.md`](../docs/PERFORMANCE.md).
4. Weigh the assembly. Nothing above accounts for hand fatigue over a long walk.
