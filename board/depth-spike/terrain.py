#!/usr/bin/env python3
"""Terrain sensor for the Shepherd cane — Phases 2+3+4 complete.

Two-signal pipeline:
  (B) TERRAIN CLASSIFIER — Places365 ResNet18 ONNX, aggregated to a 6-class
      taxonomy: {sidewalk, road, grass, stairs, indoor_floor, unknown}.
  (A) STAIR-EDGE / DROP-OFF — classical CV: Canny + probabilistic Hough on
      the lower 60% of frame; near-horizontal lines (±10°, length ≥ 40% of
      frame width); a stack of ≥3 parallel edges in a small vertical span
      (<30% of frame height) is the stair-tread signature.

Wiring to the cane (qcane-wheel on :7000):
  - Rate-limited /api/phone terrain announcements on debounced class change.
  - STAIRS GUARDRAIL: classifier='stairs' OR edge_confidence>=HIGH →
    immediate /api/motor {"dir":0} + distinctive vibro (two 300ms pulses).
    HOLD for 3.0s regardless of next classification.
  - Non-stair edges → single 400ms vibro, at most every 2s.
"""
from __future__ import annotations

import argparse
import http.server
import json
import socketserver
import threading
import time
from pathlib import Path
from urllib import request as urlreq

import cv2
import numpy as np
import onnxruntime as ort

ROOT = Path(__file__).resolve().parent
MODEL = ROOT / "models/places365/resnet18_places365.onnx"
CATEGORIES = ROOT / "models/places365/categories_places365.txt"

IMAGENET_MEAN = np.array([0.485, 0.456, 0.406], np.float32).reshape(3, 1, 1)
IMAGENET_STD = np.array([0.229, 0.224, 0.225], np.float32).reshape(3, 1, 1)

# ---- stair-edge detector config (Phase 3) -----------------------------------
EDGE_ROI_TOP = 0.40          # only analyze the lower 60% of frame
EDGE_ANGLE_TOL_DEG = 10      # near-horizontal = within ±10° of horizontal
EDGE_MIN_LEN_FRAC = 0.60     # min line length as fraction of frame width (raised from 0.40)
EDGE_MIN_PARALLEL = 5        # ≥5 parallel horizontal edges = stair signature (raised from 3)
EDGE_VERTICAL_SPAN_FRAC = 0.30  # parallel edges must be within this fraction of frame height
EDGE_HIGH_CONFIDENCE = 6     # ≥ this many parallel edges = HIGH confidence (raised from 4)

# ---- cane wiring config (Phase 4) -------------------------------------------
DEFAULT_CANE_URL = "http://127.0.0.1:7000"
POST_INTERVAL_S = 2.0           # min seconds between terrain announcements
POST_TIMEOUT_S = 1.0
STAIRS_STOP_HOLD_S = 3.0       # force-hold wheel stop on STAIRS for this long
EDGE_VIBRO_COOLDOWN_S = 2.0    # non-stair edge vibro at most every 2s
LOOP_SLEEP = 0.3               # throttle inference loop
# How long the indoor/outdoor group must be stable before sending a mode switch.
# Prevents rapid flickering when crossing a threshold (e.g. a doorway).
MODE_SWITCH_HOLD_S = 30.0

# Which terrain labels belong to which nav mode.
OUTDOOR_TERRAINS = {"sidewalk", "road", "grass"}
INDOOR_TERRAINS = {"indoor_floor"}

# ---- 6-class terrain taxonomy ----------------------------------------------
TAXONOMY = ("sidewalk", "road", "grass", "stairs", "indoor_floor", "unknown")

