# Branches

`main` is a **documentation and hardware hub** — it carries the README, license,
and `Hardware/`, but no application code. The code lives on branches. This is
deliberate: `main` is the front door, and the two runnable systems are two
different applications rather than one tree.

If you are here to run something, you want **`v3`**.

```bash
git checkout v3
```

---

## `v3` — the system

The complete implementation. Both halves in one tree:

```
app/                     Android app  (Kotlin, package dev.quad.shepherd)
board/qcane-wheel/       Cane board app  (Arduino UNO Q: sketch + Python + host daemon)
board/ble-bridge/        Nordic UART side channel, for nRF-style debugging
board/depth-spike/       On-board depth benchmark — the experiment behind docs/PERFORMANCE.md
tools/walkability/       Offline planner evaluation harness (numpy mirror of the on-device pipeline)
scripts/                 Model export + fetch
```

Everything in [`ARCHITECTURE.md`](ARCHITECTURE.md), [`MODELS.md`](MODELS.md), and
[`BOARD.md`](BOARD.md) describes this branch.

Two directories are worth knowing about even though they don't ship:

- **`board/depth-spike/`** — the CPU depth benchmark whose results are in
  [`PERFORMANCE.md`](PERFORMANCE.md). It's why depth runs on the phone.
- **`tools/walkability/`** — replays burst photos through a numpy mirror of
  `TraversabilityGrid` + `PolarPlanner`, scoring candidate pipelines by
  **angle spread** across a burst. Lower is better; that's decision jitter,
  measured offline before anything reaches the phone. Its constants must be kept
  in sync with the Kotlin by hand.

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
package rather than merging this branch.

Kept because it is a working second demo, it documents the ASCII/SPP transport
that predates the GATT protocol, and it has its own README and Arduino sketch.

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
                                    ◀── qhackcane (concepts)
qhackgps ─────────────────────────────  independent; ideas reimplemented in v3
fastscnn-depthanything ───────────────  spike, not merged
```

The branch names carry an earlier project name. They're left alone on purpose —
rewriting published branch names would break every existing clone and reference
for no functional gain.

## A note on naming

The Kotlin package is `dev.quad.shepherd` and the central service class is
`ShepherdService`. Those are the real identifiers you need in order to navigate
the source, so the documentation cites them verbatim. The project is called
Lighthouse; the package rename is deferred as a mechanical change that touches
~60 files and carries build risk disproportionate to its benefit right now.

Board and BLE identity — `QCane`, `QCane-Wheel`, the `bcf2f193-…` service — is
**not** legacy. Those are live on-the-wire names and must match the phone
exactly. Don't tidy them.
