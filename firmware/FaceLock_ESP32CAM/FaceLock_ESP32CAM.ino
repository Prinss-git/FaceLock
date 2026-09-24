/*
 * FaceLock - ESP32-CAM Firmware
 * IT-ELDRIOD1 | Facial Recognition Locker System
 *
 * Responsibilities:
 *   1. Detect a person approaching (PIR) and capture a face image.
 *   2. POST the image to the recognition backend (Cloud Function / Flask API).
 *   3. On a match, pulse the relay to open the solenoid lock.
 *   4. Report the attempt result so the backend writes the access log.
 *   5. Poll Firebase for admin-initiated remote unlock requests.
 *
 * Board: "AI Thinker ESP32-CAM"
 * Partition scheme: Huge APP (3MB No OTA)
 */

#include "esp_camera.h"
#include <WiFi.h>
#include <HTTPClient.h>
#include <ArduinoJson.h>
#include <Wire.h>
#include <LiquidCrystal_I2C.h>

// ----------------------- CONFIGURATION -----------------------
const char* WIFI_SSID     = "8 Pro";
const char* WIFI_PASSWORD = "qwerty12356";

// Recognition backend endpoint (see /firebase/functions)
const char* RECOGNIZE_URL = "https://YOUR-REGION-YOUR-PROJECT.cloudfunctions.net/recognizeFace";
// Firestore REST endpoint used only to poll the remote-unlock flag
const char* LOCKER_DOC_URL =
  "https://firestore.googleapis.com/v1/projects/YOUR-PROJECT/databases/(default)/documents/lockers/LKR-01";

const char* LOCKER_ID  = "LKR-01";
const char* DEVICE_KEY = "REPLACE_WITH_DEVICE_SHARED_SECRET";

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
#define RELAY_PIN         12   // Drives the 5V relay -> 12V solenoid lock
#define PIR_PIN           13   // Motion sensor: wakes the capture routine
#define STATUS_LED        33   // Onboard red LED (active LOW)

const unsigned long UNLOCK_MS       = 3000;   // How long the lock stays open
const unsigned long COOLDOWN_MS     = 4000;   // Debounce between attempts
const unsigned long POLL_INTERVAL   = 5000;   // Remote-unlock poll cadence

LiquidCrystal_I2C lcd(0x27, 16, 2);
unsigned long lastAttempt = 0;
unsigned long lastPoll    = 0;

// ----------------------- SETUP -----------------------
void setup() {
  Serial.begin(115200);
  Serial.setDebugOutput(false);

  pinMode(RELAY_PIN, OUTPUT);
  digitalWrite(RELAY_PIN, LOW);       // Fail-secure: locked by default
  pinMode(PIR_PIN, INPUT);
  pinMode(STATUS_LED, OUTPUT);
  digitalWrite(STATUS_LED, HIGH);

  Wire.begin(14, 15);                 // SDA, SCL for the I2C LCD
  lcd.init();
  lcd.backlight();
  showMessage("FaceLock", "Starting...");

  if (!initCamera()) {
    showMessage("Camera FAIL", "Check wiring");
    while (true) delay(1000);
  }

  connectWiFi();
  showMessage("FaceLock Ready", "Approach locker");
}

// ----------------------- MAIN LOOP -----------------------
void loop() {
  // 1) Motion-triggered recognition
  if (digitalRead(PIR_PIN) == HIGH && millis() - lastAttempt > COOLDOWN_MS) {
    lastAttempt = millis();
    handleRecognition();
  }

  // 2) Admin remote unlock, polled from Firestore
  if (millis() - lastPoll > POLL_INTERVAL) {
    lastPoll = millis();
    checkRemoteUnlock();
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
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);

  unsigned long start = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - start < 20000) {
    delay(400);
    Serial.print(".");
  }

  if (WiFi.status() == WL_CONNECTED) {
    Serial.println("\nWiFi connected: " + WiFi.localIP().toString());
  } else {
    showMessage("WiFi FAILED", "Retrying...");
    delay(2000);
    ESP.restart();
  }
}

// ----------------------- RECOGNITION -----------------------
void handleRecognition() {
  showMessage("Face detected", "Verifying...");
  digitalWrite(STATUS_LED, LOW);

  camera_fb_t* fb = esp_camera_fb_get();
  if (!fb) {
    showMessage("Capture failed", "Try again");
    digitalWrite(STATUS_LED, HIGH);
    return;
  }

  if (WiFi.status() != WL_CONNECTED) connectWiFi();

  HTTPClient http;
  http.begin(RECOGNIZE_URL);
  http.addHeader("Content-Type", "image/jpeg");
  http.addHeader("X-Locker-Id", LOCKER_ID);
  http.addHeader("X-Device-Key", DEVICE_KEY);   // Backend rejects unknown devices
  http.setTimeout(15000);

  int status = http.POST(fb->buf, fb->len);
  String payload = (status > 0) ? http.getString() : "";
  http.end();
  esp_camera_fb_return(fb);
  digitalWrite(STATUS_LED, HIGH);

  if (status != 200) {
    Serial.printf("Recognition HTTP error: %d\n", status);
    showMessage("Server error", "Try again");
    delay(1500);
    showMessage("FaceLock Ready", "Approach locker");
    return;
  }

  // Expected response: {"granted":true,"name":"Juan Dela Cruz","confidence":0.94}
  StaticJsonDocument<256> doc;
  if (deserializeJson(doc, payload)) {
    showMessage("Bad response", "Try again");
    delay(1500);
    showMessage("FaceLock Ready", "Approach locker");
    return;
  }

  bool granted = doc["granted"] | false;
  const char* name = doc["name"] | "Unknown";

  if (granted) {
    showMessage("Welcome", String(name).substring(0, 16));
    openLock();
  } else {
    // The backend has already logged this as a denied attempt and
    // notified the admin, so the device only needs to inform the user.
    showMessage("Access denied", "Not recognized");
    delay(2500);
  }
  showMessage("FaceLock Ready", "Approach locker");
}

// ----------------------- REMOTE UNLOCK -----------------------
void checkRemoteUnlock() {
  if (WiFi.status() != WL_CONNECTED) return;

  HTTPClient http;
  http.begin(LOCKER_DOC_URL);
  http.addHeader("X-Device-Key", DEVICE_KEY);
  int status = http.GET();

  if (status == 200) {
    String body = http.getString();
    // Minimal check: Firestore REST wraps booleans as "booleanValue": true
    if (body.indexOf("\"unlockRequested\"") >= 0 &&
        body.indexOf("\"booleanValue\": true") >= 0) {
      showMessage("Remote unlock", "By administrator");
      openLock();
      clearUnlockFlag();
      showMessage("FaceLock Ready", "Approach locker");
    }
  }
  http.end();
}

void clearUnlockFlag() {
  HTTPClient http;
  String url = String(LOCKER_DOC_URL) + "?updateMask.fieldPaths=unlockRequested";
  http.begin(url);
  http.addHeader("Content-Type", "application/json");
  http.addHeader("X-Device-Key", DEVICE_KEY);
  http.PATCH("{\"fields\":{\"unlockRequested\":{\"booleanValue\":false}}}");
  http.end();
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