# Hand-curated 365 -> taxonomy map. Populated from the exact Places365
# category names (see models/places365/categories_places365.txt). Every raw
# class NOT in this map falls through to "unknown" — better to admit
# uncertainty than assert a wrong label.
#
# Rules of thumb used to build this:
#   - "sidewalk"    walkable pedestrian outdoor surface
#   - "road"        vehicular surface (something a cane user should NOT be on)
#   - "grass"       natural terrain / vegetation underfoot
#   - "stairs"      any staircase / escalator (the safety-critical class)
#   - "indoor_floor" a floor you'd walk on indoors
#   - "unknown"     anything else, or ambiguous
NAME_TO_TERRAIN = {
    # ---- stairs (safety critical — err on side of firing this)
    "staircase": "stairs",
    "escalator/indoor": "stairs",
    "elevator/door": "stairs",  # deep well ahead
    "fire_escape": "stairs",

    # ---- sidewalk / pedestrian outdoor
    "sidewalk": "sidewalk",
    "promenade": "sidewalk",
    "crosswalk": "sidewalk",
    "boardwalk": "sidewalk",
    "plaza": "sidewalk",
    "courtyard": "sidewalk",
    "patio": "sidewalk",
    "driveway": "sidewalk",
    "porch": "sidewalk",
    "alley": "sidewalk",
    "campus": "sidewalk",
    "downtown": "sidewalk",       # street-level pedestrian
    "residential_neighborhood": "sidewalk",
    "picnic_area": "sidewalk",

    # ---- road (vehicular — hazard for a pedestrian)
    "street": "road",
    "highway": "road",
    "desert_road": "road",
    "field_road": "road",
    "forest_road": "road",
    "parking_lot": "road",
    "parking_garage/indoor": "road",
    "parking_garage/outdoor": "road",
    "runway": "road",
    "raceway": "road",
    "racecourse": "road",
    "gas_station": "road",
    "bridge": "road",
    "viaduct": "road",

    # ---- grass / natural terrain
    "lawn": "grass",
    "yard": "grass",
    "field/cultivated": "grass",
    "field/wild": "grass",
    "hayfield": "grass",
    "wheat_field": "grass",
    "corn_field": "grass",
    "pasture": "grass",
    "vegetable_garden": "grass",
    "park": "grass",
    "botanical_garden": "grass",
    "formal_garden": "grass",
    "japanese_garden": "grass",
    "roof_garden": "grass",
    "topiary_garden": "grass",
    "golf_course": "grass",
    "soccer_field": "grass",
    "football_field": "grass",
    "baseball_field": "grass",
    "athletic_field/outdoor": "grass",
    "forest/broadleaf": "grass",
    "forest_path": "grass",
    "rainforest": "grass",
    "bamboo_forest": "grass",
    "orchard": "grass",
    "vineyard": "grass",
    "tree_farm": "grass",
    "rice_paddy": "grass",
    "farm": "grass",
    "marsh": "grass",
    "swamp": "grass",
    "mountain_path": "grass",     # trail / natural
    "trench": "grass",             # earth underfoot

    # ---- indoor floor
    "corridor": "indoor_floor",
    "hallway": "indoor_floor",
    "lobby": "indoor_floor",
    "reception": "indoor_floor",
    "waiting_room": "indoor_floor",
    "entrance_hall": "indoor_floor",
    "elevator_lobby": "indoor_floor",
    "kitchen": "indoor_floor",
    "living_room": "indoor_floor",
    "dining_room": "indoor_floor",
    "dining_hall": "indoor_floor",
    "bedroom": "indoor_floor",
    "bedchamber": "indoor_floor",
    "childs_room": "indoor_floor",
    "dorm_room": "indoor_floor",
    "hotel_room": "indoor_floor",
    "hospital_room": "indoor_floor",
    "bathroom": "indoor_floor",
    "shower": "indoor_floor",
    "closet": "indoor_floor",
    "attic": "indoor_floor",
    "basement": "indoor_floor",
    "storage_room": "indoor_floor",
    "utility_room": "indoor_floor",
    "office": "indoor_floor",
    "office_cubicles": "indoor_floor",
    "home_office": "indoor_floor",
    "classroom": "indoor_floor",
    "kindergarden_classroom": "indoor_floor",
    "lecture_room": "indoor_floor",
    "conference_room": "indoor_floor",
    "conference_center": "indoor_floor",
    "auditorium": "indoor_floor",
    "library/indoor": "indoor_floor",
    "bookstore": "indoor_floor",
    "shopping_mall/indoor": "indoor_floor",
    "supermarket": "indoor_floor",
    "department_store": "indoor_floor",
    "clothing_store": "indoor_floor",
    "shoe_shop": "indoor_floor",
    "jewelry_shop": "indoor_floor",
    "hardware_store": "indoor_floor",
    "toyshop": "indoor_floor",
    "pharmacy": "indoor_floor",
    "drugstore": "indoor_floor",
    "gift_shop": "indoor_floor",
    "florist_shop/indoor": "indoor_floor",
    "candy_store": "indoor_floor",
    "delicatessen": "indoor_floor",
    "bakery/shop": "indoor_floor",
    "restaurant": "indoor_floor",
    "restaurant_kitchen": "indoor_floor",
    "cafeteria": "indoor_floor",
    "coffee_shop": "indoor_floor",
    "food_court": "indoor_floor",
    "fastfood_restaurant": "indoor_floor",
    "pizzeria": "indoor_floor",
    "diner/outdoor": "indoor_floor",
    "bar": "indoor_floor",
    "pub/indoor": "indoor_floor",
    "beer_hall": "indoor_floor",
    "banquet_hall": "indoor_floor",
    "ballroom": "indoor_floor",
    "museum/indoor": "indoor_floor",
    "natural_history_museum": "indoor_floor",
    "science_museum": "indoor_floor",
    "art_gallery": "indoor_floor",
    "church/indoor": "indoor_floor",
    "temple/asia": "indoor_floor",
    "throne_room": "indoor_floor",
    "bank_vault": "indoor_floor",
    "gymnasium/indoor": "indoor_floor",
    "martial_arts_gym": "indoor_floor",
    "basketball_court/indoor": "indoor_floor",
    "bowling_alley": "indoor_floor",
    "beauty_salon": "indoor_floor",
    "laundromat": "indoor_floor",
    "hospital": "indoor_floor",
    "airport_terminal": "indoor_floor",
    "bus_station/indoor": "indoor_floor",
    "train_station/platform": "indoor_floor",
    "subway_station/platform": "indoor_floor",
    "bus_interior": "indoor_floor",
    "train_interior": "indoor_floor",
    # everything else -> unknown
}


