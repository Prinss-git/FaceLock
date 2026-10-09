"""
FaceLock recognition server.

Runs on a laptop on the same Wi-Fi as the locker board. Three jobs:

  1. Enrollment: every few seconds, picks up face photos the app uploaded to
     Storage (face_templates/{uid}/enroll.jpg), turns each into a face print
     in Firestore face_templates/{uid}, and deletes the photo.
  2. Checking: the board POSTs a photo to /recognize. The face is compared
     with the print of whoever the locker is assigned to, and only them.
  3. Alerts: when the board's last try fails, staff phones get a push.

It never writes access_logs; the board does that itself.

    python server.py
"""

import hmac
import logging
import os
import socket
import threading
import time
from pathlib import Path

from dotenv import load_dotenv
from flask import Flask, jsonify, request

load_dotenv(Path(__file__).parent / ".env")

import firebase_admin  # noqa: E402  (after load_dotenv: reads emulator env vars)
from firebase_admin import credentials, firestore, messaging, storage  # noqa: E402

from faces import DEFAULT_THRESHOLD, FaceEngine, decode_jpeg, similarity  # noqa: E402

DEVICE_KEY = os.environ.get("DEVICE_KEY", "")
THRESHOLD = float(os.environ.get("MATCH_THRESHOLD", DEFAULT_THRESHOLD))
PORT = int(os.environ.get("PORT", "5000"))
SYNC_SECONDS = int(os.environ.get("ENROLL_SYNC_SECONDS", "15"))

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s", datefmt="%H:%M:%S")
log = logging.getLogger("facelock")


# ---------------------------------------------------------------- Firebase --

def init_firebase():
    options = {"projectId": os.environ["FIREBASE_PROJECT_ID"]}
    bucket = os.environ.get("FIREBASE_STORAGE_BUCKET")
    if bucket:
        options["storageBucket"] = bucket
    key = os.environ.get("SERVICE_ACCOUNT_KEY")
    if os.environ.get("FIRESTORE_EMULATOR_HOST") and not key:
        # Local emulators (testing only) accept anonymous credentials.
        from google.auth.credentials import AnonymousCredentials

        class Anonymous(credentials.Base):
            def get_credential(self):
                return AnonymousCredentials()

        firebase_admin.initialize_app(Anonymous(), options)
    else:
        if not key or not Path(key).exists():
            raise SystemExit("SERVICE_ACCOUNT_KEY in .env must point at the key file "
                             "(Firebase console > Project settings > Service accounts).")
        firebase_admin.initialize_app(credentials.Certificate(key), options)
    return firestore.client()


def lan_ip() -> str:
    """The laptop's address on the current Wi-Fi (no packet is actually sent)."""
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]


def publish_address(db):
    """Boards read config/recognizer, so a new hotspot IP needs no re-flash."""
    url = os.environ.get("PUBLIC_URL") or f"http://{lan_ip()}:{PORT}/recognize"
    db.collection("config").document("recognizer").set(
        {"url": url, "updatedAt": int(time.time() * 1000)})
    log.info("published %s to config/recognizer", url)
    return url


# -------------------------------------------------------------- enrollment --

def enroll_photo(db, engine, uid: str, data: bytes) -> bool:
    """Stores the face print for one uploaded photo. False if it is unusable."""
    image = decode_jpeg(data)
    face_print, count = engine.print_of(image) if image is not None else (None, 0)
    user_ref = db.collection("users").document(uid)
    if face_print is None or count != 1:
        # The app checks for exactly one face before uploading, so this is rare.
        # Clearing the flag makes the app ask the member to enroll again.
        log.warning("enroll %s: %s; asking for a new photo", uid,
                    "no face found" if count == 0 else f"{count} faces")
        user_ref.update({"faceEnrolled": False})
        return False
    user = user_ref.get().to_dict() or {}
    db.collection("face_templates").document(uid).set({
        "embedding": [float(x) for x in face_print],
        "fullName": user.get("fullName"),
        "updatedAt": int(time.time() * 1000),
    })
    log.info("enrolled %s (%s)", uid, user.get("fullName"))
    return True


def sync_enrollments(db, engine):
    """One pass over Storage: every waiting photo becomes a face print."""
    bucket = storage.bucket()
    for blob in bucket.list_blobs(prefix="face_templates/"):
        parts = blob.name.split("/")
        if len(parts) != 3 or parts[2] != "enroll.jpg":
            continue
        try:
            enroll_photo(db, engine, parts[1], blob.download_as_bytes())
        finally:
            blob.delete()   # Raw photos are never kept (privacy note in README)


