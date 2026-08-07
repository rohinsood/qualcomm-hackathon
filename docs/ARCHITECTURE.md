# Lighthouse — Architecture

Lighthouse is an on-device navigation aid for blind and low-vision users. A
Snapdragon phone runs the whole perception and planning stack locally; a
cane-mounted Arduino UNO Q carries a time-of-flight sensor, a haptic vibro
module and a steering wheel motor, and talks to the phone over BLE.

Everything in this document is drawn from the code on `origin/v3`. Constant
names are the real identifiers, so every number below can be grepped.

> Note on naming: the Kotlin package is `dev.quad.shepherd` and the hub class
> is `ShepherdService` — the package retains an earlier working name for the
> project and was not renamed.

---

## 1. System overview

```
┌───────────────────────────────── PHONE (Snapdragon 8 Elite / SM8750) ─────────────────────────────────┐
│                                                                                                       │
│  CameraX ImageAnalysis                                    Sensors                                     │
│  (KEEP_ONLY_LATEST)                                       TYPE_GRAVITY ──► pitchRad, rollRad          │
│        │                                                  TYPE_ROTATION_VECTOR ─► headingDeg          │
│        ▼                                                  FusedLocation (1 Hz) ─► lat/lng             │
│  ┌──────────────────────────── FrameAnalyzer ────────────────────────────┐        │                   │
│  │ YUV → sensor-upright → gravity-upright → 640×640 letterbox            │        ▼                   │
│  │                                                                       │   ┌──────────────┐         │
│  │  DetectionEngine   YOLOv8    1 Hz   ─► labels / blackboard / scale    │   │  CompassNav  │         │
│  │  DepthEngine       DA-v2     3.3 Hz ─► metric depth (in / out model)  │   │ INDOOR beeline│        │
│  │  SegEngine         FFNet | SegFormer-ADE ─► walkability mask          │   │ OUTDOOR route │        │
│  └───────────┬───────────────────────────────────────┬───────────────────┘   └──────┬───────┘         │
│              │ depth + walkable                     │ segClearance                 │ goalAngleDeg    │
│              ▼                                      ▼                              ▼                 │
│      ┌────────────────────┐   accumulate    ┌──────────────────────────────────────────┐              │
│      │ TraversabilityGrid │◄──log-odds──────│              PathPipeline                │              │
│      │  61 × 60 @ 0.10 m  │                 │  (owns grid + planner, IMU/nav inputs)   │              │
│      │  ego-centric BEV   │────raycast─────►│              PolarPlanner                │              │
│      └────────▲───────────┘                 │  37 sectors / 180° · DEFAULT ⇄ AVOID     │              │
│               │ markNearObstacle            └───────────────────┬──────────────────────┘              │
│               │                                                 │ Plan(guidance, stop, avoiding)      │
│               │                          ┌──────────────────────▼──────────────────────┐             │
│               │                          │ ShepherdService.onFrame                     │             │
│               │                          │  SteerFusion (v1 fallback only)             │             │
│               │                          │  SceneBlackboard · HapticFeedback · UI      │             │
│               │                          │  CommandAggregator  ── 200 ms vote ──► L/R/S/X            │
│               │                          └──────────────────────┬──────────────────────┘             │
└───────────────┼─────────────────────────────────────────────────┼────────────────────────────────────┘
                │  CaneReading{mm, present}          motor letter │   BLE GATT (Nordic-UART-style)
                │  ◄── TX notify bcf2f195…                        └──► RX write bcf2f194…
┌───────────────┴───────────────────── CANE BOARD (Arduino UNO Q, "QCane") ─────────────────────────────┐
│  Linux side: qcane_btd.py / main.py   — GATT server, policy, dashboard :7000, re-sends state 4×/s     │
│                     │ Unix socket                                                                     │
│  MCU sketch (Wire1 Qwiic chain): Modulino Motors ─ Modulino Vibro ─ Modulino Distance (VL53L4CD)      │
│  COMMAND_TIMEOUT_MS = 2000 → all outputs off if the Linux side goes quiet                            │
└───────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

**The control loop.** Every camera frame that survives the duty-cycle gate is
made gravity-upright and letterboxed to 640×640. Detection runs at 1 Hz, dense
metric depth at ~3.3 Hz, and segmentation alongside depth. Each depth frame is
projected through the camera model onto the ground plane and folded into a
persistent ego-centric bird's-eye-view log-odds grid — so steering rests on
accumulated world-space evidence rather than one frame's opinion. The polar
planner then raycasts that grid *every* frame (raycasts are cheap, inference is
not), producing a heading that in the normal case simply *is* the navigation
goal, and only deviates while the goal direction is actually obstructed. The
resulting guidance is voted into a single motor letter every 200 ms and written
over BLE to the cane, which independently fails safe if the phone stops
talking. The cane's ToF reading comes back the other way and is injected into
the grid as hard near-field evidence.

---

## 2. Frame pipeline

`FrameAnalyzer` (`app/src/main/java/dev/quad/shepherd/vision/FrameAnalyzer.kt`)
is a `ImageAnalysis.Analyzer` bound by `ShepherdService.bindCamera()`.

### CameraX configuration

| Setting | Value | Why |
|---|---|---|
| Backpressure | `STRATEGY_KEEP_ONLY_LATEST` | Guidance wants the *newest* view of the world; a queued frame is a stale frame. |
| Camera | `DEFAULT_BACK_CAMERA` | Chest/pocket-facing forward view. |
| Analyzer executor | single-thread `Executors.newSingleThreadExecutor()` | Serializes inference; the grid/planner state is then free of locks on the hot path. |
| Use cases | `Preview` + `ImageAnalysis` | Preview surface is attached late and identity-checked (see §8). |
| Service type | `FOREGROUND_SERVICE_TYPE_CAMERA` (`| _LOCATION` once granted) | Guidance must survive screen-off / in-pocket operation. |

### Gravity and orientation

The gravity sensor callback in `ShepherdService` derives both angles from the
raw accelerometer-free gravity vector:

```kotlin
pathPipeline.pitchRad = asin((gz / g).coerceIn(-1f, 1f))   // rear camera looks along -Z
pathPipeline.rollRad  = atan2(gx, gy)                       // 0 = upright portrait
```

`PathPipeline.gravityUprightDeg` snaps roll to the nearest 90° (normalising the
±270° wrap), and the analyzer pre-rotates the frame by `-gravityDeg` before
inference. The reason is concrete: the depth and segmentation networks are
**not rotation-invariant**, so a phone held landscape or upside down must still
present an upright world to them. What is left over —
`PathPipeline.residualRollRad()` = `rollRad` minus the snapped part — is *not*
thrown away; it is handed to the grid, which un-rolls image-plane coordinates
analytically (§4). Two-stage correction: coarse by bitmap rotation (cheap, keeps
the model in-domain), fine by math (exact, no resampling cost).

The overlay counter-rotates using `FrameResult.gravityDeg`, so the debug
rendering stays aligned with what the user sees.

### Letterboxing

The upright frame is scaled by `scale = 640 / max(w, h)` and centered into the
640×640 square with symmetric padding `padX`, `padY`, drawn over a black
`clearPaint` rect. `DetectionEngine.INPUT_SIZE = 640`.

Two consequences are handled explicitly:

1. **Boxes are mapped back**: `(d.x1 - padX) / scale`, clamped to frame bounds,
   so detections live in camera-frame pixel space downstream.
2. **The bars are poison for depth.** `maskLetterboxBars()` writes `Float.NaN`
   over every depth pixel that lands on padding. The code comment records why:
   the depth model *hallucinates geometry* for the black bars — up to 25% of the
   square — which would corrupt the grid projection, the ground-plane estimate,
   the near-field columns and the box medians. NaN is the right sentinel because
   it sorts last and fails `isFinite()` gates, so every downstream percentile or
   median degrades to "no signal" rather than to garbage.

### Cadences

| Constant | Value | Effective rate | Rationale (from the code) |
|---|---|---|---|
| `MIN_FRAME_INTERVAL_MS` | `90L` | ~11 fps ceiling on the whole analyzer | Back-to-back running on every camera frame "kept the GPU pinned and thermal-throttled the whole SoC — guidance needs 10 Hz, not 30". |
| `DEPTH_INTERVAL_MS` | `300L` | ~3.3 Hz | "Walls don't move at frame rate." Depth is the latency-critical model and gets the freed budget. |
| `DETECT_INTERVAL_MS` | `1000L` | 1 Hz | Steering no longer uses YOLO — the grid owns it. Boxes only feed labels, the `SceneBlackboard`, and the `DepthCalibrator` scale samples. Between runs `lastModelSpace` is reused. |
| `CommandAggregator.PERIOD_MS` | `200L` | 5 Hz motor letters | See §7. |
| Planner | every surviving frame | ~11 Hz | Raycasting a 61×60 grid is nearly free; re-planning is how a stale grid still yields a fresh heading. |

Other frame-level constants: `DEBUG_NEAR_M = 0.3f` / `DEBUG_FAR_M = 6.0f` fix
the debug colour ramp; `DepthEngine.CORRIDOR_TOP = 0.25f` /
`CORRIDOR_BOTTOM = 0.65f` bound the depth analysis band, reported back in
camera-frame Y as `corridorTop` / `corridorBottom`.

**Depth scale calibration.** When depth and detection ran on the *same* frame,
untruncated boxes (`d.y1 >= 8f`, `d.y2 <= size - 8f`) with a pinhole
`DistanceEstimator.estimate()` prior contribute `(model metres, reference
metres)` pairs to `DepthCalibrator`. The `runDetect` guard is deliberate: "stale
boxes against fresh depth mislead".

**Async ADE segmentation.** The SegFormer-ADE member runs on its own
`adeExecutor` single thread at `NORM_PRIORITY - 1`, guarded by `adeBusy`. In
NPU/CPU mixed mode it is far slower than FFNet, and inline it stalled the
depth → grid → plan hot path. Walkability is *soft* evidence, so a mask a few
hundred milliseconds stale is acceptable; the worker publishes `r.copyOf()`
because the engine reuses its output buffer.

---

## 3. Accelerator strategy

`OrtSessions` (`app/src/main/java/dev/quad/shepherd/vision/OrtSessions.kt`) is
the single session factory for all ONNX Runtime engines. It exists because of
one specific trap.

### The silent-CPU problem

When QNN fails to claim graph nodes, ONNX Runtime **silently runs the whole
session on CPU**. A session "created with QNN" therefore proves nothing. The
strict tiers set:

```kotlin
opts.addConfigEntry("session.disable_cpu_ep_fallback", "1")
```

which makes *session creation itself fail* unless the entire graph compiled for
that accelerator. Only sessions that clear that bar earn the bare `"NPU"` /
`"GPU"` provider labels surfaced in `ShepherdService.visionLabel`. This turns an
unfalsifiable claim into a measurable one.

### Tier ladder

`createTiers()` walks strict tiers first, then mixed, then plain CPU:

| Order | Tier | Strict? | Label on success |
|---|---|---|---|
| 1 | GPU (`libQnnGpu.so`) | yes | `GPU` |
| 2 | NPU (`libQnnHtp.so`) | yes | `NPU` |
| 3 | GPU | no | `GPU/CPU mixed` |
| 4 | NPU | no | `NPU/CPU mixed` |
| 5 | default `SessionOptions()` | — | `CPU` |

`createFromPath(..., preferNpu = true)` **flips** tiers 1↔2 and 3↔4, for models
with external-data weights whose `.data` file must resolve relative to the
`.onnx` path. `SegEngine` is the caller that opts in.

### Why GPU first for vision

The workload split is deliberate: **vision on the Adreno, the companion SLM on
the Hexagon.** The header records the measurement that forced this ordering —
when the NPU tier led, vision landed on the Hexagon and throttled Qwen's decode
from **~12 tok/s to ~5**. The accelerators must not share. `SegEngine` is the
exception that proves the rule: FFNet is tiny, and the GPU is already carrying
YOLO plus depth, so segmentation is the one workload that *should* live on the
Hexagon (`preferNpu = true`).

### HTP options

| Key | Value | Why |
|---|---|---|
| `backend_path` | `libQnnHtp.so` | Hexagon HTP backend. |
| `htp_performance_mode` | `burst` | Inference is bursty and latency-critical, not sustained. |
| `enable_htp_fp16_precision` | `1` | fp16 on HTP — depth/seg tolerate it, and it buys throughput. |
| `soc_model` | `69` | SM8750 (Snapdragon 8 Elite, incl. the `-AC` "for Galaxy" variant), from the AI Hub catalog. |
| `htp_arch` | `79` | Hexagon v79. |

The SoC is spelled out rather than auto-detected because QNN's auto-detection of
the SM8750-AC variant is unproven.

GPU is simply `backend_path = libQnnGpu.so`.

### Vendor-library gotchas (documented in the file header)

QNN's accelerator backends `dlopen` **vendor** libraries — `libcdsprpc.so` for
the Hexagon DSP, `libOpenCL.so` for Adreno — which Android blocks unless the
manifest declares them via `<uses-native-library>`. Without that, HTP fails with
`QNN_DEVICE_ERROR_INVALID_CONFIG`, GPU with
`QNN_COMMON_ERROR_PLATFORM_NOT_SUPPORTED`, and everything lands on CPU. The NPU
additionally needs `libQnnHtpV*Skel.so` extracted to disk
(`useLegacyPackaging`), because the DSP opens it by file *path*.

### Domain-matched model selection

`FrameAnalyzer.indoorMode` mirrors `CompassNav.mode` (re-synced every frame in
`ShepherdService.onFrame`) and selects *both* experts per domain:

| Mode | Depth | Segmentation | Walkable classes |
|---|---|---|---|
| INDOOR | `DepthEngine()` — Depth-Anything-v2, Hypersim fine-tune | `SegEngine.ADE` — SegFormer-B0, ADE20K, 512×512 in → 128×128 out, 150 classes, ImageNet norm, **async** | `3, 6, 9, 11, 13, 28, 52` (floor, road, grass, sidewalk, earth, rug, path) |
| OUTDOOR | `depth_anything_v2_outdoor.onnx` — VKITTI fine-tune | `SegEngine.FFNET` — FFNet-78S low-res, Cityscapes, 1024×512 in → 256×128 out, 19 classes, **inline** | `0, 1, 9` (road, sidewalk, terrain) |

Each falls back to whichever member actually loaded. The engineering win is
stated in the code: each expert on its own turf, and **half the seg compute of
the always-both ensemble** — the earlier design ran both members every depth
frame. A missing model file simply disables that member.

Note that the *walkability fusion* still merges whatever masks exist:
`mergedWalkable()` resamples each model's class map into depth-pixel geometry
(depth px → 640 letterbox → source frame → model output coords) and votes
`1` = either model says walkable, `0` = every available model says not,
`-1` = outside the frame / no opinion. `WalkableColumns.clearanceFromMask()`
consumes the merged view rather than FFNet alone, because "FFNet alone is
out-of-domain indoors and steered the fallback toward Cityscapes
hallucinations".

---

## 4. Traversability grid

`TraversabilityGrid` (`app/src/main/java/dev/quad/shepherd/path/TraversabilityGrid.kt`)
is pure Kotlin — no Android imports — so it is JVM unit-testable.

| Parameter | Value | Extent |
|---|---|---|
| `cellsWide` | `61` | ±3.05 m lateral (`halfWidthCells = 30`) |
| `cellsDeep` | `60` | 0 → 6.0 m forward |
| `cellMeters` | `0.1f` | 10 cm cells |

Frame convention: `x` = metres right of the camera, `z` = metres forward. Cell
index `i = iz * cellsWide + ix`. `logOdds > 0` leans obstacle, `< 0` leans free,
`0` unknown.

### Projection, step by step

`update(depth, depthW, depthH, walkable, pitchRad, cameraHeightM, hFovDeg, rollRad)`:

**Step 0 — decay.** `decay()` runs *first*, multiplying every cell by `DECAY`.

**Step 1 — intrinsics from FOV.** No camera calibration file is needed; the
focal length is derived from the declared horizontal field of view:

```
fx = depthW / (2 · tan(hFovDeg/2))      cx = depthW/2      cy = depthH/2
```

`PathPipeline.hFovDeg` defaults to `70f`. `fx` is reused for the vertical axis —
the depth map is square (`depth.size × depth.size`), so pixels are square.

**Step 2 — image → camera ray.** For pixel `(u, v)` at metric depth `d`:

```
xi = (u - cx) / fx · d        // metres right, image plane
yi = -((v - cy) / fx · d)     // metres up   (image v grows downward, hence the negation)
```

**Step 3 — un-roll, about the optical axis.** A tilted or sideways-propped
phone rotates the image about the optical axis; the residual roll left over
after the 90° bitmap snap is removed analytically:

```
xc   =  xi·cosR + yi·sinR
yUp  = -xi·sinR + yi·cosR
```

**Step 4 — pitch, about X.** Rotation order matters: **roll first, then
pitch.** Roll is a rotation about the *optical* axis, so it must be undone in
camera coordinates before the pitch rotation lifts the ray into the
gravity-aligned world frame:

```
yW = yUp·cosP - d·sinP        // world up
zW = yUp·sinP + d·cosP        // world forward
```

**Step 5 — height above ground.**

```
height = cameraHeightM + yW - groundOffset
```

`PathPipeline.cameraHeightM` defaults to `1.35f`. `groundOffset` is the
self-calibrated correction from below.

**Step 6 — bin.** Only `zW > 0` is kept (nothing behind the camera):

```
ix = 30 + round(xc / cellMeters)        iz = round(zW / cellMeters)
```

Out-of-range indices are dropped.

**Step 7 — classify and accumulate.** Iteration uses `SAMPLE_STRIDE = 2` in both
axes (a quarter of the pixels — the grid is 10 cm-quantised anyway, so denser
sampling buys nothing but heat), and only depths in
`MIN_DEPTH_M = 0.25f .. MAX_DEPTH_M = 8f` are considered.

### Log-odds model

| Constant | Value | Meaning |
|---|---|---|
| `L_OBSTACLE` | `0.9f` | Point between `OBSTACLE_MIN_HEIGHT_M` and `OBSTACLE_MAX_HEIGHT_M`. |
| `L_FREE` | `-0.4f` | Point at ground level with no seg objection. |
| `L_SOFT_OBSTACLE` | `0.35f` | Flat, but segmentation says *not* walkable. |
| `SOFT_CAP` | `0.55f` | Soft evidence is only applied while `logOdds[i] < SOFT_CAP`. |
| `L_CLAMP` | `4f` | Symmetric saturation, so old evidence can still be overturned. |
| `DECAY` | `0.94f` | Per-update multiplicative decay — ~0.5 s half-life at 11 Hz. |
| `OBSTACLE_MIN_HEIGHT_M` | `0.18f` | Above this counts as an obstacle. |
| `OBSTACLE_MAX_HEIGHT_M` | `2.3f` | Above this is overhead structure and ignored — but doorframes are kept. |
| `GROUND_TOLERANCE_M` | `0.16f` | `|height|` below this is ground level. |
| `OBSTACLE_THRESHOLD` | `0.7f` | `isObstacle()` — what the planner raycasts against. |

Heights that are neither obstacle-band nor ground-band (below-ground noise, or
overhead) contribute `0f` — explicitly *no* evidence rather than a guess.

The `SOFT_CAP` design is the important safety asymmetry. Segmentation-only
evidence saturates at `0.55f`, **below** `OBSTACLE_THRESHOLD = 0.7f`, so
segmentation can never on its own declare a cell blocking. The stated reason:
Cityscapes is out-of-domain indoors. Geometry gets a veto; semantics only gets a
vote. Combined with `DECAY` and `L_CLAMP`, this gives the intended property —
"same scene → same answer" by construction, since a single wobbly frame cannot
flip the map, while a genuinely changed world washes out in about half a second.

Ratio note: `L_OBSTACLE / |L_FREE| = 2.25`, i.e. obstacle evidence accumulates
faster than free evidence erases it. For a mobility aid, the asymmetry is the
point.

### Ground self-calibration

The nominal camera height is a guess, and the file records what a ±10 cm error
does: it "would reclassify the whole floor as an obstacle band". So the grid
estimates the ground plane from the depth frame itself, every update:

1. Sample on a *frame-relative* stride, `strideV = max(depthH/22, 3)` and
   `strideU = max(depthW/22, 3)`, offset by half a stride, into a 512-slot
   buffer. The stride is derived from frame size specifically so coverage spans
   the **whole** image — a fixed stride filled the sample cap from the top rows
   alone, and with the camera pitched at a desk the "ground" latched onto the
   desk surface, turning the entire room into an obstacle field.
2. Accept only finite depths in `0.25f .. 5f` (near-field, where the ground
   actually is).
3. Require `n >= 80` samples, sort, and take the **12th percentile**
   (`sorted[n * 12 / 100]`). A low percentile finds the floor even when the
   floor is a *minority* surface in the image — a mean or median would be
   dragged upward by walls and furniture.
4. Sanity-gate on `abs(p12) < 0.7f`, then EMA with α = `0.25f`:
   `groundOffsetEma += 0.25f * (p12 - groundOffsetEma)`; the first accepted
   sample seeds it directly (`groundOffsetInit`). One bad frame cannot repaint
   the map.

`groundOffsetEma` is exposed read-only and printed in the `ShepherdTime` log
line alongside the depth scale and chosen steering angle.

### Cane near-field fusion

```kotlin
fun markNearObstacle(distanceM: Float, bearingDeg: Float = 0f)
```

Polar → grid index (`ix` from `sin`, `iz` from `cos`), then
`logOdds[i] += L_OBSTACLE * 2` (= `1.8f`), clamped to `±L_CLAMP`. The **double
weight** is the point: the cane's ToF sensor is a direct physical measurement,
not a learned inference, so one reading should immediately push a cell past
`OBSTACLE_THRESHOLD` (0.7) — where a single camera observation at `0.9f` also
would, but is far more likely to be wrong.

`ShepherdService.startCaneLink()` drives this from the BLE flow whenever
`r.present == true && r.mm != null`, converting mm → m. The same event fires
`haptics.caneStop()`. The class doc notes this is also the plug-in point for the
planned cane-mounted short-range (0.25 m) depth sensor.

`renderDebug()` produces the ARGB BEV overlay with `z` flipped so far rows draw
at the top: red above `OBSTACLE_THRESHOLD`, green below `-0.4f`, amber above
`0.2f` (suspicious — the soft-obstacle band), dark slate for unknown.

---

## 5. Polar planner

`PolarPlanner` (`app/src/main/java/dev/quad/shepherd/path/PolarPlanner.kt`),
pure Kotlin, JVM-testable. This is the component that decides where the user is
told to walk, and it is deliberately **path-first**: the route owns the heading,
and vision only interrupts.

| Parameter | Value | Meaning |
|---|---|---|
| `sectors` | `37` | Sector count. |
| `maxRangeM` | `5.5f` | Raycast horizon. |
| `SPAN_DEG` | `180f` | Fan width ⇒ **5° per sector** (`180 / 36`). |
| `BLOCK_ENTER_M` | `1.8f` | Free distance below which a sector becomes blocked (matches the earlier ~2 m steering sensitivity). |
| `BLOCK_EXIT_M` | `2.2f` | Free distance a blocked sector must exceed to unblock — hysteresis. |
| `DANGER_M` | `1.05f` | Forward-cone distance below which severity is DANGER. |
| `CAUTION_M` | `2.2f` | …below which severity is CAUTION. |
| `MIN_VALLEY_SECTORS` | `3` | ~15°: narrower is not a walkable corridor. |
| `W_GOAL` | `1.0f` | Cost weight: deviation from the goal. |
| `W_PREV` | `1.6f` | Cost weight: deviation from the committed heading. |
| `W_WIDTH` | `0.8f` | Cost *reward*: valley width. |
| `COMMIT_ALPHA` | `0.35f` | Low-pass toward a chosen detour heading. |
| `RETURN_ALPHA` | `0.45f` | Faster low-pass back onto the path. |
| `STEER_FULL_DEG` | `60f` | Angle that maps to `steer = ±1`. |
| `FORWARD_CONE_DEG` | `16f` | Half-width of the severity cone. |
| `GOAL_CONE_DEG` | `10f` | Half-width of the "is the path clear" cone. |
| `RAY_START_M` | `0.15f` | Raycasts skip the first 15 cm. |

`sectorAngle(s) = -90 + 180·s/36`, negative = LEFT. `raycast()` marches in
`grid.cellMeters * 0.6f` = 6 cm steps from `RAY_START_M` and returns the first
radius where `grid.isObstacle(ix, iz)`, or `maxRangeM` — including when the ray
leaves the mapped area without hitting anything ("left the mapped area" is
treated as free, not as blocked).

### Per-plan sequence

**1. Free distance per sector.** `free[s] = raycast(grid, sectorAngle(s))`.

**2. Neighbour smoothing.** `sm[s] = 0.25·free[s-1] + 0.5·free[s] + 0.25·free[s+1]`
(edge-clamped). A single 5° ray threading a gap between two chair legs is not a
corridor; the triangular kernel makes "open" mean *locally* open.

**3. Blocked state, with hysteresis.**

```kotlin
blocked[s] = if (blocked[s]) sm[s] < BLOCK_EXIT_M else sm[s] < BLOCK_ENTER_M
```

This is a Schmitt trigger. Entering costs 1.8 m, leaving requires 2.2 m — a
**0.4 m dead band**. Without it, a sector sitting at exactly the threshold would
toggle blocked/free at the frame rate, and the whole downstream decision would
chatter. `blocked` is persistent instance state (`BooleanArray(sectors)`), so
the latch survives across frames.

**4. Forward-cone severity.** `nearestForward` = min `sm[s]` over
`|sectorAngle(s)| <= FORWARD_CONE_DEG` (16°). `nearest` is that value, reported
only when it is genuinely inside the horizon
(`< maxRangeM - 0.01f`) — the epsilon avoids reporting the sentinel as a
measurement.

**4b. The mode decision.** `goalFree` = min `sm[s]` over sectors within
`GOAL_CONE_DEG` (10°) of the goal angle, then:

```kotlin
avoiding = if (avoiding) goalFree < BLOCK_EXIT_M else goalFree < BLOCK_ENTER_M
```

The **same** hysteresis pair governs the mode latch as governs individual
sectors, so the planner cannot flip-flop at the boundary. This single line is
the architecture: the planner deviates *only while the direction the route
actually wants is obstructed*.

### DEFAULT mode — the path owns the heading

When `!avoiding`, the planner is a pass-through with a low-pass:

```kotlin
committedAngle += RETURN_ALPHA * (goal - committedAngle)
```

`RETURN_ALPHA = 0.45f` is deliberately larger than `COMMIT_ALPHA = 0.35f`:
re-centering onto the route after a detour ends should be *faster* than
committing to a detour was. Detours are expensive to enter and cheap to leave.

Severity is still computed from `nearestForward` (DANGER < 1.05 m, CAUTION <
2.2 m, else CLEAR), so the user still gets proximity feedback while walking a
clear route; when CLEAR, `nearestDistanceMeters` and `nearestLabel` are nulled
so nothing spurious reaches the blackboard. Steering is
`(committedAngle / STEER_FULL_DEG).coerceIn(-1f, 1f)`. The returned
`Plan` has `stop = false, avoiding = false`.

Note what does *not* happen here: no valley search, no cost function. In the
common case the planner burns 37 raycasts and returns the route bearing. That is
the intended behaviour — a local planner that second-guesses a good route on
every frame produces exactly the wandering, un-trustable guidance this design
avoids.

### AVOID mode — VFH+-style valley selection

**5. Find valleys.** A valley is a maximal contiguous run of `!blocked[s]`. The
loop runs `s in 0..sectors` (one past the end) so a run that reaches the last
sector is still closed and evaluated. Runs with `width < MIN_VALLEY_SECTORS`
(3 sectors ≈ 15°) are discarded outright.

**Candidate angle within a valley** — the valley is *shrunk by one sector step
on each side* before choosing:

```kotlin
val step = SPAN_DEG / (sectors - 1)          // 5°
val lo   = sectorAngle(runStart) + step
val hi   = sectorAngle(runEnd)   - step
val cand = if (lo <= hi) goal.coerceIn(lo, hi)
           else (sectorAngle(runStart) + sectorAngle(runEnd)) / 2f
