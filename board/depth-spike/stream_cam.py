#!/usr/bin/env python3
"""Phase 1 camera check: serve the USB webcam as MJPEG over HTTP.

Headless-friendly — no GUI. Open the printed URL from a laptop on the same
tailscale network. Also measures and reports real capture FPS.
"""
from __future__ import annotations

import argparse
import http.server
import socketserver
import threading
import time
from pathlib import Path

import cv2

BY_ID_GLOB = "usb-*-video-index0"


def resolve_device(configured: int) -> int:
    """Prefer the stable /dev/v4l/by-id symlink; /dev/video* order is unstable."""
    try:
        for link in sorted(Path("/dev/v4l/by-id").glob(BY_ID_GLOB)):
            name = link.resolve().name
            if name.startswith("video") and name[5:].isdigit():
                return int(name[5:])
    except OSError:
        pass
    return configured


class Camera:
    def __init__(self, index: int, width: int, height: int, fps: int):
        cap = cv2.VideoCapture(index, cv2.CAP_V4L2)
        if not cap.isOpened():
            raise RuntimeError(f"could not open camera index {index}")
        cap.set(cv2.CAP_PROP_FOURCC, cv2.VideoWriter_fourcc(*"MJPG"))
        cap.set(cv2.CAP_PROP_FRAME_WIDTH, width)
        cap.set(cv2.CAP_PROP_FRAME_HEIGHT, height)
        cap.set(cv2.CAP_PROP_FPS, fps)
        self.cap = cap
        self.aw = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
        self.ah = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
        self._jpeg: bytes | None = None
        self._lock = threading.Lock()
        self._fps = 0.0
        self._running = True
        threading.Thread(target=self._loop, daemon=True).start()

    def _loop(self):
        n, t0 = 0, time.monotonic()
        while self._running:
            ok, frame = self.cap.read()
            if not ok:
                time.sleep(0.01)
                continue
            n += 1
            now = time.monotonic()
            if now - t0 >= 1.0:
                self._fps = n / (now - t0)
                n, t0 = 0, now
            cv2.putText(frame, f"{self.aw}x{self.ah}  {self._fps:4.1f} fps",
                        (8, 22), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (0, 255, 0), 2)
            ok, buf = cv2.imencode(".jpg", frame, [cv2.IMWRITE_JPEG_QUALITY, 80])
            if ok:
                with self._lock:
                    self._jpeg = buf.tobytes()

    def latest(self) -> bytes | None:
        with self._lock:
            return self._jpeg

    @property
    def fps(self) -> float:
        return self._fps


CAM: Camera | None = None

PAGE = b"""<!doctype html><html><head><title>depth-spike cam</title>
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
            self.send_header(
                "Content-Type",
                "multipart/x-mixed-replace; boundary=frame")
            self.end_headers()
            try:
                while True:
                    jpeg = CAM.latest()
                    if jpeg is None:
                        time.sleep(0.02)
                        continue
                    self.wfile.write(b"--frame\r\n")
                    self.wfile.write(b"Content-Type: image/jpeg\r\n")
                    self.wfile.write(
                        f"Content-Length: {len(jpeg)}\r\n\r\n".encode())
                    self.wfile.write(jpeg)
                    self.wfile.write(b"\r\n")
                    time.sleep(1 / 30)
            except (BrokenPipeError, ConnectionResetError):
                return
        self.send_error(404)


class ThreadingServer(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def main():
    global CAM
    ap = argparse.ArgumentParser()
    ap.add_argument("--index", type=int, default=0)
    ap.add_argument("--width", type=int, default=640)
    ap.add_argument("--height", type=int, default=480)
    ap.add_argument("--fps", type=int, default=30)
    ap.add_argument("--port", type=int, default=8080)
    args = ap.parse_args()

    index = resolve_device(args.index)
    CAM = Camera(index, args.width, args.height, args.fps)
    print(f"camera index {index} -> {CAM.aw}x{CAM.ah}")
    print(f"serving MJPEG on http://0.0.0.0:{args.port}/  (ctrl-c to stop)")

    def report():
        while True:
            time.sleep(5)
            print(f"[capture] {CAM.fps:.1f} fps")

    threading.Thread(target=report, daemon=True).start()
    ThreadingServer(("0.0.0.0", args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
