# Known issues

Findings from a source audit of the `v3` branch, verified against both the
Kotlin (phone) and Python (board) implementations. Documented rather than
silently fixed, because each needs hardware in hand to confirm the fix.

Severity: **S1** = affects steering behavior · **S2** = feature silently dead ·
**S3** = cosmetic or stale naming.

---

## S1 — `S` means STRAIGHT to the phone and STOP to the board

**Effect:** on a clear path the wheel is commanded to brake roughly every
200 ms, instead of being left free.

The phone emits one letter per decision window
(`CommandAggregator.kt:62-65`):

| Letter | Phone intent |
|---|---|
| `X` | STOP |
| `L` | LEFT |
| `R` | RIGHT |
| `S` | **STRAIGHT** |

The board lowercases the incoming token and looks it up in `ACTIONS`
(`host/qcane_btd.py:69-75`):

```python
ACTIONS = {
    "left": "left", "l": "left", ...
    "right": "right", "r": "right", ...
    "stop": "stop", "s": "stop", "x": "stop", "halt": "stop", ...
}
```

`L`, `R`, and `X` survive the round trip. **`S` lands on the `stop` alias** —
and no `straight` action exists on the board at all.

Why it hid: `S` is also the safety hello sent by `onReady()`, so
"park the wheel on connect" works exactly as intended, which makes the wire
traffic look correct in logs.

**Fix options.** Cleanest is to stop sending a letter for STRAIGHT at all —
"no lateral force" and "brake" are genuinely different states and the protocol
currently can't distinguish them. Alternatively add `"straight"`/`"n"` to
`ACTIONS` mapping to a coast/neutral action, and change the phone to send that.
Either way, keep `s` → stop so the safety hello still parks the wheel.

---

## S2 — The phone parses telemetry on a service that never sends it

**Effect:** `CaneBleLink.reading` never leaves `null`. Everything downstream of
the cane's distance sensor is dead code:

- `pathPipeline.grid.markNearObstacle(...)` — near-field obstacle fusion
- `haptics.caneStop()` — the phone's STOP buzz

There are two distinct BLE services in this system:

| Service | UUID base | Emits |
|---|---|---|
| **QCane GATT** (`host/qcane_btd.py`) | `bcf2f193-…` | `"left:5"` — wheel state, **no newline** |
| **Nordic UART bridge** (`board/ble-bridge/ble_bridge.py`) | `6e400001-…` | `{"mm":842,"p":1}\n` — distance JSON |

`CaneBleLink` names its constants `NUS_SERVICE_UUID` / `NUS_RX_UUID` /
`NUS_TX_UUID` but the values are the **QCane** UUIDs
(`CaneBleLink.kt:454-456`). So it connects to the wheel-control service and then
runs a newline-delimited JSON parser against `"left:5"`. Result: every real
state notification logs as `cane: unparseable line: left:5`, and the distance
stream it wants is on a service it never opens.

**Fix options.** Either (a) have `qcane_btd.py` forward distance telemetry onto
the QCane state characteristic as newline-terminated JSON, or (b) have the phone
maintain a second GATT connection to the Nordic UART bridge for sensor data
while keeping QCane for motor commands. (a) is less code and one radio link.

---

## S2 — The 1200 mm presence threshold is never sent

`OBSTACLE_THRESHOLD_MM = 1200` is declared at `CaneBleLink.kt:449` and
referenced **nowhere else in the tree**. `onReady()` writes only the safety
hello.

The board therefore keeps its own default, `PRESENCE_MAX_MM = 300.0`
(`board/qcane-wheel/python/main.py`). Obstacles register at 30 cm rather than the
documented 1.2 m — far too late to walk around at pace, which is the whole point
of the wider threshold.

**Fix:** write the threshold on connect, or set `PRESENCE_MAX_MM = 1200.0`
board-side. Note this is also gated behind the S2 issue above — the phone can't
currently confirm the `{"thr":"ack"}` response either.

---

## S2 — A phone that walks out of range leaves the wheel turning

`BleTransport` in `qcane_btd.py` subscribes to no BlueZ device-property signal,
so it never learns that the phone dropped off the GATT. The last command stands.

The board's 2 s failsafe does **not** cover this: `main.py` is still alive and
still re-sending the last known desired state 4×/s as its heartbeat. From the
MCU's point of view everything is healthy.

Consequence: lose the phone mid-turn and the wheel keeps steering until an
explicit `stop` arrives or the app dies. This is the most safety-relevant item
in this document.

**Fix:** watch `PropertiesChanged` on the BlueZ device object and zero the
command on disconnect. A phone-liveness timeout in `main.py` (distinct from the
existing MCU heartbeat) would be a belt-and-braces second layer.

---

## S3 — Grid lateral extent is narrower than planner ray range

`TraversabilityGrid` spans ±3.05 m laterally; `PolarPlanner.maxRangeM` is 5.5 m.
Peripheral raycasts therefore exit the mapped area and return "fully free" by
construction. Edge sectors are optimistic — a wide detour can be chosen partly
because nothing is known out there.

Not incorrect (unmapped genuinely isn't occupied), but "unknown" and "clear"
should probably not score identically when picking a valley.

---

## S3 — Stale comments and naming

| Location | Issue |
|---|---|
| `CaneBleLink.kt` | `NUS_*` constant names hold QCane UUIDs; KDoc mentions "Distance Watch"; one log tag still reads `qhackGPS` |
| `CommandAggregator.kt` | KDoc says "1 s failsafe"; the real board value is `COMMAND_TIMEOUT_MS = 2000`. Both board READMEs are correct — only the Kotlin comment is wrong |
| `dev.quad.shepherd` | Package and `ShepherdService` retain an earlier project name |
| `strings.xml` | `app_name` and notification text still show the earlier name on screen |

---

## Deliberately disabled, not broken

| Item | State | Why |
|---|---|---|
| Companion SLM | `COMPANION_ENABLED = false` | Qwen3.5-2B on Hexagon works (benchmarked 12.1 tok/s, 186 ms first token) but is off to keep NPU and thermal budget for the steering path. Flip the flag to demo it. |
| `BleCaneActuator` | Stub with a TODO | A 12-byte binary packet was designed, then superseded by the one-letter protocol over the board's own GATT. The stub is vestigial. |
| `NavEngine` | Unused | Earlier navigation iteration; `CompassNav` is the live one. |
