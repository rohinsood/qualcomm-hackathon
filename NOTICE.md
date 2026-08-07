# Third-party notices

Lighthouse is licensed under the **GNU Affero General Public License v3.0**
(see [`LICENSE`](LICENSE)). This file records why that license was chosen and
what third-party components the project redistributes or depends on.

## Why AGPL-3.0

The repository ships a pretrained **YOLOv8n** detector at
`app/src/main/assets/yolov8_det.onnx` (12 MB, on the `v3` branch). Ultralytics
licenses YOLOv8 under **AGPL-3.0**. Distributing that weight file inside this
application makes the combined work a derivative, so the whole project is
released under AGPL-3.0 rather than a permissive license. This is deliberate:
it is the licence the bundled model requires.

If you need a permissively-licensed build, the detector is the only AGPL
component and it is **not on the steering path** — it runs at 1 Hz to supply
object labels and to calibrate the monocular depth scale. Steering is driven by
the depth model and the walkability segmentation. Removing
`yolov8_det.onnx` and swapping in a permissively-licensed detector (for example
from Qualcomm AI Hub) would allow relicensing the remainder under Apache-2.0.

## Models

Model binaries are not all committed to git — see
[`docs/MODELS.md`](docs/MODELS.md) for how each is obtained.

| Model | Role | License | Distribution |
|---|---|---|---|
| YOLOv8n (`yolov8_det.onnx`) | Object labels @ 1 Hz; depth-scale calibration | **AGPL-3.0** (Ultralytics) | Committed on `v3` |
| Depth-Anything-V2-Metric-Indoor-Small | Metric depth, indoor mode | Apache-2.0 | Exported locally (`scripts/export_depth_model.sh`) |
| Depth-Anything-V2-Metric-Outdoor-Small | Metric depth, outdoor mode | Apache-2.0 | Exported locally |
| FFNet-78S-LowRes (Cityscapes) | Walkability segmentation, outdoor | See Qualcomm AI Hub model card | Fetched per-developer |
| SegFormer-B0 (ADE20K) | Walkability segmentation, indoor | See model card | Fetched per-developer |
| Qwen3.5-2B (Q4_0 GGUF) | Optional on-device companion SLM | Apache-2.0 (Qwen) | Downloaded on-device at runtime |
| Supertonic 3 / Kokoro-82M | Neural text-to-speech | See model card | Downloaded on first launch |

Depth-Anything-V2 **Metric** checkpoints are required. The metric variants
output distance in meters directly; the relative-depth checkpoints do not, and
`DepthEngine` / `DepthCalibrator` / `TraversabilityGrid` assume meters.

## Runtimes and libraries

| Component | Version | License |
|---|---|---|
| ONNX Runtime + QNN Execution Provider | 1.28.0 | MIT |
| Qualcomm GenieX (`geniex-android`) | 0.3.16 | Qualcomm — see SDK terms |
| sherpa-onnx (static-link ORT AAR) | 1.13.4 | Apache-2.0 |
| AndroidX CameraX | 1.3.4 | Apache-2.0 |
| Google Play Services (Location 21.3.0, Maps 19.0.0) | — | Google APIs ToS |
| ML Kit text recognition | 16.0.1 | Google APIs ToS |
| kotlinx-coroutines | 1.8.1 | Apache-2.0 |
| Arduino_Modulino + STM32duino sensor drivers | pinned in `board/qcane-wheel/sketch/sketch.yaml` | see each library |

## Prior work

The steering concept — a motorized wheel at the cane tip applying **grounded
kinesthetic feedback** — comes from the Stanford Augmented Cane:

> P. Slade, A. Tambe, M. J. Kochenderfer, "Multimodal sensing and intuitive
> steering assistance improve navigation and mobility for people with impaired
> vision," *Science Robotics* **6**(59), eabg6594 (2021).
> [doi:10.1126/scirobotics.abg6594](https://doi.org/10.1126/scirobotics.abg6594)

Reference implementation: [pslade2/AugmentedCane](https://github.com/pslade2/AugmentedCane).
Lighthouse is an independent implementation and is not affiliated with or
endorsed by the paper's authors or Stanford University.

## Network services

The hot path is fully offline. These are optional and non-blocking:

- **Google Routes API v2 / Geocoding / Places** — outdoor walking routes and
  destination lookup. Requires `maps.apiKey` in `local.properties`. Absent the
  key, navigation falls back to straight-line compass bearing.
- **Hugging Face / model CDNs** — one-time downloads of the companion SLM and
  the neural voice.

No user audio, imagery, or location leaves the device for obstacle avoidance or
steering. Speech recognition uses Android's on-device recognizer.
