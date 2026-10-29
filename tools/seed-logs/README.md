# Demo seeders

Two scripts that prepare Firestore for the demo video.

`seed-accounts.js` creates the demo accounts, buildings and lockers with known
passwords. The app cannot do this itself: the Firebase client SDK swaps the
session when it registers someone, so an administrator creating a second
account is immediately signed out as them.

`seed-logs.js` fills `access_logs` with a week of realistic traffic so the Logs, My History and
dashboard screens have something to show on camera.

`firestore.rules` blocks every client write to `access_logs` on purpose — the
audit trail is server-side only. This script uses the Admin SDK, which bypasses
the rules legitimately and runs on the **free Spark plan** (only *deploying*
Cloud Functions needs Blaze).

## Setup

1. Firebase console → ⚙ Project settings → **Service accounts** → **Generate new
   private key**. Save it here as `serviceAccountKey.json`.
   It is gitignored. Never commit it — it is full admin access to the project.
2. Nothing else — `seed-accounts.js` creates the users and lockers that
   `seed-logs.js` then generates history against.

```bash
cd tools/seed-logs
npm install
node seed-accounts.js    # accounts, buildings, lockers - run this first
node seed-logs.js        # a week of access history for those members
```

Every demo account uses the password **`FaceLock#2026`**:

| Role | Email | Locker |
|---|---|---|
| ADMIN | `admin@facelock.test` | — |
| SECURITY | `security@facelock.test` | — |
| USER | `member@facelock.test` | M-101 |
| USER | `member2@facelock.test` | M-102 (face enrolled) |
| USER | `member3@facelock.test` | E-201 |
| USER | `member4@facelock.test` | none |
| USER | `member5@facelock.test` | none — assign this one on camera |

## Options

| Command | What it does |
|---|---|
| `node seed-logs.js` | Seeds 60 logs across the last 7 days |
| `node seed-logs.js --count 120` | Seeds a different number (1–500) |
| `node seed-logs.js --clear` | Removes only the rows this script created |

Documents use fixed ids (`demo-log-000`…), so re-running overwrites the same
rows rather than piling up duplicates, and `--clear` never touches a real log.

## Before you submit

Run `npm run clear` to remove every seeded account, locker, building and log,
then delete `serviceAccountKey.json`. That key is unrestricted admin access to
the project — worse than the database secret.
