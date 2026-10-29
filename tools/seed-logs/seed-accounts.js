/*
 * FaceLock - demo account and data seeder
 * IT-ELDRIOD1 | Facial Recognition Locker System
 *
 * Creates the accounts, buildings and lockers needed to film a demo, with
 * passwords you know in advance.
 *
 * WHY THIS EXISTS OUTSIDE THE APP
 *   The app signs you in as whoever it just registered - the Firebase client
 *   SDK swaps the session - so an administrator cannot create a second account
 *   without being thrown out of their own. The Admin SDK has no such problem,
 *   and it runs on the free Spark plan.
 *
 * SAFE TO RE-RUN
 *   Accounts are matched by email and updated rather than duplicated. Buildings
 *   and lockers use fixed ids. Running it twice changes nothing the second time.
 *
 * USAGE
 *   node seed-accounts.js           create the demo accounts and data
 *   node seed-accounts.js --clear   delete everything this script created
 *
 * Run this BEFORE seed-logs.js - the logs are generated against these members.
 */

const admin = require('firebase-admin');
const path = require('path');

// ----------------------- CONFIGURATION -----------------------
const KEY_PATH = path.join(__dirname, 'serviceAccountKey.json');

// One password for every demo account. Long enough to pass the app's own
// complexity rule, so Change Password can be demonstrated against it.
const PASSWORD = 'FaceLock#2026';

const ACCOUNTS = [
  { email: 'admin@facelock.test',    first: 'Prince Christian', last: 'Parnada', role: 'ADMIN' },
  { email: 'security@facelock.test', first: 'Alexa Shane',      last: 'Adolfo',  role: 'SECURITY' },
  { email: 'member@facelock.test',   first: 'Samantha',         last: 'Cortes',  role: 'USER', locker: 'M-101' },
  { email: 'member2@facelock.test',  first: 'Danniezon Meir',   last: 'Po',      role: 'USER', locker: 'M-102' },
  { email: 'member3@facelock.test',  first: 'Jose',             last: 'Rizal',   role: 'USER', locker: 'E-201' },
  { email: 'member4@facelock.test',  first: 'Andres',           last: 'Bonifacio', role: 'USER' },
  // Left unassigned on purpose: gives you a member to assign a locker to on
  // camera, and keeps the "no locker yet" empty state reachable.
  { email: 'member5@facelock.test',  first: 'Gabriela',         last: 'Silang',  role: 'USER' }
];

const BUILDINGS = [
  { code: 'M', name: 'Main Building',        floors: 3, groundFloor: true },
  { code: 'E', name: 'Engineering Building', floors: 2, groundFloor: false }
];

// Enough lockers to fill the grouped list without scrolling forever.
const LOCKERS = [
  { id: 'M-101', building: 'M', floor: 1 }, { id: 'M-102', building: 'M', floor: 1 },
  { id: 'M-103', building: 'M', floor: 1 }, { id: 'M-104', building: 'M', floor: 1 },
  { id: 'M-201', building: 'M', floor: 2 }, { id: 'M-202', building: 'M', floor: 2 },
  { id: 'M-203', building: 'M', floor: 2 },
  { id: 'E-201', building: 'E', floor: 2 }, { id: 'E-202', building: 'E', floor: 2 },
  { id: 'E-203', building: 'E', floor: 2 }
];

// ----------------------- SETUP -----------------------
const clearOnly = process.argv.slice(2).includes('--clear');

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
const auth = admin.auth();
const db = admin.firestore();

// ----------------------- HELPERS -----------------------
/** Creates the auth user, or reuses and resets the one already on that email. */
async function upsertAuthUser(account) {
  const displayName = account.first + ' ' + account.last;
  try {
    const existing = await auth.getUserByEmail(account.email);
    await auth.updateUser(existing.uid, { password: PASSWORD, displayName });
    return { uid: existing.uid, created: false };
  } catch (e) {
    if (e.code !== 'auth/user-not-found') throw e;
    const made = await auth.createUser({
      email: account.email,
      password: PASSWORD,
      displayName,
      emailVerified: true
    });
    return { uid: made.uid, created: true };
  }
}

