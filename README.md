# Lighthouse

**An open-source self-steering white cane powered by on-device AI.**

A 3D-printed omni wheel at the cane tip physically steers the user around
obstacles and along walking routes. All AI runs on Qualcomm silicon — no cloud,
no subscription, no signal required. Built, printed, assembled, and walked with.

![Full assembly](Hardware/CAD/Assembly.png)

**Snapdragon Multiverse Hackathon 2026** · Qualcomm San Diego

| | |
|---|---|
| **~$196 total** | Excluding the phone. Commercial smart canes: $800–$1,150 |
| **No depth sensor** | Monocular metric depth on the Hexagon NPU replaces a LiDAR unit |
| **Fully offline** | Airplane mode changes nothing about obstacle avoidance |
| **3 neural networks per frame** | ~11 Hz perception, 5 Hz motor commands, on a phone |
| **Open source** | Code, CAD, BOM, wiring — AGPL-3.0 |

---

## Team

| Name | Email |
|---|---|
| _TODO_ | _TODO_ |
| _TODO_ | _TODO_ |

---

## Why this exists

250 million people live with impaired vision. A white cane costs $20 and tells
you nothing until you hit something. A guide dog costs $50,000 and has a
multi-year waitlist. Most electronic aids beep or buzz — interpreting that costs
time and attention at the exact moment you have neither.

Lighthouse **steers**. A motorized wheel nudges your hand toward the clear path.
You keep walking. The cane handles the geometry.

The key insight: steering the hand through the cane — grounded kinesthetic
feedback — guides people more accurately and with lower cognitive load than audio
or vibration cues. The NPU is what makes it possible to do the perception for
that steering on a phone, at the cadences required, on a battery.

---

## Accessibility first

- **Degrades to a white cane.** If every electronic system fails, contact sensing
  still works. The familiar form is kept deliberately.
- **Steering, not beeping.** Hearing stays free for traffic and conversation.
- **Offline by default.** No account, no connectivity, no privacy cost.
- **Private by construction.** Camera, audio, and location never leave the device.
- **Power on and walk.** No configuration needed to avoid obstacles.
- **Printable.** No proprietary parts. Break a piece, print another.
- **Refuses rather than guesses.** No corridor found → STOP, not a random direction.

---

## Edge AI on the Qualcomm stack

### Models running concurrently on the Snapdragon 8 Elite

| Model | What it does | Cadence | Accelerator |
|---|---|---|---|
| **Depth-Anything-V2-Metric-Small** | Per-pixel distance in meters | ~3 Hz | QNN → Adreno GPU / Hexagon NPU |
| **FFNet-78S-LowRes** (Cityscapes) | Walkability segmentation, outdoor | every frame | QNN → Hexagon NPU |
| **SegFormer-B0** (ADE20K) | Walkability segmentation, indoor | every frame | QNN → Hexagon NPU |
| **YOLOv8n** | Object detection + depth-scale calibration | 1 Hz | QNN EP |
| **Qwen3.5-2B** (Q4_0 GGUF) | On-device scene companion | on request | GenieX → Hexagon NPU |
| **Kokoro-82M** | Neural text-to-speech | on utterance | sherpa-onnx, CPU |

Only one segmentation model runs per frame — outdoor or indoor, domain-matched.
This halves seg compute vs. ensembling both.

### Accelerator allocation

```
QNN config: soc_model=69, htp_arch=79, burst mode, fp16
Fallback:   strict → mixed → CPU (never crashes, always runs)
```

**Vision takes the GPU first.** Not because it's faster — but because the
companion SLM decodes on the Hexagon NPU, and contending for it stalls the
safety-critical steering loop. Giving vision the Adreno GPU and reserving the NPU
for language keeps the critical path deterministic. This only matters when you're
running 6 models concurrently on one SoC.

