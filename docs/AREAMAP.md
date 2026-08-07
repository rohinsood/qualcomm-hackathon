# The areamap

A persistent, world-anchored occupancy map that remembers where the user
has been and what is in the way, and plans a route through it toward the
Google walking route.

Status: the geometric core is built and unit-tested (128 tests green). The
ARCore backbone and the wiring into the live pipeline are next — see
[Remaining work](#remaining-work).

## Why

`path/TraversabilityGrid` describes itself as accumulating evidence "in
WORLD space". It does not. Cells are indexed relative to wherever the
camera is *at this instant*, and nothing ever shifts or rotates the array
between frames — `PolarPlanner.rotateFrame()` stabilises only the
committed steering *angle*, not the grid it raycasts.

It survives that by forgetting. `decay()` runs once per depth frame, and
depth is gated to 300 ms (`DEPTH_INTERVAL_MS`), so `0.94ⁿ = 0.5` lands at
about 11 frames — a **~3.4 s half-life, roughly 4.7 m of walking**, in a
grid only 6 m deep. Hence the symptoms: obstacles smearing behind you,
phantom walls after a turn, and a planner that walks into a concavity and
freezes.

Three things follow from that, in dependency order:

1. **A pose.** Nothing can be anchored without one, and there was no
   odometry anywhere in the tree.
2. **A map with fixed world coordinates**, so evidence accumulates rather
   than smears, and a dead end probed once is remembered.
3. **A search**, because VFH+ is a reflex, not a plan. It cannot route
   around a U-shaped obstacle or a van parked across the pavement.

## Frames

Three frames, and every conversion between them is unit-tested, because a
sign error here does not crash — it silently builds a mirrored map.

| Frame | Axes | Holds |
|---|---|---|
| **AR world** | X = session-start right, Y = up, −Z = session-start forward | the areamap |
| **AR planar** (`Pose2d`) | `x` = X, `y` = −Z; bearings clockwise from +y | poses, cells |
| **ENU** (`LocalFrame`) | east / north metres about a lat-lng anchor | the Google route |

**The map is stored in the AR frame, not in ENU.** This is the load-bearing
decision. ARCore's frame is rigid and gravity-aligned, so evidence stamped
ten seconds ago still lines up with evidence from now. An ENU frame derived
from GPS and compass is *not* rigid — it drifts and gets corrected — and
storing cells there would mean every correction silently displaced millions
of already-accumulated cells. Instead `WorldAnchor` carries the correction,
and a correction re-projects only the route: a few hundred points.

`LocalFrame`'s constants are deliberately identical to the ones
`nav/RouteTracker` already uses, so a route point and a map coordinate
agree to the last bit rather than to "about a metre".

### WorldAnchor

Three numbers separate the frames — a bearing θ and a translation — because
both are metric and gravity-aligned:

```
east  =  x·cos θ + y·sin θ + tE
north = -x·sin θ + y·cos θ + tN
```

θ is seeded from the compass (instant, but worth ±10–20° near rebar) then
refined by fitting the ARCore trajectory against GPS fixes — a weighted 2-D
Procrustes with the scale pinned at 1, closed-form at `θ = atan2(B, A)`.
Fitting the *shape of a walked path* beats a magnetometer reading and gets
better the further you walk.

Two safeguards matter:

- **An unobservable θ is reported as unknown, never guessed.** Standing
  still leaves the bearing undetermined; `solve()` returns null below
  `MIN_SPREAD_M` of trajectory spread rather than returning a confident
  wrong answer that would rotate the entire map.
- **Corrections slew, they don't teleport.** Capped at ~2°/s and 0.35 m/s,
  so the route projected into map space eases across instead of yanking
  guidance sideways mid-stride.

## The map

`map/AreaMap` — sparse 32×32-cell tiles at 10 cm, hashed by tile
coordinate, so memory tracks the area explored rather than a bounding box.
A kilometre of walking is a few hundred tiles.

**Two evidence channels.** `STATIC` decays slowly (walls, kerbs, parked
cars) and is what the planner routes over; `DYNAMIC` decays in seconds
(someone stepping in front of you) and is what the reactive layer reads.
An observation feeds both; only time separates them.

**Rays, not points.** Every observation clears the cells *along* the beam
before marking the endpoint. This is what stops the map filling with
ghosts: `TraversabilityGrid` stamps endpoints only, so anything that moves
leaves permanent evidence behind it. Tested directly — `clearing along the
beam erases a ghost that moved away`.

**Decay is lazy**, applied per tile from an update counter. Tiles nobody is
looking at hold their evidence, because nothing has contradicted them, and
an update costs O(observed area) rather than O(map).

`egoView()` derives the ego-centric grid `PolarPlanner` already expects by
rotating a window out of the world map — one source of truth, and unlike
the grid it replaces, it is still correct after the user turns around.

### Depth → scan

`map/ScanBuilder` reduces a depth frame to one nearest-obstacle range and
one trusted-free range per bearing bin. Not a shortcut: past the nearest
obstacle along a bearing the world is occluded, so a farther hit carries no
information. It turns ~19 000 rays a frame into ~180.

The projection matches `TraversabilityGrid` exactly — same un-roll, then
pitch, then height test — because that math is field-tested and two subtly
different projections in one app is a bug generator.

## The planner

`plan/CostMap` coarsens the map to 40 cm for search, taking the **worst**
occupancy per block so downsampling can only ever be conservative.

**Extent is local (~60 m), and that is deliberate.** The destination is not
this planner's problem — Google's route already solved streets and
crossings. What it cannot know is that a van is parked across the pavement.
So A*'s goal is the route's look-ahead point, and the search only has to be
big enough to get around what is in the way of it. That keeps the grid at
~22 000 cells instead of the millions a plan-to-destination window needs.

Costs, additive on a base of 1:

| Term | Effect |
|---|---|
| Inflation | impassable within a body radius; linear falloff over a clearance band, so paths prefer the middle of a corridor |
| Unknown | penalised but **passable** — refusing unseen ground sounds safe and isn't: at startup everything is unknown and the user would never move |
| Visited | small discount for ground physically walked on, the only cells with ground truth behind them |
| Corridor | gentle pull toward the route, so a detour comes back rather than wandering off across a plaza |

`plan/AStarPlanner` — 8-connected A* with three details that earn their
keep:

- **Corner cutting is forbidden.** A diagonal between two blocked
  orthogonals is free on a grid and impossible in a corridor.
- **The heuristic is scaled by the cheapest cell**, not by 1. The visited
  discount pushes the minimum below 1, and an unscaled octile heuristic
  would stop being admissible.
- **Line-of-sight smoothing.** Raw 8-connected output is a staircase, and a
  staircase bearing oscillates ±45° every few steps.

`lineOfSight` fails **closed** if its step guard trips — claiming a clear
line would let the smoother replace a path that went round an obstacle
with one that goes through it.

`plan/PathFollower` converts the path to the one number the rest of the app
already speaks: a signed `goalAngleDeg`, pure-pursuit against a ~3 m
look-ahead.

### What this buys

The headline test is `a U-shaped trap is escaped by planning backwards`:
three walls around the user, opening behind them, goal beyond. The polar
planner steers at the best-looking gap in view, walks into the pocket and
stops. A* produces a first move *away from the goal* — out of the mouth of
the U — which no gap-seeker can.

## Loadout

`Loadout.kt` gates the heavy subsystems. Everything is disabled, not
deleted: each engine still compiles, keeps its call sites, and returns by
flipping one flag.

| Flag | State | Note |
|---|---|---|
| `OBJECT_DETECTION` | off | fed labels and depth-scale calibration; the map owns steering and ARCore depth is already metric |
| `MONO_DEPTH` | off | Depth-Anything-V2, superseded by ARCore's Depth API |
| `SEGMENTATION` | off | FFNet + SegFormer walkability |
| `NEURAL_TTS` | off | sherpa-onnx voice and its ~130 MB download. **System TTS still speaks** — turn cues and arrival are not optional in a blind-navigation app |
| `COMPANION_SLM` | off | was already parked |
| `OCR` | off | on-demand sign reading |
| `ARCORE` | on | the new pose and depth source |

Detection used to gate the whole pipeline: `startPipeline()` aborted on a
failed `DetectionEngine.initialize()`, taking the camera, navigation and
cane link with it. Engines now initialise independently, and only a model
that was actually *asked* for reports a failure.

### Trade-offs of this loadout

Stated so they are a choice, not a surprise:

- **ARCore depth is motion stereo.** It degrades on textureless surfaces —
  a blank wall is exactly the obstacle that matters most. `MONO_DEPTH` is
  the complement for that case, and the cane's Modulino ray covers the near
  field regardless.
- **No walkability semantics** with segmentation off. The map is pure
  geometry: it can tell a kerb from flat ground but not road from pavement.
  The route corridor carries that job for now.
- **ARCore needs the camera pointed at a textured, lit scene.** That
  conflicts with the service's "works with the phone in a pocket" premise.
  Mitigation is the `PoseSource` interface: ARCore primary, dead reckoning
  on `TrackingState.PAUSED`, and a map that freezes stamping rather than
  smearing when confidence drops.

## Remaining work

- **ARCore backbone** — session behind an offscreen EGL context in the
  service, depth extraction, plane-derived ground, pose → `Pose2d`. The
  dependency (`com.google.ar:core:1.54.0`) and manifest are in place.
- **PDR fallback + `FusedPose`** — epoch handling on tracking loss, so a
  restart into a new world frame drops the map rather than painting ghosts
  across it (`AreaMap.resetForEpoch`).
- **Wiring** — `pathPipeline.goalAngleDeg` from the planner ahead of
  `CompassNav`; `PolarPlanner` fed from `egoView()`.
- **Record/replay harness** — dump `{pose, depth, cane, GPS}` on device,
  replay on the JVM. The highest-leverage item on this list; map and
  planner bugs are otherwise only reproducible by walking around outside.
- **Debug UI** — map, planned path and trail over the existing `MapView`.

## Safety

Prototype. Not a sole mobility aid. Test with a sighted companion and treat
every output as advisory.
