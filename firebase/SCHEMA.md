# FaceLock — Firestore Schema

## `users/{uid}`
| Field | Type | Notes |
|---|---|---|
| `fullName` | string | Display name |
| `email` | string | Login email |
| `role` | string | `ADMIN` \| `SECURITY` \| `USER` |
| `lockerId` | string \| null | Assigned locker document ID |
| `faceEnrolled` | boolean | True once an embedding exists |
| `active` | boolean | False = suspended, blocked at login |
| `fcmToken` | string | Device token for alert push |
| `createdAt` | number | Epoch millis |

## `lockers/{lockerId}`
| Field | Type | Notes |
|---|---|---|
| `label` | string | Human-readable name, e.g. `LKR-01` |
| `location` | string | Physical placement |
| `assignedUid` | string \| null | Current owner |
| `assignedName` | string \| null | Denormalized for list display |
| `status` | string | `AVAILABLE` \| `OCCUPIED` \| `LOCKED` \| `OFFLINE` |
| `lastOpenedAt` | number \| null | Epoch millis |
| `unlockRequested` | boolean | Set by admin, cleared by the ESP32 |
| `unlockRequestedAt` | number | Epoch millis |

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
