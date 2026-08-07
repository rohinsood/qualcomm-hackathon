# Lighthouse — Camera Obstacle Scan

The camera obstacle scan is `v3`'s **screen-thirds guidance**, ported into the
merged app on `main` as a toggleable feature. While it is on, the phone watches
the path through the back camera, decides each frame whether the middle of the
view is blocked, speaks every change of instruction ("Left" / "Right" / "Stop",
then "Straight" when the way clears), and steers the cane wheel with the same
words. While it is off — the default — the camera never opens and the app is
exactly the pre-scan qhackGPS.

Everything here is drawn from the code on `main`; constant names are the real
identifiers, so every number below can be grepped. The source is one package,
`app/src/main/java/com/example/qhackgps/scan/`; the feature's original,
full-stack incarnation is v3 commit `5d94090`.

The port is **detection-only by design** — v3 ran this same decision logic on
top of segmentation and depth models, and those stayed on their branch. What
that costs, honestly, is §4.

---

## 1. Pipeline

```
CameraX ImageAnalysis  (back camera, NO preview surface)
     │   KEEP_ONLY_LATEST, one "obstacle-scan" thread, ≥90 ms between frames (~10 Hz cap)
     ▼
rotate upright ─▶ letterbox into 640×640 ─▶ CHW float RGB 0..1
     ▼
YOLOv8 detector  (ONNX Runtime, QNN EP)        DetectionEngine + OrtSessions
     ▼
YoloPostProcessor                              conf ≥ 0.45, per-class NMS at IoU 0.5
     ▼
DistanceEstimator                              pinhole + closeness corrections
     ▼
ThirdsGuidance.update(obstacles, segClearance = null)
     ▼
Decision ∈ {STRAIGHT, LEFT, RIGHT, STOP}   ─▶  voice (§5), HUD (§6), wheel (§7)
```

`ObstacleScanner` is the glue, and two of its choices are deliberate:

- **The analysis use case binds alone.** No preview surface — which sidesteps
  the hidden-preview camera stall v3 had to fix, and means the map HUD never
  shows a camera feed.
- **`MIN_FRAME_INTERVAL_MS = 90`.** Running back-to-back on every camera frame
  pinned the GPU and thermal-throttled the SoC on v3. The thirds debounce
  assumes ~10 Hz and does not benefit from more; with `KEEP_ONLY_LATEST`, a
  slow (CPU-tier) detector degrades the scan rate, never the UI.

### Where inference runs

`OrtSessions` tries five tiers in order and labels the one that sticks:

| Tier | Backend | Strict | HUD label |
|---|---|---|---|
| 1 | QNN GPU (`libQnnGpu.so`, Adreno) | yes | `GPU` |
| 2 | QNN HTP (`libQnnHtp.so`, Hexagon NPU) | yes | `NPU` |
| 3 | QNN GPU, CPU fallback allowed | no | `GPU/CPU mixed` |
| 4 | QNN HTP, CPU fallback allowed | no | `NPU/CPU mixed` |
| 5 | plain CPU | — | `CPU` |

"Strict" sets `session.disable_cpu_ep_fallback`, so session creation **fails**
unless the entire graph compiled for the accelerator — without it, ONNX Runtime
silently runs everything on CPU and a session "created with QNN" proves
nothing. Only strict sessions earn the `GPU`/`NPU` labels. The SoC is spelled
out (`soc_model=69`, `htp_arch=79` — SM8750, Hexagon v79) because QNN's
auto-detection of the S25 Ultra's SM8750-AC variant is unproven. GPU-first is
kept from v3 (there it left the Hexagon free for the SLM; here it simply means
the scan never competes with anything for the NPU). The winning label shows in
the HUD chip (§6).

### Distances without a depth model

`DistanceEstimator` is the pinhole model over class-height priors:
`distance = realHeight × FOCAL_PX / boxHeightPx`, with `FOCAL_PX = 400` for
the 640-px letterboxed frame (S25 Ultra main camera, ~77° horizontal FOV) and
18 sidewalk-relevant priors (`person` 1.70 m … `cat` 0.30 m). Results clamp to
0.1–50 m; classes without a prior return no estimate.

Then the closeness corrections, because pinhole math *over*-estimates truncated
boxes exactly when the object is most dangerous:

- box touches **both** top and bottom edges → ≤ 1.0 m;
- box covers > 45 % of the frame → ≤ 1.1 m, whatever the class;
- unknown class but > 22 % of the frame → 2.2 m (a conservative number instead
  of none at all).

On v3 these estimates additionally calibrated a dense depth model. Here they
carry the whole job.

## 2. Decision semantics (`ThirdsGuidance`)

