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

## 6. Create lockers and link a board
1. From the admin dashboard, add a building, then use the **+** button on the
   Lockers tab. IDs look like `M-001` (building code + number).
2. **Give the board its own sign-in.** Authentication → Users → Add user, e.g.
   `board-m-001@facelock.device` with a long random password. Copy its **User UID**.
3. **Link it to the locker.** Firestore → start collection `devices` → document
   ID = that UID, fields `lockerId` (string) = `M-001`, `label` (string) =
   `Main Building board`. The rules only let this account touch `lockers/M-001`.
4. Put the same email, password, `M-001`, the Web API key and project ID into
   the sketch's `secrets.h` (and the simulator's `.env`).

One account per board. To retire a board, disable its user in Authentication.

## 7. Deploy the Cloud Functions (not used on Spark)

> **Blaze plan only.** The board already writes logs itself on Spark; if you
> deploy these, remove that write from the sketch or attempts are logged twice. Cloud Functions cannot be deployed on the free Spark
> plan, which this project currently uses. Skip this section until the plan is
> upgraded; the app works without it.

```bash
cd functions
npm install
firebase functions:config:set facelock.device_key="SOME_LONG_RANDOM_STRING"
firebase deploy --only functions
```

Copy the same random string into `SECRET_DEVICE_KEY` in the sketch's gitignored
`secrets.h` (start from `secrets.example.h`), and copy
the deployed `recognizeFace` URL into `RECOGNIZE_URL`.

> **Note:** `embedFace()` in `functions/index.js` is intentionally left
> unimplemented. Connect it to your chosen face-embedding provider (AWS
> Rekognition, Azure Face API, or a self-hosted FaceNet/ArcFace model on Cloud
> Run). Everything around it — matching, logging, alerting — is already wired.

## 8. Verify (no hardware needed)
1. Deploy the rules: `firebase deploy --only firestore:rules,database`.
2. Register a user, promote yourself to admin, add a locker, assign it.
3. In `firmware/simulator/`: `node --env-file=.env simulate.js check` — every
   line must say "refused as expected".
4. `node --env-file=.env simulate.js run` — the locker loses any Offline label;
   tap **Unlock** in the app and the simulator opens and clears it.
5. `simulate.js granted <uid> "<name>"` and `simulate.js denied` — rows appear
   in Logs and the member's history, and the denied alert badge fires.
6. Stop the simulator; about 2 minutes later the locker shows **Offline**.
7. Enroll a face and confirm the upload succeeds. New projects may need Blaze
   for Storage; if the upload fails with a billing error, that is why.
