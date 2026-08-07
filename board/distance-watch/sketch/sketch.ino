/*
 * qcane-wheel — MCU side (merged: wheel + distance + vibro).
 *
 * One sketch for the whole Modulino chain on the Qwiic bus (Wire1):
 *   UNO Q -> Modulino Motors -> Modulino Vibro -> Modulino Distance
 *
 * - Wheel: direction arrives from the Linux side via "set_wheel" (phone over
 *   BLE, or the dashboard buttons). Every spin is full scale (100% duty) —
 *   there are no graded speeds anywhere in this firmware. The 13x8 LED
 *   matrix mirrors what the motor is doing.
 * - Distance: every valid ToF measurement streams up as "distance_reading".
 * - Vibro: pulses in a rhythm while the Linux side holds "set_vibro" on
 *   (object inside the presence threshold); "vibro_pulse" fires one manual
 *   buzz from the dashboard.
 * - Failsafe: the Linux side re-sends the desired state 4x/s; if that stream
 *   stops for 2 s (app or bridge died), the wheel stops and the vibro goes
 *   quiet rather than run away.
 *
 * Wiring: the motor sits across terminals 1A and 2A, which is one half-bridge
 * from channel A and one from channel B rather than a single channel's own
 * pair, so the two channels have to be driven in opposite phase — see
 * applyMotor(). Commands step straight to full scale, no ramp ("turn hard
 * 100% no matter what"). "motor_selftest" re-measures every drive option if
 * that ever needs checking again (streams "selftest_telemetry"; the periodic
 * dashboard stream is "motor_telemetry").
 */

#include <Arduino_LED_Matrix.h>
#include <Arduino_Modulino.h>
#include <Arduino_RouterBridge.h>

Arduino_LED_Matrix matrix;
ModulinoDistance distance;  // VL53L4CD/VL53L4ED time-of-flight sensor
ModulinoMotors motors;      // MAX22211 dual H-bridge; motor across 1A <-> 2A
ModulinoVibro vibro;        // haptic vibration motor

// The wheel has exactly one speed: full scale (100% duty). The set_wheel RPC
// keeps its (dir, speed) arity for wire compatibility, but the speed argument
// is ignored — no command can produce a partial-duty spin.

// The Linux side re-sends the desired actuator state 4x/s; if that stream
// stops (app or bridge died), stop every output rather than run away.
constexpr unsigned long COMMAND_TIMEOUT_MS = 2000;

// Proximity haptics: the Linux side commands the pulse period with each
// set_vibro (parking-sensor style — tighter rhythm as the obstacle closes);
// period <= 0 means continuous buzz ("stop now" tier). The default only
// applies until the first command arrives.
constexpr int VIBRO_DEFAULT_PERIOD_MS = 500;
// Continuous mode re-issues a self-terminating buzz before it expires, so a
// wedged loop still cannot leave the vibro latched on.
constexpr unsigned long VIBRO_CONT_ON_MS = 600;
constexpr unsigned long VIBRO_CONT_REFRESH_MS = 400;

constexpr unsigned long MODULE_RETRY_MS = 3000;
constexpr unsigned long TELEMETRY_MS = 500;
constexpr unsigned long DISTANCE_POLL_MS = 20;

// LED-matrix animation step at full speed.
constexpr uint16_t MATRIX_STEP_MS = 45;

static const uint8_t MATRIX_W = 13;
static const uint8_t MATRIX_H = 8;
static const uint16_t MATRIX_PIXELS = MATRIX_W * MATRIX_H;  // 104

// Perimeter of the wheel, listed clockwise starting at the top-left of the top
// edge. Walking this list forwards looks like a clockwise spin, backwards like
// a counter-clockwise one.
static const uint8_t RING_LEN = 22;
static const uint8_t RING_ROW[RING_LEN] = {
    0, 0, 0, 0, 0, 1, 2, 3, 4, 5, 6, 7, 7, 7, 7, 7, 6, 5, 4, 3, 2, 1};
