# Profiling the model on the S25 Ultra with QUAD

The QUAD MCP server can profile models on a phone connected over ADB and
report latency percentiles, throughput, memory, and CPU-vs-NPU comparisons.
This is how you verify the detector actually runs on the Hexagon NPU and
meets the latency budget (<150 ms end-to-end reaction time, per Shepherd).

## One-time setup

1. Install Android platform-tools and put `adb` on your PATH:
   <https://developer.android.com/tools/releases/platform-tools>
2. On the S25 Ultra: Settings → About phone → tap *Build number* 7× →
   Developer options → enable **USB debugging**.
3. Plug in over USB, accept the debugging prompt, and confirm with
   `adb devices` (the phone must show as `device`, not `unauthorized`).

## Profiling via the QUAD MCP (from Claude Code)

The QUAD server used by this project is remote, so it cannot reach your
local phone directly. Use the **plan → execute → report** round-trip:

1. Ask Claude Code to call `profile_device_plan` with
   `model_name="yolov8_det.onnx"` (or the compiled `.bin`) — the server
   returns a transport-agnostic recipe: files to push and shell commands to
   run over ADB.
2. Claude Code (or you) runs those steps locally against the phone.
3. Feed the raw outputs back via `profile_device_report` to get the full
   report (`devices`, `comparison`, `recommendations`, rendered markdown).

Alternatively, the QUAD-Client CLI wraps the whole round-trip:

```
quad-client profile-device --model yolov8_det.onnx --transport adb
```

## Compiling for a bigger NPU win

A QNN **context binary** (`.bin`) skips ONNX graph loading entirely and is
the fastest path on-device. Ask Claude Code to run the QUAD `convert_model`
tool with `target_sdk="qnn"`, `quantization="int8"`, then push the `.bin`
and profile it with `qnn-net-run` via the same plan/report flow.

## Rough expectations (Snapdragon 8 Elite, YOLOv8-Det 640x640)

| Runtime | Typical inference latency |
|---|---|
| CPU (fp32) | 80–150 ms |
| Hexagon NPU, fp16 | 8–20 ms |
| Hexagon NPU, w8a8 quantized | 4–10 ms |

The in-app status bar shows live per-frame latency, so you can compare the
NPU path (status shows `Hexagon NPU (QNN)`) against the CPU fallback by
temporarily renaming `libQnnHtp.so` in the QNN options.
