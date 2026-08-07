/*
 * qcane-wheel — MCU side (merged: wheel + distance + vibro).
 *
 * One sketch for the whole Modulino chain on the Qwiic bus (Wire1):
 *   UNO Q -> Modulino Motors -> Modulino Vibro -> Modulino Distance
 *
 * - Wheel: dir/speed arrive from the Linux side via "set_wheel" (phone over
 *   the QCane GATT, or the dashboard buttons). The 13x8 LED matrix mirrors
 *   what the motor is doing.
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
 * stepMotor(). Commands ramp through a leaky integrator (tau = 0.45 s,
 * Shepherd-style) instead of stepping; the failsafe stops hard, skipping the
 * ramp. "motor_selftest" re-measures every drive option if that ever needs
 * checking again (streams "selftest_telemetry"; the periodic dashboard
 * stream is "motor_telemetry").
 */

#include <Arduino_LED_Matrix.h>
#include <Arduino_Modulino.h>
#include <Arduino_RouterBridge.h>

Arduino_LED_Matrix matrix;
ModulinoDistance distance;  // VL53L4CD/VL53L4ED time-of-flight sensor
ModulinoMotors motors;      // MAX22211 dual H-bridge; motor across 1A <-> 2A
ModulinoVibro vibro;        // haptic vibration motor

// Wheel speed 1..5 as a percentage of full scale. Starts high enough that a
// loaded motor actually turns instead of just buzzing.
static const uint8_t SPEED_PERCENT[5] = {30, 45, 60, 80, 100};

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

// Leaky-integrator drive (Shepherd's ESP32 pattern): the wheel ramps toward
// the commanded target instead of stepping to it. dS/dt = target - S/tau,
// output = S/tau — so a reversal glides through zero and reaching full scale
// from rest takes ~3*tau (~1.4 s). The failsafe bypasses the ramp (hard stop).
constexpr unsigned long MOTOR_UPDATE_MS = 25;
constexpr float MOTOR_TAU_S = 0.45f;

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
volatile int wheelSpeed = 5;  // 1 (slowest) .. 5 (full scale)
volatile bool dirty = true;
volatile int selftestPercent = 0;   // > 0 asks loop() to run the self-test
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
static int appliedSpeed = -99;

// Leaky-integrator drive state.
static float motorState = 0.0f;       // integrator state (raw-speed * seconds)
static int16_t lastWrittenRaw = 0;    // last raw value written to the module
static bool motorSettled = true;      // final stop write already done
static bool failsafeLatched = false;  // hard stop on timeout issued once
static unsigned long lastMotorStepMs = 0;

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

