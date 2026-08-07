# Lighthouse

**An open-source self-steering white cane that runs entirely on Qualcomm silicon.**

Lighthouse guides a person with impaired vision by *physically steering them*. A
motorized wheel at the cane tip applies a lateral nudge — left or right — around
obstacles and along a walking route. Every perception model runs on the phone:
metric depth, walkability segmentation, and object detection execute on the
Snapdragon 8 Elite's Hexagon NPU and Adreno GPU. Obstacle avoidance has **no
network dependency whatsoever**.

Built for the **Snapdragon Multiverse Hackathon 2026** · Qualcomm San Diego

**At a glance:**
- **No depth sensor** — monocular metric depth on the NPU replaces the laser scanner the approach normally requires
- **~$170 in parts**, excluding the phone
- **Fully offline steering** — the network is optional and only ever used for street routing
- **Open source end to end** — code, BOM, and wiring under AGPL-3.0

---

## Team

| Name | Email |
|---|---|
| _TODO: replace before submitting_ | _TODO_ |
| _TODO_ | _TODO_ |

> **Pre-submission checklist — delete this block before you submit.**
> - [ ] Team table filled in (names + emails are a hard requirement)
> - [ ] Every member has submitted the feedback form
> - [ ] Repo pushed to a **personal** GitHub account, verified public
> - [ ] Repo link submitted via the Microsoft Form by **12:00 PM, Friday Aug 7**

---

## Contents

