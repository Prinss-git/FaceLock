/*
 * FaceLock - ESP32 Connectivity Test
 * IT-ELDRIOD1 | Facial Recognition Locker System
 *
 * Proves the board can reach the FaceLock backend before you wire up the
 * camera, relay and LCD. It exercises exactly the path the real firmware
 * uses: an HTTPS POST to the recognizeFace Cloud Function, authenticated
 * with the shared device key.
 *
 * No Firebase library is needed on the device. The ESP32 never holds a
 * Google credential - it talks only to your Cloud Function, which holds
 * the admin credentials server-side.
 *
 * EXPECTED RESULT: HTTP 500 {"error":"Recognition failed"}
 *   That is a PASS. It means DNS, TLS, the URL, the device key and the
 *   locker header were all accepted, and the request reached embedFace(),
 *   which is deliberately left unimplemented in functions/index.js.
 *   Nothing is written to Firestore on that path, so running this test
 *   leaves no access log behind.
 *
 * Board:      "AI Thinker ESP32-CAM"  (or "ESP32 Dev Module" for a plain board)
 * Partition:  Huge APP (3MB No OTA)   - the default is too small for TLS
 */

#include <WiFi.h>
#include <HTTPClient.h>

// ----------------------- CONFIGURATION -----------------------
const char* WIFI_SSID     = "Kythlog";
const char* WIFI_PASSWORD = "12345678";

// Default region for a 1st-gen function with no .region() call. Confirm this
// against the URL that `firebase deploy --only functions` prints.
const char* RECOGNIZE_URL =
  "https://us-central1-facelock-eldroid.cloudfunctions.net/recognizeFace";

const char* LOCKER_ID = "LKR-01";

// Must match what the backend has. Set it there with:
//   firebase functions:config:set facelock.device_key="i_8WAogMO2pbsZis_zng8kNlKZJ1w-1RJxC_UQChPk8"
//   firebase deploy --only functions
const char* DEVICE_KEY = "i_8WAogMO2pbsZis_zng8kNlKZJ1w-1RJxC_UQChPk8";

const unsigned long WIFI_TIMEOUT_MS = 20000;

// ----------------------- SETUP -----------------------
void setup() {
  Serial.begin(115200);
  delay(500);
  Serial.println("\n\n=== FaceLock connectivity test ===");

  if (!connectWiFi()) return;
  testBackend();

  Serial.println("\nPress the RESET/EN button to run the test again.");
}

void loop() {
  // Single-shot test: nothing to do here.
}

// ----------------------- WI-FI -----------------------
bool connectWiFi() {
  // Without an explicit STA mode the ESP32 can come up in AP+STA and
  // associate unreliably.
  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);

  Serial.printf("Connecting to \"%s\"", WIFI_SSID);
  unsigned long started = millis();

  // A bounded wait: a wrong password otherwise spins here forever with no
  // clue as to why.
  while (WiFi.status() != WL_CONNECTED && millis() - started < WIFI_TIMEOUT_MS) {
    delay(500);
    Serial.print(".");
  }
  Serial.println();

  if (WiFi.status() != WL_CONNECTED) {
    Serial.println("FAIL: could not join the network.");
    Serial.println("  - Check the SSID and password (both are case sensitive).");
    Serial.println("  - The ESP32 only supports 2.4 GHz, not 5 GHz.");
    return false;
  }

  Serial.print("Connected.  IP: ");
  Serial.print(WiFi.localIP());
  Serial.printf("   signal: %d dBm\n", WiFi.RSSI());
  return true;
}

// ----------------------- BACKEND -----------------------
void testBackend() {
  Serial.printf("\nPOST %s\n", RECOGNIZE_URL);

  HTTPClient http;
  http.begin(RECOGNIZE_URL);
  http.addHeader("Content-Type", "image/jpeg");
  http.addHeader("X-Locker-Id", LOCKER_ID);
  http.addHeader("X-Device-Key", DEVICE_KEY);
  http.setTimeout(15000);

  // A 4-byte JPEG header and footer: enough to be a valid request body,
  // not enough to be a face. The point is to test the pipe, not the model.
  uint8_t probe[] = { 0xFF, 0xD8, 0xFF, 0xD9 };
  int status = http.POST(probe, sizeof(probe));
  String body = (status > 0) ? http.getString() : "";
  http.end();

  Serial.printf("HTTP %d\n", status);
  if (body.length()) Serial.println(body);
  Serial.println();

  explain(status);
}

void explain(int status) {
  switch (status) {
    case 500:
      Serial.println("PASS - reached embedFace(), which is not implemented yet.");
      Serial.println("       Wi-Fi, TLS, URL and device key are all correct.");
      break;
    case 200:
      Serial.println("PASS - the backend answered a full recognition result.");
      Serial.println("       embedFace() must already be wired up.");
      break;
    case 401:
      Serial.println("FAIL - the backend rejected the device key.");
      Serial.println("       DEVICE_KEY here must equal facelock.device_key on the");
      Serial.println("       function. Re-deploy the function after changing it.");
      break;
    case 400:
      Serial.println("FAIL - the X-Locker-Id header did not arrive.");
      break;
    case 405:
      Serial.println("FAIL - the request was not a POST.");
      break;
    case 404:
      Serial.println("FAIL - no function at that URL.");
      Serial.println("       Check the region and that `firebase deploy --only");
      Serial.println("       functions` has been run for this project.");
      break;
    default:
      if (status < 0) {
        Serial.printf("FAIL - could not connect (HTTPClient error %d).\n", status);
        Serial.println("       DNS, TLS or the host is wrong, or there is no");
        Serial.println("       route to the internet from this network.");
      } else {
        Serial.printf("Unexpected status %d - read the response body above.\n", status);
      }
      break;
  }
}