**Domain-matched segmentation, not an ensemble.** FFNet-78S is trained on
Cityscapes (roads, sidewalks, curbs). SegFormer-B0 is trained on ADE20K (indoor
floors, doors, furniture). Running both every frame and merging was the earlier
design — it averaged away the specialization. Selecting one per nav mode halves
the compute for no measurable loss.

### Measured on-device

| What | Result |
|---|---|
| Companion SLM first token (Qwen3.5-2B, Hexagon) | **186 ms** |
| Companion SLM decode rate | **12.1 tok/s** |
| Depth on UNO Q CPU (DA-V2, 252×252) | 1728 ms — **not viable for steering** |
| MiDaS int8 on UNO Q CPU | 1001 ms — **slower than float** (no NPU = dequant overhead for nothing) |

The last two rows are why perception lives on the phone's NPU rather than the
board. Quantization is a win only when there's an accelerator that wants it.

### Qualcomm tooling

| Tool | Role |
|---|---|
| **QNN / QAIRT EP** | All vision inference, three-tier fallback |
| **Qualcomm AI Hub** | Sourced FFNet-78S and SegFormer-B0 |
| **GenieX** | On-device LLM runtime |
| **QUAD MCP** | Profiled models on real silicon over ADB |
| **Hexagon NPU** | Seg, depth, SLM decode |
| **Adreno GPU** | Vision (preferred tier) |
| **Arduino UNO Q** (Dragonwing QRB2210) | Cane board — sensing and actuation |

---

## How it works

```
Galaxy S25 Ultra — Snapdragon 8 Elite
  │
  ├─ Camera ~11 Hz → gravity-upright → 640×640 letterbox
  │    ├─ Depth-Anything-V2  @~3 Hz   metric depth (meters)
  │    ├─ Walkability seg    per-mode  FFNet outdoor / SegFormer indoor
  │    └─ YOLOv8n            @1 Hz    labels + depth-scale cal
  │         │
  │         └─ TraversabilityGrid  61×60 @ 0.1 m  log-odds  self-calibrating ground
  │              │
  │              └─ PolarPlanner  37 sectors ±90°  path-first  hysteresis
  │                   │
  │                   └─ CommandAggregator  200 ms vote → one letter
  │
  ├─ CompassNav  GPS + heading → goal bearing (Google routes or beeline)
  │
  └─ BLE ──► Arduino UNO Q
               Modulino Motors → omni wheel steers
               Modulino Distance → near-field STOP
               Modulino Vibro → haptic alert
               2 s failsafe
```

### Path-first planning

The planner is a **pass-through by default** — the route owns the heading.
Avoidance engages only when a ±10° cone around the goal is blocked.

- **Hysteresis:** blocks at 1.8 m, unblocks at 2.2 m — can't flip frame to frame
- **Committed heading:** `W_PREV = 1.6` > `W_GOAL = 1.0` > `W_WIDTH = 0.8` — the
  biggest weight resists changing its mind, which kills left-right oscillation
- **Asymmetric:** slow to leave the route (0.35), quick to return (0.45)
- **Refuses rather than guesses:** no corridor + no walkable columns → STOP

### Screen-thirds spoken guidance

An independent fallback, parallel to the planner: middle third decides whether
you can proceed, better outer third becomes the latched dodge side, spoken aloud
on each transition. Reuses data the pipeline already computes — no grid, no
planner involved.

---

## Hardware

![Electronics mount](Hardware/CAD/Electronics%20Mount.png)

Built, assembled, and walked with. ~$196 in parts, excluding the phone.

```
UNO Q ──► Modulino Motors ──► Modulino Vibro ──► Modulino Distance
```

