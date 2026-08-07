# Setup from scratch

Assumes nothing installed. Two independent halves — the phone app and the cane
board — and the phone app is useful on its own, so start there.

## What you need

| | Requirement | Notes |
|---|---|---|
| Phone | Snapdragon 8 Elite Android device | Developed and tested on **Galaxy S25 Ultra**, Android 15. `minSdk 31`. Other Snapdragon devices should work; QNN falls back gracefully if the NPU config doesn't match. |
| Dev machine | Android Studio Ladybug or newer | x86-64 Linux/macOS/Windows. See the note on Windows-on-ARM below. |
| Board *(optional)* | Arduino UNO Q + 3 Modulinos | Without it you get on-screen guidance and speech but no physical steering. |
| Keys *(optional)* | Google Maps API key | Only for outdoor street routing. Without it, navigation uses straight-line compass bearing. |

The app runs **without the board** and **without an API key**. Both degrade
features, neither blocks the build.

---

## 1 — Clone and pick the branch

```bash
git clone https://github.com/<your-account>/lighthouse.git
cd lighthouse
git checkout v3          # main is docs + hardware only; the code is here
```

## 2 — Android SDK

Install Android Studio, then in **SDK Manager** confirm:

- Android SDK Platform **35**
- Android SDK Build-Tools 35.x
- Android SDK Platform-Tools (gives you `adb`)

Put `adb` on your `PATH` — you'll need it to push models.

```bash
adb devices     # should list your phone as `device`, not `unauthorized`
```

To get there: on the phone, **Settings → About phone → tap Build number 7×**,
then **Developer options → USB debugging**. Accept the dialog when you plug in.

## 3 — Configure `local.properties`

Gradle writes the SDK path here on first sync. Add your Maps key if you want
outdoor routing:

```properties
sdk.dir=/path/to/Android/Sdk
maps.apiKey=AIza...
```

Get a key from the [Google Cloud Console](https://console.cloud.google.com/) and
enable **Maps SDK for Android**, **Routes API**, **Geocoding API**, and
**Places API (New)**.

`local.properties` is gitignored — never commit it.

> Without `maps.apiKey`, `RoutesClient.available` is false. Navigation still
> works: it falls back to a straight-line bearing to the destination, which is
> also exactly what indoor mode does by design.

## 4 — Build and test

```bash
./gradlew :app:testDebugUnitTest     # 10 test classes, no device needed
./gradlew :app:assembleDebug
./gradlew :app:installDebug          # or hit Run in Android Studio
```

Run the tests first — they cover the pure-Kotlin math (planner mode machine,
grid, command aggregation, depth calibration, YOLO post-processing, nav) and need
no hardware, so a green run confirms your toolchain before any device enters the
picture.

## 5 — Install the models

**This step is required for full function.** Only the YOLOv8n detector is
committed; depth and segmentation weights are not, for size and licensing
reasons. Complete instructions per model: [`MODELS.md`](MODELS.md).

The short version — export depth, then push everything:

```bash
# Depth (Apache-2.0). Needs Linux/WSL + torch; see MODELS.md.
./scripts/export_depth_model.sh 294

adb push depth_anything_v2_small.onnx \
  /sdcard/Android/data/dev.quad.shepherd/files/models/
```

> **Use the Metric checkpoints.** Depth-Anything-V2-**Metric** outputs distance
> in meters directly. The relative-depth checkpoints do not, and the grid,
> calibrator, and every meter threshold in the planner assume meters — swapping
> one in fails quietly rather than loudly. `MODELS.md` covers this.

The app starts without these and says so on screen. Steering quality depends on
them, so don't judge the system before they're installed.

## 6 — Grant permissions and go

On first launch, allow **Camera**, **Location**, **Microphone**, **Nearby
devices**, and **Notifications**. The guidance pipeline runs as a foreground
service so it survives the screen turning off — which is the normal way to use
it, phone mounted and in motion.

Sanity check: the status bar should read **`Hexagon NPU (QNN)`** or a GPU
provider. If it reads `CPU`, QNN failed to load — check Logcat for tag
`DetectionEngine` or `OrtSessions`.

---

## 7 — Cane board (optional)

Full detail: [`BOARD.md`](BOARD.md) and
[`../Hardware/Assembly Instructions.md`](../Hardware/Assembly%20Instructions.md).

```bash
# On the UNO Q
arduino-app-cli app start ~/lighthouse/board/qcane-wheel
arduino-app-cli app logs  ~/lighthouse/board/qcane-wheel --follow
```

Open `http://<board-ip>:7000` for the dashboard. Then install the host-side
Bluetooth daemon as a user service:

```bash
mkdir -p ~/.config/systemd/user
cp ~/lighthouse/board/qcane-wheel/host/qcane-btd.service ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now qcane-btd
loginctl enable-linger arduino
```

Verify the whole chain before involving the phone:

```bash
cd ~/lighthouse/board/qcane-wheel
python3 host/qcane_btd.py --send left --speed 5    # wheel should spin
```

The phone then finds the board by GATT service UUID within seconds. No pairing.

---

## Platform notes

**Windows on ARM.** The AI Hub cloud export chain doesn't install — no ARM64
wheels for `opencv-python` or `torch`. Export models in WSL Ubuntu instead, or on
an x86-64 machine, then `adb push`. Building and running the app itself is fine.

**QNN native libraries.** `useLegacyPackaging = true` in `app/build.gradle.kts`
is load-bearing, not vestigial: GenieX `dlopen`s its plugins by absolute path
from `nativeLibraryDir`, and without legacy packaging the `.so` files stay
compressed inside the APK and that path is empty. You get `Invalid plugin` at
runtime. Don't "clean this up."

**The manifest declares `libcdsprpc.so` and `libOpenCL.so`** via
`<uses-native-library>`. Since API 31 the linker blocks undeclared vendor
libraries — these are FastRPC to the Hexagon DSP and the Adreno OpenCL backend.
Removing them silently drops you to CPU.

---

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| Status bar reads `CPU` | QNN didn't load. Check Logcat `OrtSessions`. Confirm the manifest still declares the two vendor libraries. |
| `Invalid plugin` on startup | `useLegacyPackaging` got turned off. Restore it. |
| Guidance seems oblivious to obstacles | Depth model missing — see step 5. Check the on-screen model status. |
| Everything reads as an obstacle | Camera pitch. The ground latch self-calibrates but can't recover from a camera aimed at the sky or the floor. Remount level and forward. |
| Wheel never moves | `VM` supply. The Qwiic cable is 3.3 V logic only; the motor needs its own 5 V on the screw terminals. Yellow `VM` LED confirms. Then `--selftest`. |
| Wheel brakes on a clear path | Known bug — see [`KNOWN_ISSUES.md`](KNOWN_ISSUES.md), the `S` letter collision. |
| Board reports distance, phone ignores it | Known bug — see [`KNOWN_ISSUES.md`](KNOWN_ISSUES.md), telemetry channel mismatch. |
| No routes, "navigation unavailable" | `maps.apiKey` missing or the Routes API isn't enabled on that key. |
| Speech is robotic | Neural voice hasn't downloaded yet — needs unmetered Wi-Fi on first launch. System TTS is the fallback. |