function buildingOf(code) {
  return BUILDINGS.find((b) => b.code === code);
}

/** "Main Building · GF" - matches Building.locationOf() in the app. */
function locationOf(code, floor) {
  const b = buildingOf(code);
  if (!b) return '';
  const label = b.groundFloor && floor === 1 ? 'GF' : floor + 'F';
  return b.name + ' · ' + label;
}

// ----------------------- CLEAR -----------------------
async function clear() {
  let removed = 0;

  for (const account of ACCOUNTS) {
    try {
      const user = await auth.getUserByEmail(account.email);
      await auth.deleteUser(user.uid);
      await db.collection('users').doc(user.uid).delete();
      removed++;
    } catch (e) {
      if (e.code !== 'auth/user-not-found') throw e;
    }
  }

  const batch = db.batch();
  LOCKERS.forEach((l) => batch.delete(db.collection('lockers').doc(l.id)));
  BUILDINGS.forEach((b) => batch.delete(db.collection('buildings').doc(b.code)));
  await batch.commit();

  console.log('\nRemoved ' + removed + ' demo accounts, ' + LOCKERS.length +
              ' lockers and ' + BUILDINGS.length + ' buildings.');
  console.log('Run seed-logs.js --clear as well to remove the seeded logs.\n');
}

// ----------------------- SEED -----------------------
async function seed() {
  const now = Date.now();

  // --- buildings ---
  const bBatch = db.batch();
  BUILDINGS.forEach((b) => {
    bBatch.set(db.collection('buildings').doc(b.code), {
      name: b.name, floors: b.floors, groundFloor: b.groundFloor, createdAt: now
    });
  });
  await bBatch.commit();

  // --- accounts ---
  const made = [];
  for (const account of ACCOUNTS) {
    const { uid, created } = await upsertAuthUser(account);
    const fullName = account.first + ' ' + account.last;

    await db.collection('users').doc(uid).set({
      firstName: account.first,
      lastName: account.last,
      fullName,
      email: account.email,
      role: account.role,
      lockerId: account.locker || null,
      // One member starts enrolled so the "enrolled" state is on screen, while
      // the rest stay unenrolled so enrollment can be filmed live.
      faceEnrolled: account.email === 'member2@facelock.test',
      active: true,
      createdAt: now
    });

    made.push({ ...account, uid, fullName, created });
  }

  // --- lockers ---
  const lBatch = db.batch();
  LOCKERS.forEach((l) => {
    const holder = made.find((m) => m.locker === l.id);
    lBatch.set(db.collection('lockers').doc(l.id), {
      label: l.id,
      location: locationOf(l.building, l.floor),
      building: l.building,
      floor: l.floor,
      assignedUid: holder ? holder.uid : null,
      assignedName: holder ? holder.fullName : null,
      status: holder ? 'OCCUPIED' : 'AVAILABLE',
      lastOpenedAt: holder ? now - 1000 * 60 * 60 * 5 : null
    });
  });
  await lBatch.commit();

  // --- report ---
  console.log('\n  FaceLock demo accounts');
  console.log('  ' + '-'.repeat(58));
  console.log('  Password for every account:  ' + PASSWORD);
  console.log('  ' + '-'.repeat(58));
  made.forEach((m) => {
    const locker = m.locker ? m.locker : '(no locker - assign on camera)';
    console.log('  ' + m.role.padEnd(9) + m.email.padEnd(26) + locker);
  });
  console.log('  ' + '-'.repeat(58));
  console.log('  ' + BUILDINGS.length + ' buildings, ' + LOCKERS.length + ' lockers created.');
  console.log('');
  console.log('  Next: node seed-logs.js');
  console.log('');
}

// ----------------------- MAIN -----------------------
(clearOnly ? clear() : seed())
  .then(() => process.exit(0))
  .catch((e) => {
    console.error('\nFailed: ' + e.message + '\n');
    process.exit(1);
  });
