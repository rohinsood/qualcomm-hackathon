# Branches

`main` began as a **documentation and hardware hub** — README, license,
`Hardware/`, `docs/` — with all application code on branches. Two merges
changed that. `main` now also carries a complete, runnable system:
**`qhackfinal`** merged wholesale (the qhackGPS phone app + the cane
firmware), plus one piece of **`v3`** — the screen-thirds camera scan —
ported into the app as a toggleable feature ([`SCAN.md`](SCAN.md)).

If you are here to run the merged demo, stay on `main`. If you are here for
the full perception stack — segmentation, depth, the BEV planner — you want
**`v3`**:

```bash
git checkout v3
```

---

## `main` — the merged demo system

```
app/                            Android app  (Kotlin, package com.example.qhackgps — "qhackGPS")
board/distance-watch/           Cane board app  (Arduino UNO Q: sketch + Python + web dashboard + QCane host daemon)
board/ble-bridge/               Nordic UART bridge the phone talks to  (advertises "Distance Watch")
board/depth-spike/              Depth bench + USB ground-cam navigator + terrain sensor  (bench: the experiment behind PERFORMANCE.md)
arduino/qhack_guidance_motor/   Older HC-05 SPP demo sketch, fed by the `QG,…` wire lines
Hardware/                       CAD, BOM, assembly
docs/                           this documentation
```

The app is a landscape map HUD: Google walking routes or straight-line
compass mode, alignment guidance with hysteresis and a persisted
heading-trim calibration, system-TTS voice on transitions, and phone-buzz
haptics while the cane reports an obstacle. A push-to-talk companion —
v3's Qwen SLM on the Hexagon NPU — answers questions grounded in what the
camera scan sees, reads signs on request, and takes spoken navigation
commands ("take me to…"). The **phone is the only
steering authority** — the board senses (ToF distance) and actuates (wheel,
vibro), but every turn is the phone's call, sent as `AVOID LEFT`/`AVOID
RIGHT` (full-speed dodge), `TURN LEFT|RIGHT <deg>` (route turn), or
`CLEAR`/`STOP` (wheel stop) and parsed by
`board/distance-watch/python/main.py`. The optional camera scan folds into
that same vocabulary; its precedence table is in [`SCAN.md`](SCAN.md).

The port from `v3` was **selective**: the thirds decision logic, the YOLO
detector plumbing, and the QNN session tiering came over
(`app/src/main/java/com/example/qhackgps/scan/`), followed by the
companion SLM with its push-to-talk voice loop and OCR (`…/llm/`,
`…/speech/`) and the board depth spike. The v3 app itself — package
`dev.quad.shepherd`, with the segmentation ensemble, metric depth, the
BEV planner, and the neural voice — was **not** merged and remains
complete on its branch.

## `v3` — the full perception system

The complete implementation. Both halves in one tree:

```
app/                     Android app  (Kotlin, package dev.quad.shepherd)
board/qcane-wheel/       Cane board app  (Arduino UNO Q: sketch + Python + host daemon)
board/ble-bridge/        Nordic UART side channel, for nRF-style debugging
board/depth-spike/       On-board depth benchmark — the experiment behind docs/PERFORMANCE.md
tools/walkability/       Offline planner evaluation harness (numpy mirror of the on-device pipeline)
scripts/                 Model export + fetch
```

Everything in [`ARCHITECTURE.md`](ARCHITECTURE.md), [`MODELS.md`](MODELS.md),
[`BOARD.md`](BOARD.md), and the measured numbers in
[`PERFORMANCE.md`](PERFORMANCE.md) describes **this branch** — not the app
on `main`. What `main` took from here: the screen-thirds decision logic
and the detector pipeline under it ([`SCAN.md`](SCAN.md)), the companion
SLM with its voice loop, and `board/depth-spike/`.

Two directories are worth knowing about even though they don't ship:

- **`board/depth-spike/`** — the CPU depth benchmark whose results are in
  [`PERFORMANCE.md`](PERFORMANCE.md). It's why depth runs on the phone.
- **`tools/walkability/`** — replays burst photos through a numpy mirror of
  `TraversabilityGrid` + `PolarPlanner`, scoring candidate pipelines by
  **angle spread** across a burst. Lower is better; that's decision jitter,
  measured offline before anything reaches the phone. Its constants must be kept
  in sync with the Kotlin by hand.

## `qhackfinal` — what `main` was merged from

The demo pairing, developed alongside `v3` rather than from it (`git
merge-base --is-ancestor` confirms `v3` is not in its history). It combined
`qhackgps` (the phone navigator) and `qhackcane` (the distance-sensor
board) into one branch (868b2c3), ported v3's merged wheel firmware into
`board/distance-watch/` (cd974fd), and then evolved the pair into the
system now on `main`:

- the phone became the **sole steering authority** (caffc03) — the board
  streams `{"mm","p"}` and obeys text, and never decides a turn;
- every wheel spin **pinned to 100 % duty** in the firmware, and the
  dashboard grew arrow-key driving (b3951af);
- guidance is **spoken on transitions only** (44e7bf8), and the board's
  dashboard buttons send `{"say":…}` lines the phone reads aloud;
- the BLE bridge pins the adapter alias to **"Distance Watch"** and
  retries forever (363bf09, 937f612).

`main` merged this branch wholesale, then added the camera scan on top.

## `qhackgps` — standalone compass navigation

An independent, still-runnable Android app (`com.example.qhackgps`) — the routing
prototype. Jetpack Compose map HUD, Google Directions walking routes, green-light
alignment with hysteresis, and guidance export over **Bluetooth Classic SPP** to
an Arduino:

```
QG,<dir>,<deltaDeg>,<distanceM>,<headingDeg>,<bearingDeg>,<aligned>,<obst>,<obstMM>
```

It is **not an ancestor of `v3`** — `git merge-base --is-ancestor` confirms no
shared lineage for the app code. `v3` reimplemented the useful ideas (path-first
steering, look-ahead bearing, cane-STOP haptics) in Kotlin under a different
package rather than merging this branch. It **is** an ancestor of `qhackfinal`,
and therefore of the app now on `main` — which still emits this exact wire line
at ~5 Hz, with `arduino/qhack_guidance_motor/` as its Arduino-side consumer.

Kept because it is where the merged app started, and because it documents the
ASCII/SPP transport that predates both the NUS cane link and the GATT protocol.

## `qhackgps-areamap` — persistent obstacle memory

Despite the name, this branch carries the **Shepherd app**
(`dev.quad.shepherd`) plus the mapping stack that
[`AREA_MAP_SCOPE.md`](AREA_MAP_SCOPE.md) plans against: a world-anchored
log-odds occupancy map (`AreaMap.kt`), ARCore pose, and A* planning over
remembered obstacles. Not merged into `main`.

## History

Kept for provenance. Each is a coherent snapshot, not a broken WIP.

| Branch | What it was |
|---|---|
| `v2` | Walkability segmentation + BEV traversability grid + polar planner — the first version of the current perception approach |
| `arduinov1` | Board bring-up: Modulino Distance → web dashboard, then Motors steering and Vibro haptics. Merged into `v3` under `board/` |
| `qhackcane` | Standalone distance-sensor board app with the BlueZ auto-pairing agent |
| `shepherd-snapdragon` | v1: detector + pinhole distance estimate + 9-column threat map. No depth model, no grid |
| `fastscnn-depthanything` | Model-selection spike — segmentation and depth candidates |

## Lineage

```
shepherd-snapdragon ──▶ v2 ──▶ v3   ◀── arduinov1 (merged as board/)
                               │    ◀── qhackcane (concepts)
                               │
                               ├── firmware ported (cd974fd) ──▶ qhackfinal
                               └── scan/ ported (selective) ───▶ main