The frame splits into three vertical bands. Pure Kotlin, no Android types —
the ported JVM tests run on the desktop (`ThirdsGuidanceTest`, 16 tests).

- **An obstacle is a near detection**: estimated distance
  < `OBSTRUCT_DIST_M = 2.5` m — or, with no estimate, a box taller than
  `OBSTRUCT_HEIGHT_FRAC = 0.35` of the frame (big things in front of the lens
  are close). Far detections are ignored entirely.
- **Middle-third gate**: only near objects whose center falls in the middle
  third block forward. Objects in the outer thirds never block — they count
  against their side in the election instead.
- **Debounce**: a state change must persist `DEBOUNCE_FRAMES = 3` consecutive
  frames (~300 ms at ~10 Hz) both entering and leaving avoidance, so one noisy
  frame cannot start, end, or flip a dodge.
- **Side election**: each outer third scores
  `UNKNOWN_SIDE_SCORE (0.55) − OBJECT_PENALTY (0.35) × nearObjects`; the higher
  side wins, ties go RIGHT.
- **Latching**: the elected side holds while the middle stays blocked. It
  switches only if the latched side stops being viable
  (score < `SIDE_VIABLE_SCORE = 0.30`) while the other side is viable —
  debounced again.
- **STOP**: while blocked, neither side viable. Seg-less that means a near
  object on each side (0.55 − 0.35 = 0.20 < 0.30): blocked ahead, no clear
  side, halt.
- **Hysteresis**: `MIDDLE_BLOCK_ENTER = 0.45` / `MIDDLE_BLOCK_EXIT = 0.60`
  gate the segmentation walkable fraction. Dormant on `main` (the seg input is
  always null — see §4) but kept with the logic and its tests, so the seg feed
  could be reattached without touching the decision core.

## 3. Voice

The v3 policy, verbatim in behavior:

- **Every transition is spoken** — "Left", "Right", "Stop", then "Straight"
  when the way clears.
- **The initial STRAIGHT is silent.** Turning the scan on is not an
  instruction to walk.
- **A standing STOP re-announces every `SCAN_STOP_REPEAT_MS = 4000` ms** — the
  user cannot see that the instruction still stands.
- **STOP is urgent and interrupts**: speech rate 1.25 vs the normal 1.05, and
  it flushes the TTS queue rather than waiting behind routine prompts.
