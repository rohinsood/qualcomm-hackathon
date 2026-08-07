#!/usr/bin/env python3
"""Ground-cam depth navigator.

Runs the fastest depth model (Depth-Anything-V2-small @126) on the
down-forward USB camera and turns each depth frame into a navigation
decision:

  * STOP    — an obstruction is in the path (center columns too close)
  * the 3 freest columns (most depth / most open space) to steer into
  * a single steer verdict: LEFT / RIGHT / CLEAR / STOP

Phase 1: MJPEG debug view (RGB | colorized depth) with column grid,
per-column closeness, 3 freest highlighted, verdict overlay.
Phase 2: SELF-TUNING baseline — the empty-corridor "path" closeness is a
rolling P50 over recent frames; STOP fires when the live near-closeness
exceeds baseline by MARGIN. That beats a hardcoded threshold, because
DA-V2 output is normalized per-frame — the same wall reads differently
as the scene composition changes. Baseline can be RE-ARMED via /calibrate.
Endpoints: /stats (JSON snapshot), /calibrate (POST -> reset baseline).
Phase 3: OPTIONAL wiring to the qcane-wheel REST API. When --cane-url
is set, the navigator POSTs steering via /api/phone text (primary) OR
/api/motor {dir} (--transport motor). Rate-limited + on-change so we
don't flood the 4Hz heartbeat. Depth failure is safe: the cane's own
ToF/failsafe still works independently.

Depth caveat: DA-V2 outputs RELATIVE inverse depth, normalized per frame,
so "closeness" is a 0..100 score (higher = nearer), not metres.
"""
from __future__ import annotations

import argparse
import http.server
import json
import socketserver
import threading
import time
from collections import deque
from pathlib import Path
from urllib import request as urlreq

import cv2
import numpy as np
import onnxruntime as ort

ROOT = Path(__file__).resolve().parent
MODEL = ROOT / "models/depthanything/da_v2_small.onnx"
IMAGENET_MEAN = np.array([0.485, 0.456, 0.406], np.float32).reshape(3, 1, 1)
IMAGENET_STD = np.array([0.229, 0.224, 0.225], np.float32).reshape(3, 1, 1)

# ---- decision config --------------------------------------------------------
N_COLS = 7
CENTER_COLS = (2, 3, 4)          # C3,C4,C5 are "the path ahead"
NEAR_PCTL = 80                    # closeness percentile; catches thin near objects
# Analysis band as fractions of frame height: drop top (sky/ceiling) and
# bottom (feet at cane base), keep the meaningful path region.
BAND_TOP = 0.10
BAND_BOTTOM = 0.95
LOOP_SLEEP = 0.3                  # throttle: keep board + network responsive

# ---- Phase 2: self-tuning baseline ------------------------------------------
# The baseline is the empty-corridor P50 of path-near, used to detect *sudden*
# new obstacles (something closer than what's usually there). But the ABSOLUTE
# closeness is what actually decides "wall in front vs. path is clear",
# because DA-V2 stretches per-frame — a wall-in-view and a flat-ground view
# both normalize to 0..100. So we STOP on either signal being tripped:
#     path_near >= abs_stop_min           (something is genuinely close)
#  OR path_near >= baseline_p50 + STOP_MARGIN  (a new obstacle appeared)
BASELINE_WINDOW = 60              # ~60 frames * 0.3s = 18 s rolling
BASELINE_MIN_SAMPLES = 15         # need this many before baseline arms
STOP_MARGIN = 15                  # closeness units above baseline
FREE_MARGIN = 8                   # per-column blocked cutoff = baseline + this
# Absolute floor: STOP whenever center closeness hits this, regardless of the
# learned baseline. Set from observation: a downward view of empty ground
# reads ~73 path-near; a genuine near obstruction should be well above that.
ABS_STOP_MIN = 88

# ---- Steering deadband ------------------------------------------------------
# LEFT/RIGHT only when the freest column is meaningfully clearer than the
# path center — otherwise the scene is uniform and the right call is CLEAR
# (keep going straight), not "jitter to whichever side is 1 point lower".
STEER_MIN_DELTA = 8               # freest col's near must be this much below path_near

