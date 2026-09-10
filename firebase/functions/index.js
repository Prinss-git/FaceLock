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
// Set it in functions/.env as DEVICE_KEY=... — functions.config() still works
// on older projects but is deprecated, so it is only a fallback here.
const DEVICE_KEY =
  process.env.DEVICE_KEY ||
  (typeof functions.config === "function"
    ? functions.config().facelock?.device_key
    : undefined) ||
  "REPLACE_ME";
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
/* 3. Password resets (no email)                                       */
/* ------------------------------------------------------------------ */
/*
 * Firebase's client SDK can only set a password for a user who is already
 * signed in. A member who has forgotten theirs is by definition not, so the
 * reset has to happen here, with the Admin SDK.
 *
 * The flow is deliberately human: the member raises a request, an admin
 * approves it, and the temporary password is read out in person. That suits a
 * campus locker system where the admin is physically present, and it needs no
 * mail server and no SMS billing.
 */

/** Anyone may ask. Nobody learns whether the address exists. */
exports.requestPasswordReset = functions.https.onRequest(async (req, res) => {
  if (req.method !== "POST") {
    return res.status(405).json({ error: "POST only" });
  }

  const email = String(req.body?.email || "").trim().toLowerCase();
  if (!email) {
    return res.status(400).json({ error: "Missing email" });
  }

  try {
    const user = await admin.auth().getUserByEmail(email);
    const profileSnap = await db.collection("users").doc(user.uid).get();
    const profile = profileSnap.data() || {};

    // One open request per person: repeated taps must not flood the queue.
    const open = await db
      .collection("password_resets")
      .where("uid", "==", user.uid)
      .where("status", "==", "PENDING")
      .limit(1)
      .get();

    if (open.empty) {
      await db.collection("password_resets").add({
        uid: user.uid,
        email: user.email || email,
        displayName: profile.fullName || user.displayName || null,
        status: "PENDING",
        requestedAt: Date.now(),
        handledBy: null,
        handledByName: null,
        handledAt: null,
      });
    }
  } catch (err) {
    // getUserByEmail throws for an unknown address. Swallow it: answering
    // differently here would turn this endpoint into an account-existence
    // oracle for anyone who can reach it.
    if (err.code !== "auth/user-not-found") {
      console.error("requestPasswordReset failed:", err);
    }
  }

  // Always the same answer, whatever happened above.
  return res.json({ ok: true });
});

/**
 * Admin approves or rejects. Caller identity comes from a Firebase ID token,
 * verified here — the role is re-read from Firestore rather than trusted from
 * the request, so a member cannot approve their own reset.
 */
exports.resolvePasswordReset = functions.https.onRequest(async (req, res) => {
  if (req.method !== "POST") {
    return res.status(405).json({ error: "POST only" });
  }

  const header = req.get("Authorization") || "";
  if (!header.startsWith("Bearer ")) {
    return res.status(401).json({ error: "Missing ID token" });
  }

  let caller;
  try {
    caller = await admin.auth().verifyIdToken(header.substring(7));
  } catch (err) {
    return res.status(401).json({ error: "Invalid ID token" });
  }

  const callerSnap = await db.collection("users").doc(caller.uid).get();
  const callerProfile = callerSnap.data() || {};
  if (callerProfile.role !== "ADMIN") {
    return res.status(403).json({ error: "Admins only" });
  }

  const { requestId, uid, approve } = req.body || {};
  if (!requestId && !uid) {
    return res.status(400).json({ error: "Provide requestId or uid" });
  }

  try {
    let targetUid = uid;
    let requestRef = null;

    if (requestId) {
      requestRef = db.collection("password_resets").doc(requestId);
      const snap = await requestRef.get();
      if (!snap.exists) {
        return res.status(404).json({ error: "No such request" });
      }
      if (snap.data().status !== "PENDING") {
        return res.status(409).json({ error: "Already handled" });
      }
      targetUid = snap.data().uid;
    }

    if (approve === false) {
      await requestRef.update({
        status: "REJECTED",
        handledBy: caller.uid,
        handledByName: callerProfile.fullName || null,
        handledAt: Date.now(),
      });
      return res.json({ ok: true, rejected: true });
    }

    const tempPassword = generateTempPassword();
    await admin.auth().updateUser(targetUid, { password: tempPassword });

    // Forces a change at the next sign-in: a password a second person has
    // seen must not stay valid indefinitely.
    await db.collection("users").doc(targetUid).update({
      mustChangePassword: true,
    });

    if (requestRef) {
      await requestRef.update({
        status: "COMPLETED",
        handledBy: caller.uid,
        handledByName: callerProfile.fullName || null,
        handledAt: Date.now(),
      });
    } else {
      // Reset started from the user list rather than from a request: close
      // any request the member had already raised.
      const open = await db
        .collection("password_resets")
        .where("uid", "==", targetUid)
        .where("status", "==", "PENDING")
        .get();
      await Promise.all(
        open.docs.map((d) =>
          d.ref.update({
            status: "COMPLETED",
            handledBy: caller.uid,
            handledByName: callerProfile.fullName || null,
            handledAt: Date.now(),
          })
        )
      );
    }

    // The only time this value is ever readable. It is not stored anywhere.
    return res.json({ ok: true, tempPassword });
  } catch (err) {
    console.error("resolvePasswordReset failed:", err);
    return res.status(500).json({ error: "Reset failed" });
  }
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

/**
 * Readable temporary password: no characters that get misheard when an admin
 * reads it aloud (no O/0, I/l/1), but still mixed case, a digit and a symbol
 * so it satisfies the policy the app enforces everywhere else.
 */
function generateTempPassword() {
  const upper = "ABCDEFGHJKLMNPQRSTUVWXYZ";
  const lower = "abcdefghijkmnpqrstuvwxyz";
  const digits = "23456789";
  const symbols = "!@#$%*?-";
  const all = upper + lower + digits + symbols;

  const pick = (set) => set[Math.floor(Math.random() * set.length)];
  const chars = [pick(upper), pick(lower), pick(digits), pick(symbols)];
  while (chars.length < 12) chars.push(pick(all));

  for (let i = chars.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [chars[i], chars[j]] = [chars[j], chars[i]];
  }
  return chars.join("");
}