def load_categories(path: Path) -> list[str]:
    """Return the 365-entry name list, ordered by class index."""
    out: list[str] = [""] * 365
    for line in path.read_text().splitlines():
        # lines like "/s/staircase 317" — the leading "/x/" is just the
        # alphabetical shard, strip both slashes so 'staircase' matches
        # our taxonomy keys.
        parts = line.strip().split()
        if len(parts) != 2:
            continue
        slash, idx = parts
        name = slash.strip("/").split("/", 1)[1]  # drop the leading "a/" etc.
        out[int(idx)] = name
    return out


def build_class_to_terrain(names: list[str]) -> np.ndarray:
    """365-int array mapping raw class idx -> taxonomy idx."""
    unk = TAXONOMY.index("unknown")
    m = np.full(365, unk, dtype=np.int32)
    hit = 0
    for i, name in enumerate(names):
        t = NAME_TO_TERRAIN.get(name)
        if t is not None:
            m[i] = TAXONOMY.index(t)
            hit += 1
    print(f"[terrain] mapped {hit}/365 Places365 classes; "
          f"{365 - hit} default to 'unknown'", flush=True)
    return m


# ---- stair-edge detection (Phase 3) ------------------------------------------

class StairEdgeResult:
    def __init__(self, detected: bool, edge_row: int, n_edges: int,
                 confidence: str, lines: list):
        self.detected = detected
        self.edge_row = edge_row    # row of the topmost (farthest) stair edge in full frame
        self.n_edges = n_edges      # parallel edges found
        self.confidence = confidence  # "high" | "low" | "none"
        self.lines = lines          # list of (x1,y1,x2,y2) in full-frame coords for overlay


