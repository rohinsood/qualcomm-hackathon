# Wayfinder — on-device blind-navigation aid (M1 scaffold)

An Android app that steers a user around obstacles using the **gap-seeking
algorithm** (ported from the Shepherd smart-cane — see
[`../shepherd-research/gap-seeking-algorithm.md`](../shepherd-research/gap-seeking-algorithm.md)),
with output via **TTS + haptics**. Vision (segmentation, +depth later) and the SLM
(M4) run **on-device via QAIRT / QNN** on the Hexagon NPU.

Target device: **Samsung S25 Ultra** (Snapdragon 8 Elite for Galaxy).
Architecture: [`../shepherd-research/architecture.md`](../shepherd-research/architecture.md).

> This is **Milestone 1**: the full perception→steering→output loop, running with
> a **synthetic** segmentation model so the loop is verifiable end-to-end *before*
> the real QAIRT-compiled model is dropped in. Milestones M2 (GPS nav), M3 (depth),
> M4 (SLM) are stubbed.

---

## Run it (works right now, no model required)

1. **Open in Android Studio** (Hedgehog/Koala or newer): `File → Open` → select this
   `wayfinder/` folder. Let Gradle sync. (Android Studio generates the Gradle
   wrapper jar on first sync.)
2. Connect an S25 Ultra (or any arm64 Android 11+ device) with **USB debugging** on.
3. Run `app`. Grant the **camera** permission.
4. You'll see the debug overlay: status, the live **walkable mask**, the **16-column
   clearance bars** (gap column highlighted), the **steering decision**, and **live
   tuning sliders**.

Because the default runner is `SyntheticSegmentationRunner`, you'll immediately
observe the loop behaving: the simulated gap drifts left↔right and a center
obstacle appears periodically → you'll **hear** "steer left/right" and **feel** the
haptic patterns (left = double pulse, right = long pulse) with proximity-scaled
cadence. This proves the entire pipeline before any real model exists.

### Run from the CLI (no Android Studio)
```bash
# after generating the wrapper (Android Studio sync, or: gradle wrapper)
./gradlew :app:installDebug   # installs on a connected device
```

---

## Drop in the real segmentation model (QAIRT / QNN)

1. Export your segmentation model (e.g. FastSCNN / DeepLabV3+, Cityscapes) to an
   **INT8-quantized** `.tflite` and place it at:
   ```
   app/src/main/assets/seg_model.tflite
   ```
2. Add the **QNN delegate AAR** for TFLite (from the Qualcomm QNN SDK) to
   `app/libs/` and reference it in `app/build.gradle.kts`:
   ```kotlin
   implementation(files("libs/qnn-tflite-delegate.aar"))
   ```
3. In [`TFLiteSegmentationRunner.kt`](app/src/main/java/com/wayfinder/app/perception/seg/TFLiteSegmentationRunner.kt),
   enable the QNN delegate targeting the **HTP** backend (the block is marked
   `TODO(QAIRT)`). Then flip the flag in
   [`WayfinderEngine.kt`](app/src/main/java/com/wayfinder/app/ui/WayfinderEngine.kt):
   ```kotlin
   companion object { const val USE_REAL_MODEL = true }
   ```
4. Match the model's **output shape** in `segment()`/`warmUp()` — the scaffold
   assumes `[1, H, W]` argmax labels; if your model emits `[1, numClasses, H, W]`
   logits, run an argmax across the class axis first.
5. **Warm-up** runs automatically on `engine.start()` (first inference pays NPU
   context-init cost — never on a live frame).

If the QNN delegate isn't available on a test device, the runner falls back to the
GPU delegate, then CPU, and logs which path it took.

---

## Project layout

