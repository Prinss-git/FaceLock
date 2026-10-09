/*
 * FaceLock - ESP32-CAM Firmware
 * IT-ELDRIOD1 | Facial Recognition Locker System
 *
 * Responsibilities:
 *   1. Detect a person approaching (PIR) and capture a face image.
 *   2. POST the image to the recognition server, which answers who it is.
 *      Up to 3 photos per attempt; one DENIED log only if none match.
 *   3. On a match, pulse the relay to open the solenoid lock.
 *   4. Write the attempt to access_logs itself (no Cloud Functions on Spark).
 *   5. Heartbeat lastSeenAt, so the app can show the locker as offline.
 *   6. Poll the locker document for admin remote-unlock requests.
 *
 * The board signs in to Firebase with its own email/password account. The
 * Firestore rules let that account touch only the locker named in its
 * devices/{uid} document, and only the fields below.
 *
 * REQUIRES: "Firebase Arduino Client Library for ESP8266 and ESP32" by Mobizt
 *   Arduino IDE -> Tools -> Manage Libraries -> search "Firebase ESP Client"
 *
 * Board: "AI Thinker ESP32-CAM"
 * Partition scheme: Huge APP (3MB No OTA) - the default is too small for TLS
 */

#include "esp_camera.h"
#include <WiFi.h>
#include <HTTPClient.h>
#include <ArduinoJson.h>
#include <Wire.h>
#include <LiquidCrystal_I2C.h>
#include <sys/time.h>
#include <Firebase_ESP_Client.h>
#include <addons/TokenHelper.h>

// Wi-Fi, the device sign-in and the locker ID live in secrets.h, which is
// gitignored. Copy secrets.example.h to secrets.h in this folder and fill it in.
#include "secrets.h"

// ----------------------- CONFIGURATION -----------------------
const char* LOCKER_ID     = SECRET_LOCKER_ID;
const char* PROJECT_ID    = SECRET_PROJECT_ID;
// Normally empty: the server publishes its address in config/recognizer and
// the board reads it, so a new laptop IP needs no re-flash. Set it in
// secrets.h only to force a fixed address.
const char* RECOGNIZE_URL_OVERRIDE = SECRET_RECOGNIZE_URL;

// ----------------------- PIN MAP (AI Thinker) -----------------------
#define PWDN_GPIO_NUM     32
#define RESET_GPIO_NUM    -1
#define XCLK_GPIO_NUM      0
#define SIOD_GPIO_NUM     26
#define SIOC_GPIO_NUM     27
#define Y9_GPIO_NUM       35
#define Y8_GPIO_NUM       34
#define Y7_GPIO_NUM       39
#define Y6_GPIO_NUM       36
#define Y5_GPIO_NUM       21
#define Y4_GPIO_NUM       19
#define Y3_GPIO_NUM       18
#define Y2_GPIO_NUM        5
#define VSYNC_GPIO_NUM    25
#define HREF_GPIO_NUM     23
#define PCLK_GPIO_NUM     22

// Peripherals
// GPIO 12 is a boot strapping pin: if the relay module holds it HIGH at
// power-on, the board will not start. If that happens, move the relay wire
// to GPIO 2 and change this number (see WIRING.md).
#define RELAY_PIN         12   // Drives the 5V relay -> 12V solenoid lock
#define PIR_PIN           13   // Motion sensor: wakes the capture routine
#define STATUS_LED        33   // Onboard red LED (active LOW)
#define FLASH_LED          4   // Onboard white flash LED, lights the face

const unsigned long UNLOCK_MS          = 3000;    // How long the lock stays open
const unsigned long COOLDOWN_MS        = 4000;    // Debounce between attempts
// One read every 10 s is ~8.6k reads a day per locker; Spark allows 50k.
const unsigned long POLL_INTERVAL      = 10000;   // Remote-unlock poll cadence
// The app calls a board offline after two missed beats (Locker.DEVICE_TIMEOUT_MS).
const unsigned long HEARTBEAT_INTERVAL = 60000;
const unsigned long WIFI_RETRY_MS      = 10000;
const unsigned long URL_RETRY_MS       = 30000;   // Re-read config/recognizer
const int           MAX_TRIES          = 3;       // Photos per attempt