qhackgps ──┬──▶ qhackfinal ──▶ main   (merged wholesale)
qhackcane ─┘
qhackgps-areamap ─────────────────────  Shepherd app + areamap; not merged
fastscnn-depthanything ───────────────  spike, not merged
```

The branch names carry an earlier project name. They're left alone on purpose —
rewriting published branch names would break every existing clone and reference
for no functional gain.

## A note on naming

On `v3` the Kotlin package is `dev.quad.shepherd` and the central service
class is `ShepherdService`; on `main` the app package is
`com.example.qhackgps`. Those are the real identifiers you need in order to
navigate each source tree, so the documentation cites them verbatim. The
project is called Lighthouse; the package renames are deferred as mechanical
changes that touch many files and carry build risk disproportionate to their
benefit right now.

Board and BLE identity is **not** legacy. These are live on-the-wire names
and must match the phone exactly — don't tidy them:

- **"Distance Watch"** and the Nordic UART Service
  (`6E400001-B5A3-F393-E0A9-E50E24DCCA9E`, with its RX/TX
  characteristics `…0002`/`…0003`) — the peripheral `main`'s app
  auto-discovers (`board/ble-bridge/`).
- **`QCane`, `QCane-Wheel`, the `bcf2f193-…` service** — the GATT/SPP
  identity of the `qcane_btd` host daemon, and what `v3`'s app scans for.