def detect_stair_edges(frame_bgr: np.ndarray) -> StairEdgeResult:
    """Classical stair-edge detection via Canny + probabilistic Hough."""
    h, w = frame_bgr.shape[:2]
    roi_top = int(h * EDGE_ROI_TOP)
    roi = frame_bgr[roi_top:, :]
    rh, rw = roi.shape[:2]

    gray = cv2.cvtColor(roi, cv2.COLOR_BGR2GRAY)
    edges = cv2.Canny(gray, 50, 150)
    min_len = int(rw * EDGE_MIN_LEN_FRAC)
    lines = cv2.HoughLinesP(edges, 1, np.pi / 180, threshold=40,
                            minLineLength=min_len, maxLineGap=20)
    if lines is None:
        return StairEdgeResult(False, 0, 0, "none", [])

    # Filter to near-horizontal lines
    horiz = []
    for line in lines:
        pts = line.flatten()
        x1, y1, x2, y2 = int(pts[0]), int(pts[1]), int(pts[2]), int(pts[3])
        angle = abs(np.degrees(np.arctan2(y2 - y1, x2 - x1)))
        if angle < EDGE_ANGLE_TOL_DEG or angle > (180 - EDGE_ANGLE_TOL_DEG):
            mid_y = (y1 + y2) / 2
            horiz.append((mid_y, (x1, y1 + roi_top, x2, y2 + roi_top)))

    if len(horiz) < EDGE_MIN_PARALLEL:
        return StairEdgeResult(False, 0, len(horiz), "none",
                               [ln for _, ln in horiz])

    # Check if ≥ EDGE_MIN_PARALLEL lines cluster within a small vertical span
    horiz.sort(key=lambda x: x[0])
    ys = [y for y, _ in horiz]
    max_span = rh * EDGE_VERTICAL_SPAN_FRAC
    best_count = 0
    best_top_y = 0
    for i in range(len(ys)):
        count = 0
        for j in range(i, len(ys)):
            if ys[j] - ys[i] <= max_span:
                count += 1
            else:
                break
        if count > best_count:
            best_count = count
            best_top_y = ys[i]

    detected = best_count >= EDGE_MIN_PARALLEL
    confidence = "high" if best_count >= EDGE_HIGH_CONFIDENCE else ("low" if detected else "none")
    edge_row = int(best_top_y) + roi_top if detected else 0
    return StairEdgeResult(detected, edge_row, best_count, confidence,
                           [ln for _, ln in horiz])


# ---- cane REST poster (Phase 4) ---------------------------------------------