LiquidCrystal_I2C lcd(0x27, 16, 2);
FirebaseData fbdo;
FirebaseAuth auth;
FirebaseConfig config;

String lockerDoc;   // "lockers/M-001"
unsigned long lastAttempt   = 0;
unsigned long lastPoll      = 0;
unsigned long lastHeartbeat = 0;
unsigned long lastWifiTry   = 0;
bool heartbeatSent          = false;
unsigned long lastUrlTry    = 0;
String recognizeUrl;        // From the override, or config/recognizer

// Above setup() on purpose: the Arduino builder inserts function prototypes
// before the first function, so any type they mention must already exist.
/** What the server said about one photo. */
struct Verdict {
  bool ok;            // false = network/server trouble, not an answer
  bool granted;
  bool noFace;        // nobody in the frame: a retake, not a mismatch
  String uid;
  String name;
  bool hasScore;
  float confidence;
};

// ----------------------- SETUP -----------------------
void setup() {
  Serial.begin(115200);
  Serial.setDebugOutput(false);

  pinMode(RELAY_PIN, OUTPUT);
  digitalWrite(RELAY_PIN, LOW);       // Fail-secure: locked by default
  pinMode(PIR_PIN, INPUT);
  pinMode(STATUS_LED, OUTPUT);
  digitalWrite(STATUS_LED, HIGH);
  pinMode(FLASH_LED, OUTPUT);
  digitalWrite(FLASH_LED, LOW);

  Wire.begin(14, 15);                 // SDA, SCL for the I2C LCD
  lcd.init();
  lcd.backlight();
  showMessage("FaceLock", "Starting...");

  if (!initCamera()) {
    showMessage("Camera FAIL", "Check wiring");
    while (true) delay(1000);
  }

  lockerDoc = String("lockers/") + LOCKER_ID;
  recognizeUrl = RECOGNIZE_URL_OVERRIDE;

  connectWiFi();

  // Log timestamps are epoch millis, so the clock has to be real.
  configTime(0, 0, "pool.ntp.org", "time.google.com");

  config.api_key = SECRET_API_KEY;
  auth.user.email = SECRET_DEVICE_EMAIL;
  auth.user.password = SECRET_DEVICE_PASSWORD;
  config.token_status_callback = tokenStatusCallback;
  Firebase.reconnectWiFi(true);
  // The default 1024-byte receive buffer is too small for Firebase's TLS
  // handshake; 4096 is the working minimum.
  fbdo.setBSSLBufferSize(4096, 1024);
  fbdo.setResponseSize(2048);
  Firebase.begin(&config, &auth);

  showMessage("FaceLock Ready", "Approach locker");
}

// ----------------------- MAIN LOOP -----------------------
void loop() {
  keepWiFi();

  // 1) Motion-triggered recognition
  if (digitalRead(PIR_PIN) == HIGH && millis() - lastAttempt > COOLDOWN_MS) {
    lastAttempt = millis();
    handleRecognition();
  }

  // Firebase.ready() also refreshes the sign-in token, so it runs every pass.
  if (Firebase.ready()) {
    // 2) Heartbeat, first one as soon as the clock is set
    if (clockIsSet() && (!heartbeatSent || millis() - lastHeartbeat > HEARTBEAT_INTERVAL)) {
      lastHeartbeat = millis();
      heartbeatSent = sendHeartbeat();
    }

    // Find the recognition server once signed in, and keep looking until found
    if (recognizeUrl.length() == 0 && millis() - lastUrlTry > URL_RETRY_MS) {
      loadRecognizerUrl();
    }

    // 3) Admin remote unlock, polled from Firestore
    if (millis() - lastPoll > POLL_INTERVAL) {
      lastPoll = millis();
      checkRemoteUnlock();
    }
  }

  delay(100);
}

