#!/usr/bin/env python3
"""Phase 1 bench for ResNet18-Places365 ONNX (224x224). Writes a persistent
line-JSON to bench_places365_results.txt. 3 threads (4 reboots the board),
warmup + iters + inter-iter sleep to keep the network alive.
"""
from __future__ import annotations

import json
import math
import resource
import time
from pathlib import Path

import numpy as np
import onnxruntime as ort


ROOT = Path(__file__).resolve().parent
MODEL = ROOT / "models/places365/resnet18_places365.onnx"
OUT = ROOT / "bench_places365_results.txt"
THREADS = 3
ITERS = 15
WARMUP = 3
ITER_SLEEP = 0.15


def bench(size: int) -> dict:
    so = ort.SessionOptions()
    so.intra_op_num_threads = THREADS
    so.inter_op_num_threads = 1
    so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    sess = ort.InferenceSession(str(MODEL), sess_options=so,
                                providers=["CPUExecutionProvider"])
    in_name = sess.get_inputs()[0].name
    x = np.random.rand(1, 3, size, size).astype(np.float32)
    for _ in range(WARMUP):
        sess.run(None, {in_name: x})
    ts = []
    for _ in range(ITERS):
        t = time.perf_counter()
        sess.run(None, {in_name: x})
        ts.append((time.perf_counter() - t) * 1000)
        time.sleep(ITER_SLEEP)
    ts.sort()
    med = ts[len(ts) // 2]
    p95 = ts[min(len(ts) - 1, int(math.ceil(0.95 * len(ts))) - 1)]
    peak_mb = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024.0
    return {
        "model": "resnet18-places365",
        "input": f"{size}x{size}",
        "median_ms": round(med, 1),
        "p95_ms": round(p95, 1),
        "min_ms": round(ts[0], 1),
        "fps": round(1000.0 / med, 2),
        "peak_mb": round(peak_mb, 1),
        "file_mb": round((MODEL.stat().st_size +
                          (MODEL.with_suffix(".onnx.data").stat().st_size
                           if MODEL.with_suffix(".onnx.data").is_file() else 0)) / 1e6, 1),
    }


def main():
    with OUT.open("w") as f:
        f.write(f"# threads={THREADS} iters={ITERS} warmup={WARMUP}\n")
    for size in (224, 160):
        print(f"benchmarking @ {size}x{size} ...", flush=True)
        row = bench(size)
        with OUT.open("a") as f:
            f.write(json.dumps(row) + "\n")
        print(f"  {row}", flush=True)
        time.sleep(3)


if __name__ == "__main__":
    main()
