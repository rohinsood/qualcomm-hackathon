# Walkability evaluation harness

Offline comparison of path-finding candidates on real burst photos, before
anything ships to the phone. Mirrors the v2 on-device pipeline
(`TraversabilityGrid` + `PolarPlanner`) in numpy.

## Photo protocol

For each scene, shoot a **burst of 3–5 photos from chest height** with
slight natural movement between shots (that's what exposes decision
jitter). Good scenes: clear sidewalk · sidewalk with a small obstacle ·
curb edges · grass-vs-path boundary · indoor corridor/doorway · harsh
shadows · a glass door. Arrange as:

```
photos/
  sidewalk-clear/     IMG_001.jpg IMG_002.jpg IMG_003.jpg
  sidewalk-box/       ...
```

## Run (WSL, qai-venv)

```bash
pip install onnxruntime pillow matplotlib numpy
python run.py \
  --photos ./photos \
  --ffnet  /mnt/c/.../ffnet_78s_lowres-onnx-float/ffnet_78s_lowres.onnx \
  --depth  ~/depth_anything_v2_small_294.onnx \
  --out    ./results
```

Each scene produces a panel PNG: original · FFNet walkable mask (candidate
A) · BEV grid geometry-only (candidate C) · BEV grid seg×depth fusion
(candidate D, the v2 app pipeline), each with its chosen corridor angle.
`metrics.json` records the per-burst **angle spread** — the stability
metric; lower is better, STOP frames are excluded.

Candidate B (pedestrian-view fine-tune) is added once enough photos exist
to build a SAM2 pseudo-label set.