class CanePoster:
    def __init__(self, base_url: str, transport: str):
        self.base = base_url.rstrip("/")
        self.transport = transport
        self._last_terrain: str | None = None
        self._last_post_t = 0.0
        self._stairs_hold_until = 0.0
        self._last_edge_vibro_t = 0.0
        self._last_err = ""
        self._last_ok: bool | None = None
        # Mode-switch stability tracking: the candidate mode must be held
        # continuously for MODE_SWITCH_HOLD_S before the command fires.
        self._mode_candidate: str | None = None   # "INDOOR MODE" | "OUTDOOR MODE"
        self._mode_candidate_since: float = 0.0
        self._sent_mode: str | None = None        # last mode command actually sent

    def _post(self, path: str, payload: dict) -> bool:
        try:
            body = json.dumps(payload).encode("utf-8")
            req = urlreq.Request(
                f"{self.base}{path}", data=body, method="POST",
                headers={"Content-Type": "application/json"})
            with urlreq.urlopen(req, timeout=POST_TIMEOUT_S) as r:
                r.read()
            self._last_ok = True
            self._last_err = ""
            return True
        except Exception as err:
            self._last_ok = False
            self._last_err = f"{type(err).__name__}: {err}"
            return False

    def announce_terrain(self, terrain: str):
        """Send terrain updates to the cane, with a 30-second stability gate on
        indoor/outdoor mode switches to prevent flicker at thresholds (doorways).

        - STAIRS AHEAD / CLEAR fire immediately on change (safety / info).
        - INDOOR MODE / OUTDOOR MODE only fire after MODE_SWITCH_HOLD_S seconds
          of continuous classification in the same mode group, and only when the
          mode actually changed.
        """
        now = time.monotonic()
        if now - self._last_post_t < POST_INTERVAL_S:
            return

        # Determine the message this terrain maps to.
        if terrain in OUTDOOR_TERRAINS:
            mode_text = "OUTDOOR MODE"
        elif terrain in INDOOR_TERRAINS:
            mode_text = "INDOOR MODE"
        else:
            mode_text = None  # stairs/unknown — handled separately or below

        # Non-mode messages (STAIRS AHEAD, CLEAR) fire immediately on change.
        if mode_text is None:
            text_map = {"stairs": "STAIRS AHEAD", "unknown": "CLEAR"}
            text = text_map.get(terrain, "CLEAR")
            if text != self._last_terrain:
                self._post("/api/phone", {"text": text})
                self._last_terrain = terrain
                self._last_post_t = now
            return

        # Mode messages: accumulate stability before firing.
        if mode_text != self._mode_candidate:
            # Candidate changed — restart the timer.
            self._mode_candidate = mode_text
            self._mode_candidate_since = now
            return  # not stable yet

        elapsed = now - self._mode_candidate_since
        if elapsed < MODE_SWITCH_HOLD_S:
            return  # still waiting for stability

        # Stable for 30s — fire only if the mode actually changed.
        if mode_text == self._sent_mode:
            return  # already in this mode, nothing to do

        self._post("/api/phone", {"text": mode_text})
        self._sent_mode = mode_text
        self._last_terrain = terrain
        self._last_post_t = now
        print(f"[terrain] mode switch -> {mode_text} "
              f"(stable for {elapsed:.0f}s)", flush=True)

    def stairs_stop(self):
        """STAIRS GUARDRAIL: force-stop wheel + distinctive double-vibro."""
        now = time.monotonic()
        if now < self._stairs_hold_until:
            return  # already in a hold
        self._post("/api/motor", {"dir": 0})
        self._post("/api/vibro", {"ms": 300})
        time.sleep(0.2)
        self._post("/api/vibro", {"ms": 300})
        self._stairs_hold_until = now + STAIRS_STOP_HOLD_S
        self._last_post_t = now

    def in_stairs_hold(self) -> bool:
        return time.monotonic() < self._stairs_hold_until

    def edge_vibro(self):
        """Non-stair edge: single 400ms buzz, rate-limited."""
        now = time.monotonic()
        if now - self._last_edge_vibro_t < EDGE_VIBRO_COOLDOWN_S:
            return
        self._post("/api/vibro", {"ms": 400})
        self._last_edge_vibro_t = now

    def status(self) -> dict:
        now = time.monotonic()
        elapsed = now - self._mode_candidate_since if self._mode_candidate else 0
        return {
            "transport": self.transport,
            "last_terrain": self._last_terrain,
            "last_ok": self._last_ok,
            "last_err": self._last_err,
            "stairs_hold_remaining_s": max(0, self._stairs_hold_until - now),
            "mode_candidate": self._mode_candidate,
            "mode_candidate_stable_s": round(elapsed, 1),
            "mode_candidate_needed_s": MODE_SWITCH_HOLD_S,
            "sent_mode": self._sent_mode,
        }


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
    so.intra_op_num_threads = 3  # 4 reboots the board
    so.inter_op_num_threads = 1
    so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    return ort.InferenceSession(str(path), sess_options=so,
                                providers=["CPUExecutionProvider"])


