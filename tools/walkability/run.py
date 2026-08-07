#!/usr/bin/env python3
"""Walkability candidate evaluation harness.

Runs candidate perception stacks over burst photos and renders side-by-side
panels (walkable mask, BEV traversability grid, chosen corridor) plus a
stability metric: the decision-flip rate across each burst — near-identical
frames must produce near-identical corridors.

Layout expected:
    photos/<scene-name>/*.jpg     (3-5 burst shots per scene)

Usage (WSL, qai-venv):
    python run.py --photos ./photos --ffnet /path/ffnet_78s_lowres.onnx \
                  --depth ~/depth_anything_v2_small_294.onnx --out ./results

Candidates:
    A  FFNet segmentation walkability only (image-space columns)
    C  geometry only: metric depth -> ground plane -> BEV grid
    D  fusion: FFNet walkability x depth geometry -> BEV grid  (the v2 app)
"""
import argparse
import json
import math
from pathlib import Path

import numpy as np

try:
    import onnxruntime as ort
except ImportError:
    raise SystemExit("pip install onnxruntime pillow matplotlib")
from PIL import Image

# Cityscapes walkable train ids: road, sidewalk, terrain
WALKABLE = {0, 1, 9}

# Grid parameters — keep in sync with TraversabilityGrid.kt
CELL_M = 0.1
CELLS_WIDE = 61
CELLS_DEEP = 60
L_OBSTACLE, L_FREE, L_SOFT = 0.9, -0.4, 0.35
OBSTACLE_MIN_H, GROUND_TOL = 0.18, 0.16
OBSTACLE_THRESHOLD = 0.7
CAM_HEIGHT = 1.35
PITCH_RAD = 0.30
HFOV_DEG = 70.0

# Planner parameters — keep in sync with PolarPlanner.kt
SECTORS = 37
MAX_RANGE = 5.5
BLOCK_ENTER, BLOCK_EXIT = 1.3, 1.7
W_GOAL, W_PREV, W_WIDTH = 1.0, 1.6, 0.8
COMMIT_ALPHA = 0.35
MIN_VALLEY = 3


def load_sessions(ffnet_path, depth_path):
    opts = ort.SessionOptions()
    seg = ort.InferenceSession(ffnet_path, opts, providers=["CPUExecutionProvider"])
    dep = ort.InferenceSession(depth_path, opts, providers=["CPUExecutionProvider"])
    return seg, dep


def run_seg(sess, img):
    x = np.asarray(img.resize((1024, 512)), dtype=np.float32) / 255.0
    x = x.transpose(2, 0, 1)[None]
    logits = sess.run(None, {sess.get_inputs()[0].name: x})[0][0]  # [19,128,256]
    return logits.argmax(0)  # [128,256]


