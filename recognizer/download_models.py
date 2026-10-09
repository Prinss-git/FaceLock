"""Downloads the two face models into models/. Run once after installing."""

import urllib.request

from faces import MODELS, SFACE, YUNET

BASE = "https://github.com/opencv/opencv_zoo/raw/main/models"
SOURCES = {
    YUNET: f"{BASE}/face_detection_yunet/{YUNET.name}",
    SFACE: f"{BASE}/face_recognition_sface/{SFACE.name}",
}

MODELS.mkdir(exist_ok=True)
for path, url in SOURCES.items():
    if path.exists() and path.stat().st_size > 100_000:
        print(f"already have {path.name}")
        continue
    print(f"downloading {path.name} ...")
    urllib.request.urlretrieve(url, path)
    print(f"  {path.stat().st_size // 1024} KB")
print("done")