static const uint8_t RING_COL[RING_LEN] = {
    4, 5, 6, 7, 8, 9, 10, 10, 10, 10, 9, 8, 7, 6, 5, 4, 3, 2, 2, 2, 2, 3};

// Brightness of the spinning marker and the pixels trailing behind it
// (3-bit grayscale, so 0..7).
static const uint8_t COMET[] = {7, 5, 3, 1};
static const uint8_t COMET_LEN = sizeof(COMET);

// Module presence on the Qwiic bus (all three retried every 3 s).
static bool motorReady = false;
static bool sensorReady = false;
static bool vibroReady = false;

// Bridge handlers run on the bridge thread, loop() reads them — keep volatile.
volatile int wheelDir = 0;    // -1 = left, 0 = stopped, +1 = right
volatile bool dirty = true;
volatile bool selftestPending = false;  // asks loop() to run the self-test
volatile bool desiredVibro = false;
volatile int vibroPeriodMs = VIBRO_DEFAULT_PERIOD_MS;  // commanded rhythm; <= 0 = continuous
volatile int pendingPulseMs = 0;    // one-shot manual buzz from the dashboard
volatile unsigned long lastCommandMs = 0;

static uint8_t frame[MATRIX_PIXELS];
static int head = 0;
static unsigned long lastStep = 0;

// Last command loop() acted on, so transitions are handled exactly once.
// Seeded to an impossible value so the current state is reported once at boot.
static int appliedDir = -99;

static int16_t lastWrittenRaw = 0;      // last raw value written to the module
static bool motorWritePending = false;  // I2C write failed; retry next pass
static bool failsafeLatched = false;    // hard stop on timeout issued once

static bool vibroActive = false;
static unsigned long lastPulseMs = 0;
static unsigned long lastRetryMs = 0;
static unsigned long lastTelemetryMs = 0;
static unsigned long lastStatusMs = 0;
static unsigned long lastDistancePollMs = 0;
static float lastMm = NAN;

static inline void px(uint8_t row, uint8_t col, uint8_t level) {
  if (row < MATRIX_H && col < MATRIX_W) {
    frame[row * MATRIX_W + col] = level;
  }
}

static void renderWheel() {
  memset(frame, 0, sizeof(frame));

  int dir = (appliedDir == -99) ? 0 : appliedDir;

  // Faint rim, so the wheel stays visible even when stopped.
  for (uint8_t i = 0; i < RING_LEN; i++) {
    px(RING_ROW[i], RING_COL[i], 1);
  }

  if (dir == 0) {
    // Stopped: bright hub, no motion.
    px(3, 5, 3); px(3, 6, 5); px(3, 7, 3);
    px(4, 5, 3); px(4, 6, 5); px(4, 7, 3);
    return;
  }

  px(3, 6, 2);
  px(4, 6, 2);

  // Two markers half a turn apart read as rotation much better than one.
  for (uint8_t spoke = 0; spoke < 2; spoke++) {
    for (uint8_t t = 0; t < COMET_LEN; t++) {
      int idx = head + (spoke * (RING_LEN / 2)) - (dir * (int)t);
      idx = ((idx % RING_LEN) + RING_LEN) % RING_LEN;
      px(RING_ROW[idx], RING_COL[idx], COMET[t]);
    }
  }

  // Bar on the edge of the panel we are turning towards.
  uint8_t col = (dir < 0) ? 0 : (MATRIX_W - 1);
  for (uint8_t row = 2; row <= 5; row++) {
    px(row, col, 6);
  }
}

// Signed duty percentage the H-bridge is applying RIGHT NOW (-100/0/+100).
static int appliedDutyPct() {
  return (int)lroundf(100.0f * (float)lastWrittenRaw
                      / (float)ModulinoMotors::MAX_SPEED);
}