| | |
|---|---|
| ![Omni wheel](Hardware/CAD/Omni%20Wheel.png) | **Print-in-place omni wheel** — 6 rollers captive in the hub. No bearings, no assembly. Based on [this GrabCAD design](https://grabcad.com/library/omni-wheel-print-in-place-1), adapted for the JGA25 shaft. |
| ![Phone mount](Hardware/CAD/Phone%20Mount.png) | **S25 Ultra cradle** — pivoting hinge, rear cameras forward. |
| ![Battery mount](Hardware/CAD/Battery%20Mount.png) | **Anker 737 cradle** — powers the board, charges the phone. |

**CAD source (Onshape):**
[open the live document](https://cad.onshape.com/documents/c0b905e8d1b94a52d9e9ca97/w/f6521fb190a4be4abaa604ca/e/e523a5a009f76747b4bb6591)

Full BOM and assembly: [`Hardware/`](Hardware/)

---

## Getting started

```bash
git clone https://github.com/<your-account>/lighthouse.git
cd lighthouse
git checkout v3
```

```bash
./gradlew :app:testDebugUnitTest     # 10 test classes, no device needed
./gradlew :app:installDebug
```

Models (depth + seg) must be pushed separately — see [`docs/MODELS.md`](docs/MODELS.md).
Board setup: [`docs/BOARD.md`](docs/BOARD.md).
Full from-scratch walkthrough: [`docs/SETUP.md`](docs/SETUP.md).

`main` also carries a merged demo (qhackGPS navigator + camera scan toggle) —
see [`docs/BRANCHES.md`](docs/BRANCHES.md).

---

## Repository layout

| Branch | What |
|---|---|
| **`main`** | Docs + hardware + merged demo app (qhackGPS + camera scan + voice) |
| **`v3`** | Full perception system — seg, depth, BEV grid, polar planner, SLM |
| `qhackfinal`, `qhackgps` | Nav prototypes and their lineage |

---

## Performance

| Parameter | Value |
|---|---|
| Perception cadence | 90 ms (~11 Hz) |
| Depth inference | 300 ms (~3 Hz) |
| Detection | 1000 ms (1 Hz) |
| Motor decisions | 200 ms (5 Hz) |
| Board failsafe | 2 s silence → motor stops |

Instrumented per-frame: `adb logcat -s ShepherdTime`

---

## Safety

**Research prototype. Not a sole mobility aid. Test with a sighted companion.**

| Guard | Threshold |
|---|---|
| Board motor failsafe | 2 s |
| Phone STOP on stalled vision | 600 ms |
| BLE write-stall teardown | 4 s |
| Planner can't find corridor | immediate STOP |

---

## Roadmap

**Done:** metric depth on NPU · domain-matched seg · BEV log-odds grid ·
path-first planner · walking routes + reroute · BLE steering · near-field ToF ·
neural TTS · on-device STT · OCR · screen-thirds spoken guidance · 2B companion
on Hexagon · full CAD · cane built and walked with

**Next:** end-to-end latency measurement · battery life characterization ·
companion model thermal gating · moving-obstacle prediction · user evaluation

---

## Prior work and acknowledgments

The steering concept — a motorized omni wheel applying grounded kinesthetic
feedback — is based on:

> P. Slade, A. Tambe, M. J. Kochenderfer, "Multimodal sensing and intuitive
> steering assistance improve navigation and mobility for people with impaired
> vision," *Science Robotics* **6**(59), eabg6594 (2021).
> [doi:10.1126/scirobotics.abg6594](https://doi.org/10.1126/scirobotics.abg6594)

Their study showed +18% walking speed for participants with impaired vision vs. a
white cane. Those are their results for their device — not a claim about ours.
Lighthouse is an independent implementation, not affiliated with the authors or
Stanford.

Also: [GrabCAD print-in-place omni wheel](https://grabcad.com/library/omni-wheel-print-in-place-1) ·
Qualcomm (NPU, QNN, AI Hub, GenieX, QUAD) · Arduino (UNO Q, Modulinos) ·
Depth-Anything-V2, FFNet, SegFormer, ONNX Runtime, sherpa-onnx.

## License

**AGPL-3.0** — see [`LICENSE`](LICENSE). Set by the bundled YOLOv8n detector.
Full reasoning: [`NOTICE.md`](NOTICE.md).

Open hardware, open software. Build it, change it, share it.
