# FaceLock — Firestore Schema

## `users/{uid}`
| Field | Type | Notes |
|---|---|---|
| `fullName` | string | Display name |
| `email` | string | Login email |
| `role` | string | `ADMIN` \| `SECURITY` \| `USER` |
| `lockerId` | string \| null | Assigned locker document ID |
| `faceEnrolled` | boolean | True once an embedding exists |
| `active` | boolean | False = suspended: signed out live by the app, and firestore.rules withdraw ADMIN/SECURITY powers at once |
| `fcmToken` | string | Device token for alert push |
| `createdAt` | number | Epoch millis |

## `buildings/{code}`
The document ID is the building code (1–3 capital letters, e.g. `M`, `ENG`),
which also prefixes its lockers' IDs. Admins manage these in the app.

| Field | Type | Notes |
|---|---|---|
| `name` | string | Display name, e.g. `Main Building` |
| `floors` | number | Floor count; floors are stored as `1`…`floors` |
| `groundFloor` | boolean | `true`: floor 1 is shown as `GF` (then `2F`, `3F`…); `false`: `1F`, `2F`… |
| `createdAt` | number | Epoch millis |

## `lockers/{lockerId}`
Locker IDs are `CODE-NNN` (e.g. `M-001`): the building code, then a 3-digit
number counted per building.

| Field | Type | Notes |
|---|---|---|
| `label` | string | Human-readable name; same as the ID for new lockers |
| `building` | string \| null | Building code; null for lockers made before buildings |
| `floor` | number \| null | Floor number within the building |
| `location` | string | Display text, e.g. `Main Building · 1F` (kept in sync on rename) |
| `formerId` | string \| null | Pre-buildings ID (e.g. `L-011`) it was re-created from; links old access logs |
| `assignedUid` | string \| null | Current owner |
| `assignedName` | string \| null | Denormalized for list display |
| `status` | string | `AVAILABLE` \| `OCCUPIED` \| `LOCKED` \| `OFFLINE` \| `OUT_OF_SERVICE` (retired; can't be assigned or unlocked) |
| `lastOpenedAt` | number \| null | Epoch millis; written by the board |
| `lastSeenAt` | number \| null | Board heartbeat, every 60 s. The app shows OFFLINE after 2 min of silence; null = no board has checked in yet |
| `unlockRequested` | boolean | Set by admin, cleared (never set) by the locker's board |
| `unlockRequestedAt` | number | Epoch millis |

`status` is never set to `OFFLINE` in the database; the app derives it from `lastSeenAt`.

## `devices/{uid}`
Links a board's Firebase Auth account to one locker. Created in the console
only; no client may write it.

| Field | Type | Notes |
|---|---|---|
| `lockerId` | string | The only locker this board may heartbeat, unlock-clear and log for |
| `label` | string | Free text, e.g. `Main Building board` |

## `admin_actions/{autoId}`
Activity trail of admin changes. Append-only: admins create entries in their
own name; nobody can edit or delete them. Readable by admins only.

| Field | Type | Notes |
|---|---|---|
| `actorUid` | string | Admin who made the change (must equal the writer's uid) |
| `actorName` | string | Denormalized for display |
| `action` | string | e.g. `LOCKER_ASSIGNED`, `BUILDING_EDITED`, `USER_SUSPENDED` |
| `target` | string | Locker ID, building, or person's name |
| `details` | string \| null | e.g. `to Maria Santos` |
| `timestamp` | number | Epoch millis |

## `access_logs/{autoId}`
| Field | Type | Notes |
|---|---|---|
| `lockerId` | string | Which locker was accessed |
| `uid` | string \| null | Null when the face was not recognized |
| `userName` | string \| null | Denormalized name |
| `result` | string | `GRANTED` \| `DENIED` |
| `confidence` | number \| null | 0.0–1.0 match score |
| `timestamp` | number | Epoch millis |

Written by the locker's own board (signed in as its device account), only for
its own locker and with exactly these fields. Denied attempts omit `uid` and
`userName`. No client may edit or delete an entry.

## `face_templates/{uid}` (Cloud Storage + Firestore)
Enrollment images land in Storage at `face_templates/{uid}/enroll.jpg`.
The recognition server (`recognizer/`) converts each to a face print, stores it
here, then deletes the raw image. No client may read or write this collection.

| Field | Type | Notes |
|---|---|---|
| `embedding` | number[128] | SFace face print, normalized |
| `fullName` | string | Denormalized for the server log |
| `updatedAt` | number | Epoch millis |

If a photo has no usable face, the server sets `users/{uid}.faceEnrolled` back
to false so the app asks the member to enroll again.

## `config/recognizer`
Written by the recognition server on start; readable by any signed-in account,
writable by no client.

| Field | Type | Notes |
|---|---|---|
| `url` | string | e.g. `http://192.168.43.10:5000/recognize`; the board posts photos here |
| `updatedAt` | number | Epoch millis |