```

Two ideas here. First, the inset: aiming at the literal edge sector of a valley
aims at the obstacle bounding it, so a 5° safety margin is trimmed from each
side. Second, `goal.coerceIn(lo, hi)` — within a valley the planner picks the
point **closest to the goal**, not the middle. If the route bearing already
falls inside this valley, the candidate *is* the route bearing. Only when the
inset collapses the interval (`lo > hi`, i.e. a valley barely wider than the
minimum) does it fall back to the geometric centre.

**Cost function:**

```kotlin
cost = W_GOAL  * abs(cand - goal)          / 90f
     + W_PREV  * abs(cand - committedAngle)/ 90f
     - W_WIDTH * width.toFloat()           / sectors
```

Each term is normalised — angles by 90° (the fan half-width), width by
`sectors` — so the weights are directly comparable.

- `W_GOAL = 1.0f` — get back to the route.
- `W_PREV = 1.6f` — **the largest weight**, and the reason detours are usable.
  Penalising deviation from the *previous* decision more heavily than deviation
  from the goal makes a detour **committed**: two near-identical frames cannot
  produce opposite decisions, because whichever side was chosen last frame now
  carries a 1.6× advantage. Without this term, a symmetric obstacle (a pole dead
  ahead, equal gaps left and right) makes the cost of left and right nearly
  equal, and sensor noise picks a different one every frame — the user is told
  "left, right, left, right" and stops trusting the device. Hysteresis on
  `blocked[]` fixes chatter at the *sector* level; `W_PREV` fixes it at the
  *decision* level.
- `W_WIDTH = 0.8f` — subtracted, so wider valleys are cheaper. A 5-sector gap
  and a 20-sector gap are both "passable", but the wide one tolerates
  localisation error, body sway, and a companion walking alongside. At
  `0.8/37 ≈ 0.0216` per sector, ~46 sectors of extra width would be needed to
  offset a full 90° goal deviation — so width is a genuine tiebreaker between
  comparable options, never a licence to walk away from the route.

The minimum-cost valley wins.

**7. Commit.** `committedAngle += COMMIT_ALPHA * (bestAngle - committedAngle)`.
The chosen angle is approached at 35% per frame rather than snapped, so the
steering command is continuous even when the winning valley changes.

Severity, `steer`, `threatColumns` are computed as in DEFAULT; the plan carries
`stop = false, avoiding = true`.

**6. Seg-column fallback (only when no valley exists).** Before ever stopping,
the planner consults `segClearance` — the projection-free image-space
walkable-fraction columns from `WalkableColumns` (`NUM_COLUMNS = 16`, sampled in
the body-height band `BAND_TOP = 0.35f .. BAND_BOTTOM = 0.90f`). The reasoning:
the geometric grid can be poisoned by a bad depth scale, a wrong camera height,
or a mis-estimated ground plane — the image-space channel involves *none* of
those, so when the two disagree, the grid is the more likely liar.

```kotlin
var bestFrac = 0.55f                 // must be CLEARLY open
for (c in seg.indices) {
    val lo = maxOf(0, c - 1); val hi = min(seg.size - 1, c + 1)
    val frac = (seg[lo..hi].sum()) / (hi - lo + 1)   // 3-neighbourhood mean
    if (frac > bestFrac) { bestFrac = frac; bestC = c }
}
```

The 3-column neighbourhood mean encodes the same insight as the sector
smoothing: "one noisy open column between two walls must not win — a walkable
direction is WIDE." The 0.55 floor is initialised *as* the running best, so a
column must beat it, not merely reach it. On success the column maps to an angle
via `(bestC / (seg.size - 1) - 0.5f) * segFovDeg` (`segFovDeg` is passed
`hFovDeg`, 70° by default), commits at `COMMIT_ALPHA`, and returns severity
**CAUTION** with `stop = false` — steer there cautiously rather than freezing.

Note the complementary safety rule in `WalkableColumns.clearanceFromMask()`: a
column where every pixel is `-1` (nobody has an opinion) reads `0f`, not `0.5f`
— "never steer into what nobody has seen."

**STOP.** Only when there is no geometric valley *and* no clearly-open
segmentation column does the planner stop: `committedAngle = 0f`, severity
DANGER, `steer = 0f`, `stop = true`, `avoiding = true`. Zeroing the committed
angle matters — on the next frame the planner restarts from centre rather than
resuming a stale detour that no longer exists.

### World-space anchoring

```kotlin
fun rotateFrame(deltaDeg: Float) {
    committedAngle = (committedAngle - deltaDeg).coerceIn(-90f, 90f)
}
```

`PathPipeline.plan()` computes the compass delta since the last plan, unwraps it
to ±180°, and calls this when `|delta| > 0.5f` (a deadband against compass
noise). The committed heading is camera-relative, so without this correction it
would travel *with* the camera: the user turns toward the arrow, and the arrow
keeps pointing the same amount further in the same direction, forever. Rotating
by `-delta` holds the recommendation fixed in **world** space, so turning toward
it actually satisfies it. `reset()` clears `blocked`, `committedAngle` and
`avoiding` together.

`threatColumns()` compresses the 37 sectors into the 9-column
(`GuidanceEngine.NUM_COLUMNS`) overlay bar, taking the **worst** (minimum) free
distance per column and normalising to `1 - worst/maxRangeM`.

---

## 6. Navigation

`CompassNav` (`app/src/main/java/dev/quad/shepherd/nav/CompassNav.kt`) has two
modes, both of which reduce to a single signed number for the planner.

| Mode | Goal source | Rationale |
|---|---|---|
| `Mode.INDOOR` | Straight-line compass bearing to the destination | GPS routing knows nothing about corridors, so inside a building the beeline is the honest signal. |
| `Mode.OUTDOOR` | `RoutesClient` walking route + `RouteTracker` look-ahead | Aims at a look-ahead point along the actual street path, with spoken turn cues, off-route rerouting and arrival. Falls back to the beeline when no route can be fetched. |

Mode also selects the perception loadout (§3) — `ShepherdService.onFrame` sets
`analyzer?.indoorMode` from `compassNav.mode` every frame — and is switchable by
voice ("indoor mode" / "inside mode", "outdoor mode" / "outside mode"), handled
before anything else in `ShepherdService.ask()`.

### Sensors and constants

| Constant | Value | Role |
|---|---|---|
| `FIX_INTERVAL_MS` | `1000L` | `PRIORITY_HIGH_ACCURACY` fused-location interval. |
| `ARRIVAL_M` | `12.0` | Crow-flies arrival radius; announces and stops. |
| route retry | `20_000` ms | A destination set before the first fix could not fetch a route; failed fetches retry silently from `onFix`. |
| sensor rate | `SENSOR_DELAY_UI` | `TYPE_ROTATION_VECTOR`, ~15 Hz. |

`headingDeg` is true heading: magnetic azimuth from the rotation matrix plus the
`GeomagneticField` declination for the first fix, wrapped to `[0, 360)`.
`startPassive()` brings heading and fixes up **without** a destination, so the
map arrow and the scene digest are live from app start.

### Route look-ahead

`trackerStep()` advances `RouteTracker` **once per GPS fix**, not on the ~15 Hz
compass stream that `recompute()` rides. Turn cues, arrival and off-route
strikes are position-driven events; pacing them on the compass would fire them
repeatedly from a standing start. The tracker returns `remainingMeters`,
`targetBearingDeg`, and an event: `ARRIVED` (speak + stop), `TURN_CUE` (speak
the cue), `OFF_ROUTE` (speak "Rerouting", `clearRoute()`, `fetchRoute()`).

### How `goalAngleDeg` is computed

`recompute()` runs on every heading update and every fix:

1. `Location.distanceBetween(here, dest)` → `distance`, `bearing` (normalised to
   `[0, 360)`).
2. If `distance <= ARRIVAL_M`, announce arrival and stop.
3. **Outdoor with a live route** (`mode == OUTDOOR && tracker != null &&
   !targetBearingDeg.isNaN()`): the target is the route look-ahead bearing.
4. **Otherwise** (indoor, or outdoor before/without a route): the target is the
   crow-flies `bearing`.
5. Either way:

```kotlin
goalAngleDeg = if (headingDeg.isNaN()) null else {
    var delta = target - headingDeg
    while (delta > 180f) delta -= 360f
    while (delta < -180f) delta += 360f
    delta
}
```

so `goalAngleDeg` is the **signed turn** the user must make, `null` while the
heading is unknown — which the planner reads as `goal = 0f`, i.e. straight ahead.

### How it reaches the planner

`ShepherdService.onFrame`, every frame:

```kotlin
pathPipeline.goalAngleDeg = compassNav.goalAngleDeg?.coerceIn(-90f, 90f)
pathPipeline.headingDeg   = compassNav.headingDeg
```

The `±90°` clamp matches the planner's 180° fan — a goal behind the user cannot
be represented as a sector, so it saturates at the edge and the user turns.
`PathPipeline` publishes these as `@Volatile`, written from the main thread and
read on the analysis thread; `plan()` then feeds `goalAngleDeg` into the cost
function and uses `headingDeg` for `rotateFrame()`. Destinations arrive from a
map tap (`setDestination`) or a geocoded spoken phrase (`setSpokenDestination`,
matched by the unanchored `NAV_START` regex so "hey, can you take me to…" also
works).

---

## 7. Command path

### SteerFusion

`SteerFusion` (`app/src/main/java/dev/quad/shepherd/guidance/SteerFusion.kt`)
adds the navigation bias to the obstacle steer, scaled down by proximity:

| Constant | Value | Meaning |
|---|---|---|
| `NEAR_M` | `0.8f` | At/below this obstacle distance, navigation influence is zero. |
| `FAR_M` | `3.0f` | At/above this, navigation has full influence. |

```kotlin
if (goalSteer == null) return g.steer
if (g.severity == DANGER) return g.steer          // navigation ignored outright
val proximity = when (val d = g.nearestDistanceMeters) {
    null -> if (g.severity == CLEAR) 0f else 0.5f  // unknown distance: assume mid
    else -> (1f - (d - NEAR_M) / (FAR_M - NEAR_M)).coerceIn(0f, 1f)
}
return (g.steer + goalSteer * (1f - proximity)).coerceIn(-1f, 1f)
```

The closer the obstacle, the less the route matters; in DANGER the route does
not matter at all.

**Important**: with the polar plan active this is bypassed. In
`ShepherdService.onFrame`:

```kotlin
val fused = if (result.plan != null) guidance
            else guidance.copy(steer = SteerFusion.fuse(guidance, compassNav.goalAngleDeg?.let { it / 60f }))
