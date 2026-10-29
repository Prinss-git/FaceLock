/*
 * FaceLock - access log seeder
 * IT-ELDRIOD1 | Facial Recognition Locker System
 *
 * Fills access_logs with a realistic week of traffic so the Logs, My History
 * and dashboard screens have something to show.
 *
 * WHY THIS EXISTS OUTSIDE THE APP
 *   firestore.rules blocks every client write to access_logs - the audit trail
 *   is meant to be written server-side only, and that guarantee is worth more
 *   than the convenience of seeding from the phone. The Admin SDK bypasses the
 *   rules legitimately, and it runs on the free Spark plan: only *deploying*
 *   Cloud Functions needs Blaze, not using firebase-admin locally.
 *
 * IT READS YOUR REAL DATA
 *   Logs are generated against the users and lockers already in Firestore, so
 *   names and locker ids on screen match the rest of the demo. Seed a few
 *   users and lockers in the app first.
 *
 * SAFE TO RE-RUN
 *   Every document uses a fixed id (demo-log-000...), so running it twice
 *   overwrites the same rows instead of piling up duplicates.
 *
 * USAGE
 *   1. Firebase console -> Project settings -> Service accounts
 *      -> Generate new private key. Save it next to this file as
 *      serviceAccountKey.json (it is gitignored - never commit it).
 *   2. cd tools/seed-logs && npm install
 *   3. node seed-logs.js            seed the default week
 *      node seed-logs.js --count 80 seed a different number
 *      node seed-logs.js --clear    remove everything this script created
 */

const admin = require('firebase-admin');
const path = require('path');

// ----------------------- CONFIGURATION -----------------------
const KEY_PATH = path.join(__dirname, 'serviceAccountKey.json');

const DEFAULT_COUNT = 60;
const DAYS_BACK = 7;

// Roughly one in eight attempts fails. Enough denied rows to make the filter
// chips and the denied stat tile worth looking at, without implying the system
// is unreliable.
const DENIED_RATE = 0.125;

// Named so it is obvious in the console which rows are seeded.
const DOC_PREFIX = 'demo-log-';

// ----------------------- ARGUMENTS -----------------------
const args = process.argv.slice(2);
const clearOnly = args.includes('--clear');
const countFlag = args.indexOf('--count');
const COUNT = countFlag >= 0 ? parseInt(args[countFlag + 1], 10) : DEFAULT_COUNT;

if (Number.isNaN(COUNT) || COUNT < 1 || COUNT > 500) {
  console.error('--count must be a whole number between 1 and 500');
  process.exit(1);
}

// ----------------------- SETUP -----------------------
let serviceAccount;
try {
  serviceAccount = require(KEY_PATH);
} catch (e) {
  console.error('\nCould not read serviceAccountKey.json.\n');
  console.error('  Firebase console -> Project settings -> Service accounts');
  console.error('  -> Generate new private key, and save it as:');
  console.error('  ' + KEY_PATH + '\n');
  process.exit(1);
}

admin.initializeApp({ credential: admin.credential.cert(serviceAccount) });
const db = admin.firestore();

// ----------------------- HELPERS -----------------------
const docId = (i) => DOC_PREFIX + String(i).padStart(3, '0');

function randomInt(min, max) {
  return Math.floor(Math.random() * (max - min + 1)) + min;
}

function pick(list) {
  return list[randomInt(0, list.length - 1)];
}

/**
 * A timestamp inside the last DAYS_BACK days, biased toward waking hours.
 * Logs scattered evenly across the clock look generated; locker traffic
 * clusters around classes.
 */
function plausibleTimestamp() {
  const daysAgo = randomInt(0, DAYS_BACK - 1);
  const hour = pick([7, 8, 9, 10, 11, 12, 13, 13, 14, 15, 16, 16, 17, 18, 19]);

  const when = new Date();
  when.setDate(when.getDate() - daysAgo);
  when.setHours(hour, randomInt(0, 59), randomInt(0, 59), 0);

  // Never place a log in the future - today's rows must stay within today, or
  // the "today" counters on the dashboard count events that have not happened.
  return Math.min(when.getTime(), Date.now() - 60 * 1000);
}

// ----------------------- CLEAR -----------------------
async function clear() {
  const snap = await db.collection('access_logs').get();
  const seeded = snap.docs.filter((d) => d.id.startsWith(DOC_PREFIX));

  if (seeded.length === 0) {
    console.log('Nothing to clear - no seeded logs found.');
    return;
  }

  // Firestore caps a batch at 500 writes.
  for (let i = 0; i < seeded.length; i += 450) {
    const batch = db.batch();
    seeded.slice(i, i + 450).forEach((d) => batch.delete(d.ref));
    await batch.commit();
  }

  console.log('Removed ' + seeded.length + ' seeded logs.');
  console.log('Real logs were left alone.');
}

// ----------------------- SEED -----------------------
async function seed() {
  const [usersSnap, lockersSnap] = await Promise.all([
    db.collection('users').get(),
    db.collection('lockers').get()
  ]);

  // Members only. An admin does not open a locker with their face, so seeding
  // their name into the access log would misrepresent what the trail means.
  const members = usersSnap.docs
    .map((d) => ({ uid: d.id, ...d.data() }))
    .filter((u) => (u.role || 'USER').toUpperCase() === 'USER');

  const lockerIds = lockersSnap.docs.map((d) => d.id);

  if (members.length === 0 || lockerIds.length === 0) {
    console.error('\nNeed at least one member and one locker in Firestore first.');
    console.error('  members found: ' + members.length);
    console.error('  lockers found: ' + lockerIds.length);
    console.error('\nCreate a few in the app, then run this again.\n');
    process.exit(1);
  }

  const rows = [];
  for (let i = 0; i < COUNT; i++) {
    const denied = Math.random() < DENIED_RATE;
    const member = pick(members);

    // A denied attempt is an unrecognised face: the system does not know who
    // it was, so uid and userName stay null and confidence sits below the
    // matching threshold. Filling those in would describe a different event.
    rows.push({
      id: docId(i),
      lockerId: member.lockerId || pick(lockerIds),
      uid: denied ? null : member.uid,
      userName: denied ? null : member.fullName || null,
      result: denied ? 'DENIED' : 'GRANTED',
      confidence: denied
        ? Number((Math.random() * 0.25 + 0.30).toFixed(3))   // 0.30 - 0.55
        : Number((Math.random() * 0.13 + 0.86).toFixed(3)),  // 0.86 - 0.99
      timestamp: plausibleTimestamp()
    });
  }

  rows.sort((a, b) => a.timestamp - b.timestamp);

  for (let i = 0; i < rows.length; i += 450) {
    const batch = db.batch();
    rows.slice(i, i + 450).forEach((row) => {
      const { id, ...data } = row;
      batch.set(db.collection('access_logs').doc(id), data);
    });
    await batch.commit();
  }

  const granted = rows.filter((r) => r.result === 'GRANTED').length;
  console.log('');
  console.log('Seeded ' + rows.length + ' access logs across ' + DAYS_BACK + ' days.');
  console.log('  granted: ' + granted);
  console.log('  denied:  ' + (rows.length - granted));
  console.log('  members: ' + members.length + ', lockers: ' + lockerIds.length);
  console.log('');
  console.log('Run with --clear to remove them again.');
}

// ----------------------- MAIN -----------------------
(clearOnly ? clear() : seed())
  .then(() => process.exit(0))
  .catch((e) => {
    console.error('\nFailed: ' + e.message + '\n');
    process.exit(1);
  });
