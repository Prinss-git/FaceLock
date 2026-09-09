# FaceLock — Firebase Setup Guide

## 1. Create the project
1. Go to <https://console.firebase.google.com> and click **Add project**.
2. Name it `facelock-eldroid` (or anything you prefer) and finish the wizard.

## 2. Register the Android app
1. In the project, click the Android icon to add an app.
2. Package name must be exactly: `com.eldroid.facelock`
3. Download `google-services.json`.
4. Place it at `app/google-services.json` in this repo.
   Without this file the project will not compile.

## 3. Enable the services
In the Firebase console sidebar:

| Service | What to do |
|---|---|
| **Authentication** | Build → Authentication → Sign-in method → enable **Email/Password** |
| **Firestore Database** | Build → Firestore → Create database → start in **production mode** |
| **Storage** | Build → Storage → Get started |
| **Cloud Messaging** | Enabled automatically; no action needed |

## 4. Deploy rules and indexes
Install the CLI once, then from the `firebase/` folder:

```bash
npm install -g firebase-tools
firebase login
firebase init          # select Firestore, Storage, Functions
firebase deploy --only firestore:rules,firestore:indexes,storage
```

The composite indexes in `firestore.indexes.json` are required — the access log
queries filter and sort at the same time and will fail without them.

## 5. Seed your first admin
Self-registration always produces a `USER`, so promote one account manually:

1. Register normally through the app.
2. In Firestore, open `users/{thatUid}`.
3. Change `role` from `USER` to `ADMIN`.
4. Sign out and back in — you will land on the admin dashboard.

## 6. Create lockers
From the admin dashboard, tap the **+** button on the Lockers tab and add IDs
matching your hardware, e.g. `LKR-01`. The `LOCKER_ID` constant in the
firmware must match one of these document IDs exactly.

## 7. Deploy the Cloud Functions
```bash
cd functions
npm install
firebase functions:config:set facelock.device_key="SOME_LONG_RANDOM_STRING"
firebase deploy --only functions
```

Copy the same random string into `DEVICE_KEY` in the firmware sketch, and copy
the deployed `recognizeFace` URL into `RECOGNIZE_URL`.

> **Note:** `embedFace()` in `functions/index.js` is intentionally left
> unimplemented. Connect it to your chosen face-embedding provider (AWS
> Rekognition, Azure Face API, or a self-hosted FaceNet/ArcFace model on Cloud
> Run). Everything around it — matching, logging, alerting — is already wired.

## 8. Verify
1. Run the app and register a user.
2. Promote yourself to admin, add a locker, assign it to a test user.
3. Enroll that user's face from the user dashboard.
4. Trigger the ESP32 and confirm a log row appears in the Logs tab.