```

The goal is already *inside* the planner's cost function, so additive fusion on
top would double-count it. `SteerFusion` now serves only the v1 fallback path
(`GuidanceEngine`, used when no depth model loaded), where the `/ 60f` divisor
matches `STEER_FULL_DEG`.

### GuidanceEngine → CaneCommand

`CaneCommand.from(guidance)` maps the continuous steer to a discrete direction
with `DEAD_ZONE = 0.2f`:

| Condition | Direction |
|---|---|
| DANGER and `abs(steer) <= 0.2` | `STOP` |
| `steer < -0.2` | `LEFT` |
| `steer > +0.2` | `RIGHT` |
| otherwise | `STRAIGHT` |

Danger with a usable gap still steers; danger with *no* better gap is the only
thing that stops. The dead zone keeps the wheel centred through steer noise.

### CommandAggregator — the 200 ms vote

`CommandAggregator` (`app/src/main/java/dev/quad/shepherd/path/CommandAggregator.kt`),
pure Kotlin, `@Synchronized` on both entry points.

`offer(guidance)` is called once per processed frame (~11 Hz) and adds a
severity-weighted vote for the direction:

| Severity | Weight |
|---|---|
| `DANGER` | `3f` |
| `CAUTION` | `2f` |
| `CLEAR` | `1f` |

`ShepherdService.startMotorLoop()` runs a coroutine that `delay`s
`PERIOD_MS = 200L` and calls `decide()`, writing the letter to `caneLink`. So
roughly 2–3 frames vote per window, and a single DANGER frame outweighs two
CLEAR ones — the aggregation is not a popularity contest, it is risk-weighted.

**Tie-break is a safety order, not arbitrary.** All directions within `1e-6f` of
the max are collected, then the first match wins in the order:

```
STOP  >  LEFT  >  RIGHT  >  STRAIGHT
```

STOP beats turns, turns beat STRAIGHT. A tie is exactly the ambiguous case where
the conservative choice is correct.

**Wire letters** (BLE NUS write to the UNO Q, forwarded to the Motor Modulino):

| Letter | Direction |
|---|---|
| `L` | left |
| `R` | right |
| `S` | straight |
| `X` | stop |

**Empty-window failsafe.** The ~11 Hz frame cadence beats against the 200 ms
window, so an occasional empty window is *normal* — not a fault. Failing to STOP
on the first empty window would produce a stutter the user feels as random
braking. Instead:

```kotlin
if (winner() == null) {
    emptyStreak++
    if (emptyStreak >= maxEmptyStreak) lastLetter = 'X'   // maxEmptyStreak = 3
    return lastLetter                                     // else repeat last decision
}
```

Three consecutive empties (~600 ms) means a true vision stall, and the letter
becomes `X`. `lastLetter` is initialised to `'X'`, so the very first command
before any frame arrives is stop. The board's own timeout backstops the link
itself (§8). `ShepherdService` logs only on change (`lastMotorLetter`), so the
5 Hz write stream does not flood the log.

---

## 8. Failure modes and safety

### Timeouts and watchdogs

| Layer | Mechanism | Threshold | Behaviour |
|---|---|---|---|
| Aggregator | `maxEmptyStreak` | 3 empty 200 ms windows (~600 ms) | Motor letter → `X`; before that, repeat `lastLetter`. |
| BLE link | watchdog coroutine | polls every `3000L` ms | If `desired && gatt == null && !scanning`, restart the scan. |
| BLE link | `WRITE_STALL_MS` | `4_000L` ms in flight with no callback | **Zombie-link detection** — teardown, state → `Disconnected`, `reading` → null, rescan. Android does not always deliver `STATE_DISCONNECTED` (seen when the board reflashes its MCU, or after a Bluetooth toggle), so a live-looking `gatt` that no longer delivers callbacks must be detected by *behaviour*, not by state. |
| BLE link | `WRITE_FAIL_STREAK` | `8` consecutive immediate write failures | Declare the link dead, teardown, rescan. |
| BLE link | `SCAN_WINDOW_MS` | `10_000L` ms | Scans are duty-cycled off; the 3 s watchdog starts the next round. Continuous BLE scanning is a battery sink. |
| Cane board (MCU) | `COMMAND_TIMEOUT_MS` | `2000` ms | The Linux side re-sends desired actuator state **4×/s**; if that stream stops (app or bridge died), the wheel stops and the vibro goes quiet rather than run away. |
| Cane board (Linux) | `STALE_AFTER_S` | `1.0` s | Sensor readings older than this are stale. |
| Cane board | `MODULE_RETRY_MS` | `3000` ms | Re-init any Modulino that is not ready. |
| Cane board | `DISTANCE_POLL_MS` / `TELEMETRY_MS` | `20` / `500` ms | ToF poll rate; dashboard telemetry rate. |
| Haptics | `HapticFeedback.REPEAT_MS` | `1200L` ms | Rate-limits the cane STOP buzz. |
| Vibro rhythm | `VIBRO_PULSE_MS` / `VIBRO_PERIOD_MS` | `250` / `500` ms | A 250 ms buzz every 500 ms while an object is inside the presence threshold. Each pulse self-terminates, so a wedged Linux side cannot leave the motor on. |
| Presence | `OBSTACLE_THRESHOLD_MM` | `1200` mm | Pushed to the cane on connect. |
| v1 fallback | `DANGER_HOLD_MS` / `CAUTION_HOLD_MS` | `1000` / `800` ms | `GuidanceEngine` severity hold (`dangerDistance = 1.5f`, `cautionDistance = 3.0f`). |

Note the layering: three independent stages will stop the wheel — the
aggregator (~600 ms, phone-side vision stall), the BLE watchdog (3–4 s, link
death), and the MCU timeout (2 s, anything upstream dying, including the phone
being switched off). No single failure leaves the motor running.

### Graceful degradation

| Missing / failing | Result |
|---|---|
| YOLO model absent | `visionLabel = "no model"`, spoken `R.string.model_missing`, pipeline aborts (detection init gates all other engines). |
| Depth models absent | `PathPipeline` is not passed to the analyzer; `GuidanceEngine` v1 column pipeline takes over via `result.plan ?: guidanceEngine.update(...)`. |
| One seg model file absent | That member logs and disables itself; the other carries on. `mergedWalkable` handles a single non-null mask. |
| QNN unavailable | Tier ladder falls to `CPU` (§3); label reflects it. |
| No walking route | Beeline fallback, announced **once** (`routeFallbackAnnounced`), retried silently every 20 s. |
| Geocode failure | "I couldn't find *X* nearby." |
| No location permission | `compassNav.startPassive()` skipped; FGS type omits `_LOCATION` and is re-promoted via `startForeground` once nav is requested and permission exists. |
| Depth letterbox bars | NaN-masked; consumers see "no signal", not hallucinated geometry. |
| `< 80` ground samples, or `abs(p12) >= 0.7f` | Ground offset is not updated — the last EMA value persists rather than a bad frame being trusted. |
| Grid cell with no observations | `logOdds == 0` ⇒ not an obstacle ⇒ raycast passes; unknown is treated as traversable, and `DECAY` returns stale cells toward unknown. |

### Deliberate safety choices

- **Haptics are not driven from vision.** The only buzz is the cane sensor's
  STOP, so a vibration *always* means "obstacle at the cane", never routine
  steering. Overloading the one high-bandwidth non-auditory channel with
  routine information would destroy its meaning.
- **Nothing about obstacles is spoken.** DANGER *onsets* are recorded to the
  `SceneBlackboard` (`noteAlert`) so the companion can discuss them when asked,
  but the device does not narrate. Speech is reserved for navigation events and
  answers.
- **Preview detach is identity-checked.** On activity recreation the old
  instance's teardown runs *after* the new one attached; a blind
  `setSurfaceProvider(null)` blanked the fresh preview ("can't see the camera
  sometimes"). `detachPreview` now only clears if `attachedSurface === surfaceProvider`.
- **Foreground service, `START_STICKY`.** Camera, vision, guidance and speech
  all live in `ShepherdService`, so everything keeps working with the screen off
  or the phone in a pocket; `MainActivity` is a thin bound shell.
- **Thermal awareness.** `OnThermalStatusChangedListener` appends `" · warm"` to
  the status line at `THERMAL_STATUS_MODERATE` and above, and logs transitions.
  It is surfaced, not acted on — the duty-cycle constants are the actual
  mitigation.
- **`onDestroy` releases in dependency order**: thermal listener, gravity
  listener, nav, BLE, chat, speech, OCR, actuator, analysis executor, then each
  ONNX session.

---

## 9. Known gaps

- **Companion SLM is parked.** `ShepherdService.COMPANION_ENABLED = false`. The
  NPU, memory and thermal budget goes to perception instead. Voice navigation
  intents and OCR reading never touched the SLM and keep working; with the flag
  off, a non-OCR question gets "The companion is off. Say, take me to, followed
  by a place, to navigate." `warmChat()` returns immediately. Flipping the flag
  to `true` restores it — and reintroduces the Adreno/Hexagon contention that
  §3's tier ordering exists to manage.
- **`CaneActuator` is a stub.** `ShepherdService` instantiates `NoOpActuator()`.
  The real BLE path to the cane is `CaneBleLink` + `CommandAggregator`;
  `CaneActuator.kt` still carries `TODO` comments for
  `BluetoothLeScanner → connectGatt → discoverServices` and
  `gatt.writeCharacteristic(...)`. `actuator.sendGuidance(fused)` is therefore a
  no-op today, and the file's own comment says to wire a BLE implementation.
- **Cane depth sensor not present.** `TraversabilityGrid.markNearObstacle` is
  built and wired, but only the ToF distance reading uses it; the documented
  "forthcoming cane-mounted short-range depth sensor (0.25 m)" is not shipped.
  `bearingDeg` defaults to `0f`, so every cane reading lands dead ahead — the
  sensor provides no bearing.
- **Grid is narrower than the planner's reach.** With `cellsWide = 61` at
  `0.1 m`, the lateral half-extent is 3.05 m, while `maxRangeM` is 5.5 m. Rays
  toward the fan edges leave the mapped area before the horizon and `raycast`
  returns `maxRangeM` — reported as fully free. Peripheral sectors are therefore
  optimistic by construction. The forward extent (6.0 m > 5.5 m) is fine.
- **`hFovDeg` and `cameraHeightM` are constants, not measurements.**
  `PathPipeline` defaults to `70f` and `1.35f` with no per-device calibration.
  Ground self-calibration absorbs height error, and `WalkableColumns` provides a
  projection-free cross-check, but a wrong FOV scales `fx` and therefore the
  whole lateral projection with nothing to correct it.
- **`pitchRad` initial value is a guess** (`0.30f`) until the first
  `TYPE_GRAVITY` event.
- **Detections do not feed the grid.** YOLO boxes contribute labels, blackboard
  content, and depth-scale samples only. A recognised obstacle class is not
  written into the traversability map.
- **`DepthCalibrator` needs cooperating scenes.** Scale samples require an
  untruncated detection of a known-height class in the same frame as a depth
  run; in a corridor with no people or furniture the raw metric output is used
  as-is (`calibrator.convert(raw) ?: raw`).
- **`FrameAnalyzer` allocates per frame.** `metricDepth()` builds a fresh
  `FloatArray(depth.size²)` and `mergedWalkable()` a fresh `ByteArray` on every
  depth frame; the letterbox bitmap and input tensor are pooled, these are not.
- **`Plan.sectorFreeM` aliases planner state.** `sm` is returned by reference in
  the `Plan`; it is not defensively copied.
- **`CommandAggregator.votes` is only cleared on a decisive window.** In the
  empty-window path `votes.clear()` is not reached — harmless because the window
  was empty, but the asymmetry is worth noting.
- **BLE bearing/telemetry is one-way for planning.** `CaneReading` carries `mm`
  and `present`; wheel telemetry (current, duty, applied direction) reaches the
  board dashboard on port 7000 but is not fed back into the planner as a control
  loop.