def enrollment_loop(db, engine):
    while True:
        try:
            sync_enrollments(db, engine)
        except Exception as e:  # keep the loop alive through network blips
            log.warning("enrollment sync failed: %s", e)
        time.sleep(SYNC_SECONDS)


# ----------------------------------------------------------------- alerts --

def alert_staff(db, locker_id: str):
    staff = db.collection("users").where(
        filter=firestore.FieldFilter("role", "in", ["ADMIN", "SECURITY"])).stream()
    tokens = [d.get("fcmToken") for d in staff if d.get("active") is not False and d.get("fcmToken")]
    if not tokens:
        return
    result = messaging.send_each_for_multicast(messaging.MulticastMessage(
        tokens=tokens,
        notification=messaging.Notification(
            title="Unauthorized access attempt",
            body=f"Locker {locker_id} denied an unrecognized person."),
        data={"lockerId": locker_id, "type": "ACCESS_DENIED"},
    ))
    log.info("alert sent to %d/%d staff phones", result.success_count, len(tokens))


# -------------------------------------------------------------------- web --

def create_app(db, engine) -> Flask:
    app = Flask(__name__)

    @app.get("/health")
    def health():
        return {"ok": True}

    @app.post("/recognize")
    def recognize():
        if not DEVICE_KEY or not hmac.compare_digest(request.headers.get("X-Device-Key", ""), DEVICE_KEY):
            return jsonify(error="Unrecognized device"), 401
        locker_id = request.headers.get("X-Locker-Id", "")
        if not locker_id:
            return jsonify(error="Missing X-Locker-Id"), 400

        last_try = request.headers.get("X-Last-Try") == "1"
        earlier_mismatches = int(request.headers.get("X-Mismatches", "0") or 0)

        image = decode_jpeg(request.get_data())
        if image is None:
            return jsonify(error="Body is not an image"), 400

        verdict = check(db, engine, locker_id, image)
        log.info("%s: %s", locker_id, verdict)

        # One alert per attempt: only after the final photo, and only if at
        # least one photo actually showed the wrong face.
        mismatched = earlier_mismatches + (0 if verdict["noFace"] else 1)
        if last_try and not verdict["granted"] and mismatched > 0:
            try:
                alert_staff(db, locker_id)
            except Exception as e:
                log.warning("alert failed: %s", e)
        return jsonify(verdict)

    return app


def check(db, engine, locker_id: str, image) -> dict:
    """Decides one photo. "reason" is for the server log; the board ignores it."""
    def answer(granted=False, no_face=False, uid=None, name=None, score=None, reason=""):
        return {"granted": granted, "noFace": no_face, "uid": uid, "name": name or "Not recognized",
                "confidence": None if score is None else round(max(0.0, min(1.0, score)), 3),
                "reason": reason}

    face_print, _ = engine.print_of(image)
    if face_print is None:
        return answer(no_face=True, reason="no face in photo")

    locker = db.collection("lockers").document(locker_id).get()
    if not locker.exists:
        return answer(reason=f"unknown locker {locker_id}")
    locker = locker.to_dict()
    if locker.get("status") == "OUT_OF_SERVICE":
        return answer(reason="locker out of service")
    uid = locker.get("assignedUid")
    if not uid:
        return answer(reason="locker not assigned")

    template = db.collection("face_templates").document(uid).get()
    if not template.exists or not template.get("embedding"):
        return answer(reason="owner has no face print yet")
    score = similarity(face_print, template.get("embedding"))

    user = db.collection("users").document(uid).get().to_dict() or {}
    if score < THRESHOLD:
        return answer(score=score, reason=f"score {score:.3f} < {THRESHOLD}")
    if user.get("active") is False:
        return answer(score=score, reason="owner is suspended")
    return answer(granted=True, uid=uid, name=user.get("fullName"), score=score, reason="match")


def main():
    if not DEVICE_KEY:
        raise SystemExit("Set DEVICE_KEY in .env (same value as SECRET_RECOGNIZE_KEY on the board).")
    db = init_firebase()
    engine = FaceEngine()
    publish_address(db)
    threading.Thread(target=enrollment_loop, args=(db, FaceEngine()), daemon=True).start()
    log.info("listening on port %d (Ctrl+C to stop)", PORT)
    create_app(db, engine).run(host="0.0.0.0", port=PORT, threaded=False)


if __name__ == "__main__":
    main()
