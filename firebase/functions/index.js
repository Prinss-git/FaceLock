/**
 * FaceLock Cloud Functions
 *
 *  recognizeFace  - HTTP endpoint the ESP32-CAM posts a JPEG to. Matches the
 *                   face against enrolled embeddings, writes the access log,
 *                   and tells the device whether to open the lock.
 *  onAccessDenied - Firestore trigger that pushes an alert to admins and
 *                   security staff whenever an attempt is denied.
 */

const functions = require("firebase-functions");
const admin = require("firebase-admin");

admin.initializeApp();
const db = admin.firestore();

// Shared secret the firmware sends in X-Device-Key.
const DEVICE_KEY = functions.config().facelock?.device_key || "REPLACE_ME";
// Cosine-similarity floor for accepting a match.
const MATCH_THRESHOLD = 0.85;

/* ------------------------------------------------------------------ */
/* 1. Recognition endpoint                                             */
/* ------------------------------------------------------------------ */
exports.recognizeFace = functions
  .runWith({ memory: "1GB", timeoutSeconds: 60 })
  .https.onRequest(async (req, res) => {
    if (req.method !== "POST") {
      return res.status(405).json({ error: "POST only" });
    }
    if (req.get("X-Device-Key") !== DEVICE_KEY) {
      return res.status(401).json({ error: "Unrecognized device" });
    }

    const lockerId = req.get("X-Locker-Id");
    if (!lockerId) {
      return res.status(400).json({ error: "Missing X-Locker-Id" });
    }

    try {
      // req.rawBody is the JPEG bytes sent by the ESP32-CAM.
      const probe = await embedFace(req.rawBody);

      if (!probe) {
        await writeLog({ lockerId, uid: null, userName: null,
                         result: "DENIED", confidence: null });
        return res.json({ granted: false, name: "No face detected" });
      }

      const { uid, name, score } = await bestMatch(probe);
      const granted = score >= MATCH_THRESHOLD && !!uid;

      // A recognized face still fails if the account is suspended or the
      // locker belongs to somebody else.
      let allowed = granted;
      if (granted) {
        const userSnap = await db.collection("users").doc(uid).get();
        const user = userSnap.data() || {};
        allowed = user.active === true && user.lockerId === lockerId;
      }

      await writeLog({
        lockerId,
        uid: allowed ? uid : null,
        userName: allowed ? name : null,
        result: allowed ? "GRANTED" : "DENIED",
        confidence: score,
      });

      if (allowed) {
        await db.collection("lockers").doc(lockerId).update({
          lastOpenedAt: Date.now(),
        });
      }

      return res.json({
        granted: allowed,
        name: allowed ? name : "Not recognized",
        confidence: score,
      });
    } catch (err) {
      console.error("recognizeFace failed:", err);
      return res.status(500).json({ error: "Recognition failed" });
    }
  });

/* ------------------------------------------------------------------ */
/* 2. Denied-attempt alerting                                          */
/* ------------------------------------------------------------------ */
exports.onAccessDenied = functions.firestore
  .document("access_logs/{logId}")
  .onCreate(async (snap) => {
    const log = snap.data();
    if (log.result !== "DENIED") return null;

    // Notify every admin and security account that has a device token.
    const staff = await db
      .collection("users")
      .where("role", "in", ["ADMIN", "SECURITY"])
      .get();

    const tokens = staff.docs
      .map((d) => d.data().fcmToken)
      .filter(Boolean);

    if (tokens.length === 0) return null;

    return admin.messaging().sendEachForMulticast({
      tokens,
      notification: {
        title: "Unauthorized access attempt",
        body: `Locker ${log.lockerId} denied an unrecognized person.`,
      },
      data: { lockerId: log.lockerId, type: "ACCESS_DENIED" },
    });
  });

/* ------------------------------------------------------------------ */
/* Helpers                                                             */
/* ------------------------------------------------------------------ */

async function writeLog({ lockerId, uid, userName, result, confidence }) {
  return db.collection("access_logs").add({
    lockerId,
    uid: uid || null,
    userName: userName || null,
    result,
    confidence: confidence ?? null,
    timestamp: Date.now(),
  });
}

/** Compares a probe embedding against every enrolled template. */
async function bestMatch(probe) {
  const templates = await db.collection("face_templates").get();

  let best = { uid: null, name: null, score: 0 };
  templates.forEach((doc) => {
    const data = doc.data();
    if (!Array.isArray(data.embedding)) return;
    const score = cosineSimilarity(probe, data.embedding);
    if (score > best.score) {
      best = { uid: doc.id, name: data.fullName || null, score };
    }
  });
  return best;
}

function cosineSimilarity(a, b) {
  if (a.length !== b.length) return 0;
  let dot = 0, magA = 0, magB = 0;
  for (let i = 0; i < a.length; i++) {
    dot += a[i] * b[i];
    magA += a[i] * a[i];
    magB += b[i] * b[i];
  }
  const denom = Math.sqrt(magA) * Math.sqrt(magB);
  return denom === 0 ? 0 : dot / denom;
}

/**
 * Converts a JPEG buffer into a face embedding vector.
 *
 * Plug in whichever provider you settle on, for example:
 *   - AWS Rekognition  (SearchFacesByImage)
 *   - Azure Face API   (Detect + Verify)
 *   - A self-hosted FaceNet / ArcFace model on Cloud Run
 *
 * Return null when no usable face is present in the frame.
 */
async function embedFace(jpegBuffer) {
  throw new Error(
    "embedFace() is not implemented. Wire up your face embedding provider here."
  );
}