// ----------------------- CAMERA -----------------------
bool initCamera() {
  camera_config_t config;
  config.ledc_channel = LEDC_CHANNEL_0;
  config.ledc_timer   = LEDC_TIMER_0;
  config.pin_d0 = Y2_GPIO_NUM;   config.pin_d1 = Y3_GPIO_NUM;
  config.pin_d2 = Y4_GPIO_NUM;   config.pin_d3 = Y5_GPIO_NUM;
  config.pin_d4 = Y6_GPIO_NUM;   config.pin_d5 = Y7_GPIO_NUM;
  config.pin_d6 = Y8_GPIO_NUM;   config.pin_d7 = Y9_GPIO_NUM;
  config.pin_xclk = XCLK_GPIO_NUM;
  config.pin_pclk = PCLK_GPIO_NUM;
  config.pin_vsync = VSYNC_GPIO_NUM;
  config.pin_href = HREF_GPIO_NUM;
  config.pin_sccb_sda = SIOD_GPIO_NUM;
  config.pin_sccb_scl = SIOC_GPIO_NUM;
  config.pin_pwdn = PWDN_GPIO_NUM;
  config.pin_reset = RESET_GPIO_NUM;
  config.xclk_freq_hz = 20000000;
  config.pixel_format = PIXFORMAT_JPEG;

  // PSRAM gives us a larger frame and a second buffer for smoother capture.
  if (psramFound()) {
    config.frame_size = FRAMESIZE_VGA;   // 640x480 is enough for face matching
    config.jpeg_quality = 12;
    config.fb_count = 2;
  } else {
    config.frame_size = FRAMESIZE_QVGA;
    config.jpeg_quality = 15;
    config.fb_count = 1;
  }

  esp_err_t err = esp_camera_init(&config);
  if (err != ESP_OK) {
    Serial.printf("Camera init failed: 0x%x\n", err);
    return false;
  }
  return true;
}

// ----------------------- WIFI -----------------------
void connectWiFi() {
  showMessage("Connecting to", "WiFi...");
  WiFi.mode(WIFI_STA);
  WiFi.begin(SECRET_WIFI_SSID, SECRET_WIFI_PASSWORD);

  unsigned long start = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - start < 20000) {
    delay(400);
    Serial.print(".");
  }

  if (WiFi.status() == WL_CONNECTED) {
    Serial.println("\nWiFi connected: " + WiFi.localIP().toString());
  } else {
    // Carry on: keepWiFi() retries from the loop, and the lock stays secured.
    showMessage("WiFi FAILED", "Retrying...");
    delay(1500);
  }
  lastWifiTry = millis();
}

/** Non-blocking: a dropped network must not freeze the PIR or the relay. */
void keepWiFi() {
  if (WiFi.status() == WL_CONNECTED) return;
  if (millis() - lastWifiTry < WIFI_RETRY_MS) return;
  lastWifiTry = millis();
  Serial.println("WiFi lost, reconnecting");
  WiFi.reconnect();
}

// ----------------------- TIME -----------------------
bool clockIsSet() {
  return time(nullptr) > 1700000000;   // Anything before late 2023 is the unset clock
}

uint64_t nowMillis() {
  struct timeval tv;
  gettimeofday(&tv, nullptr);
  return (uint64_t)tv.tv_sec * 1000ULL + tv.tv_usec / 1000ULL;
}

String nowString() {
  return String((unsigned long long)nowMillis());
}

// ----------------------- RECOGNITION -----------------------
void handleRecognition() {
  if (WiFi.status() != WL_CONNECTED) {
    showMessage("Offline", "Try again later");
    delay(1500);
    showMessage("FaceLock Ready", "Approach locker");
    return;
  }
  if (recognizeUrl.length() == 0 && Firebase.ready()) loadRecognizerUrl();
  if (recognizeUrl.length() == 0) {
    showMessage("No recognizer", "Start the server");
    delay(2000);
    showMessage("FaceLock Ready", "Approach locker");
    return;
  }

  // Up to MAX_TRIES photos. The first match opens; a blink or a bad angle on
  // one photo does not count against anyone. Only real mismatches lead to a
  // denial, and that is logged once for the whole attempt.
  int mismatches = 0;
  Verdict best = {true, false, false, "", "", false, 0.0f};

  for (int attempt = 1; attempt <= MAX_TRIES; attempt++) {
    showMessage(attempt == 1 ? String("Face detected")
                             : "Try " + String(attempt) + " of " + String(MAX_TRIES),
                "Verifying...");
    Verdict v = recognizeOnce(attempt == MAX_TRIES, mismatches);

    if (!v.ok) {
      // The laptop may have a new address; look it up again next time.
      if (strlen(RECOGNIZE_URL_OVERRIDE) == 0) recognizeUrl = "";
      showMessage("Server error", "Try again");
      delay(1500);
      showMessage("FaceLock Ready", "Approach locker");
      return;   // Infrastructure trouble is not a denial; nothing is logged
    }

    if (v.granted) {
      showMessage("Welcome", v.name.substring(0, 16));
      openLock();
      stampOpened();
      writeAccessLog(true, v.uid.c_str(), v.name.c_str(), v.hasScore, v.confidence);
      showMessage("FaceLock Ready", "Approach locker");
      return;
    }

    if (!v.noFace) {
      mismatches++;
      if (!best.hasScore || v.confidence > best.confidence) best = v;
    }
    if (attempt < MAX_TRIES) {
      showMessage(v.noFace ? "No face seen" : "Not matched", "Look at camera");
      delay(800);
    }
  }

  if (mismatches == 0) {
    // Only empty frames: the PIR fired but nobody faced the camera.
    showMessage("No face seen", "Try again");
  } else {
    showMessage("Access denied", "Not recognized");
    writeAccessLog(false, "", "", best.hasScore, best.confidence);
  }
  delay(2500);
  showMessage("FaceLock Ready", "Approach locker");
}

