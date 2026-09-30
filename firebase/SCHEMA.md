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
| `lastOpenedAt` | number \| null | Epoch millis |
| `unlockRequested` | boolean | Set by admin, cleared by the ESP32 |
| `unlockRequestedAt` | number | Epoch millis |

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

Written exclusively by the Cloud Function using the Admin SDK so the
audit trail cannot be altered from any client.

## `face_templates/{uid}` (Cloud Storage + Firestore)
Enrollment images land in Storage at `face_templates/{uid}/enroll.jpg`.
The recognition backend converts each to an embedding vector, stores it in
the `face_templates` collection, then deletes the raw image. Only the
embedding is retained, which limits exposure if the database is compromised.