def run_depth(sess, img):
    inp = sess.get_inputs()[0]
    n = inp.shape[-1] if isinstance(inp.shape[-1], int) else 294
    # Letterbox to square like the app does
    w, h = img.size
    s = n / max(w, h)
    sw, sh = int(w * s), int(h * s)
    canvas = Image.new("RGB", (n, n))
    canvas.paste(img.resize((sw, sh)), ((n - sw) // 2, (n - sh) // 2))
    x = np.asarray(canvas, dtype=np.float32) / 255.0
    mean = np.array([0.485, 0.456, 0.406], np.float32)
    std = np.array([0.229, 0.224, 0.225], np.float32)
    x = ((x - mean) / std).transpose(2, 0, 1)[None]
    out = sess.run(None, {inp.name: x})[0].squeeze()
    pad = ((n - sw) // 2, (n - sh) // 2)
    return out, pad, s, n


def grid_update(log_odds, depth_m, walkable, pitch=PITCH_RAD):
    h, w = depth_m.shape
    log_odds *= 0.94
    fx = w / (2 * math.tan(math.radians(HFOV_DEG / 2)))
    cx, cy = w / 2, h / 2
    cp, sp = math.cos(pitch), math.sin(pitch)
    us, vs = np.meshgrid(np.arange(0, w, 2), np.arange(0, h, 2))
    d = depth_m[vs, us]
    ok = np.isfinite(d) & (d > 0.25) & (d < 8)
    xc = (us - cx) / fx * d
    y_up = -((vs - cy) / fx * d)
    y_w = y_up * cp - d * sp
    z_w = y_up * sp + d * cp
    height = CAM_HEIGHT + y_w
    ix = (CELLS_WIDE // 2 + np.round(xc / CELL_M)).astype(int)
    iz = np.round(z_w / CELL_M).astype(int)
    ok &= (ix >= 0) & (ix < CELLS_WIDE) & (iz >= 0) & (iz < CELLS_DEEP) & (z_w > 0)
    obstacle = ok & (height > OBSTACLE_MIN_H) & (height < 2.3)
    ground = ok & (np.abs(height) <= GROUND_TOL)
    if walkable is not None:
        wk = walkable[vs, us]
        soft = ground & (wk == 0)
        ground = ground & (wk != 0)
    else:
        soft = np.zeros_like(ground)
    for mask, delta in ((obstacle, L_OBSTACLE), (ground, L_FREE), (soft, L_SOFT)):
        np.add.at(log_odds, (iz[mask], ix[mask]), delta)
    np.clip(log_odds, -4, 4, out=log_odds)


def plan(log_odds, prev_angle, blocked):
    free = np.full(SECTORS, MAX_RANGE)
    for s in range(SECTORS):
        ang = math.radians(-90 + 180 * s / (SECTORS - 1))
        r = 0.15
        while r < MAX_RANGE:
            ix = CELLS_WIDE // 2 + round(r * math.sin(ang) / CELL_M)
            iz = round(r * math.cos(ang) / CELL_M)
            if not (0 <= ix < CELLS_WIDE and 0 <= iz < CELLS_DEEP):
                break
            if log_odds[iz, ix] > OBSTACLE_THRESHOLD:
                free[s] = r
                break
            r += CELL_M * 0.6
    sm = np.convolve(free, [0.25, 0.5, 0.25], "same")
    sm[0], sm[-1] = free[0], free[-1]
    for s in range(SECTORS):
        blocked[s] = sm[s] < (BLOCK_EXIT if blocked[s] else BLOCK_ENTER)
    best, best_cost = None, 1e9
    s = 0
    while s < SECTORS:
        if not blocked[s]:
            e = s
            while e + 1 < SECTORS and not blocked[e + 1]:
                e += 1
            width = e - s + 1
            if width >= MIN_VALLEY:
                step = 180 / (SECTORS - 1)
                lo = -90 + step * s + step
                hi = -90 + step * e - step
                cand = min(max(0.0, lo), hi) if lo <= hi else (-90 + step * (s + e) / 2)
                cost = (W_GOAL * abs(cand) / 90 + W_PREV * abs(cand - prev_angle) / 90
                        - W_WIDTH * width / SECTORS)
                if cost < best_cost:
                    best_cost, best = cost, cand
            s = e + 1
        else:
            s += 1
    if best is None:
        return 0.0, True, sm
    return prev_angle + COMMIT_ALPHA * (best - prev_angle), False, sm


def seg_to_depth_space(seg, pad, scale, n, img):
    w, h = img.size
    walk = np.full((n, n), -1, dtype=np.int8)
    vs, us = np.meshgrid(np.arange(n), np.arange(n), indexing="ij")
    sx = (us - pad[0]) / scale / w
    sy = (vs - pad[1]) / scale / h
    ok = (sx >= 0) & (sx < 1) & (sy >= 0) & (sy < 1)
    gx = np.clip((sx * seg.shape[1]).astype(int), 0, seg.shape[1] - 1)
    gy = np.clip((sy * seg.shape[0]).astype(int), 0, seg.shape[0] - 1)
    cls = seg[gy, gx]
    walk[ok] = np.isin(cls, list(WALKABLE))[ok].astype(np.int8)
    return walk


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--photos", required=True)
    ap.add_argument("--ffnet", required=True)
    ap.add_argument("--depth", required=True)
    ap.add_argument("--out", default="results")
    args = ap.parse_args()

    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    seg_sess, dep_sess = load_sessions(args.ffnet, args.depth)
    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    metrics = {}

    for scene in sorted(Path(args.photos).iterdir()):
        if not scene.is_dir():
            continue
        shots = sorted(p for p in scene.iterdir() if p.suffix.lower() in {".jpg", ".jpeg", ".png"})
        if not shots:
            continue
        angles = {"C": [], "D": []}
        state = {k: (np.zeros((CELLS_DEEP, CELLS_WIDE)), np.zeros(SECTORS, bool), 0.0)
                 for k in ("C", "D")}
        fig, axes = plt.subplots(len(shots), 4, figsize=(16, 3.2 * len(shots)))
        if len(shots) == 1:
            axes = axes[None, :]
        for i, shot in enumerate(shots):
            img = Image.open(shot).convert("RGB")
            seg = run_seg(seg_sess, img)
            depth, pad, scale, n = run_depth(dep_sess, img)
            walk_depthspace = seg_to_depth_space(seg, pad, scale, n, img)
            for key, walk in (("C", None), ("D", walk_depthspace)):
                grid, blocked, prev = state[key]
                grid_update(grid, depth, walk)
                ang, stop, _ = plan(grid, prev, blocked)
                state[key] = (grid, blocked, ang)
                angles[key].append(999.0 if stop else ang)
            axes[i][0].imshow(img)
            axes[i][0].set_title(shot.name, fontsize=8)
            axes[i][1].imshow(np.isin(seg, list(WALKABLE)), cmap="Greens")
            axes[i][1].set_title("A: walkable mask", fontsize=8)
            for j, key in enumerate(("C", "D")):
                grid, _, ang = state[key]
                axes[i][2 + j].imshow(grid[::-1], cmap="RdYlGn_r", vmin=-2, vmax=2)
                a = angles[key][-1]
                label = "STOP" if a == 999.0 else f"{a:+.0f}°"
                axes[i][2 + j].set_title(f"{key}: {label}", fontsize=8)
            for ax in axes[i]:
                ax.axis("off")
        fig.tight_layout()
        fig.savefig(out_dir / f"{scene.name}.png", dpi=110)
        plt.close(fig)
        metrics[scene.name] = {
            k: {"angles": v, "spread_deg": float(np.ptp([a for a in v if a != 999.0]) if any(a != 999.0 for a in v) else 0)}
            for k, v in angles.items()
        }
        print(f"{scene.name}: " + "  ".join(
            f"{k} spread={metrics[scene.name][k]['spread_deg']:.1f}°" for k in angles))

    (out_dir / "metrics.json").write_text(json.dumps(metrics, indent=2))
    print(f"\nPanels + metrics.json in {out_dir}/ — lower spread = more stable.")


if __name__ == "__main__":
    main()