/** Takes one photo with the flash and asks the server about it. */
Verdict recognizeOnce(bool lastTry, int mismatchesSoFar) {
  Verdict v = {false, false, false, "", "", false, 0.0f};

  digitalWrite(STATUS_LED, LOW);
  digitalWrite(FLASH_LED, HIGH);
  delay(150);   // Let the exposure settle under the flash
  // With two frame buffers the first one can predate the flash; drop it.
  camera_fb_t* fb = esp_camera_fb_get();
  if (fb) esp_camera_fb_return(fb);
  fb = esp_camera_fb_get();
  digitalWrite(FLASH_LED, LOW);

  if (!fb) {
    digitalWrite(STATUS_LED, HIGH);
    Serial.println("Capture failed");
    v.ok = true;        // Treated like an empty frame: retake
    v.noFace = true;
    return v;
  }

  HTTPClient http;
  http.begin(recognizeUrl);
  http.addHeader("Content-Type", "image/jpeg");
  http.addHeader("X-Locker-Id", LOCKER_ID);
  http.addHeader("X-Device-Key", SECRET_RECOGNIZE_KEY);
  // The server alerts staff only after the final failed photo of an attempt.
  http.addHeader("X-Last-Try", lastTry ? "1" : "0");
  http.addHeader("X-Mismatches", String(mismatchesSoFar));
  http.setTimeout(15000);

  int status = http.POST(fb->buf, fb->len);
  String payload = (status > 0) ? http.getString() : "";
  http.end();
  esp_camera_fb_return(fb);
  digitalWrite(STATUS_LED, HIGH);

  if (status != 200) {
    Serial.printf("Recognition HTTP error: %d\n", status);
    return v;
  }

  // Expected response:
  //   {"granted":true,"uid":"abc123","name":"Juan Dela Cruz","confidence":0.94,"noFace":false}
  // The server decides "granted": the face matches the person this locker is
  // assigned to, and their account is active.
  StaticJsonDocument<384> doc;
  if (deserializeJson(doc, payload)) {
    Serial.println("Bad response: " + payload);
    return v;
  }

  v.ok         = true;
  v.granted    = doc["granted"] | false;
  v.noFace     = doc["noFace"] | false;
  v.uid        = (const char*)(doc["uid"] | "");
  v.name       = (const char*)(doc["name"] | "Unknown");
  v.hasScore   = doc["confidence"].is<float>();
  v.confidence = doc["confidence"] | 0.0f;
  return v;
}

/** Reads the address the recognition server published in config/recognizer. */
void loadRecognizerUrl() {
  lastUrlTry = millis();
  if (!Firebase.Firestore.getDocument(&fbdo, PROJECT_ID, "", "config/recognizer", "url")) {
    Serial.println("No recognizer address yet: " + fbdo.errorReason());
    return;
  }
  FirebaseJson payload;
  payload.setJsonData(fbdo.payload());
  FirebaseJsonData url;
  payload.get(url, "fields/url/stringValue");
  if (url.success && url.to<String>().length() > 0) {
    recognizeUrl = url.to<String>();
    Serial.println("Recognizer at " + recognizeUrl);
  }
}