def softmax(x: np.ndarray) -> np.ndarray:
    x = x - x.max()
    e = np.exp(x)
    return e / e.sum()


class Decision:
    def __init__(self, top_idx, top_p, terrain, terrain_p, emitted, ms,
                 stair_edge: StairEdgeResult | None = None):
        self.top_idx = top_idx
        self.top_p = top_p
        self.terrain = terrain
        self.terrain_p = terrain_p
        self.emitted = emitted
        self.ms = ms
        self.stair_edge = stair_edge


class Terrain:
    def __init__(self, size: int, index: int, debounce: int,
                 cane: CanePoster | None = None):
        self.size = size
        self.debounce_n = debounce
        self.cane = cane
        self.categories = load_categories(CATEGORIES)
        self.cls_to_terrain = build_class_to_terrain(self.categories)
        self.session = make_session(MODEL)
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
        self._decision: Decision | None = None
        self._pending: str | None = None
        self._pending_n: int = 0
        self._emitted: str = "unknown"
        self._lock = threading.Lock()
        self._running = True
        threading.Thread(target=self._capture_loop, daemon=True).start()
        threading.Thread(target=self._infer_loop, daemon=True).start()

    def _capture_loop(self):
        while self._running:
            ok, frame = self.cap.read()
            if not ok:
                time.sleep(0.01)
                continue
            with self._lock:
                self._frame = frame

    def _preprocess(self, frame_bgr):
        rgb = cv2.cvtColor(frame_bgr, cv2.COLOR_BGR2RGB)
        r = cv2.resize(rgb, (self.size, self.size), interpolation=cv2.INTER_AREA)
        chw = np.transpose(r.astype(np.float32) / 255.0, (2, 0, 1))
        return ((chw - IMAGENET_MEAN) / IMAGENET_STD)[None].astype(np.float32)

    def _aggregate(self, probs: np.ndarray) -> tuple[str, float]:
        """Sum per-terrain-group probability mass; return the winning label
        and its total probability. This is more robust than picking the top-1
        raw class and mapping — a scene that is "60% sidewalk-like" spread
        across 3 sidewalk classes should still win."""
        totals = np.zeros(len(TAXONOMY), dtype=np.float32)
        for cls in range(365):
            totals[self.cls_to_terrain[cls]] += probs[cls]
        # If the biggest terrain mass is 'unknown', consider the runner-up:
        # unknown is a catch-all bucket, so anything else with meaningful
        # mass is more informative even if slightly smaller. Only fall back
        # to 'unknown' if it's dominant by a clear margin.
        unk = TAXONOMY.index("unknown")
        order = np.argsort(-totals)
        best = int(order[0])
        if best == unk and totals[order[1]] >= 0.15:
            best = int(order[1])
        return TAXONOMY[best], float(totals[best])

    def _step_debounce(self, terrain: str) -> str:
        """Apply N-frame debounce; return the currently-emitted label."""
        if terrain == self._emitted:
            self._pending = None
            self._pending_n = 0
            return self._emitted
        if terrain == self._pending:
            self._pending_n += 1
            if self._pending_n >= self.debounce_n:
                self._emitted = terrain
                self._pending = None
                self._pending_n = 0
        else:
            self._pending = terrain
            self._pending_n = 1
        return self._emitted

    def _infer_loop(self):
        while self._running:
            with self._lock:
                frame = None if self._frame is None else self._frame.copy()
            if frame is None:
                time.sleep(0.02)
                continue

            t = time.perf_counter()
            x = self._preprocess(frame)
            logits = self.session.run(None, {self.in_name: x})[0][0]
            ms = (time.perf_counter() - t) * 1000

            probs = softmax(logits)
            top_idx = np.argsort(-probs)[:3]
            top_p = probs[top_idx]

            terrain, terrain_p = self._aggregate(probs)
            emitted = self._step_debounce(terrain)

            # Phase 3: stair-edge detection (runs in ~5-20ms, doesn't compete)
            stair_edge = detect_stair_edges(frame)

            dec = Decision(top_idx, top_p, terrain, terrain_p, emitted, ms,
                           stair_edge)
            panel = self._render(frame, dec)
            with self._lock:
                self._decision = dec
                self._panel = panel

            # Phase 4: cane wiring
            if self.cane is not None:
                # STAIRS GUARDRAIL: gate edge detector on classifier agreement.
                # Only fire STAIRS hard-stop if:
                #   (a) classifier says 'stairs', OR
                #   (b) edge detector fires HIGH *and* classifier is NOT
                #       confidently saying something else (indoor_floor/grass/etc).
                # This prevents false STAIRS-STOP on striped carpets/textures.
                classifier_says_stairs = (emitted == "stairs")
                edge_gated = (stair_edge.detected and
                              stair_edge.confidence == "high" and
                              emitted in ("stairs", "unknown"))
                stairs_triggered = classifier_says_stairs or edge_gated

                if stairs_triggered:
                    self.cane.stairs_stop()
                elif not self.cane.in_stairs_hold():
                    self.cane.announce_terrain(emitted)
                    # Non-stair edge with low confidence = curb/drop-off hint
                    if (stair_edge.detected and
                            stair_edge.confidence == "low" and
                            emitted in ("sidewalk", "road", "unknown")):
                        self.cane.edge_vibro()

            top_names = [f"{self.categories[i]}:{p:.2f}"
                         for i, p in zip(top_idx.tolist(), top_p.tolist())]
            edge_str = (f"  EDGE({stair_edge.n_edges},{stair_edge.confidence})"
                        if stair_edge.detected else "")
            print(f"{ms:5.0f}ms  top3={top_names}  "
                  f"terrain={terrain}({terrain_p:.2f})  emitted={emitted}"
                  f"{edge_str}",
                  flush=True)
            time.sleep(LOOP_SLEEP)

    # ---- render debug view -----------------------------------------------
    _COLOR = {
        "sidewalk": (200, 200, 200),
        "road": (0, 0, 255),
        "grass": (0, 200, 0),
        "stairs": (0, 165, 255),
        "indoor_floor": (255, 200, 100),
        "unknown": (128, 128, 128),
    }

    def _render(self, frame, dec: Decision) -> bytes:
        H = 480
        rgb = cv2.resize(frame, (int(frame.shape[1] * H / frame.shape[0]), H))
        w = rgb.shape[1]
        scale_y = H / frame.shape[0]
        scale_x = w / frame.shape[1]

        # Overlay stair edges (Phase 3)
        if dec.stair_edge and dec.stair_edge.lines:
            for (x1, y1, x2, y2) in dec.stair_edge.lines:
                pt1 = (int(x1 * scale_x), int(y1 * scale_y))
                pt2 = (int(x2 * scale_x), int(y2 * scale_y))
                color = (0, 0, 255) if dec.stair_edge.confidence == "high" else (0, 165, 255)
                cv2.line(rgb, pt1, pt2, color, 2)
            if dec.stair_edge.detected:
                row_y = int(dec.stair_edge.edge_row * scale_y)
                cv2.line(rgb, (0, row_y), (w, row_y), (0, 0, 255), 1)
                cv2.putText(rgb, f"EDGE x{dec.stair_edge.n_edges} "
                            f"[{dec.stair_edge.confidence}]",
                            (w - 200, row_y - 8), cv2.FONT_HERSHEY_SIMPLEX,
                            0.5, (0, 0, 255), 2)

        # Header overlay
        cv2.rectangle(rgb, (0, 0), (w - 1, 100), (0, 0, 0), -1)
        col = self._COLOR.get(dec.emitted, (128, 128, 128))
        cv2.putText(rgb, dec.emitted.upper(), (10, 40),
                    cv2.FONT_HERSHEY_SIMPLEX, 1.2, col, 3)
        cv2.putText(rgb, f"live: {dec.terrain} ({dec.terrain_p:.2f})",
                    (10, 68), cv2.FONT_HERSHEY_SIMPLEX, 0.55,
                    (255, 255, 255), 1)
        cane_str = ""
        if self.cane:
            hold = self.cane.status()["stairs_hold_remaining_s"]
            if hold > 0:
                cane_str = f"  STAIRS-STOP {hold:.1f}s"
        cv2.putText(rgb, f"{dec.ms:.0f}ms  {1000/max(dec.ms,1):.1f}fps{cane_str}",
                    (10, 92), cv2.FONT_HERSHEY_SIMPLEX, 0.5,
                    (200, 200, 200), 1)

        # Top-3 raw predictions
        for i, (cls, p) in enumerate(zip(dec.top_idx.tolist(),
                                         dec.top_p.tolist())):
            name = self.categories[cls]
            terrain = TAXONOMY[self.cls_to_terrain[cls]]
            line = f"#{i+1} {name}  {p:.2f}  ({terrain})"
            cv2.putText(rgb, line, (10, H - 60 + i * 20),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.5, (255, 255, 255), 1)

        ok, buf = cv2.imencode(".jpg", rgb, [cv2.IMWRITE_JPEG_QUALITY, 80])
        return buf.tobytes() if ok else b""

    def latest(self) -> bytes | None:
        with self._lock:
            return self._panel

    def snapshot(self) -> dict:
        with self._lock:
            dec = self._decision
            emitted = self._emitted
            pending = self._pending
            pending_n = self._pending_n
        out = {"emitted": emitted, "pending": pending, "pending_n": pending_n,
               "debounce_n": self.debounce_n, "input_size": self.size}
        if dec:
            out["live"] = {
                "terrain": dec.terrain,
                "terrain_p": round(dec.terrain_p, 3),
                "ms": round(dec.ms, 1),
                "top3": [{
                    "class": self.categories[int(cls)],
                    "p": round(float(p), 3),
                    "terrain": TAXONOMY[int(self.cls_to_terrain[int(cls)])],
                } for cls, p in zip(dec.top_idx.tolist(), dec.top_p.tolist())],
            }
            if dec.stair_edge:
                out["stair_edge"] = {
                    "detected": dec.stair_edge.detected,
                    "n_edges": dec.stair_edge.n_edges,
                    "confidence": dec.stair_edge.confidence,
                    "edge_row": dec.stair_edge.edge_row,
                }
        if self.cane:
            out["cane"] = self.cane.status()
        return out