# ---- Phase 3: REST wiring ---------------------------------------------------
DEFAULT_CANE_URL = "http://127.0.0.1:7000"
POST_INTERVAL_S = 0.75            # rate-limit; well under 4 Hz heartbeat
POST_TIMEOUT_S = 1.0
# Steady-state re-poke: even if verdict didn't change, re-send this often so
# a bounced cane app picks up the current state on reconnect.
POST_REFRESH_S = 5.0

VERDICTS = ("STOP", "LEFT", "RIGHT", "CLEAR")


def resolve_device(configured: int) -> int:
    try:
        for link in sorted(Path("/dev/v4l/by-id").glob("usb-*-video-index0")):
            name = link.resolve().name
            if name.startswith("video") and name[5:].isdigit():
                return int(name[5:])
    except OSError:
        pass
    return configured


def make_session(path: Path) -> ort.InferenceSession:
    so = ort.SessionOptions()
    so.intra_op_num_threads = 3  # 4 reboots the board (thermal/power)
    so.inter_op_num_threads = 1
    so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    return ort.InferenceSession(str(path), sess_options=so,
                                providers=["CPUExecutionProvider"])


def preprocess(frame_bgr: np.ndarray, size: int) -> np.ndarray:
    rgb = cv2.cvtColor(frame_bgr, cv2.COLOR_BGR2RGB)
    s = (size // 14) * 14
    r = cv2.resize(rgb, (s, s), interpolation=cv2.INTER_AREA)
    chw = np.transpose(r.astype(np.float32) / 255.0, (2, 0, 1))
    return ((chw - IMAGENET_MEAN) / IMAGENET_STD)[None].astype(np.float32)


class Decision:
    def __init__(self, med, near, freest, verdict, stop, ms,
                 path_near, baseline, stop_thr):
        self.med = med
        self.near = near
        self.freest = freest
        self.verdict = verdict
        self.stop = stop
        self.ms = ms
        self.path_near = path_near
        self.baseline = baseline
        self.stop_thr = stop_thr


class CanePoster:
    """Phase 3: rate-limited POSTer to qcane-wheel."""

    def __init__(self, base_url: str, transport: str):
        self.base = base_url.rstrip("/")
        self.transport = transport            # "phone" | "motor"
        self._last_verdict = None
        self._last_post = 0.0
        self._last_send_ok = None
        self._last_err = ""

    def _post(self, path: str, payload: dict) -> bool:
        try:
            body = json.dumps(payload).encode("utf-8")
            req = urlreq.Request(
                f"{self.base}{path}", data=body, method="POST",
                headers={"Content-Type": "application/json"})
            with urlreq.urlopen(req, timeout=POST_TIMEOUT_S) as r:
                r.read()
            self._last_send_ok = True
            self._last_err = ""
            return True
        except Exception as err:
            self._last_send_ok = False
            self._last_err = f"{type(err).__name__}: {err}"
            return False

    def send(self, verdict: str):
        now = time.monotonic()
        changed = verdict != self._last_verdict
        due_refresh = (now - self._last_post) >= POST_REFRESH_S
        due_rate = (now - self._last_post) >= POST_INTERVAL_S
        if not ((changed and due_rate) or due_refresh):
            return
        if self.transport == "motor":
            dir_ = {"LEFT": -1, "RIGHT": 1, "STOP": 0, "CLEAR": 0}[verdict]
            ok = self._post("/api/motor", {"dir": dir_})
        else:
            ok = self._post("/api/phone", {"text": verdict})
        if ok:
            self._last_verdict = verdict
            self._last_post = now

    def status(self):
        return {"transport": self.transport, "last_verdict": self._last_verdict,
                "last_post_ago_s": (time.monotonic() - self._last_post
                                    if self._last_post else None),
                "last_send_ok": self._last_send_ok,
                "last_err": self._last_err}


class Navigator:
    def __init__(self, size: int, index: int, cane: CanePoster | None,
                 abs_stop_min: int):
        self.size = size
        self.session = make_session(MODEL)
        self.in_name = self.session.get_inputs()[0].name
        self.cane = cane
        self.abs_stop_min = abs_stop_min

        cap = cv2.VideoCapture(index, cv2.CAP_V4L2)
        if not cap.isOpened():
            raise RuntimeError(f"could not open camera index {index}")
        cap.set(cv2.CAP_PROP_FOURCC, cv2.VideoWriter_fourcc(*"MJPG"))
        cap.set(cv2.CAP_PROP_FRAME_WIDTH, 640)
        cap.set(cv2.CAP_PROP_FRAME_HEIGHT, 480)
        cap.set(cv2.CAP_PROP_FPS, 30)
        self.cap = cap

        self._frame: np.ndarray | None = None
        self._panel: bytes | None = None
        self._decision: Decision | None = None
        self._baseline_samples: deque[int] = deque(maxlen=BASELINE_WINDOW)
        self._baseline_p50: float | None = None
        self._calibrating = True   # first BASELINE_MIN_SAMPLES: only collect
        self._lock = threading.Lock()
        self._running = True
        threading.Thread(target=self._capture_loop, daemon=True).start()
        threading.Thread(target=self._depth_loop, daemon=True).start()

    def rearm_baseline(self):
        with self._lock:
            self._baseline_samples.clear()
            self._baseline_p50 = None
            self._calibrating = True

    def snapshot(self):
        with self._lock:
            dec = self._decision
            base = self._baseline_p50
            samples = len(self._baseline_samples)
            calibrating = self._calibrating
        out = {
            "calibrating": calibrating,
            "baseline_samples": samples,
            "baseline_p50": base,
            "abs_stop_min": self.abs_stop_min,
            "stop_margin": STOP_MARGIN,
            "free_margin": FREE_MARGIN,
        }
        if dec is not None:
            out["decision"] = {
                "verdict": dec.verdict, "stop": dec.stop, "ms": round(dec.ms, 1),
                "near": dec.near, "med": dec.med, "freest": [c + 1 for c in dec.freest],
                "path_near": dec.path_near, "stop_thr": dec.stop_thr,
            }
        if self.cane is not None:
            out["cane"] = self.cane.status()
        return out

    def _capture_loop(self):
        while self._running:
            ok, frame = self.cap.read()
            if not ok:
                time.sleep(0.01)
                continue
            with self._lock:
                self._frame = frame

    # ---- calibration sampling (Phase 2) --------------------------------
    _calibration_target: str | None = None
    _calibration_remaining: int = 0
    _calibration_buffer: list | None = None
    _calibration_results: dict = {}

    def start_calibration(self, label: str, frames: int = 30) -> str | None:
        """Begin averaging the next `frames` decisions under `label`.
        Returns None on success, or an error message if a sample is already
        in flight (prevents overwriting a partial capture)."""
        with self._lock:
            if self._calibration_target is not None:
                return (f"already sampling '{self._calibration_target}' "
                        f"({self._calibration_remaining} frames remaining)")
            self._calibration_target = label
            self._calibration_remaining = int(frames)
            self._calibration_buffer = []
        return None

    def calibration_status(self):
        with self._lock:
            return {
                "target": self._calibration_target,
                "remaining": self._calibration_remaining,
                "collected": (0 if self._calibration_buffer is None
                              else len(self._calibration_buffer)),
                "results": dict(self._calibration_results),
            }

    def calibration_report(self):
        """Aggregate all captured labels into per-label stats, then propose
        thresholds from the labels that look like clear/obstruction pairs."""
        with self._lock:
            results = dict(self._calibration_results)
        report = {"labels": {}}
        for label, samples in results.items():
            arr_path = np.array([s["path_near"] for s in samples])
            arr_near = np.array([s["near"] for s in samples])
            report["labels"][label] = {
                "n": len(samples),
                "path_near_p50": float(np.median(arr_path)),
                "path_near_p90": float(np.percentile(arr_path, 90)),
                "path_near_min": int(arr_path.min()),
                "path_near_max": int(arr_path.max()),
                "col_near_p50": [int(x) for x in np.median(arr_near, axis=0)],
            }
        # Propose thresholds from any label pair with names hinting at
        # clear vs obstructed. Heuristic: proposal = midpoint of
        # (clear.p90, obstructed.p50) — halfway between "still clear" and
        # "typical obstruction reading".
        def find(kind: str):
            for name, stats in report["labels"].items():
                if kind in name.lower():
                    return name, stats
            return None
        clear = find("clear")
        obs = find("obstruct") or find("wall") or find("block") or find("stop")
        if clear and obs:
            c_p90 = clear[1]["path_near_p90"]
            o_p50 = obs[1]["path_near_p50"]
            proposed = int(round((c_p90 + o_p50) / 2))
            report["proposal"] = {
                "abs_stop_min": proposed,
                "clear_label": clear[0],
                "obstruction_label": obs[0],
                "rationale": (f"midpoint of clear.p90={c_p90:.0f} and "
                              f"obstruction.p50={o_p50:.0f}"),
            }
        return report

    def _record_calibration_sample(self, dec: Decision):
        """Called from the depth loop after each decision, feeds the sampler."""
        with self._lock:
            if self._calibration_target is None or self._calibration_remaining <= 0:
                return
            self._calibration_buffer.append({
                "path_near": dec.path_near,
                "near": list(dec.near),
                "med": list(dec.med),
            })
            self._calibration_remaining -= 1
            if self._calibration_remaining == 0:
                label = self._calibration_target
                bucket = self._calibration_results.setdefault(label, [])
                bucket.extend(self._calibration_buffer)
                self._calibration_target = None
                self._calibration_buffer = None

    def _column_stats(self, dnorm: np.ndarray):
        h, w = dnorm.shape
        r0, r1 = int(h * BAND_TOP), int(h * BAND_BOTTOM)
        band = dnorm[r0:r1, :]
        med, near = [], []
        for i in range(N_COLS):
            col = band[:, i * w // N_COLS:(i + 1) * w // N_COLS]
            med.append(int(round(float(np.median(col)) * 100)))
            near.append(int(round(float(np.percentile(col, NEAR_PCTL)) * 100)))
        return med, near

    def _decide(self, med, near, ms) -> Decision:
        path_near = max(near[i] for i in CENTER_COLS)

        # Feed the empty-corridor baseline. To avoid poisoning it with actual
        # obstructions, only accept samples that are (a) not currently STOP-ing
        # and (b) below the running baseline + a big margin (or during initial
        # calibration, accept everything).
        with self._lock:
            base = self._baseline_p50
            if base is None or path_near <= base + STOP_MARGIN * 2:
                self._baseline_samples.append(path_near)
            if len(self._baseline_samples) >= BASELINE_MIN_SAMPLES:
                self._baseline_p50 = float(np.median(self._baseline_samples))
                self._calibrating = False
            base = self._baseline_p50
            calibrating = self._calibrating

        # STOP triggers (OR):
        #   (a) absolute closeness in the path — a genuine wall/obstruction
        #   (b) relative jump above learned baseline — a new near thing appeared
        abs_hit = path_near >= self.abs_stop_min
        rel_thr = None if base is None else int(round(base + STOP_MARGIN))
        rel_hit = (rel_thr is not None) and (path_near >= rel_thr)
        stop_thr = self.abs_stop_min if rel_thr is None else min(self.abs_stop_min, rel_thr)
        stop = abs_hit or rel_hit

        # Rank columns by "farthest = freest" (lowest near).
        order = sorted(range(N_COLS), key=lambda i: near[i])
        freest = order[:3]

        if stop:
            verdict = "STOP"
        else:
            best = freest[0]
            mid = N_COLS // 2
            # Steering deadband: only turn if the freest col is meaningfully
            # clearer than the path center. Otherwise the scene is uniform ->
            # CLEAR (keep going straight), don't jitter L/R on 1-point noise.
            delta = path_near - near[best]
            if delta < STEER_MIN_DELTA:
                verdict = "CLEAR"
            elif best < mid:
                verdict = "LEFT"
            elif best > mid:
                verdict = "RIGHT"
            else:
                verdict = "CLEAR"
        return Decision(med, near, freest, verdict, stop, ms,
                        path_near, base, stop_thr)

    def _depth_loop(self):
        while self._running:
            with self._lock:
                frame = None if self._frame is None else self._frame.copy()
            if frame is None:
                time.sleep(0.02)
                continue

            t = time.perf_counter()
            x = preprocess(frame, self.size)
            out = self.session.run(None, {self.in_name: x})[0]
            ms = (time.perf_counter() - t) * 1000

            d = np.asarray(out).squeeze().astype(np.float32)
            lo, hi = float(d.min()), float(d.max())
            dnorm = (d - lo) / (hi - lo + 1e-6)
            med, near = self._column_stats(dnorm)
            dec = self._decide(med, near, ms)

            panel = self._render(frame, dnorm, dec)
            with self._lock:
                self._decision = dec
                self._panel = panel

            self._record_calibration_sample(dec)

            if self.cane is not None:
                self.cane.send(dec.verdict)

            print(f"{ms:5.0f}ms  path_near={dec.path_near:2d}  "
                  f"base={('--' if dec.baseline is None else f'{dec.baseline:4.1f}'):>4}  "
                  f"thr={dec.stop_thr:2d}  "
                  f"near={dec.near}  freest={[c+1 for c in dec.freest]}  "
                  f"-> {dec.verdict}", flush=True)
            time.sleep(LOOP_SLEEP)

    def _render(self, frame, dnorm, dec: Decision) -> bytes:
        u8 = (dnorm * 255).astype(np.uint8)
        color = cv2.applyColorMap(u8, cv2.COLORMAP_MAGMA)
        H = 480
        rgb = cv2.resize(frame, (int(frame.shape[1] * H / frame.shape[0]), H))
        dep = cv2.resize(color, (rgb.shape[1], H), interpolation=cv2.INTER_NEAREST)
        w = rgb.shape[1]
        freest_set = set(dec.freest)

        for panel in (rgb, dep):
            cv2.line(panel, (0, int(H * BAND_TOP)), (w, int(H * BAND_TOP)),
                     (40, 40, 40), 1)
            cv2.line(panel, (0, int(H * BAND_BOTTOM)), (w, int(H * BAND_BOTTOM)),
                     (40, 40, 40), 1)

        for i in range(N_COLS):
            x0 = i * w // N_COLS
            x1 = (i + 1) * w // N_COLS
            cx = (x0 + x1) // 2
            is_free = i in freest_set
            is_center = i in CENTER_COLS
            if is_free:
                ov = dep.copy()
                cv2.rectangle(ov, (x0, int(H * BAND_TOP)),
                              (x1, int(H * BAND_BOTTOM)), (0, 180, 0), -1)
                cv2.addWeighted(ov, 0.25, dep, 0.75, 0, dep)
            for panel in (rgb, dep):
                cv2.line(panel, (x0, 0), (x0, H), (60, 60, 60), 1)
            over_thr = dec.near[i] >= dec.stop_thr
            col = (0, 255, 0) if is_free else ((0, 0, 255) if over_thr else (255, 255, 255))
            txt = str(dec.near[i])
            (tw, _), _ = cv2.getTextSize(txt, cv2.FONT_HERSHEY_SIMPLEX, 0.9, 2)
            cv2.putText(dep, txt, (cx - tw // 2, H // 2),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.9, col, 2)
            rank = f"#{dec.freest.index(i)+1}" if i in dec.freest else ""
            cv2.putText(dep, f"C{i+1}{rank}", (cx - 20, 28),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.5, col, 2)
            if is_center:
                cv2.putText(dep, "path", (cx - 16, H - 12),
                            cv2.FONT_HERSHEY_SIMPLEX, 0.4, (200, 200, 0), 1)

        combo = cv2.hconcat([rgb, dep])
        vcol = (0, 0, 255) if dec.stop else (0, 220, 0)
        cv2.rectangle(combo, (0, 0), (combo.shape[1] - 1, 34), (0, 0, 0), -1)
        cv2.putText(combo, dec.verdict, (10, 26),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.9, vcol, 2)
        base_str = "--" if dec.baseline is None else f"{dec.baseline:.1f}"
        cv2.putText(combo, f"path_near={dec.path_near} thr={dec.stop_thr} "
                    f"baseline={base_str}  freest={[c+1 for c in dec.freest]}  "
                    f"{dec.ms:.0f}ms",
                    (150, 24), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (255, 255, 255), 1)
        cv2.putText(combo, "RGB", (10, H - 12),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.6, (0, 255, 0), 2)
        ok, buf = cv2.imencode(".jpg", combo, [cv2.IMWRITE_JPEG_QUALITY, 80])
        return buf.tobytes() if ok else b""

    def latest(self) -> bytes | None:
        with self._lock:
            return self._panel


NAV: Navigator | None = None
PAGE = b"""<!doctype html><html><head><title>navigator</title>
<style>body{margin:0;background:#111;display:flex;justify-content:center}
img{max-width:100vw;max-height:100vh}</style></head>
<body><img src="/stream"></body></html>"""


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def _json(self, obj, code=200):
        body = json.dumps(obj).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path in ("/", "/index.html"):
            self.send_response(200)
            self.send_header("Content-Type", "text/html")
            self.send_header("Content-Length", str(len(PAGE)))
            self.end_headers()
            self.wfile.write(PAGE)
            return
        if self.path == "/stats":
            self._json(NAV.snapshot() if NAV else {})
            return
        if self.path == "/calibrate/status":
            self._json(NAV.calibration_status() if NAV else {})
            return
        if self.path == "/calibrate/report":
            self._json(NAV.calibration_report() if NAV else {})
            return
        if self.path == "/stream":
            self.send_response(200)
            self.send_header("Content-Type",
                             "multipart/x-mixed-replace; boundary=frame")
            self.end_headers()
            try:
                while True:
                    jpeg = NAV.latest()
                    if jpeg is None:
                        time.sleep(0.05)
                        continue
                    self.wfile.write(b"--frame\r\n")
                    self.wfile.write(b"Content-Type: image/jpeg\r\n")
                    self.wfile.write(f"Content-Length: {len(jpeg)}\r\n\r\n".encode())
                    self.wfile.write(jpeg)
                    self.wfile.write(b"\r\n")
                    time.sleep(0.05)
            except (BrokenPipeError, ConnectionResetError):
                return
        self.send_error(404)

    def do_POST(self):
        if self.path == "/calibrate":
            if NAV:
                NAV.rearm_baseline()
            self._json({"ok": True, "message": "baseline re-armed"})
            return
        # POST /calibrate/sample?label=NAME&frames=30 — sample the next N
        # frames under this label; result folded into /calibrate/report.
        if self.path.startswith("/calibrate/sample"):
            from urllib.parse import urlparse, parse_qs
            q = parse_qs(urlparse(self.path).query)
            label = (q.get("label", [""])[0]).strip()
            try:
                frames = int(q.get("frames", ["30"])[0])
            except ValueError:
                frames = 30
            frames = max(5, min(200, frames))
            if not label:
                self._json({"error": "missing label"}, code=400)
                return
            if NAV:
                err = NAV.start_calibration(label, frames)
                if err:
                    self._json({"error": err}, code=409)
                    return
            self._json({"ok": True, "label": label, "frames": frames,
                        "estimated_seconds": round(frames * LOOP_SLEEP + 1, 1)})
            return
        self.send_error(404)


class ThreadingServer(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def main():
    global NAV
    ap = argparse.ArgumentParser()
    ap.add_argument("--size", type=int, default=126)
    ap.add_argument("--index", type=int, default=0)
    ap.add_argument("--port", type=int, default=8080)
    ap.add_argument("--cane-url", default=None,
                    help="qcane-wheel base URL, e.g. http://127.0.0.1:7000; omit to disable Phase 3 posting")
    ap.add_argument("--transport", choices=("phone", "motor"), default="phone",
                    help="cane transport: /api/phone text (default) or /api/motor {dir}")
    ap.add_argument("--abs-stop-min", type=int, default=ABS_STOP_MIN,
                    help="absolute floor for STOP threshold (0..100)")
    args = ap.parse_args()

    cane = CanePoster(args.cane_url, args.transport) if args.cane_url else None
    NAV = Navigator(args.size, resolve_device(args.index), cane,
                    args.abs_stop_min)
    tag = f"da-small@{(args.size//14)*14}"
    print(f"navigator: {tag}  {N_COLS} cols  "
          f"cane={'off' if cane is None else args.cane_url+' ('+args.transport+')'}  "
          f"serving http://0.0.0.0:{args.port}/")
    print(f"  /stream  MJPEG debug view")
    print(f"  /stats   JSON snapshot")
    print(f"  POST /calibrate  re-arm baseline")
    ThreadingServer(("0.0.0.0", args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
