// Copy this file to secrets.h in the same folder and fill in real values.
// secrets.h is gitignored - never commit it.
#pragma once

// Wi-Fi the board joins.
#define SECRET_WIFI_SSID     "YOUR_WIFI_SSID"
#define SECRET_WIFI_PASSWORD "YOUR_WIFI_PASSWORD"

// Firebase console -> Project settings -> General.
#define SECRET_API_KEY       "YOUR_WEB_API_KEY"
#define SECRET_PROJECT_ID    "facelock-eldroid"

// The board's own sign-in, made in Authentication -> Users, and linked to its
// locker by a devices/{uid} document (see firebase/SETUP.md).
#define SECRET_DEVICE_EMAIL    "board-m-001@facelock.device"
#define SECRET_DEVICE_PASSWORD "YOUR_DEVICE_PASSWORD"

// Must match the locker's ID in the app exactly, e.g. M-001.
#define SECRET_LOCKER_ID     "M-001"

// Leave "" normally: the recognition server publishes its address in
// Firestore (config/recognizer) and the board reads it from there. Set it only
// to force a fixed address, e.g. "http://192.168.43.10:5000/recognize".
#define SECRET_RECOGNIZE_URL ""
// Shared secret sent as X-Device-Key; must match DEVICE_KEY in recognizer/.env.
#define SECRET_RECOGNIZE_KEY "YOUR_RECOGNIZER_SHARED_SECRET"