NAV: Terrain | None = None
PAGE = b"""<!doctype html><html><head><title>terrain</title>
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


class ThreadingServer(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def main():
    global NAV
    ap = argparse.ArgumentParser()
    ap.add_argument("--size", type=int, default=224)
    ap.add_argument("--index", type=int, default=0)
    ap.add_argument("--port", type=int, default=8080)
    ap.add_argument("--debounce", type=int, default=3)
    ap.add_argument("--cane-url", default=None,
                    help="qcane-wheel base URL e.g. http://127.0.0.1:7000; omit to disable wiring")
    ap.add_argument("--transport", choices=("phone", "motor"), default="phone")
    args = ap.parse_args()

    cane = CanePoster(args.cane_url, args.transport) if args.cane_url else None
    NAV = Terrain(args.size, resolve_device(args.index), args.debounce, cane)
    cane_str = 'off' if cane is None else f'{args.cane_url} ({args.transport})'
    print(f"terrain: resnet18-places365 @ {args.size}x{args.size}  "
          f"6-class taxonomy  debounce={args.debounce}  cane={cane_str}  "
          f"serving http://0.0.0.0:{args.port}/", flush=True)
    print(f"  /stream  MJPEG debug view", flush=True)
    print(f"  /stats   JSON snapshot", flush=True)
    ThreadingServer(("0.0.0.0", args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
