# Performance

Two kinds of number appear here, and they are kept strictly separate:

- **Measured** — captured from hardware, with the command to reproduce it.
- **Design budget** — a target the code enforces (a cadence, a timeout). Real,
  but not a measurement of achieved latency.

Nothing below is extrapolated or estimated. Where we don't have a number, it says
so.

---

## Reproducing the phone-side measurement

The pipeline instruments itself. Every 5th frame with depth,
`FrameAnalyzer` emits a full per-stage breakdown under tag `ShepherdTime`:

```bash
adb logcat -s ShepherdTime
```

Format (`FrameAnalyzer.kt:318-332`):

```
mode=in yolo=45ms(1Hz) depth=32ms ffnet=2ms ade=180ms(async)
grid=8ms plan=1ms e2e=98ms ground=0.05m scale=1.05 steer=-15°
```

| Field | Meaning |
|---|---|
| `mode` | `in` / `out` — which domain-matched model pair is active |
| `yolo` | Detector inference; runs at 1 Hz, off the steering path |
| `depth` | Metric depth inference |
| `ffnet` / `ade` | Walkability segmentation — outdoor inline / indoor async |
| `grid` | Ground projection + log-odds update + ground self-calibration |
| `plan` | 37-sector raycast and planner decision |
| `e2e` | Whole frame, camera callback to guidance verdict |
| `ground` | Self-calibrated ground offset — should settle near 0 |
| `scale` | Depth-scale correction from the calibrator — should sit near 1.0 |
| `steer` | Chosen corridor angle |

Two of these are diagnostics as much as timings. If `ground` drifts far from 0,
the camera mount pitch is wrong. If `scale` sits far from 1.0, monocular depth is
being systematically corrected — check that a Metric checkpoint is installed and
not a relative one.

To compare accelerators, watch the provider that `OrtSessions` reports at
startup, then force the CPU tier and re-measure the same scene.

> **Provenance note.** The design budgets below are read from source. Populate
> the measured table by running the command above on your device — we are not
> quoting phone latency figures we can't attach a capture to.

### Phone: design budgets (from source)

| Stage | Budget | Constant |
|---|---|---|
| Analysis cadence | 90 ms (~11 Hz) | `MIN_FRAME_INTERVAL_MS` |
| Depth inference gate | 300 ms (~3 Hz) | `DEPTH_INTERVAL_MS` |
| Detection gate | 1000 ms (1 Hz) | `DETECT_INTERVAL_MS` |
| Motor decision window | 200 ms (5 Hz) | `CommandAggregator.PERIOD_MS` |

### Phone: measured

| Stage | Median | p95 | Device |
|---|---|---|---|
| _run `adb logcat -s ShepherdTime` and fill in_ | | | |

---

## Measured: on-board depth, UNO Q (CPU only)

This one we do have. It's the experiment that decided where depth runs.

**Setup:** UNO Q / QRB2210, 4× Cortex-A53 @ 2 GHz, **CPU only**, 3 threads,
12 iterations, synthetic input. Reproduce with `board/depth-spike/bench.py` on
the `v3` branch.

| Model | Input | Median | p95 | FPS | Peak MB | File MB |
|---|---|---|---|---|---|---|
| MiDaS float | 256×256 | 864 ms | 1023 ms | 1.16 | 197 | 0.1 |
| MiDaS w8a8 | 256×256 | 1001 ms | 1009 ms | 1.00 | 157 | 0.3 |
| Depth-Anything-V2-Small | 126×126 | 452 ms | 453 ms | 2.21 | 213 | 99.1 |
| Depth-Anything-V2-Small | 252×252 | 1728 ms | 1732 ms | 0.58 | 244 | 99.1 |
| Depth-Anything-V2-Small | 378×378 | 4172 ms | 4184 ms | 0.24 | 303 | 99.1 |

**What this settled.** Depth on the board's CPU is not viable for steering. Even
the fastest configuration — DA-Small at a degraded 126×126 — is 452 ms per frame,
and resolution scales brutally: 252×252 costs 3.8× more than 126×126, and
378×378 costs 9.2×. A steering loop needs depth at a few Hz with the *pixels* to
locate a corridor, and this table offers a choice between too slow and too
coarse.

Note also that int8 quantization made MiDaS **slower** (1001 ms vs 864 ms) — on
a CPU-only A53 with no NPU to consume the quantized graph, w8a8 buys smaller
weights and pays in dequantization. Quantization is not automatically a win; it's
a win when there's an accelerator that wants it.

Hence the split the system actually uses: **depth and segmentation on the phone's
NPU/GPU, the board does actuation and near-field sensing.** The board's
Dragonwing has an Adreno GPU, so a QNN path there is future work rather than a
dead end — but the phone's Hexagon is the right target today.

---

## Design budgets: safety timeouts

Every one of these is enforced in code. See
[`ARCHITECTURE.md`](ARCHITECTURE.md) for the mechanisms.

| Guard | Threshold | Where |
|---|---|---|
| Board motor failsafe — command stream silent | 2000 ms | `COMMAND_TIMEOUT_MS`, `sketch.ino` |
| Phone STOP after empty decision windows | 3 windows (600 ms) | `CommandAggregator` |
| BLE write-stall teardown | 4000 ms | `WRITE_STALL_MS`, `CaneBleLink` |
| BLE consecutive write failures | 8 | `WRITE_FAIL_STREAK` |
| BLE watchdog tick | 3000 ms | `CaneBleLink` |
| Haptic STOP repeat interval | 1200 ms | `HapticFeedback` |
| Route look-ahead | 12 m | `LOOKAHEAD_M`, `RouteTracker` |
| Off-route reroute | 30 m × 4 fixes | `OFF_ROUTE_M`, `OFF_ROUTE_STRIKES` |

---

## Measured: companion SLM

From `GenieBench` on Galaxy S25 Ultra (Snapdragon 8 Elite), Qwen3.5-2B Q4_0 on
Hexagon NPU:

| Metric | Value |
|---|---|
| First token | 186 ms |
| Decode | 12.1 tok/s |

Reply length is capped at `MAX_REPLY_TOKENS = 96` (~8 s of speech at that rate)
and history at 6 messages, to bound both rambling and prefill cost.

This is **off by default** (`COMPANION_ENABLED = false`) — it is a conversational
feature, not part of steering, and it wants the same Hexagon the vision models
would otherwise use. That contention is exactly why vision prefers the Adreno GPU
first.

---

## Deeper profiling with QUAD

For per-op HTP cycle counts, power, and CPU/GPU/NPU allocation comparisons, the
QUAD MCP server profiles on real silicon over ADB via a plan → execute → report
round trip:

```
quad-client profile-device --model yolov8_det.onnx --transport adb
```

A QNN **context binary** (`.bin`) skips ONNX graph load entirely and is the
fastest on-device path; `convert_model` with `target_sdk="qnn"` and
`quantization="int8"` produces one. Worth measuring rather than assuming — as the
MiDaS w8a8 row above shows, quantization needs the right consumer to pay off.

---

## What we have not measured

The cane is built, assembled, and has been walked with — but these numbers have
not been captured yet, and they are the ones a reader will most want:

- **End-to-end reaction time**, obstacle entering frame → wheel torque. The
  assembly exists to measure it on; what remains is instrumenting the capture. It
  must include mechanical inertia, which no bench number here accounts for.
- **Battery life** under sustained motor load, phone charging from the same bank.
- **Steering accuracy or walking-speed effect.** The figures in the top-level
  README are the *research paper's* results for *their* device. This
  implementation has not been evaluated with users with impaired vision — informal
  walking by the team is not that.