// Animation step delay: higher speed spins faster.
static uint16_t stepIntervalMs(int speed) {
  switch (speed) {
    case 1:  return 220;
    case 2:  return 150;
    case 3:  return 100;
    case 4:  return 70;
    default: return 45;
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

// Convert a wheel speed of 1..5 into a raw signed H-bridge value.
static int16_t rawForSpeed(int speed) {
  uint8_t percent = SPEED_PERCENT[constrain(speed, 1, 5) - 1];
  return (int16_t)((int32_t)percent * ModulinoMotors::MAX_SPEED / 100);
}

// Signed duty percentage the H-bridge is applying RIGHT NOW — the live
// integrator output, not the command target, so the dashboard voltage graph
// shows the actual ramps.
static int appliedDutyPct() {
  return (int)lroundf(100.0f * (float)lastWrittenRaw
                      / (float)ModulinoMotors::MAX_SPEED);
}

// Commanded target for the integrator, as a signed raw H-bridge value.
static float targetRaw() {
  if (appliedDir == 0 || appliedDir == -99 || appliedSpeed < 1) {
    return 0.0f;
  }
  return (float)appliedDir * (float)rawForSpeed(appliedSpeed);
}

// One integrator step. The motor bridges terminal 1A (channel A) and 2A
// (channel B), so the channels are driven in opposite phase:
// setDcSpeedRaw(+out, -out). Writes go to the module only when the output
// moved ~1% of full scale (or for the final snap to zero), keeping the I2C
// traffic modest at 40 Hz.
static void stepMotor(unsigned long now) {
  if (now - lastMotorStepMs < MOTOR_UPDATE_MS) {
    return;
  }
  float dt = (float)(now - lastMotorStepMs) / 1000.0f;
  if (dt > 0.1f) {
    dt = 0.1f;  // clamp after blocking stretches (self-test) so state can't jump
  }
  lastMotorStepMs = now;

  float target = targetRaw();
  motorState += dt * (target - motorState / MOTOR_TAU_S);

  float outF = motorState / MOTOR_TAU_S;
  const float lim = (float)ModulinoMotors::MAX_SPEED;
  if (outF > lim) {
    outF = lim;
  }
  if (outF < -lim) {
    outF = -lim;
  }
  int16_t out = (int16_t)outF;

  const int deadband = ModulinoMotors::MAX_SPEED / 100;  // ~1% of full scale
  if (target == 0.0f && out > -deadband && out < deadband) {
    if (!motorSettled) {
      motors.stop();
      motorState = 0.0f;
      lastWrittenRaw = 0;
      motorSettled = true;
    }
    return;
  }
  int delta = (int)out - (int)lastWrittenRaw;
  if (delta < 0) {
    delta = -delta;
  }
  if (delta >= deadband || motorSettled) {
    if (motors.setDcSpeedRaw(out, (int16_t)-out)) {
      lastWrittenRaw = out;
      motorSettled = false;
    }
  }
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
static void runSelftest(int percent) {
  if (!motorReady) {
    Bridge.notify("selftest_telemetry", String("no modulino found"),
                  -1.0f, -1.0f, String("?"), false);
    return;
  }

  int16_t raw = (int16_t)((int32_t)constrain(percent, 10, 100)
                          * ModulinoMotors::MAX_SPEED / 100);

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

  // Ramp back to the current target from rest rather than jumping.
  motorState = 0.0f;
  lastWrittenRaw = 0;
  motorSettled = false;
  lastMotorStepMs = millis();
}

static bool beginMotors() {
  if (!motors.begin()) {
    return false;
  }
  motors.setStepperModeEnabled(false);  // DC, not stepper
  motors.setDecay(ModulinoMotors::DecayMode::SLOW);
  motors.stop();
  // Force the next loop() pass to re-apply and re-report the wheel state,
  // and restart the ramp from rest.
  appliedDir = -99;
  appliedSpeed = -99;
  motorState = 0.0f;
  lastWrittenRaw = 0;
  motorSettled = true;
  return true;
}

// Called from Python: Bridge.call("set_wheel", dir, speed).
//
// This runs on the bridge thread, in the middle of an RPC request, so it only
// touches state. Logging here would be a nested RPC (Monitor.write() calls
// back over the same bridge) and driving hardware would stall the request —
// both are handled from loop() instead.
void set_wheel(int dir, int speed) {
  int d = (dir > 0) ? 1 : ((dir < 0) ? -1 : 0);
  int s = constrain(speed, 1, 5);
  // The Linux side re-sends this 4x/s as a heartbeat; only redraw on change.
  if (d != wheelDir || s != wheelSpeed) {
    dirty = true;
  }
  wheelDir = d;
  wheelSpeed = s;
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

// Called from Python: Bridge.call("motor_selftest", percent). Same rule as
// set_wheel — only set a flag; loop() does the blocking work.
void motor_selftest(int percent) {
  selftestPercent = constrain(percent, 10, 100);
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

  if (selftestPercent > 0) {
    int percent = selftestPercent;
    selftestPercent = 0;
    runSelftest(percent);
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
  // The wheel stop here is HARD (no ramp-down) — safety first.
  int effDir = wheelDir;
  int effSpeed = wheelSpeed;
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
      motorState = 0.0f;
      lastWrittenRaw = 0;
      motorSettled = true;
    }
  } else {
    failsafeLatched = false;
  }

  // React to a new command: record the ramp target, tell Python what got
  // applied, and log. All of it outside the bridge handler, where blocking and
  // nested RPCs are safe. The integrator below does the actual driving.
  if (effDir != appliedDir || effSpeed != appliedSpeed) {
    appliedDir = effDir;
    appliedSpeed = effSpeed;

    // Fire-and-forget, so a Python side that is not listening cannot stall us.
    // motorReady rides along so the app can flag a missing Modulino.
    Bridge.notify("wheel_applied", effDir, effSpeed, motorReady);

    Monitor.print("[wheel] dir=");
    Monitor.print(effDir);
    Monitor.print(" speed=");
    Monitor.println(effSpeed);
    dirty = true;
  }

  // Ramp the H-bridge toward the commanded target (leaky integrator).
  if (motorReady && !failsafe) {
    stepMotor(now);
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
      && (now - lastStep) >= stepIntervalMs(appliedSpeed)) {
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
