#!/usr/bin/env bash
# Exports Depth-Anything-V2-METRIC-Indoor-Small (Apache-2.0) to a
# static-shape ONNX for the app's dense-depth proximity sense, then installs
# it as an app asset. The METRIC variant outputs distance in meters directly
# (lower = closer) — required by DepthEngine/DepthCalibrator; do not swap in
# a relative-depth checkpoint without changing those classes.
#
# Run on Linux / WSL (Windows-on-ARM machines lack torch wheels) inside a
# venv with:  pip install torch transformers onnx onnxscript onnxruntime
#
# The model is ~99 MB and is NOT committed to git; each developer runs this
# once (or pushes the file over adb to the app's files/models directory).
set -euo pipefail

SIZE="${1:-294}"   # multiple of 14 (ViT-S patch size); 294 balances speed/quality
OUT="depth_anything_v2_small.onnx"

python - "$SIZE" <<'EOF'
import sys
import torch
from transformers import AutoModelForDepthEstimation

size = int(sys.argv[1])
model = AutoModelForDepthEstimation.from_pretrained(
    "depth-anything/Depth-Anything-V2-Metric-Indoor-Small-hf"
)
model.eval()

dummy = torch.zeros(1, 3, size, size)
torch.onnx.export(
    model, dummy, "depth_anything_v2_small.onnx",
    opset_version=17,
    input_names=["pixel_values"],
    output_names=["predicted_depth"],
    do_constant_folding=True,
    dynamo=False,
)
print(f"exported at {size}x{size}")
EOF

python - "$SIZE" <<'EOF'
import sys
import numpy as np
import onnxruntime as ort

size = int(sys.argv[1])
sess = ort.InferenceSession("depth_anything_v2_small.onnx", providers=["CPUExecutionProvider"])
inp = sess.get_inputs()[0]
x = np.random.rand(1, 3, size, size).astype(np.float32)
(out,) = sess.run(None, {inp.name: x})
assert out.shape == (1, size, size), out.shape
assert np.isfinite(out).all()
print("verified:", out.shape, "meters range:", round(float(out.min()), 2), "-", round(float(out.max()), 2))
EOF

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cp "$OUT" "$SCRIPT_DIR/../app/src/main/assets/$OUT"
echo "installed -> app/src/main/assets/$OUT"
