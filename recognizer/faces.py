"""
Face detection and comparison with OpenCV's built-in models.

  YuNet  finds faces in a photo and marks the eyes, nose and mouth.
  SFace  turns one face into 128 numbers (a "face print"). Two prints of the
         same person point in nearly the same direction; cosine similarity
         measures how close they are, from -1 (opposite) to 1 (identical).

Both models are small ONNX files that download_models.py fetches once.
"""

from pathlib import Path

import cv2
import numpy as np

# OpenCV 5 prints a harmless "Targets are not supported" warning per model.
cv2.utils.logging.setLogLevel(cv2.utils.logging.LOG_LEVEL_ERROR)

MODELS = Path(__file__).parent / "models"
YUNET = MODELS / "face_detection_yunet_2023mar.onnx"
SFACE = MODELS / "face_recognition_sface_2021dec.onnx"

# OpenCV's published cut-off for SFace cosine similarity. Above it, the two
# faces are treated as the same person. Raise it to be stricter.
DEFAULT_THRESHOLD = 0.363

# Faces smaller than this many pixels across are too blurry to trust.
MIN_FACE_PX = 60


class FaceEngine:
    def __init__(self):
        for model in (YUNET, SFACE):
            if not model.exists():
                raise SystemExit(f"Missing {model.name}. Run: python download_models.py")
        # The input size is reset per photo in detect().
        self.detector = cv2.FaceDetectorYN.create(str(YUNET), "", (320, 320), 0.8, 0.3, 5000)
        self.recognizer = cv2.FaceRecognizerSF.create(str(SFACE), "")

    def detect(self, image):
        """All faces in a BGR image, biggest first."""
        h, w = image.shape[:2]
        self.detector.setInputSize((w, h))
        _, faces = self.detector.detect(image)
        if faces is None:
            return []
        faces = [f for f in faces if min(f[2], f[3]) >= MIN_FACE_PX]
        return sorted(faces, key=lambda f: f[2] * f[3], reverse=True)

    def embed(self, image, face):
        """The 128-number face print of one detected face."""
        aligned = self.recognizer.alignCrop(image, face)
        feature = self.recognizer.feature(aligned).flatten().astype(np.float32)
        return feature / (np.linalg.norm(feature) or 1.0)

    def print_of(self, image):
        """(face print of the biggest face or None, number of faces seen)."""
        faces = self.detect(image)
        if not faces:
            return None, 0
        return self.embed(image, faces[0]), len(faces)


def decode_jpeg(data: bytes):
    """JPEG/PNG bytes to a BGR image, or None if they are not an image."""
    array = np.frombuffer(data, dtype=np.uint8)
    return cv2.imdecode(array, cv2.IMREAD_COLOR) if array.size else None


def similarity(a, b) -> float:
    """Cosine similarity of two face prints."""
    a = np.asarray(a, dtype=np.float32)
    b = np.asarray(b, dtype=np.float32)
    denom = float(np.linalg.norm(a) * np.linalg.norm(b))
    return float(np.dot(a, b) / denom) if denom else 0.0
