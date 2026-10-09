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
│       │   ├── data/repo/     Firebase repositories (Auth, Users, Lockers, Logs, Buildings)
│       │   ├── ui/auth/       Login, Register, Forgot / Change password
│       │   ├── ui/admin/      Dashboard, Lockers, Users, Logs, Buildings, Activity trail
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
│   ├── FaceLock_ESP32CAM/     Capture → recognize → unlock → log → heartbeat
│   ├── simulator/             Node stand-in for the board, for testing without hardware
│   └── WIRING.md              Bill of materials and pin connections
├── recognizer/                Python face-recognition server (runs on a laptop)
└── firebase/
    ├── SETUP.md               Step-by-step console setup
    ├── SCHEMA.md              Firestore collection reference
    ├── firestore.rules        Role-based security rules (incl. device accounts)
    ├── database.rules.json    Realtime Database rules (unused; denies everything)
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

1. **Open in Android Studio** — File → Open → select the folder that contains
   `settings.gradle.kts`. Not the folder above it.
   Built and pinned for **Android Studio Iguana (2023.2.1)**:
   AGP 8.3.2 · Gradle 8.4 · Kotlin 1.9.22 · JDK 17 (use the bundled JBR).
   Let the Gradle sync finish before running.
2. **Get `google-services.json`** — it is deliberately not in the repo. Either
   download it from the Firebase console (Project settings → Your apps → Android
   app → `google-services.json`) or ask a teammate for their copy, and put it at
   `app/google-services.json`. The build fails without it.
3. **Promote yourself to admin** — register in the app, then flip your `role`
   field to `ADMIN` in the Firestore console.
4. **Add lockers** — from the admin dashboard, Lockers tab, **+** button.
5. **Set up the locker board** — see "The locker board" below.

### If Android Studio shows hundreds of red errors

Errors like *"Unresolved class 'FaceLockApp'"*, *"Attribute android:allowBackup is
not allowed here"* or *"Unresolved package 'ui'"* in `AndroidManifest.xml` do not
mean the code is broken. They mean Gradle never synced, so the IDE is reading the
manifest as plain XML with no Android project behind it. Two causes:

