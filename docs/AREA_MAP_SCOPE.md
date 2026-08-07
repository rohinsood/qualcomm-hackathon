# Scope: cane ToF → the areamap (never hit the same obstacle twice)

> **Status: historical scope document.** Written on `qhackfinal` before the
> merge to `main`, as the plan for feeding cane hits into the areamap. It is
> not what shipped: the merge brought `v3`'s screen-thirds camera scan
> instead ([`SCAN.md`](SCAN.md)), and none of the phases below are on
> `main`. The mapping stack this plugs into lives on the `qhackgps-areamap`
> branch ([`BRANCHES.md`](BRANCHES.md)). Kept because the observation model
> — weak arcs, evidence-only, no ray-clearing from a swinging beam — still
> holds if the work is picked back up.

Goal: every time the distance Modulino runs into an obstacle, that obstacle
gets remembered in a map, so later passes route around it instead of
rediscovering it by contact.

## What already exists (qhackgps-areamap branch)

The `qhackgps-areamap` branch carries the full mapping stack this feature
should plug into — do **not** build a second map:

- `map/AreaMap.kt` — persistent, world-anchored occupancy map: 10 cm cells
  in sparse 6.4 m tiles, log-odds evidence, **ray-clearing** (free space is
  carved along the beam before the endpoint is marked), lazy per-tile decay,
  and two channels — STATIC (walls, kerbs; what the planner routes over)
  and DYNAMIC (a person who just stepped in; seconds of decay).
- `pose/ArCoreTracker.kt` + `world/WorldAnchor.kt` — the map lives in the
  rigid **AR world frame**; GPS/compass corrections move only the route
  (via a Procrustes-fit bearing), never the accumulated cells.
- `plan/AStarPlanner.kt` + `CostMap.kt` + `PathFollower.kt` — global search
  over STATIC with obstacle inflation; "path-first, deviate only around
  obstacles" (a0a706d).
- Feeds today: phone metric depth (Depth-Anything) + ARCore pose. 128 unit
  tests green (`AreaMapTest` etc.), debug overlay on the nav map (b32b515).
- Cane link: mm readings + presence already stream to the phone over BLE
  (~2–4 Hz on the NUS transport; the areamap branch's `CaneBleLink` was
  retargeted to the QCane GATT — **phase 0 below**).

## The core problem

A map ingests observations as *(beam origin, bearing, range)* in the AR
frame. The cane knows only **range**. The phone knows its own pose — but
the cane is a separate, swinging object: its beam direction relative to the
phone is unknown, easily ±30° while walking. Stamping cane hits as if they
came from the phone's forward vector is wrong in exactly the way that fills
a map with phantom walls — unless the observation model admits the
uncertainty.

## Phased plan

**Phase 0 — transport (half a day).** Ensure the mm/presence stream reaches
the Shepherd app on the areamap branch (its `CaneBleLink` now speaks QCane
GATT, which carries wheel state, not distance). Either re-add a NUS client
for the `{"mm":…,"p":…}` stream or extend the QCane GATT STATE
characteristic with the mm field. Board change: none (it already streams).

**Phase 1 — low-confidence arc observations (1 day).** New
`AreaMapper.observeCane(pose, headingRad, rangeM)`:

- Mark an **arc** of cells at the measured range, spread ±25° around the
  phone's heading, with log-odds weight well below a camera-depth hit
  (e.g. 1/4) — several cane hits over seconds accumulate into a real
  obstacle; one glancing swing does not.
- **No ray-clearing from cane beams.** Clearing along a mis-aimed bearing
  erases true obstacles the camera found; the cane only ever adds evidence.
  (Clearing stays the camera's job — this is the asymmetry that keeps the
  map honest.)
- Feed both channels like any observation; STATIC is what makes the "don't
  hit it twice" promise — `CostMap` inflation then routes A* around the
  remembered cells, and `PathFollower` never leads the user back into it.
- Trigger on presence-enter events (the debounced flip), not on every
  reading, plus a re-mark every ~1 s while presence holds. Gate to
  readings ≤ 1.2 m (sensor trust range).
- Announce on re-approach: when the planned path passes within ~2 m of
  remembered STATIC mass, the new TTS layer says "Remembered obstacle
  ahead, rerouting." (hook: `PathFollower` deviation events.)
- Tests mirror `AreaMapTest`: arc spread, weight ratio vs camera, no-clear
  invariant, accumulation over N hits.

**Phase 2 — a real cane bearing (2–3 days, hardware).** The kit's
**Modulino Movement (LSM6DSOX IMU)** joins the cane's Qwiic chain; the
sketch streams orientation at ~20 Hz (compact framing — NUS chunks are
20 B). Gravity gives pitch/roll directly; yaw drifts, so fuse it against
the phone heading with a slow bias estimator (the cane swings *about* the
walking heading — its average yaw ≈ heading). Result: arc narrows from
±25° to ±8–10°, and low obstacles (kerbs — below the camera's view) become
the cane's signature contribution to the map.

**Phase 3 — persistence across sessions (2 days).** The AR frame dies with
the session. Export STATIC tiles above an evidence floor through
`WorldAnchor` into ENU (lat/lng anchored GeoJSON); on the next session,
re-import through the new anchor with inflated uncertainty (spread each
cell by the anchor's current θ/translation error) and a capped prior
weight, so a stale or badly-anchored memory biases the planner without
overruling fresh observations. This is the piece that makes "again"
mean *ever*, not just *this walk*.

## Risks / limits (honest)

- Phase 1's ±25° arc means isolated single hits are deliberately weak —
  that is the tuning knob between "remembers real obstacles" and "map
  fills with smear". Start weak; raise only with field evidence.
- The camera depth already sees most of what the cane hits; the cane's
  unique value is **below the frame** (kerbs, steps) and phone-in-pocket
  operation. Judge phase 2 by kerb recall, not by total cells added.
- GPS-anchored persistence (phase 3) inherits WorldAnchor's error budget
  (±2–5 m typical) — remembered obstacles are advisory cost, never hard
  walls.

Total: ~4–6 working days across the three phases, all phone-side except
phase 2's IMU streaming (small sketch addition).
