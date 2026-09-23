/*
 * FaceLock - ESP32 LED Control
 * IT-ELDRIOD1 | Facial Recognition Locker System
 *
 * Listens to one key in the Realtime Database and drives an LED from it.
 * The phone's "LED control" screen writes that key; this sketch reads it.
 *
 *     phone  ->  device/led = true/false  ->  ESP32  ->  GPIO  ->  LED
 *
 * Neither end ever talks to the other directly, so the board does not have to
 * be on the same network as the phone, and nothing here needs Cloud Functions
 * or a paid Firebase plan.
 *
 * NO EXTRA LIBRARY IS NEEDED. This uses the Realtime Database REST API in
 * streaming mode (server-sent events): one long-lived HTTPS connection that
 * the server pushes to, so the LED reacts in well under a second and the
 * board is not hammering the network with polls.
 *
 * Board:      "ESP32 Dev Module"  (or "AI Thinker ESP32-CAM")
 * Partition:  Huge APP (3MB No OTA)  - the default is too small for TLS
 *
 * WIRING
 *   GPIO 2  ->  LED long leg (anode)
 *   LED short leg (cathode)  ->  220 ohm resistor  ->  GND
 *   On most dev boards GPIO 2 is also the on-board blue LED, so the sketch
 *   can be proved with nothing wired up at all.
 */

#include <WiFi.h>
#include <WiFiClientSecure.h>

// ----------------------- CONFIGURATION -----------------------
const char* WIFI_SSID     = "Kythlog";
const char* WIFI_PASSWORD = "12345678";

// Host only - no scheme, no trailing slash.
const char* RTDB_HOST = "facelock-eldroid-default-rtdb.asia-southeast1.firebasedatabase.app";

// The key the app writes. Must match FirebaseRefs.RTDB_LED_PATH.
const char* LED_PATH = "/device/led";

// Firebase console -> Project settings -> Service accounts -> Database secrets.
// This bypasses the database rules, which is why the device can read without
// signing in. Treat it as a password: anyone who can read the flash on this
// board can read this string, so regenerate it before the project is handed in.
const char* DATABASE_SECRET = "PASTE_YOUR_DATABASE_SECRET_HERE";

const int LED_PIN = 2;

// Most dev boards drive the on-board LED high-active. If your LED is lit when
// the app says OFF, flip this to true rather than rewiring.
const bool LED_ACTIVE_LOW = false;

const unsigned long WIFI_TIMEOUT_MS   = 20000;
// Firebase sends a keep-alive roughly every 30s; if nothing arrives for well
// over that, the connection is dead even though the socket still looks open.
const unsigned long STREAM_TIMEOUT_MS = 70000;

// ----------------------- STATE -----------------------
WiFiClientSecure client;
unsigned long lastEventMs = 0;
bool ledState = false;

// ----------------------- SETUP -----------------------
void setup() {
  Serial.begin(115200);
  delay(500);
  Serial.println("\n\n=== FaceLock LED control ===");

  pinMode(LED_PIN, OUTPUT);
  applyLed(false);

  connectWiFi();

  // Skipping certificate validation keeps the sketch short and the partition
  // small. Fine for a campus demo; a shipped product would pin the root CA.
  client.setInsecure();
}

// ----------------------- LOOP -----------------------
void loop() {
  if (WiFi.status() != WL_CONNECTED) {
    Serial.println("[wifi] lost, reconnecting");
    connectWiFi();
    return;
  }

  if (!client.connected()) {
    if (!openStream()) {
      delay(3000);
      return;
    }
  }

  readStream();

  // A silent stream is a stale stream. Drop it and let the next loop reopen.
  if (millis() - lastEventMs > STREAM_TIMEOUT_MS) {
    Serial.println("[stream] silent too long, reconnecting");
    client.stop();
  }
}

// ----------------------- WIFI -----------------------
void connectWiFi() {
  Serial.printf("[wifi] connecting to %s", WIFI_SSID);
  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);

  unsigned long start = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - start < WIFI_TIMEOUT_MS) {
    delay(400);
    Serial.print(".");
  }
  Serial.println();

  if (WiFi.status() == WL_CONNECTED) {
    Serial.print("[wifi] connected, IP ");
    Serial.println(WiFi.localIP());
  } else {
    Serial.println("[wifi] FAILED - check the SSID and password (2.4GHz only)");
  }
}

// ----------------------- STREAM -----------------------
bool openStream() {
  Serial.println("[stream] opening");

  if (!client.connect(RTDB_HOST, 443)) {
    Serial.println("[stream] TLS connect failed");
    return false;
  }

  // text/event-stream is what turns a plain REST read into a live subscription.
  client.printf("GET %s.json?auth=%s HTTP/1.1\r\n", LED_PATH, DATABASE_SECRET);
  client.printf("Host: %s\r\n", RTDB_HOST);
  client.print("Accept: text/event-stream\r\n");
  client.print("Connection: keep-alive\r\n\r\n");

  // Firebase answers a stream request with a 307 to the regional host. Follow
  // it silently - it is normal, not an error.
  String status = client.readStringUntil('\n');
  Serial.print("[stream] ");
  Serial.println(status);

  lastEventMs = millis();
  return true;
}

void readStream() {
  while (client.available()) {
    String line = client.readStringUntil('\n');
    line.trim();
    if (line.length() == 0) continue;

    lastEventMs = millis();

    if (!line.startsWith("data:")) continue;      // event:, keep-alive, headers

    String payload = line.substring(5);
    payload.trim();
    if (payload == "null") { applyLed(false); continue; }

    // The node is a leaf, so every push arrives as {"path":"/","data":<value>}.
    int at = payload.indexOf("\"data\":");
    if (at < 0) continue;

    String value = payload.substring(at + 7);
    value.replace("}", "");
    value.replace("\"", "");
    value.trim();

    Serial.print("[stream] device/led = ");
    Serial.println(value);

    applyLed(value == "true" || value == "1" || value == "on");
  }
}

// ----------------------- OUTPUT -----------------------
void applyLed(bool on) {
  ledState = on;
  digitalWrite(LED_PIN, (on != LED_ACTIVE_LOW) ? HIGH : LOW);
  Serial.println(on ? "[led] ON" : "[led] OFF");
}
