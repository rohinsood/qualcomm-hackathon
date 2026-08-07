# Lighthouse

**A self-steering white cane that runs entirely on Qualcomm silicon.**

Lighthouse guides a person with impaired vision by *physically steering them* —
a motorized wheel at the cane tip nudges left or right around obstacles and
along a walking route. All perception runs on-device: metric depth estimation,
walkability segmentation, and object detection execute on the Snapdragon 8
Elite's Hexagon NPU and Adreno GPU. Obstacle avoidance has **no network
dependency at all**.

Built for the **Snapdragon Multiverse Hackathon 2026** (Qualcomm San Diego).

---

## Team

| Name | Email |
|---|---|
| _TODO: replace before submitting_ | _TODO_ |
| _TODO_ | _TODO_ |

> **Submission checklist — remove this block before you submit.**
> - [ ] Fill in the team table above (names + emails are a hard requirement)
> - [ ] Every team member has submitted the feedback form
> - [ ] Repository is pushed to a **personal** GitHub account and is public
> - [ ] Submit the repo link via the Microsoft Form by **12:00 PM, Friday Aug 7**

---

## Why steer instead of beep

Most electronic travel aids tell you an obstacle exists — a beep, a buzz, a
spoken warning — and leave the interpretation to you. That costs time and
attention at exactly the wrong moment.

The approach Lighthouse builds on comes from Stanford's Augmented Cane research:

> P. Slade, A. Tambe, M. J. Kochenderfer, **"Multimodal sensing and intuitive
> steering assistance improve navigation and mobility for people with impaired
> vision,"** *Science Robotics* **6**(59), eabg6594 (2021).
> [doi:10.1126/scirobotics.abg6594](https://doi.org/10.1126/scirobotics.abg6594)
> · [reference implementation](https://github.com/pslade2/AugmentedCane)

That work tested a white cane augmented with a motorized omni wheel against a
standard white cane, with both blindfolded sighted participants and
participants with impaired vision. The finding that matters here: **grounded
kinesthetic feedback** — steering the user's hand through the cane itself —
guided people *more accurately and with lower cognitive load* than vibrotactile
or audio feedback. Measured against a white cane, it increased walking speed by
**18 ± 7%** for participants with impaired vision and **35 ± 12%** for
blindfolded sighted participants. The authors attribute the gain to accurate
steering, reduced cognitive load, fewer environmental contacts, and higher user
confidence.

The paper's sensing stack was a LiDAR unit plus GPS and an IMU, with compute in
the handle. **Lighthouse replaces that with a phone.** A Galaxy S25 Ultra
supplies the camera, GPS, compass, and gravity vector, and the Snapdragon 8
Elite NPU/GPU run monocular metric depth and semantic segmentation where the
research prototype needed a laser scanner. The steering actuator — the part the
paper showed to be the effective ingredient — stays.

### What that changes

| | Augmented Cane (2021) | Lighthouse |
|---|---|---|
| Range sensing | 2D LiDAR | Monocular **metric depth** on NPU/GPU (Depth-Anything-V2-Metric) |
| Scene understanding | Object recognition | Walkability **segmentation**, domain-matched indoor/outdoor |
| Compute | Onboard in the handle | Phone SoC — Hexagon NPU + Adreno GPU |
| Steering actuator | Motorized omni wheel | Motorized wheel, Modulino Motors on Arduino UNO Q |
| Obstacle memory | Per-scan | Persistent **BEV log-odds occupancy grid** |
| Cost of range sensing | LiDAR unit | $0 — reuses the phone camera |

Dropping the laser scanner is the main engineering claim: a $0 camera plus an
NPU substitutes for the sensor that dominated the original bill of materials.

---

## How it works

```
Galaxy S25 Ultra (Snapdragon 8 Elite)
  │
  ├─ CameraX ~11 Hz ─ YUV → gravity-upright → 640×640 letterbox
  │    ├─ YOLOv8n              @1 Hz   GPU/NPU   labels + depth-scale calibration
  │    ├─ Depth-Anything-V2    @~3 Hz  GPU/NPU   metric depth, meters
  │    └─ Walkability seg      per-mode NPU      FFNet-78S outdoor · SegFormer-B0 indoor
  │         │
  │         └─ TraversabilityGrid   61×60 cells @ 0.1 m  (±3 m × 6 m ahead)
  │              ground-plane projection · log-odds evidence · self-calibrating ground latch
  │              │
  │              └─ PolarPlanner    37 sectors over ±90°
  │                   path-first: the route owns the heading
  │                   AVOID engages only when the goal cone is blocked
  │                   │
  │                   └─ CommandAggregator   200 ms vote → one letter
  │
  ├─ CompassNav ─ GPS + rotation vector → goal bearing
  │    outdoor: Google walking route, 12 m look-ahead · indoor: straight-line
  │
  └─ BLE ──► Arduino UNO Q (Dragonwing QRB2210)
               Modulino Motors  → wheel steers the user
               Modulino Distance → near-field obstacle → phone STOP buzz
               Modulino Vibro   → local haptic alert
               2 s failsafe: no command → motor stops
```

### Path-first planning

The planner's default state is a **pass-through**: the route owns the heading
and obstacles off the path do not perturb steering at all. Avoidance engages
only while a ±10° cone around the goal direction is actually obstructed, with
1.8 m enter / 2.2 m exit hysteresis so the decision cannot oscillate at the
boundary. Detours use a goal-biased valley search with a committed heading
(`W_PREV = 1.6`), which is what prevents the left-right-left overcorrection that
naive obstacle-repulsion steering produces at close range. When neither the
geometric grid nor the image-space walkable columns can find a corridor, the
planner emits STOP rather than guessing.

### Evidence, not snapshots

Obstacles accumulate in a bird's-eye grid as **log-odds**, decaying at 0.94 per
frame (~0.5 s half-life). A single bad depth frame cannot flip a decision. The
grid self-calibrates its ground plane from a 12th-percentile height estimate,
so a ±10 cm error in assumed camera height corrects itself instead of painting
the floor as a wall.

### Where each model runs, and why

Vision prefers the **Adreno GPU first**, with the NPU as the next tier — not
because the GPU is faster in isolation, but because the optional companion SLM
decodes on the Hexagon NPU, and contending for it stalls the steering path.
Segmentation is domain-matched per navigation mode (one model, not an
always-both ensemble), which halves segmentation compute. QNN is configured for
`soc_model=69`, `htp_arch=79`, burst performance mode, fp16.

Full engineering detail: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) ·
Measured latency: [`docs/PERFORMANCE.md`](docs/PERFORMANCE.md)

---

## Repository layout

This branch (`main`) is the **documentation and hardware hub**. The application
code lives on branches:

| Branch | Contents |
|---|---|
| **`v3`** | The system. Android app (`app/`) + cane board app (`board/qcane-wheel/`). **Start here.** |
| **`qhackgps`** | Standalone compass-navigation app — the routing prototype, Bluetooth Classic SPP to an Arduino. Independent, still runnable. |
| `v2`, `arduinov1`, `shepherd-snapdragon`, `fastscnn-depthanything` | Development history, kept for provenance. |

```
main
├── README.md              this file
├── LICENSE               AGPL-3.0
├── NOTICE.md             third-party components + why AGPL
├── Hardware/             BOM, wiring, CAD
│   ├── Assembly Instructions.md
│   ├── Bill of Materials.md
│   └── CAD/
└── docs/
    ├── ARCHITECTURE.md   full pipeline walkthrough
    ├── MODELS.md         every model: source, license, how to obtain
    ├── PERFORMANCE.md    measured latency + how to reproduce
    ├── BOARD.md          cane board + BLE protocol
    └── BRANCHES.md       what lives where
```

---

## Quick start

Full instructions from scratch: [`docs/SETUP.md`](docs/SETUP.md). In brief:

```bash
git clone https://github.com/<your-account>/lighthouse.git
cd lighthouse
git checkout v3
```

**1 — Phone app.** Android Studio Ladybug+, a Snapdragon 8 Elite device
(developed on Galaxy S25 Ultra, Android 15, minSdk 31).

```bash
echo "maps.apiKey=AIza..." >> local.properties   # optional: outdoor routing
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest                 # 9 unit-test classes, no device needed
```

The depth and segmentation models are **not committed** (size + licensing) —
`docs/MODELS.md` has the one-command export and the `adb push` targets. The app
runs without them at reduced capability and states so on screen.

**2 — Cane board.** Arduino UNO Q with three Modulinos on the Qwiic bus:

```bash
arduino-app-cli app start ~/lighthouse/board/qcane-wheel
```

Dashboard at `http://<board-ip>:7000`. Wiring and the motor-terminal quirk:
[`Hardware/Assembly Instructions.md`](Hardware/Assembly%20Instructions.md).

**3 — Pair.** Power the board; the phone finds it by GATT service UUID within
seconds. No pairing needed for the BLE path.

---

## Safety

**This is a research prototype and must not be relied on as a sole mobility
aid.** Test with a sighted companion. Treat all guidance as advisory. It has not
been evaluated with users with impaired vision, and the walking-speed figures
quoted above are the *research paper's* results for their device — they are not
measurements of this implementation.

Failsafes that are implemented: the board stops the motor if the command stream
goes quiet for 2 s; the phone sends STOP after 3 empty decision windows (600 ms)
if vision stalls; the BLE link tears down and rescans on a 4 s write stall or 8
consecutive write failures.

## License

**AGPL-3.0** — see [`LICENSE`](LICENSE). The bundled YOLOv8n detector is
Ultralytics-licensed AGPL-3.0, which sets the license for the combined work.
[`NOTICE.md`](NOTICE.md) explains the reasoning and how to build a permissive
variant.

## Acknowledgments

- **Slade, Tambe & Kochenderfer** (Stanford ISL / Aeronautics & Astronautics)
  for the Augmented Cane research this steering approach is built on, and for
  open-sourcing it.
- **Qualcomm** — Hexagon NPU, QNN, AI Hub, GenieX, and the QUAD tooling used to
  profile on real silicon.
- **Arduino** — UNO Q and the Modulino ecosystem.
