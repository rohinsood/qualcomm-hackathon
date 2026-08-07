#!/usr/bin/env python3
"""Depth viewer with per-column readings.

Serves RGB | colorized-depth side by side over MJPEG, split into vertical
columns. Each column shows a "closeness" reading (0-100, higher = nearer),
which is what the colors encode.

IMPORTANT: MiDaS / Depth-Anything output *relative inverse depth*, not metres.
The readings are a per-frame normalized closeness score, not a calibrated
distance. Metric distance would need calibration or a metric-depth model.
"""
from __future__ import annotations

import argparse
import http.server
import socketserver
import threading
import time
from pathlib import Path

import cv2
import numpy as np
import onnxruntime as ort

ROOT = Path(__file__).resolve().parent
MODELS = {
    "midas-float": ROOT / "models/midas/float/midas-onnx-float/midas.onnx",
    "midas-w8a8": ROOT / "models/midas/w8a8/midas-onnx-w8a8/midas.onnx",
    "da-small": ROOT / "models/depthanything/da_v2_small.onnx",
}
IMAGENET_MEAN = np.array([0.485, 0.456, 0.406], np.float32).reshape(3, 1, 1)
IMAGENET_STD = np.array([0.229, 0.224, 0.225], np.float32).reshape(3, 1, 1)
N_COLS = 5


def resolve_device(configured: int) -> int:
    try:
        for link in sorted(Path("/dev/v4l/by-id").glob("usb-*-video-index0")):
            name = link.resolve().name
            if name.startswith("video") and name[5:].isdigit():
                return int(name[5:])
    except OSError:
        pass
    return configured


def make_session(path: Path, model: str) -> ort.InferenceSession:
    so = ort.SessionOptions()
    so.intra_op_num_threads = 3  # leave a core for tailscale/http
    so.inter_op_num_threads = 1
    so.graph_optimization_level = (
        ort.GraphOptimizationLevel.ORT_ENABLE_BASIC if model == "midas-w8a8"
        else ort.GraphOptimizationLevel.ORT_ENABLE_ALL)
    return ort.InferenceSession(str(path), sess_options=so,
                                providers=["CPUExecutionProvider"])


def preprocess(model: str, frame_bgr: np.ndarray, size: int):
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


def raw_depth(model: str, out: np.ndarray) -> np.ndarray:
    d = np.asarray(out).squeeze().astype(np.float32)
    if model == "midas-w8a8":
        d = d * 6.5142998695373535
    return d