// Drive the H-bridge: full scale in the given direction, or stop. The motor
// bridges terminal 1A (channel A) and 2A (channel B), so the channels are
// driven in opposite phase: setDcSpeedRaw(+raw, -raw). Returns false when
// the I2C write did not go through, so loop() can retry.
static bool applyMotor(int dir) {
  if (dir == 0 || dir == -99) {
    motors.stop();
    lastWrittenRaw = 0;
    return true;
  }
  int16_t raw = (dir > 0) ? (int16_t)ModulinoMotors::MAX_SPEED
                          : (int16_t)-ModulinoMotors::MAX_SPEED;
  if (!motors.setDcSpeedRaw(raw, (int16_t)-raw)) {
    return false;
  }
  lastWrittenRaw = raw;
  return true;
}

// Sample the current sensors and hand the numbers to the Linux side. Used by
// the self-test to work out which terminals the motor is actually on: only a
// channel with the motor across it draws current.
static void reportCurrent(const char* stage) {
  delay(500);  // let the new drive state settle before sampling
  float milliampsA = -1.0f;
  float milliampsB = -1.0f;
  String mode = "?";
  bool busy = false;
  if (motors.update()) {
    milliampsA = motors.sensedCurrentA();
    milliampsB = motors.sensedCurrentB();
    // Reading the mode back proves the module is acting on what we send,
    // which separates "not listening" from "listening but unpowered".
    mode = motors.stepperModeEnabled() ? "stepper" : "dc";
    busy = motors.busy();
  }
  Bridge.notify("selftest_telemetry", String(stage), milliampsA, milliampsB,
                mode, busy);
}

// Try every sensible way of driving the two channels and report the current
// each one draws. Runs from loop(), so it is free to block.
static void runSelftest() {
  if (!motorReady) {
    Bridge.notify("selftest_telemetry", String("no modulino found"),
                  -1.0f, -1.0f, String("?"), false);
    return;
  }

  // Full scale, like every other spin in this firmware.
  int16_t raw = (int16_t)ModulinoMotors::MAX_SPEED;

  // Half-full-scale doubles the current-sense resolution (~0.65 mA per count
  // instead of ~1.3), which matters when we are looking for "any current".
  motors.setHalfFullScaleEnabled(true);

  motors.stop();
  reportCurrent("idle");
  motors.setDcSpeedRaw(raw, 0);
  reportCurrent("A+ only (1A/1B)");
  motors.setDcSpeedRaw(0, raw);
  reportCurrent("B+ only (2A/2B)");
  motors.setDcSpeedRaw(raw, (int16_t)-raw);
  reportCurrent("A+/B- (1A -> 2A)");
  motors.setDcSpeedRaw((int16_t)-raw, raw);
  reportCurrent("A-/B+ (2A -> 1A)");
  motors.stop();
  reportCurrent("stopped");

  motors.setHalfFullScaleEnabled(false);

  // Re-apply the current wheel command on the next loop() pass.
  lastWrittenRaw = 0;
  appliedDir = -99;
}

static bool beginMotors() {
  if (!motors.begin()) {
    return false;
  }
  motors.setStepperModeEnabled(false);  // DC, not stepper
  motors.setDecay(ModulinoMotors::DecayMode::SLOW);
  motors.stop();
  // Force the next loop() pass to re-apply and re-report the wheel state.
  appliedDir = -99;
  lastWrittenRaw = 0;
  motorWritePending = false;
  return true;
}

// Called from Python: Bridge.call("set_wheel", dir, speed). The speed
// argument is accepted for wire compatibility and IGNORED — every spin runs
// at full scale.
//
// This runs on the bridge thread, in the middle of an RPC request, so it only
// touches state. Logging here would be a nested RPC (Monitor.write() calls
// back over the same bridge) and driving hardware would stall the request —
// both are handled from loop() instead.
void set_wheel(int dir, int speed) {
  (void)speed;
  int d = (dir > 0) ? 1 : ((dir < 0) ? -1 : 0);
  // The Linux side re-sends this 4x/s as a heartbeat; only redraw on change.
  if (d != wheelDir) {
    dirty = true;
  }
  wheelDir = d;
  lastCommandMs = millis();
}

