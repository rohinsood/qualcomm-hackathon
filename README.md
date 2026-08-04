# Shepherd Snapdragon

An assistive-navigation computer-vision app for the **Samsung Galaxy S25
Ultra**, inspired by [Shepherd](https://github.com/tonywangs/shepherd) (the
iOS smart white cane) but built on Qualcomm silicon: real-time object
detection runs on the **Snapdragon 8 Elite's Hexagon NPU**, obstacle
guidance is computed on-device, and feedback is spoken + haptic. An optional
LLM feature narrates the surrounding scenery on demand.

## How it maps to Shepherd

| Shepherd (iPhone) | This app (S25 Ultra) |
|---|---|
| ARKit LiDAR depth | Monocular estimation: bbox-height pinhole model now, depth model (Depth-Anything via QUAD/AI Hub) planned |
| Vision framework person detection | YOLOv8 (80 COCO classes) on the Hexagon NPU via ONNX Runtime + QNN EP |
| 16-column depth gap-seeking | 9-column threat map + safest-window steering (`GuidanceEngine`) |
| Haptic + voice guidance | Android TTS + parking-sensor haptics |
| BLE motorized cane | `CaneActuator` interface with a documented 12-byte packet stub — plug in hardware later |
| Vapi voice assistant | "Describe scene" button → Claude vision (cloud) today, Genie on-device LLM planned |

## Architecture

```
CameraX (KEEP_ONLY_LATEST)
   └─ FrameAnalyzer        YUV → upright bitmap → 640×640 letterbox → CHW float
        └─ DetectionEngine ONNX Runtime, QNN EP (libQnnHtp.so) → Hexagon NPU
             └─ YoloPostProcessor   decode (split or raw layout) + NMS
                  └─ DistanceEstimator    pinhole distance per detection
                       └─ GuidanceEngine  column threat map → severity + steer
                            ├─ OverlayView       boxes, threat bar, steer arrow
                            ├─ SpeechFeedback    rate-limited TTS
                            ├─ HapticFeedback    distance-keyed pulses
                            ├─ CaneActuator      (stub) BLE steering packets
                            └─ SceneDescriber    on-demand LLM narration
```

Everything on the hot path is offline and on-device — like Shepherd, only
the optional narration touches the network.

## Getting started

1. **Open in Android Studio** (Ladybug or newer). Gradle sync pulls
   everything, including `onnxruntime-android-qnn` (the QNN/NPU runtime —
   no Qualcomm SDK install needed).
2. **Fetch the model** (one-time, needs a free
   [Qualcomm AI Hub](https://aihub.qualcomm.com) account):
   ```powershell
   pip install qai-hub "qai-hub-models[yolov8-det]"
   qai-hub configure --api_token YOUR_TOKEN
   .\scripts\fetch_model.ps1
   ```
   Or push a model to an installed app without rebuilding:
   ```
   adb push yolov8_det.onnx /sdcard/Android/data/dev.quad.shepherd/files/models/
   ```
3. **Run on the S25 Ultra** (USB debugging on). The status bar should read
   `Hexagon NPU (QNN)`; if it says `CPU`, QNN failed to load — check
   Logcat tag `DetectionEngine`.
4. **Optional — scene narration**: add to `local.properties`:
   ```
   claude.apiKey=sk-ant-...
   ```
   This enables the *Describe scene* button (Claude, `claude-opus-5`; set
   `claude.model=claude-haiku-4-5` for lower latency/cost). For a personal
   prototype only — a shipped app must proxy LLM calls through a backend.

Unit tests for the pure-Kotlin math (`YoloPostProcessor`, `GuidanceEngine`,
`DistanceEstimator`) run with `gradlew :app:testDebugUnitTest`.

## The QUAD workflow

The [QUAD MCP](https://github.com/CBN-AI-TEAM/QUAD-Client) tooling covers
the full model lifecycle for this app:

| Step | QUAD tool |
|---|---|
| Pick/fetch a model from the AI Hub catalog | `aihub_select` |
| Compile to a QNN context binary (fastest NPU path), quantize int8 | `convert_model` |
| Profile on the phone over ADB (latency/FPS/memory, CPU vs NPU) | `profile_device_plan` / `profile_device_report` — see [docs/PROFILING.md](docs/PROFILING.md) |
| CPU/GPU/NPU layer allocation by power mode | `orchestrate_workload` |
| Stage an on-device LLM (Genie bundle) for offline narration | `aihub_select action=ensure` |

## Roadmap

- **Depth estimation** — Depth-Anything-V2 from AI Hub as a second NPU
  model; replaces the class-height heuristic with true per-pixel distance
  (this is the LiDAR-equivalent Shepherd relies on).
- **On-device LLM guidance** — a Genie NPU bundle (e.g. Llama-class 3B)
  narrating from the structured detection list, fully offline; staged via
  QUAD `aihub_select`.
- **Scene classification** at ~2 Hz (sidewalk / crosswalk / indoors) for
  context-aware prompts, as Shepherd does.
- **BLE cane hardware** — implement `BleCaneActuator` GATT plumbing against
  an ESP32 + motorized omni wheel (Shepherd's `ESP32/` firmware is directly
  reusable).

## Licensing notes

- YOLOv8 weights are **AGPL-3.0** (Ultralytics) and must be exported via
  your own AI Hub account (not redistributed here — the fetch script exists
  for this reason). For commercial use pick a permissively-licensed
  detector from the catalog; the post-processor handles both common output
  layouts.
- App code: yours to license as you wish.

## Safety

This is a prototype. It must not be relied on as a sole mobility aid —
test with a sighted companion, and treat guidance output as advisory.
