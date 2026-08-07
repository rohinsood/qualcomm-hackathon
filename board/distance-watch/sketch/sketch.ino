#include <Arduino_Modulino.h>
#include <Arduino_RouterBridge.h>

// Modulino Distance — VL53L4CD/VL53L4ED time-of-flight sensor on the Qwiic chain
ModulinoDistance distance;

bool sensorReady = false;
float lastMm = NAN;
unsigned long lastStatusMs = 0;
unsigned long lastRetryMs = 0;

void setup() {
  Serial.begin(115200);
  Bridge.begin();

  // Initialize Modulino I2C communication (Qwiic connector is on Wire1)
  Modulino.begin(Wire1);
  sensorReady = distance.begin();
  Serial.print("Modulino Distance init: ");
  Serial.println(sensorReady ? "OK" : "NOT FOUND");
}

void loop() {
  unsigned long now = millis();

  // If the sensor was not found, look for it again every 3 s (supports hot-plug)
  if (!sensorReady && (now - lastRetryMs >= 3000)) {
    lastRetryMs = now;
    sensorReady = distance.begin();
    if (sensorReady) {
      Serial.println("Modulino Distance connected");
    }
  }

  // available() is true only when a NEW valid measurement arrived;
  // no target in range produces no data at all.
  if (sensorReady && distance.available()) {
    lastMm = distance.get();
    Bridge.notify("distance_reading", lastMm);
  }

  // 1 Hz heartbeat so the Linux side can tell "no object" apart from "no sensor",
  // plus a raw-value debug line on the serial monitor
  if (now - lastStatusMs >= 1000) {
    lastStatusMs = now;
    Bridge.notify("sensor_status", sensorReady);
    if (sensorReady) {
      Serial.print("distance mm: ");
      Serial.println(lastMm);
    }
  }

  delay(20);
}
