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
│       │   ├── data/repo/     Firebase repositories (Auth, Users, Lockers, Logs)
│       │   ├── ui/auth/       Login, Register, Forgot / Change password
│       │   ├── ui/admin/      Dashboard, Lockers, Users, Access Logs
│       │   ├── ui/user/       My Locker, History, Profile, Face Enrollment
│       │   ├── ui/adapter/    RecyclerView adapters
│       │   └── util/          Session, extensions, FCM service
│       ├── res/values/        Design system: colours, type, components
│       ├── res/values-night/  Dark theme overrides
│       └── res/layout/        Screens, list rows, shared includes
├── firmware/
│   ├── FaceLock_ESP32CAM.ino  Capture → recognize → unlock → log
│   └── WIRING.md              Bill of materials and pin connections
└── firebase/
    ├── SETUP.md               Step-by-step console setup
    ├── SCHEMA.md              Firestore collection reference
    ├── firestore.rules        Role-based security rules
    ├── storage.rules          Face image upload rules
    ├── firestore.indexes.json Required composite indexes
    └── functions/             recognizeFace + denied-attempt alerts
```

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

## One thing you must implement

`embedFace()` in `firebase/functions/index.js` is deliberately left as a stub.
It takes a JPEG buffer and should return a face embedding vector. Everything
downstream — similarity matching, threshold checks, access logging, and push
alerts — is already written and will work once you plug in a provider:

- **AWS Rekognition** (`SearchFacesByImage`) — easiest to get running
- **Azure Face API** (Detect + Verify)
- **Self-hosted FaceNet / ArcFace** on Cloud Run — no per-call cost, more setup

## Privacy note

Raw enrollment photos are never readable from any client. They are uploaded to
Storage, converted to an embedding by the backend, and the original image is
deleted. Only the embedding vector persists. Access logs are written server-side
with the Admin SDK so the audit trail cannot be edited or deleted from a device.
This design supports compliance with the Data Privacy Act of 2012 (RA 10173).

## Tech stack

Kotlin · XML Views + ViewBinding · Material 3 · Coroutines + Flow ·
Firebase Auth / Firestore / Storage / Cloud Messaging · CameraX ·
ML Kit Face Detection · Cloud Functions (Node 20) · ESP32-CAM (Arduino)
