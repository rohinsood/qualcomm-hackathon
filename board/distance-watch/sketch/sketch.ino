#include <Arduino_Modulino.h>
#include <Arduino_RouterBridge.h>

// Modulino daisy chain on the Qwiic connector (Wire1), in wiring order:
//   UNO Q -> Modulino Motors -> Modulino Vibro -> Modulino Distance
// (chain order does not matter on I2C; each node answers on its own address)
ModulinoDistance distance;  // VL53L4CD/VL53L4ED time-of-flight sensor
ModulinoMotors motors;      // MAX22211 dual H-bridge; DC motor on channel A (terminals 1A/2A)
ModulinoVibro vibro;        // haptic vibration motor

// Steering spin speed, percent of full scale.
constexpr int MOTOR_SPEED_PCT = 60;
constexpr int16_t MOTOR_RAW_SPEED =
    (int32_t)ModulinoMotors::MAX_SPEED * MOTOR_SPEED_PCT / 100;

// The Linux side re-sends the desired actuator state 4x/s; if that stream
// stops (app or bridge died), stop every output rather than run away.
constexpr unsigned long COMMAND_TIMEOUT_MS = 2000;

// Proximity alert rhythm: a VIBRO_PULSE_MS buzz every VIBRO_PERIOD_MS.
constexpr unsigned long VIBRO_PULSE_MS = 250;
constexpr unsigned long VIBRO_PERIOD_MS = 500;

constexpr unsigned long MODULE_RETRY_MS = 3000;
constexpr unsigned long TELEMETRY_MS = 500;

bool sensorReady = false;
bool motorsReady = false;
bool vibroReady = false;

float lastMm = NAN;
unsigned long lastStatusMs = 0;
unsigned long lastRetryMs = 0;
unsigned long lastTelemetryMs = 0;

// Desired actuator state; written by Bridge handlers, applied in loop().
volatile int desiredMotor = 0;  // -1 spin left, 0 stop, +1 spin right
volatile bool desiredVibro = false;
volatile unsigned long lastCommandMs = 0;
volatile int pendingPulseMs = 0;  // one-shot manual buzz from the dashboard

int appliedMotor = 0;
bool vibroActive = false;
unsigned long lastPulseMs = 0;

void onSetMotor(int dir) {
  desiredMotor = dir < 0 ? -1 : (dir > 0 ? 1 : 0);
  lastCommandMs = millis();
}

void onSetVibro(int on) {
  desiredVibro = (on != 0);
  lastCommandMs = millis();
}

void onVibroPulse(int ms) {
  if (ms < 50) {
    ms = 50;
  }
  if (ms > 3000) {
    ms = 3000;
  }
  pendingPulseMs = ms;
}

bool beginMotors() {
  if (!motors.begin()) {
    return false;
  }
  motors.setStepperModeEnabled(false);  // plain DC drive on channel A
  motors.setDecay(ModulinoMotors::DecayMode::SLOW);
  motors.stop();
  appliedMotor = 0;
  return true;
}

void setup() {
  Serial.begin(115200);
  Bridge.begin();

  // Commands pushed by the Linux side (phone over BLE, or dashboard buttons)
  Bridge.provide("set_motor", onSetMotor);
  Bridge.provide("set_vibro", onSetVibro);
  Bridge.provide("vibro_pulse", onVibroPulse);

  // Initialize Modulino I2C communication (Qwiic connector is on Wire1)
  Modulino.begin(Wire1);
  sensorReady = distance.begin();
  motorsReady = beginMotors();
  vibroReady = vibro.begin();
  Serial.print("Modulino init: distance=");
  Serial.print(sensorReady ? "OK" : "NOT FOUND");
  Serial.print(" motors=");
  Serial.print(motorsReady ? "OK" : "NOT FOUND");
  Serial.print(" vibro=");
  Serial.println(vibroReady ? "OK" : "NOT FOUND");
}

void loop() {
  unsigned long now = millis();

  // Look again every 3 s for any module that is missing (supports hot-plug)
  if ((!sensorReady || !motorsReady || !vibroReady) && (now - lastRetryMs >= MODULE_RETRY_MS)) {
    lastRetryMs = now;
    if (!sensorReady) {
      sensorReady = distance.begin();
      if (sensorReady) {
        Serial.println("Modulino Distance connected");
      }
    }
    if (!motorsReady) {
      motorsReady = beginMotors();
      if (motorsReady) {
        Serial.println("Modulino Motors connected");
      }
    }
    if (!vibroReady) {
      vibroReady = vibro.begin();
      if (vibroReady) {
        Serial.println("Modulino Vibro connected");
      }
    }
  }

  // available() is true only when a NEW valid measurement arrived;
  // no target in range produces no data at all.
  if (sensorReady && distance.available()) {
    lastMm = distance.get();
    Bridge.notify("distance_reading", lastMm);
  }

  // Failsafe: without fresh commands from the Linux side, stop everything.
  int motorTarget = desiredMotor;
  bool vibroTarget = desiredVibro;
  if (lastCommandMs == 0 || (now - lastCommandMs) > COMMAND_TIMEOUT_MS) {
    motorTarget = 0;
    vibroTarget = false;
  }

  // Steering motor (channel A, screw terminals 1A/2A). setDcSpeedRaw is one
  // atomic command carrying the signed speed, so direction changes are clean.
  if (motorsReady && motorTarget != appliedMotor) {
    if (motors.setDcSpeedRaw((int16_t)(motorTarget * MOTOR_RAW_SPEED), 0)) {
      appliedMotor = motorTarget;
    }
  }

  // Proximity haptics: rhythmic pulses while an object is inside the threshold.
  // Each pulse self-terminates after VIBRO_PULSE_MS, so a wedged loop cannot
  // leave the vibro buzzing.
  if (vibroReady) {
    if (vibroTarget) {
      if (!vibroActive || (now - lastPulseMs) >= VIBRO_PERIOD_MS) {
        vibro.on(VIBRO_PULSE_MS);
        lastPulseMs = now;
        vibroActive = true;
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
      Serial.print("manual vibro pulse ms: ");
      Serial.println(pulse);
    }
  } else {
    pendingPulseMs = 0;  // drop manual requests while the module is missing
  }

  // 2 Hz motor telemetry for the dashboard: current sense per channel (mA),
  // what the sketch is actually applying (direction + signed PWM duty), and
  // the driver busy flag. The firmware does not report VM voltage; the signed
  // duty is the applied output voltage as a fraction of VM.
  if (motorsReady && (now - lastTelemetryMs >= TELEMETRY_MS)) {
    lastTelemetryMs = now;
    if (motors.update()) {
      Bridge.notify("motor_telemetry",
                    motors.sensedCurrentA(),
                    motors.sensedCurrentB(),
                    appliedMotor,
                    appliedMotor * MOTOR_SPEED_PCT,
                    vibroActive ? 1 : 0,
                    motors.busy() ? 1 : 0);
    }
  }

  // 1 Hz heartbeats so the Linux side can tell "no object" apart from "no
  // sensor" and can show which actuators are attached, plus a raw-value debug
  // line on the serial monitor
  if (now - lastStatusMs >= 1000) {
    lastStatusMs = now;
    Bridge.notify("sensor_status", sensorReady);
    Bridge.notify("actuator_status", (motorsReady ? 1 : 0) | (vibroReady ? 2 : 0));
    if (sensorReady) {
      Serial.print("distance mm: ");
      Serial.print(lastMm);
      Serial.print("  motor: ");
      Serial.print(appliedMotor);
      Serial.print("  vibro: ");
      Serial.println(vibroTarget ? "on" : "off");
    }
  }

  delay(20);
}
