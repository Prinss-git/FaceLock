# FaceLock — Facial Recognition Locker System

IT-ELDRIOD1 | University of Cebu – Banilad

Android application, ESP32-CAM firmware, and Firebase backend for a keyless
locker access system that authenticates users by facial recognition.

**Group:** Prince Christian S. Parnada (Hardware) · Alexa Shane D. Adolfo
(Documentation) · Samantha A. Cortes (Mobile App) · Danniezon Meir Po (Cloud & Database)

---

## What's in here

```
FaceLock/
├── app/                       Android application (Kotlin, XML views)
│   └── src/main/
│       ├── java/com/eldroid/facelock/
│       │   ├── data/model/    User, Locker, AccessLog, Role
│       │   ├── data/repo/     Firebase repositories (Auth, Users, Lockers, Logs, RTDB)
│       │   ├── ui/auth/       Login, Register, Forgot / Change password
│       │   ├── ui/admin/      Dashboard, Lockers, Users, Logs, Device test, LED control
│       │   ├── ui/user/       My Locker, History, Profile, Face Enrollment
│       │   ├── ui/adapter/    RecyclerView adapters
│       │   └── util/          Session, extensions, FCM service
│       └── res/               Split by feature, mirroring the java/ folders:
│           ├── core/          Design system + shared assets (values, values-night,
│           │                  drawable, font, color, menu, mipmap, xml)
│           ├── auth/          Login, Register, password screens
│           ├── admin/         Admin screens
│           ├── user/          Member screens
│           ├── adapter/       List row layouts
│           └── common/        Includes shared between features
├── firmware/
│   ├── FaceLock_ESP32CAM.ino  Capture → recognize → unlock → log
│   ├── ConnectivityTest/      Proves the board can reach the backend
│   ├── LedControl/            Listens to device/led and drives a GPIO
│   └── WIRING.md              Bill of materials and pin connections
└── firebase/
    ├── SETUP.md               Step-by-step console setup
    ├── SCHEMA.md              Firestore collection reference
    ├── firestore.rules        Role-based security rules
    ├── database.rules.json    Realtime Database rules (device paths)
    ├── storage.rules          Face image upload rules
    ├── firestore.indexes.json Required composite indexes
    └── functions/             recognizeFace + denied-attempt alerts
```

Each `res/` sub-folder is registered as its own Android resource root in
`app/build.gradle.kts`, so the resource namespace is still flat — `R.layout.…`
does not change. Put a new layout in the folder that matches its feature.

## Roles

The app has one login that routes by the `role` field on the user document:

| Role | Lands on | Can do |
|---|---|---|
| `ADMIN` | Admin dashboard | Manage lockers, users, roles; assign lockers; remote unlock; view all logs |
| `SECURITY` | Security console | View all access logs and failed attempts (read-only) |
| `USER` | My Locker | See own locker status, enroll face, view own access history |

Self-registration always creates a `USER`. Elevated roles are granted by an
existing admin, and this is enforced in the Firestore rules, not just the UI.

## Getting started

1. **Open in Android Studio** — File → Open → select the `FaceLock` folder.
   Built and pinned for **Android Studio Iguana (2023.2.1)**:
   AGP 8.3.2 · Gradle 8.4 · Kotlin 1.9.22 · JDK 17 (use the bundled JBR).
   Let the Gradle sync finish before running.
2. **Set up Firebase** — follow `firebase/SETUP.md`. You must place
   `google-services.json` at `app/google-services.json` or the build will fail.
3. **Promote yourself to admin** — register in the app, then flip your `role`
   field to `ADMIN` in the Firestore console.
4. **Add lockers** — from the admin dashboard, Lockers tab, **+** button.
5. **Flash the ESP32-CAM** — see `firmware/WIRING.md`, then set `WIFI_SSID`,
   `WIFI_PASSWORD`, `RECOGNIZE_URL`, `DEVICE_KEY`, and `LOCKER_ID` in the sketch.

## Talking to the ESP32

Two screens under **Profile → (admin only)** exercise the device link, both
through the Realtime Database rather than Cloud Functions — that path costs
nothing and works on the free Spark plan.

| Screen | Key | Direction |
|---|---|---|
| **Device test** | `test/data` | Board writes a number, phone reads and writes it back |
| **LED control** | `device/led` | Phone writes a boolean, board reads it and drives a GPIO |

Neither end talks to the other directly: the database key *is* the state, so the
board does not have to be on the same network as the phone. The LED screen shows
the raw value alongside the lamp on purpose — when the two disagree, the board is
not running its sketch.

To wire up the LED:

1. Open `firmware/LedControl/LedControl.ino` and set `WIFI_SSID`,
   `WIFI_PASSWORD`, and `DATABASE_SECRET` (Firebase console → Project settings →
   Service accounts → Database secrets).
2. LED from **GPIO 2** through a 220 Ω resistor to GND. On most dev boards GPIO 2
   is the on-board LED, so it can be proved with nothing wired.
3. Upload, then tap the button in the app.

The sketch holds one streaming HTTPS connection (server-sent events) to that key,
so it reacts in well under a second and needs **no extra Arduino library**.

> The database secret bypasses the database rules — that is how the board reads
> without signing in — so it is a full-access password. Regenerate it before
> submission and do not commit a filled-in sketch.

## One thing you must implement

`embedFace()` in `firebase/functions/index.js` is deliberately left as a stub.
It takes a JPEG buffer and should return a face embedding vector. Everything
downstream — similarity matching, threshold checks, access logging, and push
alerts — is already written and will work once you plug in a provider:

- **AWS Rekognition** (`SearchFacesByImage`) — easiest to get running
- **Azure Face API** (Detect + Verify)
- **Self-hosted FaceNet / ArcFace** on Cloud Run — no per-call cost, more setup

**The functions in `firebase/functions/` are not deployed.** Deploying Cloud
Functions requires the Blaze (pay-as-you-go) plan, so anything that routes
through them — `recognizeFace`, the password-reset helpers, denied-attempt push
alerts — returns 404 against this project today. The app itself does not depend
on them: it reads and writes Firestore directly, and the ESP32 link runs over the
Realtime Database (see above). Upgrade the plan and `firebase deploy --only
functions` to switch the recognition path on.

## Privacy note

Raw enrollment photos are never readable from any client. They are uploaded to
Storage, converted to an embedding by the backend, and the original image is
deleted. Only the embedding vector persists. Access logs are written server-side
with the Admin SDK so the audit trail cannot be edited or deleted from a device.
This design supports compliance with the Data Privacy Act of 2012 (RA 10173).

## Tech stack

Kotlin · XML Views + ViewBinding · Material 3 · Coroutines + Flow ·
Firebase Auth / Firestore / Realtime Database / Storage / Cloud Messaging ·
CameraX · ML Kit Face Detection · Cloud Functions (Node 20) · ESP32 (Arduino)

minSdk 24 · targetSdk 34 · Firebase BoM 33.1.2