- [The problem](#the-problem)
- [Our approach](#our-approach)
  - [What the research established](#what-the-research-established)
  - [What we changed](#what-we-changed)
  - [Capabilities](#capabilities)
- [How it works](#how-it-works)
  - [System architecture](#system-architecture)
  - [The sensing and steering loop](#the-sensing-and-steering-loop)
  - [Why on-device](#why-on-device)
  - [Design deep-dive: path-first planning](#design-deep-dive-path-first-planning)
  - [Models and where each one runs](#models-and-where-each-one-runs)
- [Hardware](#hardware)
- [Getting started](#getting-started)
  - [Prerequisites](#prerequisites)
  - [1. Clone and select the branch](#1-clone-and-select-the-branch)
  - [2. Configure keys](#2-configure-keys)
  - [3. Build and test](#3-build-and-test)
  - [4. Install the models](#4-install-the-models)
  - [5. Bring up the cane board](#5-bring-up-the-cane-board)
  - [6. Exercise the features](#6-exercise-the-features)
- [Repository layout](#repository-layout)
- [Board pinout and bus map](#board-pinout-and-bus-map)
- [Performance](#performance)
- [Safety](#safety)
- [Roadmap](#roadmap)
- [Troubleshooting](#troubleshooting)
- [Contributing](#contributing)
- [Prior work and acknowledgments](#prior-work-and-acknowledgments)
- [License](#license)

---

## The problem

Roughly **250 million people** worldwide live with impaired vision. Navigating an
unfamiliar route means solving several problems at once — avoiding obstacles,
identifying what they are, and wayfinding both indoors and out. The available
tools each address only part of that, and the good ones are priced out of reach
for most of the people who need them.

| Aid | Typical cost |
|---|---|
| White cane | ~$20 — contact sensing only, no wayfinding |
| Sensor-equipped smart canes | $800 – $1,150 |
| AI wearables | $2,000 – $5,000 |
| Guide dog | ~$50,000, with multi-year waitlists |

The majority of people with impaired vision live in low- and middle-income
countries, where any of the assistive options above can exceed an annual income.

There is a second problem that price doesn't capture. Most electronic travel aids
**report** — a beep, a buzz, a spoken phrase — and leave you to interpret it.
Interpretation costs time and attention at precisely the moment you have least of
both. Devices that lean on cloud inference add seconds of round-trip latency on
top, which is untenable when the obstacle is moving or you are approaching a
crossing.

## Our approach

Lighthouse **steers** instead of reporting. A motorized wheel at the cane tip
tugs your hand toward the clear path. You keep walking; the cane resolves the
geometry.

### What the research established

The steering principle comes from published work on augmented white canes:

> P. Slade, A. Tambe, M. J. Kochenderfer, **"Multimodal sensing and intuitive
> steering assistance improve navigation and mobility for people with impaired
> vision,"** *Science Robotics* **6**(59), eabg6594 (2021).
> [doi:10.1126/scirobotics.abg6594](https://doi.org/10.1126/scirobotics.abg6594)
> · [open-source reference design](https://github.com/pslade2/AugmentedCane)

That study compared a cane augmented with a motorized omni wheel against a plain
white cane, across four navigation tasks, with both blindfolded sighted
participants and participants with impaired vision. Two results matter here.

First, **grounded kinesthetic feedback** — steering the hand through the cane
itself — directed people more accurately and with measurably lower cognitive load
than vibrotactile or audio cues. Participants began turning sooner when steered
than when buzzed.

Second, walking speed rose **18 ± 7%** for participants with impaired vision and
**35 ± 12%** for blindfolded sighted participants, relative to a white cane. The
authors attribute the gain to four factors: accurate steering, reduced cognitive
load, fewer contacts with the environment, and greater user confidence.

Those figures describe *their* device. They are the reason we chose a steering
actuator over a haptic one — not a claim about this implementation, which has not
been evaluated with users.

### What we changed

The published design senses with a 2D LiDAR unit plus GPS and an IMU, computing
in the handle. **Lighthouse substitutes a phone.** A Galaxy S25 Ultra provides the
camera, GPS, compass, and gravity vector, and the Snapdragon 8 Elite runs
monocular metric depth and semantic segmentation where the reference prototype
needed a laser scanner. The steering actuator — the ingredient the research
identified as effective — is retained.

| | Reference design (2021) | Lighthouse |
|---|---|---|
| Range sensing | 2D LiDAR | Monocular **metric depth** on NPU/GPU (Depth-Anything-V2-Metric) |
| Scene understanding | Object recognition | Walkability **segmentation**, domain-matched indoor/outdoor |
| Compute location | Onboard, in the handle | Phone SoC — Hexagon NPU + Adreno GPU |
| Obstacle memory | Per-scan | Persistent **BEV log-odds occupancy grid** |
| Route following | GPS waypoints | Google walking route, 12 m look-ahead, auto-reroute |
| Steering actuator | Motorized omni wheel | Motorized wheel via Modulino Motors on Arduino UNO Q |
| Cost of range sensing | LiDAR unit | **$0** — reuses the phone camera |

Eliminating the laser scanner is the central engineering claim: an NPU and a
camera the user already owns stand in for the most expensive sensor in the
original bill of materials.

### Capabilities

- **Physical steering** — a wheel at the tip applies lateral force. Forward
  rolling is unimpeded, so the cane never feels like it is dragging.
- **Monocular metric depth** — per-pixel distance in meters from a single RGB
  camera, on the NPU. No depth sensor, no stereo rig.
- **Persistent bird's-eye map** — obstacles accumulate as log-odds evidence in a
  61×60 cell grid at 0.1 m resolution, so one bad frame cannot flip a decision.
- **Path-first planning** — the route owns the heading; obstacles off the path are
  ignored entirely rather than perturbing your course.
- **Indoor and outdoor modes** — Google walking routes outdoors, straight-line
  compass bearing indoors, each with its own domain-matched model pair.
- **Self-calibrating ground plane** — the grid estimates its own ground offset, so
  mount height error corrects itself instead of painting the floor as a wall.
- **Near-field tip sensing** — a time-of-flight sensor at the cane tip catches
  what the camera cannot see, and triggers a stop.
- **Neural speech** — on-device TTS for alerts and navigation cues.
- **Optional on-device companion** — a 2B-parameter language model on the Hexagon
  NPU answers questions about the scene, fully offline. Off by default; see
  [Roadmap](#roadmap).
- **Reads text on request** — signs, labels, and menus via on-device OCR.

## How it works

### System architecture

```
Galaxy S25 Ultra — Snapdragon 8 Elite
  │
  ├─ CameraX  ~11 Hz
  │    YUV → gravity-upright rotate → 640×640 letterbox
  │      ├─ YOLOv8n            @1 Hz    GPU/NPU   object labels, depth-scale calibration
  │      ├─ Depth-Anything-V2  @~3 Hz   GPU/NPU   metric depth, meters
  │      └─ Walkability seg    per-mode  NPU      FFNet-78S outdoor · SegFormer-B0 indoor
  │            │
  │            └─ TraversabilityGrid    61 × 60 cells @ 0.1 m   (±3.05 m × 6 m ahead)
  │                 ground-plane projection · log-odds evidence · ground self-calibration
  │                   │
  │                   └─ PolarPlanner   37 sectors across ±90°
  │                        DEFAULT: the route owns the heading
  │                        AVOID:   engages only when the goal cone is blocked
  │                          │
  │                          └─ CommandAggregator   200 ms weighted vote → one letter
  │
  ├─ Sensors     gravity vector → camera pitch / roll  (grid tilt correction)
  ├─ CompassNav  GPS + rotation vector → goal bearing
  │                outdoor: walking route, 12 m look-ahead · indoor: direct bearing
  │
  └─ BLE  ──▶  Arduino UNO Q — Dragonwing QRB2210
                 Modulino Motors   → wheel steers the user
                 Modulino Distance → near-field obstacle → phone STOP buzz
                 Modulino Vibro    → local haptic alert
                 failsafe: command stream silent 2 s → motor stops
```

### The sensing and steering loop

1. **Capture** — CameraX delivers YUV frames, keeping only the latest so a slow
   frame never queues staleness behind it.
2. **Orient** — the frame is rotated upright using the gravity vector, then
   letterboxed to 640×640. Camera pitch and roll are read live, so the geometry
   downstream stays valid as the user's hand moves.
3. **Depth** — Depth-Anything-V2-Metric infers per-pixel distance **in meters**,
   gated to ~3 Hz.
4. **Segment** — a walkability model labels traversable surface. One model runs,
   not two: outdoors FFNet-78S (Cityscapes), indoors SegFormer-B0 (ADE20K).
5. **Project** — depth pixels are projected through the ground plane into a
   bird's-eye grid. Height above ground classifies each cell; 0.18–2.3 m is an
   obstacle, within ±0.16 m of ground is free.
6. **Accumulate** — evidence updates as log-odds and decays at 0.94 per frame
   (~0.5 s half-life). Certainty builds across frames rather than resetting.
7. **Fuse the tip sensor** — the cane's time-of-flight reading is injected as
   hard near-field evidence at double weight.
8. **Plan** — 37 rays are cast across ±90°. If the goal cone is clear the route's
   heading passes straight through. If it is blocked, the planner searches for the
   widest valley that best trades off goal alignment against commitment to its
   current choice.
9. **Vote** — per-frame verdicts are aggregated over a 200 ms window into one
   command letter. STOP outranks turns; turns outrank straight.
10. **Steer** — the letter goes out over BLE. The board drives the wheel and
    stops on its own if the stream goes quiet for 2 s.

### Why on-device

Obstacle avoidance cannot wait on a network. A cloud round trip costs seconds; a
person walking at 1.4 m/s covers several meters in that time. Every model on the
steering path runs locally, and the pipeline keeps working in airplane mode.

Only two things ever touch the network, both optional and neither blocking:
outdoor street routing, and one-time model downloads. Speech recognition uses
Android's on-device recognizer — audio never leaves the phone.

### Design deep-dive: path-first planning

Naive obstacle avoidance steers *away from* whatever it detects. That fails badly
at close range: approaching an obstacle head-on, the planner corrects left, then
overshoots right, then left again. The cane fights the user.

Lighthouse inverts the default. **The planner is a pass-through** — the route owns
the heading, and obstacles that are not on the path do not influence steering at
all. Avoidance engages only while a **±10° cone around the goal direction** is
genuinely obstructed.

Three mechanisms keep that decision stable:

- **Hysteresis.** A sector blocks at **1.8 m** and only unblocks at **2.2 m**. The
  0.4 m gap means an obstacle hovering near the threshold cannot flip the mode
  frame to frame.
- **Committed heading.** When choosing a detour, the cost function weights
  *staying with the current choice* (`W_PREV = 1.6`) above *goal alignment*
  (`W_GOAL = 1.0`) and *corridor width* (`W_WIDTH = 0.8`). Deliberately, the
  largest weight resists changing its mind — that is what stops the left-right
  oscillation when two openings are nearly equally good.
- **Asymmetric commitment.** Deviating is gradual (`COMMIT_ALPHA = 0.35`),
  returning to the path is quicker (`RETURN_ALPHA = 0.45`). Leave the route
  reluctantly, rejoin it promptly.

When no geometric corridor exists, the planner falls back to image-space
walkability columns, requiring better than 0.55 walkable fraction. If that also
fails, it emits **STOP** rather than guessing. Refusing to answer is a valid
answer for a device that steers a person.

### Models and where each one runs

| Model | Role | Cadence | Runs on |
|---|---|---|---|
| Depth-Anything-V2-Metric (Small) | Per-pixel metric depth | ~3 Hz | GPU preferred, NPU next |
| FFNet-78S-LowRes (Cityscapes) | Walkability, **outdoor** | per frame, inline | NPU |
| SegFormer-B0 (ADE20K) | Walkability, **indoor** | per frame, async | NPU |
| YOLOv8n | Object labels, depth-scale calibration | 1 Hz | GPU preferred, NPU next |
| Qwen3.5-2B (Q4_0) | Optional scene companion | on request | NPU |
| Supertonic 3 / Kokoro-82M | Neural speech | on utterance | CPU |

Two deliberate choices are worth surfacing:

**Vision prefers the GPU first, not the NPU.** Counterintuitive, but the optional
language model decodes on the Hexagon NPU, and contending for it stalls the
steering loop. Reserving the NPU keeps the safety-critical path predictable. QNN
falls back strict → mixed → CPU, so a model that cannot fully compile still runs.

**Segmentation is domain-matched, not ensembled.** Running both experts every
frame and merging their votes was the earlier design. Selecting one per
navigation mode halves segmentation compute for no measurable loss — each model
is already specialized for its domain.

Full detail: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) ·
[`docs/MODELS.md`](docs/MODELS.md)

## Hardware

The electronics are three Modulino modules on the Arduino UNO Q's Qwiic bus:

```
UNO Q ──▶ Modulino Motors ──▶ Modulino Vibro ──▶ Modulino Distance
```

Bus order is irrelevant — each node answers on its own I²C address.

**Verified working on the bench:** UNO Q, all three Modulinos, wheel drive,
distance streaming, haptics, the web dashboard, and the full phone→board BLE
path. You can reproduce all of it with the parts loose on a desk.

**Not yet fabricated:** the mechanical assembly — motor mount, shaft clamp,
handle enclosure, phone mount. Specified in the BOM and marked clearly as
unbuilt. We are not shipping CAD that has never been printed.

- Parts and costs: [`Hardware/Bill of Materials.md`](Hardware/Bill%20of%20Materials.md)
- Wiring and bring-up: [`Hardware/Assembly Instructions.md`](Hardware/Assembly%20Instructions.md)
- CAD status: [`Hardware/CAD/README.md`](Hardware/CAD/README.md)

> **One wiring detail that will cost you an hour.** The wheel motor sits across
> terminals `1A` + `2A` — one half-bridge from each channel, not a single
> channel's pair — so the sketch drives the two channels in opposite phase. And
> the Qwiic cable carries **3.3 V logic only**: without a separate 5 V supply on
> the motor's `VM` terminals, the H-bridge acknowledges every command and drives
> nothing. Every layer reports success. Run `--selftest` to measure which
> terminals actually draw current.

## Getting started

### Prerequisites

| | Requirement |
|---|---|
| Phone | Snapdragon 8 Elite Android device. Developed on **Galaxy S25 Ultra**, Android 15. `minSdk 31`. |
| Dev machine | Android Studio Ladybug or newer, plus `adb` on `PATH` |
| Board *(optional)* | Arduino UNO Q + Modulino Motors, Distance, Vibro |
| Keys *(optional)* | Google Maps API key — outdoor routing only |

The app builds and runs **without the board** and **without an API key**. Each
absence removes a feature; neither blocks you.

Full walkthrough including platform gotchas: [`docs/SETUP.md`](docs/SETUP.md)

### 1. Clone and select the branch

```bash
git clone https://github.com/<your-account>/lighthouse.git
cd lighthouse
git checkout v3          # main is docs + hardware; the code is on v3
```

### 2. Configure keys

```bash
echo "maps.apiKey=AIza..." >> local.properties
```

Enable **Maps SDK for Android**, **Routes API**, **Geocoding API**, and **Places
API (New)** on the key. Omit it and navigation uses a straight-line bearing —
which is also what indoor mode does by design. `local.properties` is gitignored.

### 3. Build and test

```bash
./gradlew :app:testDebugUnitTest     # 9 test classes, no device required
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

Run the tests first. They cover the planner state machine, grid math, command
aggregation, depth calibration, detector post-processing, and navigation — all
pure Kotlin, so a green run validates your toolchain before hardware is involved.

### 4. Install the models

**Required for full function.** Only the detector is committed; depth and
segmentation weights are not, for size and licensing reasons.

```bash
./scripts/export_depth_model.sh 294     # Linux/WSL + torch

adb push depth_anything_v2_small.onnx \
  /sdcard/Android/data/dev.quad.shepherd/files/models/
```

> **Use the Metric checkpoints.** Depth-Anything-V2-**Metric** emits meters
> directly. The relative-depth variants do not, and the grid, calibrator, and
> every meter threshold in the planner assume meters — substituting one degrades
> silently rather than failing loudly.

Per-model commands and push targets: [`docs/MODELS.md`](docs/MODELS.md)

### 5. Bring up the cane board

```bash
arduino-app-cli app start ~/lighthouse/board/qcane-wheel
arduino-app-cli app logs  ~/lighthouse/board/qcane-wheel --follow
```

Dashboard at `http://<board-ip>:7000`. Verify the whole chain before involving
the phone — this drives the MCU and waits for its acknowledgement:

```bash
python3 host/qcane_btd.py --send left --speed 5
python3 host/qcane_btd.py --selftest       # profile the motor wiring
```

If the wheel spins, the electronics are done. Then power the board and launch the
app: the phone scans by GATT service UUID and connects within seconds, no pairing.

Protocol reference: [`docs/BOARD.md`](docs/BOARD.md)

### 6. Exercise the features

**Steering.** Stand with an obstacle ~1.5 m ahead. The wheel should pick a side
and hold it, not hunt. Walk a clear corridor: near-neutral. If it wanders, camera
pitch or the ground latch is off — watch `ground=` in the logs.

**Path-first behavior.** Set a destination, then place an obstacle *off* the
route. Steering should ignore it completely. Move it into the path and the planner
should deviate, then rejoin. Transitions log as `deviate` / `return`.

**Stop condition.** Walk toward a wall. Expect `X` (STOP), not a guess.

**Tip sensor.** Pass a hand in front of the cane tip inside the presence
threshold: the board should buzz.

**Routing.** Set an outdoor destination. The wheel should bias toward the route
while ignoring off-path obstacles, and reroute if you stray 30 m for 4 fixes.

**Live telemetry.**

```bash
adb logcat -s ShepherdTime
```

Per-stage latency plus two diagnostics: `ground=` should settle near 0, and
`scale=` near 1.0.

## Repository layout

`main` is the documentation and hardware hub. Application code lives on branches.

| Branch | Contents |
|---|---|
| **`v3`** | The system — Android app (`app/`) + cane board (`board/qcane-wheel/`). **Start here.** |
| **`qhackgps`** | Standalone compass-navigation app; the routing prototype over Bluetooth Classic SPP. Independent, still runnable. |
| `v2`, `arduinov1`, `shepherd-snapdragon`, `fastscnn-depthanything` | Development history, retained for provenance. |

```
main
├── README.md                     this file
├── LICENSE                       AGPL-3.0
├── NOTICE.md                     third-party components + licensing rationale
├── Hardware/
│   ├── Bill of Materials.md      verified electronics vs unbuilt mechanical
│   ├── Assembly Instructions.md  bench bring-up, then mounting
│   └── CAD/                      placeholder — see the README inside
└── docs/
    ├── ARCHITECTURE.md           pipeline, accelerators, grid math, planner
    ├── MODELS.md                 every model: license, fetch command, push target
    ├── BOARD.md                  board internals + both BLE transports
    ├── SETUP.md                  from-scratch setup + troubleshooting
    ├── PERFORMANCE.md            measured numbers and how to reproduce them
    ├── BRANCHES.md               what lives where
    └── KNOWN_ISSUES.md           audited defects, stated plainly
```

On the `v3` branch:

```
app/                     Android app (Kotlin)
board/qcane-wheel/       board app: MCU sketch + Linux policy + host BT daemon
board/ble-bridge/        Nordic UART side channel, for nRF-style debugging
board/depth-spike/       on-board depth benchmark — the basis for PERFORMANCE.md
tools/walkability/       offline planner evaluation (numpy mirror of the pipeline)
scripts/                 model export and fetch
```

## Board pinout and bus map

```
Qwiic / Wire1  (I²C, 3.3 V logic)
  Modulino Motors     0x48   MAX22211 dual H-bridge, ≤3.8 A per channel
  Modulino Distance   ——     VL53L4CD time-of-flight
  Modulino Vibro      ——     haptic alert

Motor screw terminals
  1A + 2A                    wheel — one half-bridge from each channel
  VM + GND                   5 V motor supply (NOT from Qwiic; yellow LED confirms)

13×8 LED matrix              mirrors wheel direction, with a bar on the turn side
```

BLE identity — service `bcf2f193-f22b-4695-af5e-fd3b9caf4977`, plus standard SPP
as a second transport. Full UUID and command reference:
[`docs/BOARD.md`](docs/BOARD.md)

## Performance

Two categories, kept strictly separate. Nothing here is estimated.

**Design budgets** — enforced in code:

| Parameter | Value |
|---|---|
| Analysis cadence | 90 ms (~11 Hz) |
| Depth inference gate | 300 ms (~3 Hz) |
| Detection gate | 1000 ms (1 Hz) |
| Motor decision window | 200 ms (5 Hz) |
| Board motor failsafe | 2000 ms of silence |
| Phone STOP on vision stall | 3 empty windows (600 ms) |

**Measured — companion language model** (`GenieBench`, S25 Ultra, Qwen3.5-2B
Q4_0, Hexagon NPU):

| Metric | Value |
|---|---|
| First token | 186 ms |
| Decode rate | 12.1 tok/s |

**Measured — depth on the board's CPU** (UNO Q, 4×A53 @ 2 GHz, 3 threads). This
is the experiment that decided where depth runs:

| Model | Input | Median | FPS |
|---|---|---|---|
| MiDaS float | 256×256 | 864 ms | 1.16 |
| MiDaS w8a8 | 256×256 | 1001 ms | 1.00 |
| Depth-Anything-V2-Small | 126×126 | 452 ms | 2.21 |
| Depth-Anything-V2-Small | 252×252 | 1728 ms | 0.58 |

Depth on the board CPU is not viable for steering: the fastest configuration is
452 ms at a resolution too coarse to locate a corridor, and cost scales sharply
with input size. Note that int8 quantization made MiDaS **slower** — on a CPU
with no NPU to consume the quantized graph, you pay dequantization for nothing.
Quantization is a win only when an accelerator wants it. Hence the split: depth
and segmentation on the phone's NPU/GPU, actuation and near-field sensing on the
board.

Per-stage phone latency is instrumented and reproducible via `adb logcat -s
ShepherdTime`. We publish the method rather than quoting figures we cannot attach
a capture to — see [`docs/PERFORMANCE.md`](docs/PERFORMANCE.md), which also lists
plainly what we have **not** measured: end-to-end reaction time including
mechanical inertia, battery life under load, and any steering-accuracy result.

## Safety

**This is a research prototype. It must not be used as a sole mobility aid.**
Test with a sighted companion. Treat all guidance as advisory.

It has not been evaluated with users with impaired vision. The walking-speed
figures in [What the research established](#what-the-research-established) belong
to the published study's device and are **not** measurements of this system.

Failsafes that exist today:

| Guard | Threshold |
|---|---|
| Board stops the motor when commands go silent | 2 s |
| Phone sends STOP after empty decision windows | 3 windows (600 ms) |
| BLE teardown and rescan on write stall | 4 s |
| BLE teardown after consecutive write failures | 8 |
| Planner emits STOP when no corridor is found | immediate |

Defects we know about are documented rather than hidden, including two that
affect behavior: [`docs/KNOWN_ISSUES.md`](docs/KNOWN_ISSUES.md).

## Roadmap

**Working:**
- [x] Monocular metric depth on NPU/GPU — no depth sensor
- [x] Walkability segmentation, domain-matched indoor/outdoor
- [x] Persistent BEV log-odds grid with ground self-calibration
- [x] Path-first polar planner with hysteresis and committed heading
- [x] Outdoor walking routes with look-ahead and auto-reroute; indoor beeline
- [x] BLE steering to the board, with zombie-link detection and recovery
- [x] Near-field tip sensing and haptic stop
- [x] Neural on-device TTS; on-device speech recognition; OCR on request
- [x] Board dashboard with live motor current and voltage telemetry
- [x] 2B-parameter companion model on Hexagon (built, disabled by default)

**In progress:**
- [ ] Mechanical assembly — mount, clamp, enclosure, phone mount
- [ ] End-to-end reaction-time measurement including mechanical inertia
- [ ] Fixes for the two behavioral defects in `KNOWN_ISSUES.md`
- [ ] Enable the companion model once thermal headroom is characterized

**Future:**
- [ ] Moving-obstacle prediction
- [ ] QNN path for the board's Adreno GPU, to offload near-field perception
- [ ] Crossing and traffic-signal awareness
- [ ] Evaluation with users with impaired vision — the only test that counts

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| Status bar reads `CPU` | QNN failed to load. Check Logcat `OrtSessions`. Confirm the manifest still declares `libcdsprpc.so` and `libOpenCL.so` — since API 31 the linker blocks undeclared vendor libraries. |
| `Invalid plugin` at startup | `useLegacyPackaging` was disabled. Restore it: the runtime `dlopen`s plugins by absolute path, which is empty when `.so` files stay compressed in the APK. |
| Guidance ignores obstacles | Depth model missing. See [step 4](#4-install-the-models); check the on-screen model status. |
| Everything looks like an obstacle | Camera pitch. The ground latch self-calibrates but cannot recover from a camera aimed at sky or floor. Remount level and forward; watch `ground=`. |
| Wheel never moves | No `VM` supply — Qwiic is 3.3 V logic only. Confirm the yellow LED, then `--selftest`. |
| Wheel brakes on a clear path | Known defect — see `KNOWN_ISSUES.md`, the `S` letter collision. |
| Board reads distance, phone ignores it | Known defect — see `KNOWN_ISSUES.md`, telemetry channel mismatch. |
| Steering oscillates | Should not happen — hysteresis and committed heading prevent it. If it does, verify you are on `v3` and file an issue with a `ShepherdTime` capture. |
| No routing available | `maps.apiKey` missing, or Routes API not enabled on that key. |
| Speech sounds robotic | Neural voice has not downloaded — needs unmetered Wi-Fi on first launch. System TTS is the fallback. |

More: [`docs/SETUP.md`](docs/SETUP.md)

## Contributing

Contributions are welcome, particularly:

- **Accessibility testing** — feedback from users with impaired vision matters
  more than any other contribution here, and we have none yet.
- **Mechanical design** — the assembly is unbuilt; CAD for the mount, clamp,
  enclosure, and phone holder would unblock field testing.
- **Perception** — moving-obstacle prediction, better indoor segmentation,
  robustness to glass and harsh shadows.
- **Board offload** — a QNN path for the Dragonwing's Adreno GPU.
- **The defects in `KNOWN_ISSUES.md`** — two affect behavior and each needs
  hardware to confirm a fix.

Standard flow: fork, branch, commit, open a PR against `v3`. Please run
`./gradlew :app:testDebugUnitTest` first, and add a test for any planner or grid
change — that math is safety-relevant and the existing suite is the only thing
guarding it.

Offline planner changes can be validated without hardware using
`tools/walkability/`, which replays burst photos through a numpy mirror of the
pipeline and scores decision jitter as angle spread across the burst.

## Prior work and acknowledgments

- **P. Slade, A. Tambe, and M. J. Kochenderfer** (Stanford), whose *Science
  Robotics* work established that grounded kinesthetic steering outperforms
  vibrotactile and audio feedback for this task, and who open-sourced their
  design. Lighthouse is an independent implementation on different hardware; it
  is not affiliated with or endorsed by the authors or Stanford University.
- **Qualcomm** — Hexagon NPU, QNN, AI Hub, GenieX, and the QUAD tooling used to
  profile on real silicon.
- **Arduino** — the UNO Q and the Modulino ecosystem.
- **Depth-Anything-V2, FFNet, and SegFormer** authors, and the ONNX Runtime and
  sherpa-onnx projects.

## License

**AGPL-3.0** — see [`LICENSE`](LICENSE).

This is set by the bundled YOLOv8n detector, which Ultralytics licenses AGPL-3.0.
That detector is the only AGPL component and it is **not on the steering path** —
it supplies object labels at 1 Hz and calibrates the depth scale. Removing it and
substituting a permissively-licensed detector would allow relicensing the
remainder under Apache-2.0. Full reasoning and a complete component inventory:
[`NOTICE.md`](NOTICE.md).

Open hardware, open software. Build it, change it, share it.
