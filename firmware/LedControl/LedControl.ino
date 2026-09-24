/*
 * FaceLock - ESP32 LED Control
 * IT-ELDRIOD1 | Facial Recognition Locker System
 *
 * Reads one key in the Realtime Database and drives an LED from it. The app's
 * "LED control" screen writes that key; this sketch follows it.
 *
 *     phone  ->  device/led = true/false  ->  ESP32  ->  GPIO 23  ->  LED
 *
 * Neither end talks to the other directly, so the board does not have to be on
 * the same network as the phone, and nothing here needs Cloud Functions or a
 * paid Firebase plan.
 *
 * REQUIRES: "Firebase Arduino Client Library for ESP8266 and ESP32" by Mobizt
 *   Arduino IDE -> Tools -> Manage Libraries -> search "Firebase ESP Client"
 *
 * Board:      "ESP32 Dev Module"
 * Partition:  Huge APP (3MB No OTA)  - the default is too small for TLS
 *
 * WIRING
 *   GPIO 23  ->  LED long leg (anode)
 *   LED short leg (cathode)  ->  220 ohm resistor  ->  GND
 */

#include <WiFi.h>
#include <Firebase_ESP_Client.h>

// ----------------------- CONFIGURATION -----------------------
#define WIFI_SSID     "8 Pro"
#define WIFI_PASSWORD "REPLACE_ME"

#define DATABASE_URL "facelock-eldroid-default-rtdb.asia-southeast1.firebasedatabase.app"

// Firebase console -> Project settings -> Service accounts -> Database secrets.
// This bypasses the database rules, which is how the board reads without
// signing in - so it is a full-access password. Paste it here on your own
// machine and do NOT commit the filled-in file.
#define DATABASE_SECRET "PASTE_YOUR_DATABASE_SECRET_HERE"

#define LED_PIN 23

// Some boards light the pin when it is driven LOW. If the serial log says ON
// while the LED is dark, flip this rather than rewiring.
const bool LED_ACTIVE_LOW = false;

const unsigned long POLL_INTERVAL_MS = 1000;

// ----------------------- STATE -----------------------
FirebaseData fbdo;
FirebaseAuth auth;
FirebaseConfig config;

unsigned long lastPollMs = 0;
int lastShown = -1;   // -1 = nothing read yet, so the first result always prints

// ----------------------- SETUP -----------------------
void setup() {
  Serial.begin(115200);
  delay(500);
  Serial.println("\n\n=== FaceLock LED control ===");

  pinMode(LED_PIN, OUTPUT);
  applyLed(false);

  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  Serial.print("Connecting to Wi-Fi");
  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }
  Serial.println();
  Serial.print("Connected with IP: ");
  Serial.println(WiFi.localIP());

  config.database_url = DATABASE_URL;
  config.signer.tokens.legacy_token = DATABASE_SECRET;

  Firebase.reconnectWiFi(true);

  // 1024 is not enough room for Firebase's TLS handshake - the connection
  // fails before any read happens, and every getBool() comes back with a
  // handshake error. 4096 on the receive side is the working minimum.
  fbdo.setBSSLBufferSize(4096, 1024);
  fbdo.setResponseSize(2048);

  Firebase.begin(&config, &auth);
}

// ----------------------- LOOP -----------------------
void loop() {
  // Firebase.ready() pumps the library's own token handling. Calling into
  // RTDB before it is ready fails for reasons that have nothing to do with
  // the network, so the poll waits for it.
  if (!Firebase.ready()) return;

  if (millis() - lastPollMs < POLL_INTERVAL_MS) return;
  lastPollMs = millis();

  if (Firebase.RTDB.getBool(&fbdo, "/device/led")) {
    bool ledOn = fbdo.to<bool>();
    applyLed(ledOn);
  } else {
    Serial.println("Read failed: " + fbdo.errorReason());
  }
}

// ----------------------- OUTPUT -----------------------
void applyLed(bool on) {
  digitalWrite(LED_PIN, (on != LED_ACTIVE_LOW) ? HIGH : LOW);

  // Only speak up when something actually changed - a line every second
  // buries the errors that matter.
  if (lastShown != (int)on) {
    lastShown = (int)on;
    Serial.println(on ? "device/led: true  -> LED ON" : "device/led: false -> LED OFF");
  }
}
