# Lighthouse — Model Guide

Every ML model Lighthouse loads, where it comes from, and exactly how to get
it onto the phone.

The Kotlin package is `dev.quad.shepherd`, so every on-device path below is
rooted at:

```
/sdcard/Android/data/dev.quad.shepherd/files/
```

---

## READ THIS FIRST — only one model is committed

**`yolov8_det.onnx` (12,824,107 bytes ≈ 12 MB) is the ONLY model file in
git.** Everything else — both depth models, both segmentation models, the
companion SLM, and the neural voice — is absent from a fresh clone and must
be exported, fetched, pushed, or downloaded on-device.

This is enforced by `.gitignore` on `origin/v3`:

```gitignore
app/src/main/assets/*.onnx
!app/src/main/assets/yolov8_det.onnx
app/src/main/assets/*.bin
app/src/main/assets/*.dlc
scripts/build/
```

So a clean clone builds and runs, but it runs **detection-only**: no metric
depth, therefore no traversability grid, therefore no BEV steering. See
[Does the app run without these?](#does-the-app-run-without-these).

---

## Metric depth is not optional — it is a correctness requirement

**Depth-Anything-V2 must be a `Metric` checkpoint.** The metric variants
predict **distance in meters per pixel** (lower = closer). Both consumers
assume that unit outright:

| Consumer | File | Assumption |
|---|---|---|
| `DepthCalibrator` | `app/src/main/java/dev/quad/shepherd/guidance/DepthCalibrator.kt` | Gates samples on `modelMeters in 0.2f..25f`, rejects ratios outside `0.6f..1.8f`, clamps scale to `0.7f..1.4f` |
| `TraversabilityGrid` (via `FrameAnalyzer`) | `app/src/main/java/dev/quad/shepherd/vision/FrameAnalyzer.kt` | Feeds `columnNearField` output straight into world-space BEV cells |

A **relative-depth** checkpoint outputs inverse-depth/disparity in arbitrary
per-frame units. Nothing throws. The plausibility gates simply reject almost
every sample, the scale factor stays pinned at its clamp, and the grid fills
with garbage geometry — the failure is **silent and steering-relevant**.

`scripts/export_depth_model.sh` warns about this in its header comment:

> The METRIC variant outputs distance in meters directly (lower = closer) —
> required by DepthEngine/DepthCalibrator; do not swap in a relative-depth
> checkpoint without changing those classes.

The `DepthCalibrator` docstring records that this was learned the hard way:

> (Historical note: the previous relative-depth design fitted a global
> disparity-to-meters mapping, which is unsound for models with per-frame
> scale — that is why walls used to go unreported.)

---

## Exact filenames the app looks for

From `git grep -ohE '"(models/)?[a-z0-9_]+\.onnx"' origin/v3 -- 'app/src/main/java'`:

| Filename in code | Engine | Resolution order |
|---|---|---|
| `yolov8_det.onnx` | `DetectionEngine` | `files/models/` → APK asset |
| `depth_anything_v2_small.onnx` | `DepthEngine` (indoor, default ctor) | `files/models/` → APK asset |
| `depth_anything_v2_outdoor.onnx` | `DepthEngine("depth_anything_v2_outdoor.onnx")` | `files/models/` → APK asset |
| `models/ffnet_78s_lowres.onnx` | `SegEngine(SegEngine.FFNET)` | `files/models/` **only** |
| `models/segformer_b0_ade.onnx` | `SegEngine(SegEngine.ADE)` | `files/models/` **only** |

Two different loaders, and the difference matters:

- `DetectionEngine` / `DepthEngine` use `loadModelBytes()`: try
  `<external-files>/models/<name>` first, then fall back to
  `context.assets.open(<name>)`. **Both push and asset work.**
- `SegEngine.initialize()` does `File(context.getExternalFilesDir(null), spec.modelFile)`
  and returns `false` if `!f.isFile`. **There is no asset fallback** — the
  segmentation models *must* be adb-pushed. Note the spec strings already
  contain the `models/` prefix.

`SegEngine` uses `OrtSessions.createFromPath` (not the byte-array overload)
specifically so external-data weights resolve their sibling `.data` file
relative to the `.onnx` path.

---

## Model-by-model reference

### 1. YOLOv8n detector — `yolov8_det.onnx`

| | |
|---|---|
| Class | `DetectionEngine` (`app/src/main/java/dev/quad/shepherd/vision/DetectionEngine.kt`) |
| Purpose | 80-class COCO object detection. **Does not steer.** Feeds labels, the `SceneBlackboard`, and depth scale calibration |
| Cadence | `DETECT_INTERVAL_MS = 1000L` → ~1 Hz |
| Input | `1 x 3 x 640 x 640` float32, CHW, RGB normalized to 0..1 (`INPUT_SIZE = 640`). Letterboxed, aspect preserved |
| Output | Two layouts, auto-detected by `YoloPostProcessor.parse`: **raw** `[1, 4+numClasses, N]` (cx, cy, w, h + per-class scores) or **split** boxes `[1,N,4]` xyxy + scores `[1,N]` + class ids `[1,N]`. Class-id tensors may be int64/int32/uint8 — `toFloatArray` handles all |
| Post-proc | `confThreshold = 0.45f`, `iouThreshold = 0.5f`, then NMS |
| License | **AGPL-3.0** (Ultralytics YOLOv8 weights) |
| In git? | **YES — the only committed model.** `app/src/main/assets/yolov8_det.onnx`, 12,824,107 bytes |

`FrameAnalyzer` also caps the whole analyzer at `MIN_FRAME_INTERVAL_MS = 90L`
(~11 fps) — running back-to-back pinned the GPU and thermally throttled the
SoC.

The comment in `FrameAnalyzer` is explicit about why 1 Hz is enough:

> Steering no longer uses YOLO (the grid does that job); boxes only feed
> labels, the blackboard, and the depth scale calibration — 1 Hz is plenty,
> and the freed GPU time goes to the depth model, which IS latency-critical.

#### Refresh it (optional — already committed)

Route A, Qualcomm AI Hub (x86-64; needs a free account + API token):

```bash
pip install qai-hub "qai-hub-models[yolov8-det]"
qai-hub configure --api_token YOUR_TOKEN
./scripts/fetch_model.sh          # Linux/WSL
.\scripts\fetch_model.ps1         # Windows
```

Both scripts run the same export and copy the result into place:

```bash
python -m qai_hub_models.models.yolov8_det.export \
    --device "Samsung Galaxy S25 (Family)" \
    --target-runtime onnx \
    --skip-profiling --skip-inferencing \
    --output-dir scripts/build/yolov8_det
# then cp <found>.onnx -> app/src/main/assets/yolov8_det.onnx
```

`fetch_model.ps1` notes a smaller NPU variant: re-run with `--precision w8a8`
for a quantized ~3.3 MB model.

Route B, plain Ultralytics export (works on ARM64 dev boxes where the AI Hub
chain has no wheels):

```bash
pip install ultralytics
python -c "from ultralytics import YOLO; YOLO('yolov8n.pt').export(format='onnx', imgsz=640, opset=17)"
cp yolov8n.onnx app/src/main/assets/yolov8_det.onnx
```

No device-specific compile step is needed either way — `DetectionEngine`
compiles for the Hexagon NPU on-device at first run via the QNN EP.

#### Push without rebuilding

```bash
adb push yolov8_det.onnx /sdcard/Android/data/dev.quad.shepherd/files/models/
```

---

### 2 & 3. Depth-Anything-V2-Metric — indoor and outdoor

| | Indoor | Outdoor |
|---|---|---|
| Filename | `depth_anything_v2_small.onnx` | `depth_anything_v2_outdoor.onnx` |
| HF checkpoint | `depth-anything/Depth-Anything-V2-Metric-Indoor-Small-hf` | Metric **Outdoor** Small sibling |
| Fine-tune domain | **Hypersim** (synthetic indoor) | **VKITTI** (synthetic driving/outdoor) |
| Instantiated at | `ShepherdService.kt:111` — `DepthEngine()` | `ShepherdService.kt:112` — `DepthEngine("depth_anything_v2_outdoor.onnx")` |
| Selected when | `FrameAnalyzer.indoorMode == true` | `indoorMode == false` |
| Size | ~99 MB (per the export script) | ~99 MB |
| License | **Apache-2.0** | **Apache-2.0** |
| In git? | **No** — gitignored | **No** — gitignored |

Domain selection, from `FrameAnalyzer.analyze`, with fallback to whichever
member actually loaded:

```kotlin
val activeDepth = when {
    indoor -> depthEngine ?: depthEngineOutdoor
    else -> depthEngineOutdoor ?: depthEngine
}
```

`indoorMode` mirrors `CompassNav.Mode` (`OUTDOOR` / `INDOOR`).

#### Tensors

| | |
|---|---|
| Input name | `pixel_values` |
| Input shape | `1 x 3 x 294 x 294` float32, CHW |
| Normalization | ImageNet — mean `{0.485, 0.456, 0.406}`, std `{0.229, 0.224, 0.225}` |
| Output name | `predicted_depth` |
| Output shape | `1 x 294 x 294` float32 — **meters**, lower = closer |
| Opset | 17, `do_constant_folding=True`, `dynamo=False` |

**Why 294?** It is a multiple of **14**, the ViT-S patch size. The export
script's own comment: `# multiple of 14 (ViT-S patch size); 294 balances
speed/quality`. It is the script's default (`SIZE="${1:-294}"`).

`DepthEngine` does **not** hardcode this. It reads dim 2 of the input
`TensorInfo` and accepts anything in `70..1030`, falling back to
`DEFAULT_INPUT_SIZE = 294`. Re-exports at a different size are drop-in:

```kotlin
inputSize = shape?.getOrNull(2)?.toInt()?.takeIf { it in 70..1030 } ?: DEFAULT_INPUT_SIZE
```

#### Cadence and consumption

| | |
|---|---|
| Cadence | `DEPTH_INTERVAL_MS = 300L` → ~3.3 Hz, time-gated ("walls don't move at frame rate") |
| Analysis band | `CORRIDOR_TOP = 0.25f`, `CORRIDOR_BOTTOM = 0.65f` of frame height — top cut drops sky/ceiling, bottom cut stops the floor at the user's feet reading as an obstacle |
| Column reduction | `columnNearField(numColumns)` takes the **20th percentile** (close tail) per column, subsampled every 2 px |
| Box query | `boxMedian(...)` — median over the central 50% of a detection box, feeding `DepthCalibrator` |
| Letterbox fix | `maskLetterboxBars(...)` — the model hallucinates geometry for the black padding bars (up to 25% of the square), which is "poison for the grid and the ground estimate" |

#### Export the indoor model (exact command)

Run on Linux/WSL — Windows-on-ARM lacks torch wheels. In a venv:

```bash
pip install torch transformers onnx onnxscript onnxruntime
./scripts/export_depth_model.sh          # defaults to 294
./scripts/export_depth_model.sh 294      # explicit
```

The script exports, then **verifies** with onnxruntime — asserting
`out.shape == (1, size, size)` and `np.isfinite(out).all()`, and printing the
observed meters range. It finishes by copying to
`app/src/main/assets/depth_anything_v2_small.onnx`.

#### Export the outdoor model

There is **no** committed script for the outdoor variant. `origin/v3` contains
only `scripts/export_depth_model.sh`, which is hardcoded to
`Depth-Anything-V2-Metric-Indoor-Small-hf` and to the output filename
`depth_anything_v2_small.onnx`.

To produce `depth_anything_v2_outdoor.onnx`, copy the script and change the
checkpoint to the Metric **Outdoor** Small sibling (VKITTI fine-tune) and the
output filename. Keep opset 17, keep 294x294, and keep `Metric`.

> Marked explicitly: the exact HF repo id for the outdoor checkpoint is **not
> present anywhere in `origin/v3`** — only the "VKITTI fine-tune" description
> in `DepthEngine` and `FrameAnalyzer` KDoc. Confirm the id on Hugging Face
> before exporting.

#### Install

Asset (rebuild required):

```
app/src/main/assets/depth_anything_v2_small.onnx
app/src/main/assets/depth_anything_v2_outdoor.onnx
```

Or push to an installed app (no rebuild):

```bash
adb push depth_anything_v2_small.onnx   /sdcard/Android/data/dev.quad.shepherd/files/models/
adb push depth_anything_v2_outdoor.onnx /sdcard/Android/data/dev.quad.shepherd/files/models/
```

---

### 4. FFNet-78S-LowRes — `models/ffnet_78s_lowres.onnx`

| | |
|---|---|
| Spec | `SegEngine.FFNET` |
| Role | **Outdoor** walkability expert — road / sidewalk / terrain |
| Dataset | Cityscapes, 19 classes |
| Walkable class ids | `0` (road), `1` (sidewalk), `9` (terrain) |
| Quantization | int8 (`w8a8`); the export quantizes at the boundary — **uint8 in, uint8 out** |
| Latency | ~3 ms on the Hexagon (per `SegEngine` KDoc) |
| Provider | `preferNpu = true` |
| License | Qualcomm AI Hub model — **verify the license on the AI Hub model page**; not stated in-repo |
| In git? | **No** — and **no asset fallback**, must be pushed |

| Tensor | Shape |
|---|---|
| Input | `1 x 3 x 512 x 1024` (`inW=1024`, `inH=512`), **no** ImageNet norm (`imagenetNorm = false`, raw 0..1 or uint8 0..255) |
| Output | `19 x 128 x 256` logits (`outW=256`, `outH=128`), argmax'd to row-major class ids |

`SegEngine` detects the uint8 boundary from the input `TensorInfo` type and
switches buffers automatically:

```kotlin
uint8Io = info?.type == OnnxJavaType.UINT8
```

`argmaxU8` notes that uint8 logits share one quantization scale, so a raw
argmax is valid without dequantizing.

FFNet runs **inline on the NPU** in the hot path (`ffnetMs` is measured
synchronously).

#### Obtain

Fetch **FFNet-78S-LowRes** from the Qualcomm AI Hub catalog (`aihub.qualcomm.com`).
The repo does not ship an export script for it. `tools/walkability/README.md`
shows the artifact path shape from a real AI Hub export:

```
/mnt/c/.../ffnet_78s_lowres-onnx-float/ffnet_78s_lowres.onnx
```

By analogy with `scripts/fetch_model.sh`, the AI Hub CLI export is:

```bash
pip install qai-hub "qai-hub-models[ffnet-78s-lowres]"
qai-hub configure --api_token YOUR_TOKEN
python -m qai_hub_models.models.ffnet_78s_lowres.export \
    --device "Samsung Galaxy S25 (Family)" \
    --target-runtime onnx \
    --skip-profiling --skip-inferencing \
    --output-dir build/ffnet
```

> Marked explicitly: that exact module path is **inferred** from the YOLOv8
> pattern in `scripts/fetch_model.sh`, not committed anywhere in `origin/v3`.
> Confirm the model slug in the AI Hub catalog.

#### Push (mandatory — no asset fallback)

```bash
adb push ffnet_78s_lowres.onnx /sdcard/Android/data/dev.quad.shepherd/files/models/
```

If the model has external-data weights, push the sibling `.data` file into
the same directory — `SegEngine` loads by path so it resolves relatively.

---

### 5. SegFormer-B0-ADE20K — `models/segformer_b0_ade.onnx`

| | |
|---|---|
| Spec | `SegEngine.ADE` |
| Role | **Indoor** walkability expert — "knows what a FLOOR is" |
| Dataset | ADE20K, 150 classes |
| Walkable class ids | `3, 6, 9, 11, 13, 28, 52` — floor, road, grass, sidewalk, earth, rug, path |
| Normalization | ImageNet (`imagenetNorm = true`) — HF export |
| Provider | `preferNpu = true` |
| License | SegFormer-B0 / ADE20K upstream — **verify**; not stated in-repo |
| In git? | **No** — and **no asset fallback**, must be pushed |

| Tensor | Shape |
|---|---|
| Input | `1 x 3 x 512 x 512` (`inW=512`, `inH=512`) |
| Output | `150 x 128 x 128` logits (`outW=128`, `outH=128`) |

**Runs asynchronously**, unlike FFNet. From `FrameAnalyzer`:

> The ADE20K ensemble member runs on its own thread: in NPU/CPU mixed mode it
> is far slower than FFNet, and inline it stalled the depth → grid → plan hot
> path. Walkability is soft evidence, so a mask a few hundred ms stale is
> fine.

It runs on a single-threaded executor named `ade-seg` at
`Thread.NORM_PRIORITY - 1`, guarded by an `adeBusy` flag, and publishes a
`copyOf()` because `SegEngine` reuses its output buffer.

#### Obtain

Export SegFormer-B0 fine-tuned on ADE20K from Hugging Face
(`transformers` → ONNX, 512x512, ImageNet-normalized input, CHW). No script
for this exists in `origin/v3`.

> Marked explicitly: the exact HF checkpoint id is **not committed** in
> `origin/v3` — only "SegFormer-B0 (ADE20K, 150 classes)" in `SegEngine`.

#### Push (mandatory)

```bash
adb push segformer_b0_ade.onnx /sdcard/Android/data/dev.quad.shepherd/files/models/
```

---

### Segmentation is domain-matched, not an always-both ensemble

Although `SegEngine`'s KDoc describes an ENSEMBLE, `FrameAnalyzer` picks one
member per nav mode:

```kotlin
val engA = if (indoor) null else segEngine      // FFNet, outdoor, inline
val engB = if (indoor) segEngine2 else null     // ADE, indoor, async
```

> Segmentation is DOMAIN-MATCHED to the nav mode: outdoor runs FFNet
> (Cityscapes, inline on the NPU), indoor runs the ADE20K member (knows
> floors, async) — each expert on its own turf, and half the seg compute of
> the always-both ensemble.

`mergedWalkable(...)` still combines whatever masks are available, and
`WalkableColumns.clearanceFromMask` prefers the merged view because "FFNet
alone is out-of-domain indoors and steered the fallback toward Cityscapes
hallucinations."

---

### 6. Qwen3.5-2B companion SLM

| | |
|---|---|
| Model id | `unsloth/Qwen3.5-2B-GGUF` (`GenieRuntime.MODEL`) |
| Precision | `Q4_0` (`GenieRuntime.PRECISION`) — "best Hexagon kernel coverage in llama.cpp's NPU backend" |
| Runtime | GenieX SDK (`com.qualcomm.qti:geniex-android:0.3.16`), `runtime_id = "llama_cpp"` |
| Compute unit | `npu` (`GenieChat.COMPUTE_UNIT`) |
| Hub | `HubSource.HUGGINGFACE` |
| Download size | **~1.2 GB** |
| Measured perf | 12 tok/s, 186 ms first token (step-0 winner) |
| Role | Conversational companion; scene-grounded via `SceneBlackboard`, streams sentence-by-sentence |
| License | Qwen3.5 upstream + the unsloth GGUF repack — **verify on the HF model card** |
| In git? | **No.** Downloaded **on-device at runtime** — never adb-pushed, no asset path |

Generation config:

| Constant | Value | Meaning |
|---|---|---|
| `N_CTX` | `4096` | Context window |
| `MAX_REPLY_TOKENS` | `96` | ~8 s of speech at 12 tok/s — a hard cap on rambling |
| `MAX_HISTORY_MESSAGES` | `6` | Verbatim turns kept; every history token is prefill latency |

Thinking is disabled two ways — `enableThinking=false` in the chat-template
call plus a ` /no_think` suffix appended to each user turn, because "Qwen3.5's
hybrid reasoning would sit in silence for seconds before the first spoken
word."

#### How to obtain — you don't, the app does

`GenieRuntime.ensureModel()` pulls it through `ModelManagerWrapper.pullFlow`
on first use (Wi-Fi expected), with `modelPresent()` making the call
idempotent. `GenieChat.warmUp()` drives init → download → `LlmWrapper` build.

Two hard requirements on the build side:

1. `GenieXSdk.getInstance().init(...)` registers both plugins **by absolute
   `.so` path**, which requires extracted native libs —
   `useLegacyPackaging = true` in `app/build.gradle.kts`.
2. GenieX is declared **before** onnxruntime in the dependency block so its
   newer QNN libs win the `jniLibs` merge.

Accelerator split, from `OrtSessions` — this is why the SLM gets the NPU:

> Tier order: GPU (full) -> NPU (full) -> GPU/CPU -> NPU/CPU -> CPU.
> GPU comes FIRST by design: the workload split is vision=Adreno,
> SLM=Hexagon. When the NPU tier led, vision landed on the Hexagon and
> throttled Qwen's decode from ~12 tok/s to ~5 — the accelerators must not
> share.

---

### 7. Neural TTS — Supertonic 3 (preferred) / Kokoro-82M (fallback)

| | Supertonic 3 | Kokoro-82M |
|---|---|---|
| Directory | `files/models/supertonic/` | `files/models/kokoro/` |
| Params | 66M | 82M |
| Speed | ~10x faster synthesis — preferred | fallback |
| Speaker id | `0` | `3` (`af_heart`, Kokoro v1.0's best-rated English voice) |
| Runtime | sherpa-onnx `OfflineTts`, `provider = "cpu"`, `numThreads = 4` | same |
| In git? | **No** — self-downloaded on-device | **No** — manual push only |

Both run on **CPU worker threads** by design: "the NPU belongs to the SLM and
the GPU to vision."

Playback speed: `NORMAL_SPEED = 1.05f`, `URGENT_SPEED = 1.25f`. Audio goes out
through an `AudioTrack` with `USAGE_ASSISTANCE_ACCESSIBILITY` /
`CONTENT_TYPE_SPEECH`, `ENCODING_PCM_FLOAT`, mono, written in 6000-sample
slices (0.25 s at 24 kHz) so a generation bump can cancel between slices.

Selection order in `NeuralTts.tryCreate(baseDir)`: Supertonic first, then
Kokoro, else `null` → `SpeechFeedback` stays on the **system** TTS engine.

#### Required files — Supertonic (all 7 must exist, else the member is skipped)

```
files/models/supertonic/duration_predictor.int8.onnx
files/models/supertonic/text_encoder.int8.onnx
files/models/supertonic/vector_estimator.int8.onnx
files/models/supertonic/vocoder.int8.onnx
files/models/supertonic/tts.json
files/models/supertonic/unicode_indexer.bin
files/models/supertonic/voice.bin
```

#### Required files — Kokoro

```
files/models/kokoro/model.int8.onnx      (required)
files/models/kokoro/voices.bin           (required)
files/models/kokoro/tokens.txt           (required)
files/models/kokoro/espeak-ng-data/      (required, directory)
files/models/kokoro/lexicon-us-en.txt    (optional)
files/models/kokoro/lexicon-zh.txt       (optional)
files/models/kokoro/dict/                (optional, directory)
```

#### How Supertonic arrives — automatic, on-device

`VoiceFetcher.ensureAsync()` downloads and unpacks it. It is a **no-op** when:

- `files/models/supertonic/vocoder.int8.onnx` already exists, **or**
- `files/models/kokoro/model.int8.onnx` already exists, **or**
- a fetch is already in flight, **or**
- the active network is **not** unmetered (`NET_CAPABILITY_NOT_METERED`) —
  logged as "no voice on disk but no unmetered network — will retry next launch"

Package URL (~130 MB), hardcoded in `VoiceFetcher`:

```
https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2
```

It is unpacked with commons-compress (bzip2 + tar), stripping the top-level
directory and rejecting any entry path containing `..`.

#### Manual install (offline / metered device)

```bash
curl -LO https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2
tar xjf sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2
# strip the top-level dir, exactly as VoiceFetcher does
adb shell mkdir -p /sdcard/Android/data/dev.quad.shepherd/files/models/supertonic
adb push sherpa-onnx-supertonic-3-tts-int8-2026-05-11/. \
    /sdcard/Android/data/dev.quad.shepherd/files/models/supertonic/
```

For Kokoro, push the equivalent sherpa-onnx Kokoro int8 package into
`files/models/kokoro/` with the layout above.

> Note: `NeuralTts` deliberately calls `tts.generate()`, **not**
> `generateWithCallback()` — the JNI callback path fatally aborts under
> Kotlin 2.x invokedynamic lambdas because D8's synthetic class lacks the
> specialized `invoke([F)Integer` method the native side looks up.

> `sherpa-onnx` is a **local AAR**: `app/libs/sherpa-onnx-static-link-onnxruntime-1.13.4.aar`.
> It is a static-link build — its ORT is linked into `libsherpa-onnx-jni.so`
> so it cannot clash with the QNN ORT (`onnxruntime-android-qnn:1.28.0`).

---

## Does the app run without these?

### Hard requirement — exactly one model

**`yolov8_det.onnx` is the only model whose absence stops the app.**
`ShepherdService.startPipeline()` gates everything on it:

```kotlin
val detectionOk = engine.initialize(this@ShepherdService)
if (detectionOk) {
    depthEngine.initialize(...)
    depthEngineOutdoor.initialize(...)
    segEngine.initialize(...)
    segEngineAde.initialize(...)
}
detectionOk
```

If it fails: `visionLabel = "no model"`, `R.string.model_missing` is spoken,
and `startPipeline` returns — **the camera is never bound**. Note the nesting:
depth and seg are only even attempted when detection succeeded.

Since it is committed, a fresh clone clears this bar automatically.

### Required for real steering — at least one metric depth model

The BEV path pipeline is wired in only if a depth model loaded:

```kotlin
pathPipeline.takeIf { depthEngine.available || depthEngineOutdoor.available }
```

With **no** depth model, `path` is `null`, so:

- no `TraversabilityGrid` accumulation, no `PolarPlanner.Plan`
- `result.plan` is `null`, so `ShepherdService` falls back to
  `guidanceEngine.update(...)` — the **v1** 9-column pinhole heuristic
  (`GuidanceEngine.NUM_COLUMNS = 9`), driven by bbox-height distance estimates
  for YOLO's 80 COCO classes only
- class-free obstacles — walls, poles, doors, furniture — are **invisible**,
  which `DepthEngine`'s KDoc calls out as exactly the sense the detector lacks
- the standing-still case degrades: metric depth reads a wall's true distance
  with no motion; pinhole needs a recognized object of known height

So: the app *runs* and *steers*, but on v1 quality. **Push at least one
metric depth model.**

### Fully optional — graceful degradation

| Model | Absent behavior |
|---|---|
| Indoor depth only missing | Outdoor model serves both modes (`depthEngine ?: depthEngineOutdoor`) |
| Outdoor depth only missing | Indoor model serves both modes |
| `ffnet_78s_lowres.onnx` | Logs `no models/ffnet_78s_lowres.onnx — ffnet off`; grid runs geometry-only outdoors |
| `segformer_b0_ade.onnx` | Same, `segformer-ade off`; grid runs geometry-only indoors |
| Qwen3.5-2B | Companion chat unavailable (`Status.FAILED` / never warmed). Navigation, haptics, and TTS unaffected |
| Supertonic + Kokoro both absent | `tryCreate` returns `null`; `SpeechFeedback` uses the Android **system** TTS voice. Guidance still speaks |

Segmentation is *soft evidence* fused into the grid — depth geometry alone
still produces a plan.

---

## Minimum viable push, from a fresh clone

```bash
# 0. Build + install. yolov8_det.onnx ships in the APK.
./gradlew :app:installDebug

# 1. Export the indoor metric depth model (Linux/WSL venv).
pip install torch transformers onnx onnxscript onnxruntime
./scripts/export_depth_model.sh 294

# 2. Push it (no rebuild needed).
adb shell mkdir -p /sdcard/Android/data/dev.quad.shepherd/files/models
adb push depth_anything_v2_small.onnx \
    /sdcard/Android/data/dev.quad.shepherd/files/models/

# 3. Optional: walkability experts (MUST be pushed, no asset fallback).
adb push ffnet_78s_lowres.onnx  /sdcard/Android/data/dev.quad.shepherd/files/models/
adb push segformer_b0_ade.onnx  /sdcard/Android/data/dev.quad.shepherd/files/models/

# 4. Nothing to do for Qwen3.5-2B or the voice — both self-provision
#    on-device over Wi-Fi.
```

### Verify what actually loaded

`ShepherdService` builds `visionLabel` from the live providers, e.g.
`GPU +depth-in(GPU) +depth-out(NPU) +seg(NPU) +ade(NPU/CPU mixed)`. It appears
in the status bar and in `DebugLog` tag `VIS`.

```bash
adb logcat -s DetectionEngine:I DepthEngine:I SegEngine:I NeuralTts:I \
              VoiceFetcher:I GenieRuntime:I GenieChat:I
```

Per-engine expectations:

| Tag | Healthy line |
|---|---|
| `DepthEngine` | `depth_anything_v2_small.onnx ready on <provider>, input 294x294` |
| `SegEngine` | `ffnet ready on NPU (uint8Io=true)` |
| `NeuralTts` | `supertonic ready: <n> voices @ <rate> Hz` |
| `DetectionEngine` | `Session ready on <provider>, input=<name>` |

If a provider reads `CPU` where you expected NPU, QNN failed to claim the
graph — `OrtSessions` only labels a tier `NPU`/`GPU` when
`session.disable_cpu_ep_fallback` let the *entire* graph compile there, so the
label is trustworthy.

---

## Offline evaluation harness

`tools/walkability/` mirrors the on-device `TraversabilityGrid` +
`PolarPlanner` in numpy so candidates can be compared on real burst photos
before anything ships.

```bash
pip install onnxruntime pillow matplotlib numpy
python tools/walkability/run.py \
  --photos ./photos \
  --ffnet  /mnt/c/.../ffnet_78s_lowres-onnx-float/ffnet_78s_lowres.onnx \
  --depth  ~/depth_anything_v2_small_294.onnx \
  --out    ./results
```

Photo protocol: bursts of **3-5 photos from chest height** with slight natural
movement between shots — that movement is what exposes decision jitter.
Layout is `photos/<scene-name>/*.jpg`.

Candidates:

| ID | Stack |
|---|---|
| A | FFNet walkability mask only (image-space columns) |
| B | Pedestrian-view fine-tune — **not yet built**, awaiting a SAM2 pseudo-label set |
| C | Geometry only: metric depth → ground plane → BEV grid |
| D | Fusion: FFNet walkability x depth geometry → BEV grid (**the shipped pipeline**) |

Output: a per-scene panel PNG plus `metrics.json` recording per-burst **angle
spread** — the stability metric, lower is better, STOP frames excluded.

The harness hardcodes constants that must stay in sync with the Kotlin:

| Constant | Value | Kotlin owner |
|---|---|---|
| `CELL_M` | `0.1` | `TraversabilityGrid.kt` |
| `CELLS_WIDE` / `CELLS_DEEP` | `61` / `60` | `TraversabilityGrid.kt` |
| `L_OBSTACLE` / `L_FREE` / `L_SOFT` | `0.9` / `-0.4` / `0.35` | `TraversabilityGrid.kt` |
| `OBSTACLE_MIN_H` / `GROUND_TOL` | `0.18` / `0.16` | `TraversabilityGrid.kt` |
| `OBSTACLE_THRESHOLD` | `0.7` | `TraversabilityGrid.kt` |
| `CAM_HEIGHT` / `PITCH_RAD` / `HFOV_DEG` | `1.35` / `0.30` / `70.0` | `TraversabilityGrid.kt` |
| `SECTORS` / `MAX_RANGE` | `37` / `5.5` | `PolarPlanner.kt` |
| `BLOCK_ENTER` / `BLOCK_EXIT` | `1.3` / `1.7` | `PolarPlanner.kt` |
| `W_GOAL` / `W_PREV` / `W_WIDTH` | `1.0` / `1.6` / `0.8` | `PolarPlanner.kt` |
| `COMMIT_ALPHA` / `MIN_VALLEY` | `0.35` / `3` | `PolarPlanner.kt` |

Note the harness runs both models on `CPUExecutionProvider` and resizes seg
input to `(1024, 512)`, matching `SegEngine.FFNET`.

---

## Summary table

| Model | File / id | Committed? | Acquisition | Destination |
|---|---|---|---|---|
| YOLOv8n | `yolov8_det.onnx` | **YES (12 MB)** | AI Hub or Ultralytics export | asset **or** `files/models/` |
| Depth V2 Metric Indoor | `depth_anything_v2_small.onnx` | No | `scripts/export_depth_model.sh` | asset **or** `files/models/` |
| Depth V2 Metric Outdoor | `depth_anything_v2_outdoor.onnx` | No | adapt the export script (VKITTI ckpt) | asset **or** `files/models/` |
| FFNet-78S-LowRes | `models/ffnet_78s_lowres.onnx` | No | Qualcomm AI Hub | `files/models/` **only** |
| SegFormer-B0-ADE20K | `models/segformer_b0_ade.onnx` | No | HF → ONNX export | `files/models/` **only** |
| Qwen3.5-2B | `unsloth/Qwen3.5-2B-GGUF` `Q4_0` | No | **runtime** via GenieX, ~1.2 GB | GenieX-managed |
| Supertonic 3 | `files/models/supertonic/*` | No | **runtime** via `VoiceFetcher`, ~130 MB | `files/models/supertonic/` |
| Kokoro-82M | `files/models/kokoro/*` | No | manual sherpa-onnx package | `files/models/kokoro/` |

### Licenses at a glance

| Model | License | Note |
|---|---|---|
| YOLOv8n | **AGPL-3.0** | Ultralytics. Fine for this open-source assistive project; a commercial app needs a commercial Ultralytics license or a permissive detector from the AI Hub catalog. `YoloPostProcessor` handles both output layouts, so swapping is a drop-in asset replacement |
| Depth-Anything-V2-Metric (both) | **Apache-2.0** | Stated in `scripts/export_depth_model.sh` |
| FFNet-78S-LowRes | Verify on the AI Hub model page | Not stated in-repo |
| SegFormer-B0-ADE20K | Verify upstream | Not stated in-repo |
| Qwen3.5-2B GGUF | Verify on the HF model card | Not stated in-repo |
| Supertonic 3 / Kokoro-82M | Verify in the sherpa-onnx release | Not stated in-repo |

---

## Safety

This is a prototype. It must not be relied on as a sole mobility aid — test
with a sighted companion, and treat all guidance output as advisory. A missing
or wrong-variant depth model degrades steering **silently**; verify
`visionLabel` before every field test.
