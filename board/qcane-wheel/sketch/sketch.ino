/*
 * qcane-wheel — MCU side.
 *
 * Spins a wheel on a Modulino Motors board (MAX22211 dual H-bridge, on Qwiic /
 * Wire1 at I2C 0x48). Direction and speed arrive from the Linux side over the
 * Router Bridge via the "set_wheel" method. The 13x8 LED matrix mirrors what
 * the motor is doing, as a status display.
 *
 * Wiring: the motor sits across terminals 1A and 2A, which is one half-bridge
 * from channel A and one from channel B rather than a single channel's own
 * pair, so the two channels have to be driven in opposite phase — see
 * driveMotor(). "motor_selftest" re-measures every option if that ever needs
 * checking again.
 */

#include <Arduino_LED_Matrix.h>
#include <Arduino_Modulino.h>
#include <Arduino_RouterBridge.h>

Arduino_LED_Matrix matrix;
ModulinoMotors motors;

// True once the Modulino has answered on the Qwiic bus.
static bool motorReady = false;

// Wheel speed 1..5 as a percentage of full scale. Starts high enough that a
// loaded motor actually turns instead of just buzzing.
static const uint8_t SPEED_PERCENT[5] = {30, 45, 60, 80, 100};

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

// Bridge handlers run on the bridge thread, loop() reads them — keep volatile.
volatile int wheelDir = 0;    // -1 = left, 0 = stopped, +1 = right
volatile int wheelSpeed = 3;  // 1 (slowest) .. 5 (fastest)
volatile bool dirty = true;
volatile int selftestPercent = 0;  // > 0 asks loop() to run the self-test

static uint8_t frame[MATRIX_PIXELS];
static int head = 0;
static unsigned long lastStep = 0;

// Last command loop() acted on, so transitions are handled exactly once.
// Seeded to an impossible value so the current state is reported once at boot.
static int appliedDir = -99;
static int appliedSpeed = -99;

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

  int dir = wheelDir;

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

// Drive the wheel. Called from loop() once per command, not per animation
// frame, so the I2C traffic stays proportional to commands rather than frames.
static void driveMotor(int dir, int speed) {
  if (!motorReady) {
    return;
  }
  if (dir == 0) {
    motors.stop();
    return;
  }

  // The motor bridges terminal 1A (channel A) and 2A (channel B), so it only
  // sees a voltage when the two channels are pushed in opposite directions.
  int16_t raw = rawForSpeed(speed);
  motors.setDcSpeedRaw(dir > 0 ? raw : (int16_t)-raw,
                       dir > 0 ? (int16_t)-raw : raw);
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
  Bridge.notify("motor_telemetry", String(stage), milliampsA, milliampsB,
                mode, busy);
}

// Try every sensible way of driving the two channels and report the current
// each one draws. Runs from loop(), so it is free to block.
static void runSelftest(int percent) {
  if (!motorReady) {
    Bridge.notify("motor_telemetry", String("no modulino found"), -1.0f, -1.0f);
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

  // Put the wheel back where the app last left it.
  driveMotor(appliedDir, appliedSpeed);
}

// Called from Python: Bridge.call("set_wheel", dir, speed).
//
// This runs on the bridge thread, in the middle of an RPC request, so it only
// touches state. Logging here would be a nested RPC (Monitor.write() calls
// back over the same bridge) and driving hardware would stall the request —
// both are handled from loop() instead.
void set_wheel(int dir, int speed) {
  wheelDir = (dir > 0) ? 1 : ((dir < 0) ? -1 : 0);
  wheelSpeed = constrain(speed, 1, 5);
  dirty = true;
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

  // Qwiic bus. On UNO Q, Modulino.begin() defaults to Wire1.
  Modulino.begin();
  motorReady = motors.begin();
  if (motorReady) {
    motors.setStepperModeEnabled(false);  // DC, not stepper
    motors.setDecay(ModulinoMotors::DecayMode::SLOW);
    motors.stop();
  }

  Bridge.begin();
  Monitor.begin();

  bool ok = Bridge.provide("set_wheel", set_wheel);
  ok = Bridge.provide("motor_selftest", motor_selftest) && ok;
  Monitor.print("[qcane-wheel] sketch ready, bridge=");
  Monitor.print(ok ? "yes" : "no");
  Monitor.print(" modulino=");
  Monitor.println(motorReady ? "found" : "MISSING");

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

  // React to a new command: drive the motor, tell Python what actually got
  // applied, and log. All of it outside the bridge handler, where blocking and
  // nested RPCs are safe.
  int dir = wheelDir;
  int speed = wheelSpeed;
  if (dir != appliedDir || speed != appliedSpeed) {
    appliedDir = dir;
    appliedSpeed = speed;

    driveMotor(dir, speed);

    // Fire-and-forget, so a Python side that is not listening cannot stall us.
    // motorReady rides along so the app can flag a missing Modulino.
    Bridge.notify("wheel_applied", dir, speed, motorReady);

    Monitor.print("[wheel] dir=");
    Monitor.print(dir);
    Monitor.print(" speed=");
    Monitor.println(speed);
  }

  if (wheelDir != 0 && (now - lastStep) >= stepIntervalMs(wheelSpeed)) {
    lastStep = now;
    head = ((head + wheelDir) % RING_LEN + RING_LEN) % RING_LEN;
    dirty = true;
  }

  if (dirty) {
    dirty = false;
    renderWheel();
    matrix.draw(frame);
  }

  delay(5);
}