void set_vibro(int on, int period) {
  desiredVibro = (on != 0);
  vibroPeriodMs = period;
  lastCommandMs = millis();
}

void vibro_pulse(int ms) {
  pendingPulseMs = constrain(ms, 50, 3000);
}

// Called from Python: Bridge.call("motor_selftest", percent). The percent is
// ignored — the self-test drives full scale like everything else. Same rule
// as set_wheel: only set a flag; loop() does the blocking work.
void motor_selftest(int percent) {
  (void)percent;
  selftestPending = true;
}

void setup() {
  matrix.begin();
  matrix.setGrayscaleBits(3);  // 8 brightness levels (0..7)
  matrix.clear();

  // Initialize Modulino I2C communication (Qwiic connector is on Wire1)
  Modulino.begin(Wire1);
  motorReady = beginMotors();
  sensorReady = distance.begin();
  vibroReady = vibro.begin();

  Bridge.begin();
  Monitor.begin();

  bool ok = Bridge.provide("set_wheel", set_wheel);
  ok = Bridge.provide("set_vibro", set_vibro) && ok;
  ok = Bridge.provide("vibro_pulse", vibro_pulse) && ok;
  ok = Bridge.provide("motor_selftest", motor_selftest) && ok;
  Monitor.print("[qcane-wheel] sketch ready, bridge=");
  Monitor.print(ok ? "yes" : "no");
  Monitor.print(" motors=");
  Monitor.print(motorReady ? "found" : "MISSING");
  Monitor.print(" distance=");
  Monitor.print(sensorReady ? "found" : "MISSING");
  Monitor.print(" vibro=");
  Monitor.println(vibroReady ? "found" : "MISSING");

  renderWheel();
  matrix.draw(frame);
}

