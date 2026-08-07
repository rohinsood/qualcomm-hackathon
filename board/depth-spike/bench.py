#!/usr/bin/env python3
"""Phase 2 benchmark: latency / FPS / peak memory per depth model.

Runs at 3 threads with small inter-iteration sleeps + per-config cooldown: at
4 threads flat-out this board resets (thermal/power/watchdog), so we stay under
that. Latency is input-content-independent, so if the USB camera is absent we
benchmark on a synthetic frame (real-frame accuracy was checked in the viewer).

Results are appended to a PERSISTENT file (bench_results.txt) as each config
finishes, so a network blip or reset cannot erase collected rows.

Parent: for each (model,size) run a fresh subprocess (clean peak RSS), collect,
print + persist a table. Child (--one MODEL SIZE): time one model, print JSON.
"""
from __future__ import annotations

import argparse
import json
import math
import resource
import subprocess
import sys
import time
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parent
SAMPLE = ROOT / "models" / "sample.npy"
RESULTS = ROOT / "bench_results.txt"
MODELS = {
    "midas-float": ROOT / "models/midas/float/midas-onnx-float/midas.onnx",
    "midas-w8a8": ROOT / "models/midas/w8a8/midas-onnx-w8a8/midas.onnx",
    "da-small": ROOT / "models/depthanything/da_v2_small.onnx",
}
CONFIGS = [
    ("midas-float", 256),
    ("midas-w8a8", 256),
    ("da-small", 126),
    ("da-small", 252),
    ("da-small", 378),
]
IMAGENET_MEAN = np.array([0.485, 0.456, 0.406], np.float32).reshape(3, 1, 1)
IMAGENET_STD = np.array([0.229, 0.224, 0.225], np.float32).reshape(3, 1, 1)
THREADS = 3
ITERS = 12
WARMUP = 2
ITER_SLEEP = 0.1      # keep duty < 100% so the board does not reset
COOLDOWN = 3.0


def get_frame():
    if SAMPLE.is_file():
        return np.load(SAMPLE), "camera-sample"
    return (np.random.randint(0, 255, (480, 640, 3), np.uint8), "synthetic")


def preprocess(model, frame_bgr, size):
    import cv2
    rgb = cv2.cvtColor(frame_bgr, cv2.COLOR_BGR2RGB)
    if model.startswith("midas"):
        r = cv2.resize(rgb, (256, 256), interpolation=cv2.INTER_AREA)
        chw = np.transpose(r.astype(np.float32) / 255.0, (2, 0, 1))[None]
        if model == "midas-w8a8":
            scale, zp = 0.00487531116232276, 24
            return np.clip(np.round(chw / scale) + zp, 0, 255).astype(np.uint8)
        return chw.astype(np.float32)
    s = (size // 14) * 14
    r = cv2.resize(rgb, (s, s), interpolation=cv2.INTER_AREA)
    chw = np.transpose(r.astype(np.float32) / 255.0, (2, 0, 1))
    return ((chw - IMAGENET_MEAN) / IMAGENET_STD)[None].astype(np.float32)


def run_child(model, size):
    import onnxruntime as ort
    frame, _ = get_frame()
    so = ort.SessionOptions()
    so.intra_op_num_threads = THREADS
    so.inter_op_num_threads = 1
    so.graph_optimization_level = (
        ort.GraphOptimizationLevel.ORT_ENABLE_BASIC if model == "midas-w8a8"
        else ort.GraphOptimizationLevel.ORT_ENABLE_ALL)
    sess = ort.InferenceSession(str(MODELS[model]), sess_options=so,
                                providers=["CPUExecutionProvider"])
    name = sess.get_inputs()[0].name
    x = preprocess(model, frame, size)
    shape = tuple(x.shape[2:])
    for _ in range(WARMUP):
        sess.run(None, {name: x})
    ts = []
    for _ in range(ITERS):
        t = time.perf_counter()
        sess.run(None, {name: x})
        ts.append((time.perf_counter() - t) * 1000)
        time.sleep(ITER_SLEEP)
    ts.sort()
    med = ts[len(ts) // 2]
    p95 = ts[min(len(ts) - 1, int(math.ceil(0.95 * len(ts))) - 1)]
    peak_mb = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024.0
    print(json.dumps({
        "model": model, "input": f"{shape[0]}x{shape[1]}",
        "median_ms": round(med, 1), "p95_ms": round(p95, 1),
        "min_ms": round(ts[0], 1), "fps": round(1000.0 / med, 2),
        "peak_mb": round(peak_mb, 1),
        "file_mb": round(MODELS[model].stat().st_size / 1e6, 1),
    }))


def parent():
    _, src = get_frame()
    RESULTS.write_text(f"# input source: {src}, threads={THREADS}, iters={ITERS}\n")
    rows = []
    for model, size in CONFIGS:
        print(f"benchmarking {model} @ {size} ...", flush=True)
        r = subprocess.run([sys.executable, __file__, "--one", model, str(size)],
                           capture_output=True, text=True)
        line = [l for l in r.stdout.splitlines() if l.startswith("{")]
        if not line:
            msg = f"{model}@{size} FAILED: {r.stderr.strip()[-200:]}"
            print("  " + msg, flush=True)
            with RESULTS.open("a") as f:
                f.write(msg + "\n")
            continue
        row = json.loads(line[-1])
        rows.append(row)
        with RESULTS.open("a") as f:          # persist immediately
            f.write(json.dumps(row) + "\n")
        print(f"  {row}", flush=True)
        time.sleep(COOLDOWN)

    hdr = (f"{'model':<13} {'input':>9} {'median':>8} {'p95':>8} "
           f"{'fps':>6} {'peakMB':>7} {'fileMB':>7}")
    lines = ["", "=" * 72,
             f"PHASE 2 BENCHMARK  UNO Q / QRB2210  4xA53@2GHz  CPU-only  {THREADS} threads",
             "=" * 72, hdr, "-" * len(hdr)]
    for r in rows:
        lines.append(f"{r['model']:<13} {r['input']:>9} {r['median_ms']:>7.0f}m "
                     f"{r['p95_ms']:>7.0f}m {r['fps']:>6.2f} {r['peak_mb']:>7.0f} "
                     f"{r['file_mb']:>7.1f}")
    lines += ["=" * 72, "DONE"]
    table = "\n".join(lines)
    print(table, flush=True)
    with RESULTS.open("a") as f:
        f.write(table + "\n")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--one", nargs=2, metavar=("MODEL", "SIZE"))
    args = ap.parse_args()
    if args.one:
        run_child(args.one[0], int(args.one[1]))
    else:
        parent()


if __name__ == "__main__":
    main()
