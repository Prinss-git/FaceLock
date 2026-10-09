"""
Try face matching with the laptop webcam, before the board exists.

  python webcam_test.py --photo me.jpg       compare the webcam with a photo (no Firebase needed)
  python webcam_test.py --uid <uid>          compare with an enrolled member's face print

A window shows the camera. The score and MATCH / no match are drawn on it.
Press Q to quit.
"""

import argparse
import os
from pathlib import Path

import cv2

from faces import DEFAULT_THRESHOLD, FaceEngine, similarity


def reference_print(args, engine):
    if args.photo:
        image = cv2.imread(args.photo)
        if image is None:
            raise SystemExit(f"Cannot read {args.photo}")
        face_print, count = engine.print_of(image)
        if face_print is None:
            raise SystemExit("No face found in that photo.")
        if count > 1:
            print("Several faces in the photo; using the biggest.")
        return face_print, Path(args.photo).name

    # Same .env and key as the server.
    from dotenv import load_dotenv
    import firebase_admin
    from firebase_admin import credentials, firestore
    load_dotenv(Path(__file__).parent / ".env")
    firebase_admin.initialize_app(credentials.Certificate(os.environ["SERVICE_ACCOUNT_KEY"]))
    doc = firestore.client().collection("face_templates").document(args.uid).get()
    if not doc.exists:
        raise SystemExit("No face print for that uid. Enroll in the app and let the server run once.")
    return doc.get("embedding"), doc.get("fullName") or args.uid


def main():
    parser = argparse.ArgumentParser()
    who = parser.add_mutually_exclusive_group(required=True)
    who.add_argument("--photo", help="photo of the person to match")
    who.add_argument("--uid", help="enrolled member's uid")
    parser.add_argument("--camera", type=int, default=0)
    parser.add_argument("--threshold", type=float,
                        default=float(os.environ.get("MATCH_THRESHOLD", DEFAULT_THRESHOLD)))
    args = parser.parse_args()

    engine = FaceEngine()
    reference, label = reference_print(args, engine)
    print(f"Matching against {label}. Threshold {args.threshold}. Press Q to quit.")

    cam = cv2.VideoCapture(args.camera)
    while True:
        ok, frame = cam.read()
        if not ok:
            raise SystemExit("Cannot read the webcam.")
        faces = engine.detect(frame)
        if faces:
            x, y, w, h = map(int, faces[0][:4])
            score = similarity(engine.embed(frame, faces[0]), reference)
            match = score >= args.threshold
            color = (0, 200, 0) if match else (0, 0, 255)
            cv2.rectangle(frame, (x, y), (x + w, y + h), color, 2)
            cv2.putText(frame, f"{'MATCH' if match else 'no match'}  {score:.2f}",
                        (x, max(20, y - 10)), cv2.FONT_HERSHEY_SIMPLEX, 0.8, color, 2)
        cv2.imshow("FaceLock webcam test", frame)
        if cv2.waitKey(1) & 0xFF in (ord("q"), ord("Q")):
            break
    cam.release()
    cv2.destroyAllWindows()


if __name__ == "__main__":
    main()
