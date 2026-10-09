# FaceLock recognition server

A small Python program that runs on a laptop and answers the locker board's
question: **"is this the face of the person this locker belongs to?"**

```
 App ──enroll photo──► Storage ──► server makes a face print ──► Firestore face_templates/{uid}
 Board ──photo──► server ──"granted: Juan" / "no match"──► board opens + writes the log
```

It does three things:
1. **Enrollment:** every 15 s, turns new face photos from the app into face
   prints (128 numbers each), then deletes the photos.
2. **Checking:** compares the board's photo with the print of the person the
   locker is *currently* assigned to, and refuses suspended accounts.
3. **Alerts:** after the board's last failed try, pushes a notification to
   admin and security phones.

It uses OpenCV's built-in face models (YuNet to find faces, SFace to compare
them). Nothing needs a C++ compiler, and nothing costs money.

## One-time setup (Windows)

Run these in this folder (`recognizer/`) in a terminal:

```bash
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
python download_models.py
copy .env.example .env
```

Then:

1. **Service-account key.** Firebase console → Project settings → Service
   accounts → **Generate new private key**. Save the file in this folder as
   `serviceAccountKey.json`. It is a full-access password: never commit it or
   send it around (`.gitignore` already blocks it).
2. **Storage bucket name.** Firebase console → Storage; copy the name at the
   top without `gs://` into `FIREBASE_STORAGE_BUCKET` in `.env`.
3. **Device key.** Make up a long random string and put it in `.env` as
   `DEVICE_KEY` *and* in the board's `secrets.h` as `SECRET_RECOGNIZE_KEY`.

## Running it

```bash
.venv\Scripts\activate
python server.py
```

Leave the window open. It prints each check, e.g.
`M-001: {'granted': True, ... 'reason': 'match'}`.

On start it saves the laptop's address to Firestore (`config/recognizer`). The
board reads it from there, so a new hotspot IP needs **no re-flashing**.

**The first time,** Windows asks whether Python may use the network. Allow it,
including **Public networks**, because phone hotspots usually count as public.
If the board says "Server error" while the server is running, this firewall
prompt is the usual cause.

## Testing without the board

```bash
python webcam_test.py --photo me.jpg     # webcam vs. a photo; no Firebase needed
python webcam_test.py --uid <uid>        # webcam vs. an enrolled member's face print
```

A box shows **MATCH** (green) or **no match** (red) with the score. The same
person usually scores 0.5–0.9 and different people below 0.3. The cut-off is
`MATCH_THRESHOLD` in `.env` (default 0.363); raise it if strangers ever match.

To send a photo the way the board does:

```bash
curl -X POST --data-binary @face.jpg -H "X-Device-Key: <DEVICE_KEY>" -H "X-Locker-Id: M-001" http://localhost:5000/recognize
```

## What the board gets back

```json
{"granted": true, "uid": "abc123", "name": "Juan Dela Cruz", "confidence": 0.82, "noFace": false, "reason": "match"}
```

`noFace: true` means nobody was in the photo; the board retakes it without
counting a mismatch. `reason` is only for the server log.
