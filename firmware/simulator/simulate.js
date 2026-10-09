/*
 * FaceLock device simulator
 *
 * Does what FaceLock_ESP32CAM.ino does, minus the camera and relay, so the app
 * and firestore.rules can be tested before the hardware exists. It signs in
 * with the board's own account, so the rules treat it exactly like the board.
 *
 *   node --env-file=.env simulate.js run              heartbeat + answer remote unlocks
 *   node --env-file=.env simulate.js granted <uid> "<name>" [confidence]
 *   node --env-file=.env simulate.js denied [confidence]
 *   node --env-file=.env simulate.js check            try writes the rules must refuse
 */

import { initializeApp } from "firebase/app";
import { connectAuthEmulator, getAuth, signInWithEmailAndPassword } from "firebase/auth";
import {
  connectFirestoreEmulator, doc, getDoc, getFirestore, setDoc, updateDoc,
} from "firebase/firestore";

// Same cadence as the sketch.
const POLL_INTERVAL_MS = 10_000;
const HEARTBEAT_INTERVAL_MS = 60_000;
const UNLOCK_MS = 3_000;

const env = (name) => {
  const value = process.env[name];
  if (!value) {
    console.error(`Missing ${name}. Copy .env.example to .env and fill it in.`);
    process.exit(1);
  }
  return value;
};

const lockerId = env("LOCKER_ID");
const app = initializeApp({ apiKey: env("FIREBASE_API_KEY"), projectId: env("FIREBASE_PROJECT_ID") });
const auth = getAuth(app);
const db = getFirestore(app);
if (process.env.USE_EMULATOR === "1") {
  connectAuthEmulator(auth, "http://127.0.0.1:9099", { disableWarnings: true });
  connectFirestoreEmulator(db, "127.0.0.1", 8080);
}
const lockerRef = doc(db, "lockers", lockerId);

const log = (...args) => console.log(new Date().toLocaleTimeString(), ...args);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function heartbeat() {
  await updateDoc(lockerRef, { lastSeenAt: Date.now() });
  log("heartbeat");
}

async function pollUnlock() {
  const snap = await getDoc(lockerRef);
  if (!snap.exists()) return log(`lockers/${lockerId} does not exist`);
  if (snap.get("unlockRequested") !== true) return;

  log("remote unlock: relay open");
  await sleep(UNLOCK_MS);
  log("remote unlock: relay closed");
  await updateDoc(lockerRef, { unlockRequested: false, lastOpenedAt: Date.now() });
}

async function writeLog({ granted, uid, name, confidence }) {
  const ts = Date.now();
  const entry = { lockerId, result: granted ? "GRANTED" : "DENIED", timestamp: ts };
  if (granted) Object.assign(entry, { uid, userName: name });
  if (confidence !== undefined) entry.confidence = confidence;

  const id = `${lockerId}_${ts}_${Math.floor(Math.random() * 10000)}`;
  await setDoc(doc(db, "access_logs", id), entry);
  if (granted) await updateDoc(lockerRef, { lastOpenedAt: ts });
  log(`access_logs/${id}`, entry);
}

/** Each of these must be refused; a success means the rules are too loose. */
async function checkRules() {
  const attempts = [
    ["set unlockRequested", () => updateDoc(lockerRef, { unlockRequested: true })],
    ["reassign the locker", () => updateDoc(lockerRef, { assignedUid: "someone" })],
    ["heartbeat another locker", () => updateDoc(doc(db, "lockers", "ZZZ-999"), { lastSeenAt: Date.now() })],
    ["log for another locker", () => setDoc(doc(db, "access_logs", `x_${Date.now()}`),
      { lockerId: "ZZZ-999", result: "DENIED", timestamp: Date.now() })],
    ["log with an extra field", () => setDoc(doc(db, "access_logs", `y_${Date.now()}`),
      { lockerId, result: "DENIED", timestamp: Date.now(), note: "x" })],
    ["read the users list", () => getDoc(doc(db, "users", auth.currentUser.uid + "_other"))],
  ];
  // Writing true over a pending true changes nothing, so the rules rightly let
  // it through; the probe only means something once the flag is clear.
  if ((await getDoc(lockerRef)).get("unlockRequested") === true) {
    attempts.shift();
    log("skipped: set unlockRequested (an unlock is pending; run `run` first to clear it)");
  }
  let loose = 0;
  for (const [what, attempt] of attempts) {
    try {
      await attempt();
      loose++;
      log(`ALLOWED (should be refused): ${what}`);
    } catch (e) {
      log(`refused as expected: ${what} (${e.code ?? e.message})`);
    }
  }
  // And the one read the board depends on to find the recognition server.
  try {
    const cfg = await getDoc(doc(db, "config", "recognizer"));
    log(`allowed as expected: read config/recognizer (${cfg.exists() ? cfg.get("url") : "not published yet"})`);
  } catch (e) {
    loose++;
    log(`REFUSED (should be allowed): read config/recognizer (${e.code ?? e.message})`);
  }
  process.exitCode = loose ? 1 : 0;
}

async function main() {
  const [command = "run", ...args] = process.argv.slice(2);
  await signInWithEmailAndPassword(auth, env("DEVICE_EMAIL"), env("DEVICE_PASSWORD"));
  log(`signed in as ${auth.currentUser.uid} for ${lockerId}`);

  switch (command) {
    case "run": {
      await heartbeat();
      setInterval(() => heartbeat().catch((e) => log("heartbeat failed:", e.code ?? e.message)), HEARTBEAT_INTERVAL_MS);
      log("waiting for remote unlocks (Ctrl+C to stop; the app shows Offline ~2 min later)");
      for (;;) {
        await pollUnlock().catch((e) => log("poll failed:", e.code ?? e.message));
        await sleep(POLL_INTERVAL_MS);
      }
    }
    case "granted": {
      const [uid, name, confidence] = args;
      if (!uid || !name) throw new Error('usage: granted <uid> "<name>" [confidence]');
      await writeLog({ granted: true, uid, name, confidence: confidence ? Number(confidence) : undefined });
      break;
    }
    case "denied":
      await writeLog({ granted: false, confidence: args[0] ? Number(args[0]) : undefined });
      break;
    case "check":
      await checkRules();
      break;
    default:
      throw new Error(`unknown command: ${command}`);
  }
  if (command !== "run") process.exit(process.exitCode ?? 0);
}

main().catch((e) => {
  console.error(e.code ?? "", e.message);
  process.exit(1);
});
