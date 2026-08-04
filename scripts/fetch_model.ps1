# Fetches the YOLOv8-Detection model from Qualcomm AI Hub, compiled for the
# Galaxy S25 family, and installs it as an app asset.
#
# Prerequisites (one-time):
#   1. Create a free account at https://aihub.qualcomm.com and copy your API
#      token from Account -> Settings -> API Token.
#   2. pip install qai-hub "qai-hub-models[yolov8-det]"
#   3. qai-hub configure --api_token YOUR_TOKEN
#
# Licensing note: YOLOv8 weights are AGPL-3.0 (Ultralytics). Fine for this
# open-source assistive project; for a commercial app consider a
# permissively-licensed detector from the AI Hub catalog instead (the
# post-processor in this repo handles both split and raw output layouts).

$ErrorActionPreference = "Stop"
$outDir = Join-Path $PSScriptRoot "build\yolov8_det"
$assetDir = Join-Path $PSScriptRoot "..\app\src\main\assets"

Write-Host "Exporting YOLOv8-Det (ONNX, targeting Samsung Galaxy S25 family)..." -ForegroundColor Cyan
python -m qai_hub_models.models.yolov8_det.export `
    --device "Samsung Galaxy S25 (Family)" `
    --target-runtime onnx `
    --skip-profiling --skip-inferencing `
    --output-dir $outDir
if ($LASTEXITCODE -ne 0) { throw "Export failed. Is qai-hub configured with your API token?" }

$onnx = Get-ChildItem -Path $outDir -Recurse -Filter "*.onnx" | Select-Object -First 1
if ($null -eq $onnx) { throw "No .onnx file produced in $outDir" }

New-Item -ItemType Directory -Force $assetDir | Out-Null
Copy-Item $onnx.FullName (Join-Path $assetDir "yolov8_det.onnx") -Force
Write-Host "Installed $($onnx.Name) -> app/src/main/assets/yolov8_det.onnx" -ForegroundColor Green
Write-Host ""
Write-Host "Tip: for a smaller, faster NPU model re-run with '--precision w8a8'"
Write-Host "(quantized, ~3.3 MB). Alternatively skip the rebuild and push straight"
Write-Host "to an installed app:"
Write-Host "  adb push yolov8_det.onnx /sdcard/Android/data/dev.quad.shepherd/files/models/"
