#!/usr/bin/env bash
# See fetch_model.ps1 for prerequisites and licensing notes.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_DIR="$SCRIPT_DIR/build/yolov8_det"
ASSET_DIR="$SCRIPT_DIR/../app/src/main/assets"

echo "Exporting YOLOv8-Det (ONNX, targeting Samsung Galaxy S25 family)..."
python -m qai_hub_models.models.yolov8_det.export \
    --device "Samsung Galaxy S25 (Family)" \
    --target-runtime onnx \
    --skip-profiling --skip-inferencing \
    --output-dir "$OUT_DIR"

ONNX_FILE="$(find "$OUT_DIR" -name '*.onnx' | head -n 1)"
[ -n "$ONNX_FILE" ] || { echo "No .onnx produced"; exit 1; }

mkdir -p "$ASSET_DIR"
cp "$ONNX_FILE" "$ASSET_DIR/yolov8_det.onnx"
echo "Installed $(basename "$ONNX_FILE") -> app/src/main/assets/yolov8_det.onnx"
