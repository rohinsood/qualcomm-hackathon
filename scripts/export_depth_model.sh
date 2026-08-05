#!/usr/bin/env bash
# Exports Depth-Anything-V2-Small (Apache-2.0) to a static-shape ONNX for
# the app's dense-depth proximity sense, then installs it as an app asset.
#
# Run on Linux / WSL (Windows-on-ARM machines lack torch wheels) inside a
# venv with:  pip install torch transformers onnx onnxscript onnxruntime
#
# The model is ~99 MB and is NOT committed to git; each developer runs this
# once (or pushes the file over adb to the app's files/models directory).
set -euo pipefail

OUT="depth_anything_v2_small.onnx"

python - <<'EOF'
import torch
from transformers import AutoModelForDepthEstimation

model = AutoModelForDepthEstimation.from_pretrained(
    "depth-anything/Depth-Anything-V2-Small-hf"
)
model.eval()

dummy = torch.zeros(1, 3, 518, 518)
torch.onnx.export(
    model, dummy, "depth_anything_v2_small.onnx",
    opset_version=17,
    input_names=["pixel_values"],
    output_names=["predicted_depth"],
    do_constant_folding=True,
    dynamo=False,
)
print("exported depth_anything_v2_small.onnx")
EOF

python - <<'EOF'
import numpy as np
import onnxruntime as ort

sess = ort.InferenceSession("depth_anything_v2_small.onnx", providers=["CPUExecutionProvider"])
inp = sess.get_inputs()[0]
x = np.random.rand(1, 3, 518, 518).astype(np.float32)
(out,) = sess.run(None, {inp.name: x})
assert out.shape == (1, 518, 518), out.shape
assert np.isfinite(out).all()
print("verified:", out.shape)
EOF

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cp "$OUT" "$SCRIPT_DIR/../app/src/main/assets/$OUT"
echo "installed -> app/src/main/assets/$OUT"