- **The route phrasing yields.** A scan dodge folds into the same
  `GuidanceUpdate.direction` the route announcer watches, so without a guard
  one event would be spoken twice in different words ("Left" and "Turn
  left."). While a dodge or STOP stands, the bus's turn lines are suppressed;
  the hand-back "Straight" counts as the spoken state, so a pending route
  turn still announces itself but "Straight ahead." doesn't echo it.

Speech goes through the Android system `TextToSpeech` (`SpeechFeedback` —
trimmed from v3's class, same announce contract, without the neural-voice
stack). A 2 s same-text guard stops identical lines from stammering.

## 4. What stayed on v3 — and what that costs

Deliberately not ported: the segmentation ensemble, the metric depth model,
the BEV traversability grid and polar planner, the SLM, and the neural TTS.
Consequences:

- `ThirdsGuidance` runs its **seg-less fallback**: callers pass
  `segClearance = null`, sides start from `UNKNOWN_SIDE_SCORE`, and the middle
  blocks on near objects alone.
- An obstacle means **"a recognized object, near."** Walls, poles, kerbs —
  anything outside the COCO vocabulary — are invisible to the scan. Those
  remain the cane sensor's job (`{"mm","p"}` over BLE → the cane dodge in §7).
  The two sources complement each other; neither replaces the other.
- Distances are priors plus corrections (§1), not measured depth.

The full-stack version of this feature — same decision core, fed by
segmentation and depth — is on `v3` and documented in
[`ARCHITECTURE.md`](ARCHITECTURE.md).

## 5. The toggle

A **"SCAN" FAB** on the main screen turns the feature on and off, announcing
"Camera scan on." / "Camera scan off.". The choice persists
(`NavSettings.obstacleScan`, default **off**), and off is genuinely off: the
camera and detector only run while the toggle is on, so the default screen and
behavior are exactly the pre-scan app. The first enable requests the `CAMERA`
runtime permission; a denial snaps the toggle back off rather than leaving a
dead switch armed.

## 6. UI surfaces

Both exist only while the toggle is on:

- **The "Scan …" HUD chip** — `Scan starting…`, then
  `Scan clear|left|right|STOP · <provider>` where `<provider>` is the
  `OrtSessions` label (§1), or `Scan needs camera` / `Scan failed` (model
  missing, camera unavailable). Green dot while clear, orange on a dodge, red
  on STOP.
- **HUD card variants** — a scan STOP card ("STOP — Camera: blocked ahead, no
  clear side") that sits *above* the cane branch in the card's `when`,
  matching the wheel's precedence; and a scan dodge card ("Go left" /
  "Go right — Camera: obstacle in your path"). While the scan says STRAIGHT
  the card shows whatever it showed before — route guidance keeps the screen.

## 7. Wheel precedence

One text stream steers the board (`CaneBleLink` → NUS RX, parsed by
`board/distance-watch/python/main.py`). When several sources want the wheel,
highest wins:

| Priority | Source | Wire text | Wheel |
|---|---|---|---|
| 1 | scan **STOP** | `STOP` | stop — halting beats steering |
| 2 | cane dodge (ToF obstacle) | `AVOID LEFT` / `AVOID RIGHT` | full-speed dodge |
| 3 | scan dodge | `AVOID LEFT` / `AVOID RIGHT` | full-speed dodge |
| 4 | route turn | `TURN LEFT\|RIGHT <deg>` (5° buckets) | full-speed turn |
| 5 | otherwise | `CLEAR` | stop |

Scan STOP outranks even the cane's dodge: when the camera sees no side worth
taking, steering around is the wrong move. On the legacy `QG,…` SPP line
(consumed by `arduino/qhack_guidance_motor/`), scan STOP exports direction
`NONE` — that wire has no stop verb, and an idle direction is exactly what its
motor should do. A scan dodge rides the same `dir`/`deltaDeg` avoidance fields
a cane dodge uses, while `obst`/`obstMm` keep telling the truth about the cane
(they stay `0`/`-1` for a camera-seen obstacle) — one coherent instruction
across ears, screen, and wheel, and the wire never claims the cane sensed
something it didn't.

## 8. The model

`yolov8_det.onnx` (12,824,107 bytes ≈ 12 MB) — the same detector file v3
commits, bundled here at `app/src/main/assets/yolov8_det.onnx` so the scan
works from a fresh clone. `DetectionEngine` resolution order:

1. `<external-files>/models/yolov8_det.onnx` — override without rebuilding:

   ```bash
   adb push yolov8_det.onnx /sdcard/Android/data/com.example.qhackgps/files/models/
   ```

2. the bundled asset.

`YoloPostProcessor` accepts both layouts Qualcomm AI Hub exports produce — the
split form (boxes `[1,N,4]` + scores + class ids, including normalized-box and
int64/int32/uint8 class-id variants) and the raw `[1, 4+C, N]` head — so a
re-exported detector drops in without code changes. Detector provenance and
licensing: [`MODELS.md`](MODELS.md) (its on-device paths are v3's
`dev.quad.shepherd`; substitute `com.example.qhackgps` here).

## 9. Build and manifest facts

All load-bearing; none vestigial:

| Fact | Where | Why |
|---|---|---|
| `minSdk = 31` (was 24) | `app/build.gradle.kts` | the QNN EP and `<uses-native-library>` need API 31+; the target device (Galaxy S25 Ultra) is far above either way |
| CameraX 1.3.4 (`camera-core`/`camera2`/`lifecycle`) | `gradle/libs.versions.toml` | frames for the scan |
| `onnxruntime-android-qnn 1.28.0` | `gradle/libs.versions.toml` | ONNX Runtime with the Qualcomm QNN execution provider |
| `jniLibs.useLegacyPackaging = true` | `app/build.gradle.kts` | the HTP backend opens `libQnnHtpV*Skel.so` **by file path**; libs left compressed in the APK make that path empty |
| `<uses-native-library>` `libcdsprpc.so` + `libOpenCL.so`, `required="false"` | `AndroidManifest.xml` | API-31+ linker namespace rules block undeclared vendor libraries — FastRPC to the Hexagon and Adreno OpenCL. Remove them and everything silently lands on CPU |
| `CAMERA` permission | `AndroidManifest.xml` | requested on first enable (§5), not at startup |

A chip reading `CPU` means QNN didn't load — check Logcat tags `OrtSessions`
and `DetectionEngine`, then the two manifest declarations. The same failure
modes are documented for v3 in [`SETUP.md`](SETUP.md).

## Tests

Pure-Kotlin, no device:

```bash
./gradlew :app:testDebugUnitTest
```

`ThirdsGuidanceTest` (16 tests — the middle gate, side election, seg-less
fallback, STOP, debounce, latch and abandonment, recovery hysteresis, the
obstacle mapping) and `YoloPostProcessorTest` (both export layouts, NMS).