```
wayfinder/
├── settings.gradle.kts / build.gradle.kts / gradle/libs.versions.toml
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/values/{themes,strings}.xml
        └── java/com/wayfinder/app/
            ├── WayfinderApp.kt                 # Application
            ├── core/
            │   ├── config/Tunables.kt          # all tunable params (live-mutable)
            │   ├── model/SteeringDecision.kt    # decision + Direction enum/ext
            │   ├── loop/{FrameSlot,DecisionStore,SteeringLoop}.kt
            │   └── safety/Watchdog.kt           # fail-safe on stale data/camera
            ├── camera/CameraProvider.kt         # CameraX KEEP_ONLY_LATEST
            ├── perception/
            │   ├── ModelRunner.kt               # pluggable model interface
            │   ├── seg/{WalkableMask,MaskBuilder,SyntheticSegmentationRunner,TFLiteSegmentationRunner}.kt
            │   └── columnize/Columnizer.kt      # mask → clearance[16] + reach
            ├── steering/GapSeeker.kt            # §8 gap-seeking port
            ├── output/
            │   ├── haptics/{DistanceToCadence,HapticLoop}.kt
            │   └── speech/{TtsController,SpeechGate}.kt
            └── ui/{WayfinderEngine,MainActivity,DebugScreen}.kt
```

---

## How the loop works (one cycle)

```
CameraX (drop old) → FrameSlot (latest-wins)
   → ModelRunner.segment()  → WalkableMask
   → Columnizer.columnize() → ColumnSignal(clearance[16], nearestObstacleM)
   → GapSeeker.compute()    → SteerResult(command, proximity, gap)
   → DecisionStore (atomic latest)
        ├─ HapticLoop   (direction waveform + proximity cadence)
        └─ SpeechGate   (throttled/deduped TTS)
[Watchdog] → fail-safe if no fresh decision (500ms) or camera (1s)
```

Everything reads a single shared `Tunables`, so the on-screen sliders retune the
running system live.

---

## Status & next milestones

- ✅ **M1** — full loop with synthetic model; haptics + speech; safety watchdog;
  live debug overlay.
- ⬜ **M2** — outdoor GPS navigation: `navigation/` (FusedLocationProvider, routing,
  micro-waypoints, heading fusion, route-bias blend into the gap-seeker).
- ✅ **M3** — depth layer: **Depth-Anything V2 Small** via ONNX Runtime (NNAPI →
  Hexagon NPU), low-rate/ROI scheduling, **fusion** (seg→direction, depth→magnitude,
  **safety override**), depth strip + tuning in the overlay.
- ⬜ **M4** — on-device SLM narration via QAIRT (structured state → spoken guidance).

### On-device models (QAIRT-aligned, ONNX Runtime + NNAPI → Hexagon NPU)
- Segmentation: **Fast-SCNN (Cityscapes)** — `assets/seg_model.onnx` (~4 MB).
  Walkable = `{road(0), sidewalk(1)}`. Input `[1,3,384,576]` NCHW, ImageNet-normalized.
- Depth: **Depth-Anything V2 Small** — `assets/depth_model.onnx` (~95 MB, relative depth,
  mapped to frame-relative pseudo-meters). Input `[1,3,518,518]` NCHW.
- Both run via `onnxruntime-android`; the QNN execution provider can replace NNAPI later
  for tighter NPU control. TFLite runners are retained as an alternative (flags in `WayfinderEngine`).

> `seg_model.onnx` (~4 MB) **is committed**. `depth_model.onnx` (~95 MB) is **gitignored**
> (too large for plain git) — fetch it with:
> ```bash
> curl -L -o app/src/main/assets/depth_model.onnx \
>   https://github.com/fabio-sim/Depth-Anything-ONNX/releases/download/v2.0.0/depth_anything_v2_vits.onnx
> ```
> (Without it the app still runs — it falls back to synthetic depth, real seg stays on.)

---

## Notes / known scaffolding items
- `TFLiteSegmentationRunner` model I/O and QNN delegate wiring are intentionally
  left as `TODO`s so the project compiles before a model/delegate is present.
- Monocular reach→distance is an uncalibrated proxy in M1; calibrate against real
  depth in M3.
- Continuous camera + NPU is power/thermal-heavy; a low-power mode (lower seg FPS,
  wider MA window) is planned.
```
