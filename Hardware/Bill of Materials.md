# Bill of Materials

Two halves: the **electronics that exist and are verified working** on the bench,
and the **mechanical assembly** needed to mount them on a real cane.

> **Status honesty.** Everything in "Verified electronics" has been wired,
> flashed, and driven end-to-end from the phone. Everything marked
> **`[NOT YET BUILT]`** is the mounting hardware — specified from the reference
> research design but not yet fabricated or purchased for this build. Do not
> read this table as a photograph of a finished cane.

## Verified electronics

| # | Part | Qty | Notes |
|---|---|---|---|
| 1 | **Arduino UNO Q** (Dragonwing QRB2210) | 1 | Quad-core Cortex-A + STM32 MCU + Adreno GPU, 4 GB RAM. Runs the cane app and the Bluetooth daemon. |
| 2 | **Modulino Motors** | 1 | MAX22211 dual H-bridge, Qwiic, I²C `0x48`. Drives the steering wheel. Needs `Arduino_Modulino` ≥ 0.9.0 — the 0.7.0 bundled with the core has no `ModulinoMotors` class. |
| 3 | **Modulino Distance** | 1 | VL53L4CD time-of-flight. Near-field obstacle sense at the cane tip. |
| 4 | **Modulino Vibro** | 1 | Local haptic alert. |
| 5 | **Qwiic cables** | 3 | Daisy-chain. Order on the bus is irrelevant — each node has its own address. |
| 6 | **5 V motor supply** | 1 | Into the Modulino Motors `VM` + `GND` screw terminals. **The Qwiic cable only carries 3.3 V logic** — without a separate supply the H-bridge accepts every command and drives nothing. The yellow `VM` LED confirms power. |
| 7 | **Android phone, Snapdragon 8 Elite** | 1 | Developed on Galaxy S25 Ultra (Android 15). Supplies camera, GPS, compass, gravity vector, and all AI compute. |

### Motor wiring — read this before powering up

The wheel motor sits across screw terminals **`1A` + `2A`**. That is one
half-bridge from channel A and one from channel B — *not* a single channel's own
pair. The sketch therefore drives the two channels in **opposite phase**:

```cpp
motors.setDcSpeedRaw(+raw, -raw);   // one direction
motors.setDcSpeedRaw(-raw, +raw);   // the other
```

Both channels show current draw while it spins. This also works unchanged if the
motor is moved onto a single channel's pair (`1A`/`1B`), so it is a safe default
either way. If left and right come out swapped, flip the sign in `driveMotor()`.

Unsure how yours is wired? Measure it:

```bash
python3 host/qcane_btd.py --selftest        # 40% duty
python3 host/qcane_btd.py --selftest 100    # full power
```

Only a channel with the motor across it draws current. Readings of 0.6–1.3 mA
are the ADC noise floor — i.e. nothing is being driven. If every row looks like
that while `mode` still reads `dc`, the module is listening but not driving:
check the 5 V supply first, then that the leads are properly clamped.

## Mechanical assembly `[NOT YET BUILT]`

Specified from the reference design ([Slade et al. 2021](https://doi.org/10.1126/scirobotics.abg6594),
[pslade2/AugmentedCane](https://github.com/pslade2/AugmentedCane)). Quantities
and dimensions are starting points, not a validated parts list.

| # | Part | Qty | Notes |
|---|---|---|---|
| 8 | Omni wheel, ~3.25 in | 1 | Lateral force without resisting forward roll. The omni is what makes steering feel like a nudge rather than a drag. |
| 9 | Geared DC motor | 1 | Reference design used a GoBilda 5203 series, 312 RPM. Must match the Modulino Motors current ceiling (3.8 A/channel). |
| 10 | Wheel-to-motor-hub adapter | 1 | Bore-specific to the wheel and motor chosen. |
| 11 | White cane / PVC shaft, 1.25 in OD | 1 | Cut to user height. |
| 12 | Motor mount + clamp | 1 set | Clamps the motor assembly to the shaft bottom. |
| 13 | Handle enclosure | 1 | Houses the UNO Q, Modulinos, and battery. |
| 14 | Phone mount | 1 | Must hold the camera **facing forward** at a stable pitch — the depth pipeline self-calibrates its ground plane but cannot recover from a camera pointed at the sky. |
| 15 | Battery pack + BMS | 1 | Sized for motor draw plus the UNO Q. The reference design used 3S LiPo with a 12 V→5 V step-down that also charged the phone. |
| 16 | M4 heat-set inserts, M4 bolts | as needed | For the printed parts. |

## Estimated cost

| Item | Cost |
|---|---|
| Arduino UNO Q | ~$45 |
| Modulino Motors + Distance + Vibro | ~$30 |
| Qwiic cables, 5 V supply | ~$10 |
| Motor + omni wheel + adapter `[NOT YET BUILT]` | ~$45 |
| Shaft, printed parts, fasteners, battery `[NOT YET BUILT]` | ~$40 |
| **Total, excluding phone** | **~$170** |

Excludes the phone, which most users already own. Notably absent: a LiDAR unit.
Replacing the laser scanner with monocular metric depth on the NPU is what
removes the single most expensive sensing component from the research design.

## What is *not* needed

- **No LiDAR or depth camera.** Metric depth is inferred from the RGB camera on
  the NPU.
- **No ESP32 or separate BLE module.** The UNO Q's own radio serves both the
  GATT and SPP transports via a host-side BlueZ daemon.
- **No cloud service** for obstacle avoidance or steering.

## Next steps for this build

1. Fabricate the mount and clamp; verify the phone holds a stable forward pitch.
2. Move the wheel from bench supply to battery and re-run `--selftest` under load.
3. Re-measure steering latency end-to-end once the wheel is on a real shaft —
   mechanical inertia is not captured by the bench numbers in
   [`docs/PERFORMANCE.md`](../docs/PERFORMANCE.md).