class Viewer:
    def __init__(self, model: str, size: int, index: int):
        self.model = model
        self.size = size
        self.session = make_session(MODELS[model], model)
        self.in_name = self.session.get_inputs()[0].name

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
        self._ms = 0.0
        self._lock = threading.Lock()
        self._running = True
        threading.Thread(target=self._capture_loop, daemon=True).start()
        threading.Thread(target=self._depth_loop, daemon=True).start()

    def _capture_loop(self):
        while self._running:
            ok, frame = self.cap.read()
            if not ok:
                time.sleep(0.01)
                continue
            with self._lock:
                self._frame = frame

    def _column_readings(self, dnorm: np.ndarray):
        """Per-column closeness 0-100 (higher = nearer), on the normalized map."""
        h, w = dnorm.shape
        out = []
        for i in range(N_COLS):
            band = dnorm[:, i * w // N_COLS:(i + 1) * w // N_COLS]
            out.append(int(round(float(np.median(band)) * 100)))
        return out

    def _depth_loop(self):
        while self._running:
            with self._lock:
                frame = None if self._frame is None else self._frame.copy()
            if frame is None:
                time.sleep(0.02)
                continue

            t = time.perf_counter()
            x = preprocess(self.model, frame, self.size)
            out = self.session.run(None, {self.in_name: x})[0]
            self._ms = (time.perf_counter() - t) * 1000

            d = raw_depth(self.model, out)
            lo, hi = float(d.min()), float(d.max())
            dnorm = (d - lo) / (hi - lo + 1e-6)
            readings = self._column_readings(dnorm)
            nearest = int(np.argmax(readings))

            u8 = (dnorm * 255).astype(np.uint8)
            color = cv2.applyColorMap(u8, cv2.COLORMAP_MAGMA)

            h = 480
            rgb = cv2.resize(frame, (int(frame.shape[1] * h / frame.shape[0]), h))
            dep = cv2.resize(color, (rgb.shape[1], h), interpolation=cv2.INTER_NEAREST)
            w = rgb.shape[1]

            # column dividers + per-column readings on both panels
            for i in range(1, N_COLS):
                x0 = i * w // N_COLS
                cv2.line(rgb, (x0, 0), (x0, h), (60, 60, 60), 1)
                cv2.line(dep, (x0, 0), (x0, h), (60, 60, 60), 1)
            for i, val in enumerate(readings):
                cx = i * w // N_COLS + (w // N_COLS) // 2
                near = (i == nearest)
                col = (0, 0, 255) if near else (255, 255, 255)
                txt = f"{val}"
                (tw, _), _ = cv2.getTextSize(txt, cv2.FONT_HERSHEY_SIMPLEX, 1.1, 3)
                cv2.putText(dep, txt, (cx - tw // 2, h // 2),
                            cv2.FONT_HERSHEY_SIMPLEX, 1.1, col, 3)
                cv2.putText(dep, f"C{i+1}", (cx - 14, 30),
                            cv2.FONT_HERSHEY_SIMPLEX, 0.6, col, 2)
                if near:
                    cv2.putText(dep, "NEAREST", (cx - 42, h // 2 + 34),
                                cv2.FONT_HERSHEY_SIMPLEX, 0.55, (0, 0, 255), 2)

            combo = cv2.hconcat([rgb, dep])
            tag = self.model if self.model != "da-small" else f"da-small@{(self.size//14)*14}"
            cv2.putText(combo, f"{tag}  {self._ms:.0f} ms  {1000/max(self._ms,1):.2f} fps"
                        f"  | closeness 0-100 (higher=nearer, relative not metres)",
                        (10, 24), cv2.FONT_HERSHEY_SIMPLEX, 0.55, (255, 255, 255), 2)
            cv2.putText(combo, "RGB", (10, h - 12), cv2.FONT_HERSHEY_SIMPLEX,
                        0.6, (0, 255, 0), 2)

            ok, buf = cv2.imencode(".jpg", combo, [cv2.IMWRITE_JPEG_QUALITY, 80])
            if ok:
                with self._lock:
                    self._panel = buf.tobytes()
            print(f"{self._ms:5.0f} ms  cols(C1..C{N_COLS})="
                  f"{readings}  nearest=C{nearest+1}", flush=True)
            time.sleep(0.3)  # throttle: keep box + network responsive

    def latest(self) -> bytes | None:
        with self._lock:
            return self._panel


V: Viewer | None = None
PAGE = b"""<!doctype html><html><head><title>depth</title>
<style>body{margin:0;background:#111;display:flex;justify-content:center}
img{max-width:100vw;max-height:100vh}</style></head>
<body><img src="/stream"></body></html>"""


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_GET(self):
        if self.path in ("/", "/index.html"):
            self.send_response(200)
            self.send_header("Content-Type", "text/html")
            self.send_header("Content-Length", str(len(PAGE)))
            self.end_headers()
            self.wfile.write(PAGE)
            return
        if self.path == "/stream":
            self.send_response(200)
            self.send_header("Content-Type",
                             "multipart/x-mixed-replace; boundary=frame")
            self.end_headers()
            try:
                while True:
                    jpeg = V.latest()
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


class ThreadingServer(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def main():
    global V
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", choices=list(MODELS), default="midas-float")
    ap.add_argument("--size", type=int, default=252)
    ap.add_argument("--index", type=int, default=0)
    ap.add_argument("--port", type=int, default=8080)
    args = ap.parse_args()

    V = Viewer(args.model, args.size, resolve_device(args.index))
    print(f"model={args.model}  serving http://0.0.0.0:{args.port}/  (ctrl-c to stop)")
    ThreadingServer(("0.0.0.0", args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