**You opened the wrong folder.** Downloading the ZIP from GitHub gives you
`FaceLock-main\FaceLock-main\` — the project is in the *inner* one. Opening the
outer folder finds no `settings.gradle.kts` and nothing syncs. Close the project
and reopen at the level where you can see `settings.gradle.kts`, `gradlew` and
`app/` side by side. **Cloning with `git clone` avoids this entirely** and is
worth doing instead of downloading the ZIP.

**`google-services.json` is missing.** See step 2 above — the sync fails outright
without it, which leaves the IDE in the same unresolved state.

After fixing either, run **File → Sync Project with Gradle Files**. The errors
clear when the sync succeeds.

## The locker board

The ESP32-CAM talks to Firestore directly, signed in with its **own Firebase
account**. Nothing here needs Cloud Functions, so it works on the free Spark plan.

| What | Who writes it | Where |
|---|---|---|
| Heartbeat | Board, every 60 s | `lockers/{id}.lastSeenAt` |
| Remote unlock | Admin sets it, board clears it | `lockers/{id}.unlockRequested` |
| Last opened | Board | `lockers/{id}.lastOpenedAt` |
| Access attempts | Board | `access_logs/*` (append-only) |

The app shows a locker as **Offline** once its board has missed two heartbeats
(about 2 minutes). A locker whose board has never checked in keeps its normal
status, so nothing reads as offline before the hardware exists.

`firestore.rules` only lets a board touch the locker named in its
`devices/{uid}` document, only those fields, and it can clear the unlock flag
but never set it. Setup steps are in `firebase/SETUP.md` (step 6).

### Testing without hardware

`firmware/simulator/` signs in with the same device account and makes the same
writes as the sketch, so the app and the rules can be checked end to end now:

```bash
cd firmware/simulator
npm install
cp .env.example .env        # fill in the same values as secrets.h
node --env-file=.env simulate.js run       # heartbeat + answers remote unlocks
node --env-file=.env simulate.js granted <uid> "Juan Dela Cruz" 0.93
node --env-file=.env simulate.js denied 0.41
node --env-file=.env simulate.js check     # writes the rules must refuse
```

### Flashing the real board

1. In `firmware/FaceLock_ESP32CAM/`, copy `secrets.example.h` to `secrets.h`
   and fill it in (gitignored).
2. Install the libraries listed in `firmware/WIRING.md`.
3. Board **AI Thinker ESP32-CAM**, Partition Scheme **Huge APP (3MB No OTA)**.
4. Upload, open the Serial Monitor at 115200, and check for `Heartbeat failed`
   or `Unlock poll failed` lines.

## Face recognition server

Faces are compared by a small Python program on a laptop, `recognizer/`. The
free Spark plan cannot run server code, so a laptop on the same Wi-Fi as the
board does the work. Setup and use are in [`recognizer/README.md`](recognizer/README.md).

- The board takes **up to 3 photos** per attempt and opens on the first match.
  Empty frames are retaken without counting. If none match, it writes **one**
  DENIED log, and the server pushes **one** alert to admin and security phones.
- The server compares the photo only with the person the locker is assigned to
  *right now*, so reassigning a locker in the app takes effect immediately.
- The server publishes its address in Firestore (`config/recognizer`); the board
  reads it, so a new laptop IP needs no re-flash.

`firebase/functions/` holds the older Blaze-only design and is **not deployed**;
the recognizer replaces it. If the project ever moves to Blaze and deploys those
functions, remove the board's own `access_logs` write or every attempt is logged
twice.

## Demo

**The day before**
1. APK installed on the admin phone (Build → Build APK(s), real
   `google-services.json` in `app/`).
2. Accounts: one admin, two members (e.g. Alice and Bob), both faces enrolled
   in the app. Leave the recognizer running for a minute so it picks them up.
3. One building with locker `M-001`; the board's account linked to `M-001`
   (`firebase/SETUP.md` step 6).
4. Board and laptop both on a **2.4 GHz phone hotspot**. Start
   `python server.py`, then power the board.
5. Run `node --env-file=.env simulate.js check` once against the live project.
6. Full dry run of the script below.

**Script (about 8 minutes)**
1. Admin app → Lockers: `M-001` is online (the board's heartbeat).
2. Assign `M-001` to **Alice**. Alice faces the camera → "Welcome" → lock opens
   → GRANTED in Logs.
3. **Bob** faces the camera → 3 tries → "Access denied" → one DENIED log, plus
   the alert on the admin phone.
4. **Reassign `M-001` to Bob, live.** Now Bob opens it and Alice is denied. No
   re-flashing, only the app.
5. **Remote unlock** from the admin app → opens within about 10 s.
6. **Suspend Bob** → his app signs out by itself and his face is denied.
7. Logs filters, a member's history, and the Activity trail showing every step.
8. Unplug the board → `M-001` shows **Offline** about 2 minutes later.

**If the hardware fails:** run the simulator (`run`, `granted`, `denied`) so
the app side can still be shown.

## Privacy note

Raw enrollment photos are never readable from any client. They are uploaded to
Storage, the recognition server converts each into a face print (128 numbers),
and then deletes the photo, so only the face print persists.
Access logs are append-only: the locker's board may add entries for its own
locker, and no client can edit or delete one.
This design supports compliance with the Data Privacy Act of 2012 (RA 10173).

## Tech stack

Kotlin · XML Views + ViewBinding · Material 3 · Coroutines + Flow ·
Firebase Auth / Firestore / Realtime Database / Storage / Cloud Messaging ·
CameraX · ML Kit Face Detection · Cloud Functions (Node 20) · ESP32 (Arduino)

minSdk 24 · targetSdk 34 · Firebase BoM 33.1.2
