/*
 * qhackGPS guidance receiver
 * --------------------------
 * Receives guidance frames from the qhackGPS Android app over an HC-05/HC-06
 * Bluetooth SPP module and drives a motor.
 *
 * Frame format (ASCII, newline-terminated, sent ~5x per second):
 *
 *   QG,<dir>,<deltaDeg>,<distanceM>,<headingDeg>,<bearingDeg>,<aligned>,<obst>,<obstMM>
 *
 *   dir       S = straight (pointing the right way)  |  L = turn left
 *             R = turn right                          |  N = no destination set
 *   deltaDeg  0..180, how many degrees off you are pointing
 *   distanceM straight-line meters to destination, -1 if none
 *   headingDeg / bearingDeg  0..359 true north, -1 if unknown
 *   aligned   1 = green light, 0 = not aligned
 *   obst      1 while the smart cane reports an object in the way (the app has
 *             already turned dir/deltaDeg into the avoidance turn)
 *   obstMM    cane distance to that object in mm, -1 if unknown
 *
 * Older 6-field frames parse fine too (obst/obstMM just stay 0/-1).
 *
 * Example: "QG,L,37,171,147,183,0" -> turn left 37 degrees, 171 m to go.
 *
 * Failsafe: the app streams continuously, so if no frame arrives for 1 second
 * (link lost, app closed), the motor centers/stops and the LED turns off.
 *
 * Wiring (Uno/Nano):
 *   HC-05 VCC -> 5V, GND -> GND
 *   HC-05 TXD -> D10 (Arduino RX)
 *   HC-05 RXD <- D11 (Arduino TX) through a 1k/2k voltage divider (HC-05 RX is 3.3V!)
 *   Servo signal -> D9 (example actuator; adapt applyGuidance() for your motor)
 */

#include <SoftwareSerial.h>
#include <Servo.h>

SoftwareSerial bt(10, 11);  // RX, TX
Servo steer;

char buf[48];
uint8_t len = 0;
unsigned long lastFrameMs = 0;

void setup() {
  Serial.begin(9600);   // USB debug echo
  bt.begin(9600);       // HC-05/HC-06 default baud
  steer.attach(9);
  pinMode(LED_BUILTIN, OUTPUT);
  failsafe();
}

void loop() {
  while (bt.available()) {
    char c = bt.read();
    if (c == '\n' || c == '\r') {
      if (len > 0) {
        buf[len] = '\0';
        handleLine(buf);
        len = 0;
      }
    } else if (len < sizeof(buf) - 1) {
      buf[len++] = c;
    } else {
      len = 0;  // line overflow: drop it
    }
  }
  if (millis() - lastFrameMs > 1000) {
    failsafe();
  }
}

void handleLine(char* line) {
  if (strncmp(line, "QG,", 3) != 0) return;
  char dir = 'N';
  int delta = 0, dist = -1, heading = -1, bearing = -1, aligned = 0;
  int obst = 0;
  long obstMm = -1;
  int n = sscanf(line, "QG,%c,%d,%d,%d,%d,%d,%d,%ld",
                 &dir, &delta, &dist, &heading, &bearing, &aligned, &obst, &obstMm);
  if (n < 2) return;
  lastFrameMs = millis();
  Serial.println(line);  // debug echo over USB
  applyGuidance(dir, delta, aligned != 0, obst != 0);
}

/*
 * >>> Adapt this to your motor. <<<
 * Example below: a steering servo that deflects proportionally to how far off
 * you are pointing, plus the built-in LED as the "green light".
 *
 * When `obstacle` is true the direction already encodes the dodge around the
 * object the cane sees — treat it with priority (e.g. stronger haptics).
 *
 * Vibration-motor idea: buzz left/right motors with intensity ~ delta.
 * DC motor idea: map dir/delta to H-bridge direction + PWM duty.
 */
void applyGuidance(char dir, int delta, bool aligned, bool obstacle) {
  digitalWrite(LED_BUILTIN, aligned ? HIGH : LOW);
  int amount = (constrain(delta, 0, 90) * 2) / 3;  // 0..60 degrees of throw
  if (obstacle) amount = 60;                       // hard dodge cue
  if (dir == 'L') {
    steer.write(90 - amount);
  } else if (dir == 'R') {
    steer.write(90 + amount);
  } else {
    steer.write(90);  // straight, or no destination
  }
}

void failsafe() {
  digitalWrite(LED_BUILTIN, LOW);
  steer.write(90);
}