// ----------------------- FIRESTORE -----------------------
/**
 * Appends one access_logs entry. firestore.rules accept only this field set,
 * only for this board's own locker, and never let it be edited afterwards.
 * Denied attempts carry no uid or name, so nobody is named for a stranger.
 */
void writeAccessLog(bool granted, const char* uid, const char* name,
                    bool hasScore, float confidence) {
  if (!Firebase.ready() || !clockIsSet()) {
    Serial.println("Log skipped: not signed in or clock unset");
    return;
  }

  String ts = nowString();
  FirebaseJson content;
  content.set("fields/lockerId/stringValue", LOCKER_ID);
  content.set("fields/result/stringValue", granted ? "GRANTED" : "DENIED");
  content.set("fields/timestamp/integerValue", ts);
  if (granted && strlen(uid) > 0) {
    content.set("fields/uid/stringValue", uid);
    content.set("fields/userName/stringValue", name);
  }
  if (hasScore) content.set("fields/confidence/doubleValue", confidence);

  // The board names the document itself: locker, time and a random tail.
  String path = String("access_logs/") + LOCKER_ID + "_" + ts + "_" + String(esp_random() % 10000);
  if (!Firebase.Firestore.createDocument(&fbdo, PROJECT_ID, "", path.c_str(), content.raw())) {
    Serial.println("Log write failed: " + fbdo.errorReason());
  }
}

bool sendHeartbeat() {
  FirebaseJson content;
  content.set("fields/lastSeenAt/integerValue", nowString());
  bool ok = Firebase.Firestore.patchDocument(&fbdo, PROJECT_ID, "", lockerDoc.c_str(),
                                             content.raw(), "lastSeenAt");
  if (!ok) Serial.println("Heartbeat failed: " + fbdo.errorReason());
  return ok;
}

void stampOpened() {
  if (!Firebase.ready() || !clockIsSet()) return;
  FirebaseJson content;
  content.set("fields/lastOpenedAt/integerValue", nowString());
  if (!Firebase.Firestore.patchDocument(&fbdo, PROJECT_ID, "", lockerDoc.c_str(),
                                        content.raw(), "lastOpenedAt")) {
    Serial.println("lastOpenedAt failed: " + fbdo.errorReason());
  }
}

// ----------------------- REMOTE UNLOCK -----------------------
void checkRemoteUnlock() {
  // Reads only the one field, which keeps the TLS response small.
  if (!Firebase.Firestore.getDocument(&fbdo, PROJECT_ID, "", lockerDoc.c_str(), "unlockRequested")) {
    Serial.println("Unlock poll failed: " + fbdo.errorReason());
    return;
  }

  FirebaseJson payload;
  payload.setJsonData(fbdo.payload());
  FirebaseJsonData requested;
  payload.get(requested, "fields/unlockRequested/booleanValue");
  if (!requested.success || !requested.to<bool>()) return;

  showMessage("Remote unlock", "By administrator");
  openLock();
  clearUnlockFlag();
  showMessage("FaceLock Ready", "Approach locker");
}

/** Clears the flag and stamps the opening in one write. */
void clearUnlockFlag() {
  FirebaseJson content;
  content.set("fields/unlockRequested/booleanValue", false);
  String mask = "unlockRequested";
  if (clockIsSet()) {
    content.set("fields/lastOpenedAt/integerValue", nowString());
    mask += ",lastOpenedAt";
  }
  if (!Firebase.Firestore.patchDocument(&fbdo, PROJECT_ID, "", lockerDoc.c_str(),
                                        content.raw(), mask.c_str())) {
    // Left set, the next poll would open the lock again; say so loudly.
    Serial.println("Could not clear unlock flag: " + fbdo.errorReason());
  }
}

// ----------------------- LOCK CONTROL -----------------------
void openLock() {
  digitalWrite(RELAY_PIN, HIGH);
  delay(UNLOCK_MS);
  digitalWrite(RELAY_PIN, LOW);   // Always re-secure, even on error paths
}

// ----------------------- LCD -----------------------
void showMessage(const String& line1, const String& line2) {
  lcd.clear();
  lcd.setCursor(0, 0);
  lcd.print(line1);
  lcd.setCursor(0, 1);
  lcd.print(line2);
  Serial.println(line1 + " | " + line2);
}