void loop() {
  unsigned long now = millis();

  if (selftestPending) {
    selftestPending = false;
    runSelftest();
    lastStep = millis();
  }

  // Look again every 3 s for any module that is missing (supports hot-plug)
  if ((!motorReady || !sensorReady || !vibroReady) && (now - lastRetryMs >= MODULE_RETRY_MS)) {
    lastRetryMs = now;
    if (!motorReady) {
      motorReady = beginMotors();
      if (motorReady) {
        Monitor.println("[qcane-wheel] Modulino Motors connected");
      }
    }
    if (!sensorReady) {
      sensorReady = distance.begin();
      if (sensorReady) {
        Monitor.println("[qcane-wheel] Modulino Distance connected");
      }
    }
    if (!vibroReady) {
      vibroReady = vibro.begin();
      if (vibroReady) {
        Monitor.println("[qcane-wheel] Modulino Vibro connected");
      }
    }
  }

  // available() is true only when a NEW valid measurement arrived; no target
  // in range produces no data at all. Gated so the animation's 5 ms loop does
  // not multiply the I2C traffic.
  if (sensorReady && (now - lastDistancePollMs >= DISTANCE_POLL_MS)) {
    lastDistancePollMs = now;
    if (distance.available()) {
      lastMm = distance.get();
      Bridge.notify("distance_reading", lastMm);
    }
  }

  // Failsafe: without fresh commands from the Linux side, stop everything.
  int effDir = wheelDir;
  bool vibroTarget = desiredVibro;
  bool failsafe = (lastCommandMs == 0 || (now - lastCommandMs) > COMMAND_TIMEOUT_MS);
  if (failsafe) {
    effDir = 0;
    vibroTarget = false;
    if (!failsafeLatched) {
      failsafeLatched = true;
      if (motorReady) {
        motors.stop();
      }
      lastWrittenRaw = 0;
      motorWritePending = false;
    }
  } else {
    failsafeLatched = false;
  }

  // React to a new command: drive the H-bridge straight to full scale (or
  // stop), tell Python what got applied, and log. All of it outside the
  // bridge handler, where blocking and nested RPCs are safe.
  if (effDir != appliedDir) {
    appliedDir = effDir;
    if (motorReady && !failsafe) {
      motorWritePending = !applyMotor(effDir);
    }

    // Fire-and-forget, so a Python side that is not listening cannot stall us.
    // motorReady rides along so the app can flag a missing Modulino. The
    // speed slot is pinned to 5 (full scale) for payload compatibility.
    Bridge.notify("wheel_applied", effDir, 5, motorReady);

    Monitor.print("[wheel] dir=");
    Monitor.print(effDir);
    Monitor.println(" (full scale)");
    dirty = true;
  }

  // Retry a wheel write that failed (transient I2C hiccup).
  if (motorWritePending && motorReady && !failsafe) {
    motorWritePending = !applyMotor(appliedDir);
  }

  // Proximity haptics at the commanded rhythm: the period tightens as the
  // obstacle closes; period <= 0 is the "stop now" tier (continuous buzz).
  // Every buzz is a self-terminating vibro.on(), so a wedged loop cannot
  // leave the vibro latched on.
  if (vibroReady) {
    if (vibroTarget) {
      int period = vibroPeriodMs;
      if (period <= 0) {
        // Continuous: refresh the buzz before the previous one expires.
        if (!vibroActive || (now - lastPulseMs) >= VIBRO_CONT_REFRESH_MS) {
          vibro.on(VIBRO_CONT_ON_MS);
          lastPulseMs = now;
          vibroActive = true;
        }
      } else {
        unsigned long pulse = (unsigned long)constrain(period / 2, 100, 250);
        if (!vibroActive || (now - lastPulseMs) >= (unsigned long)period) {
          vibro.on(pulse);
          lastPulseMs = now;
          vibroActive = true;
        }
      }
    } else if (vibroActive) {
      vibro.off();
      vibroActive = false;
    }

    // One-shot manual buzz requested from the dashboard
    int pulse = pendingPulseMs;
    if (pulse > 0) {
      pendingPulseMs = 0;
      vibro.on((size_t)pulse);
    }
  } else {
    pendingPulseMs = 0;  // drop manual requests while the module is missing
  }

  // 2 Hz motor telemetry for the dashboard: current sense per channel (mA),
  // what the sketch is actually applying (direction + signed duty), and the
  // driver busy flag. The firmware does not report VM voltage; the signed
  // duty is the applied differential output as a fraction of VM.
  if (motorReady && (now - lastTelemetryMs >= TELEMETRY_MS)) {
    lastTelemetryMs = now;
    if (motors.update()) {
      Bridge.notify("motor_telemetry",
                    motors.sensedCurrentA(),
                    motors.sensedCurrentB(),
                    appliedDir == -99 ? 0 : appliedDir,
                    appliedDutyPct(),
                    vibroActive ? 1 : 0,
                    motors.busy() ? 1 : 0);
    }
  }

  // 1 Hz heartbeats so the Linux side can tell "no object" apart from "no
  // sensor" and can show which modules are attached.
  if (now - lastStatusMs >= 1000) {
    lastStatusMs = now;
    Bridge.notify("sensor_status", sensorReady);
    Bridge.notify("actuator_status", (motorReady ? 1 : 0) | (vibroReady ? 2 : 0));
  }

  if (appliedDir != 0 && appliedDir != -99
      && (now - lastStep) >= MATRIX_STEP_MS) {
    lastStep = now;
    head = ((head + appliedDir) % RING_LEN + RING_LEN) % RING_LEN;
    dirty = true;
  }

  if (dirty) {
    dirty = false;
    renderWheel();
    matrix.draw(frame);
  }

  delay(5);
}
