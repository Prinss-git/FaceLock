package com.eldroid.facelock.data.repo

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage

object FirebaseRefs {
    val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    val storage: FirebaseStorage by lazy { FirebaseStorage.getInstance() }

    /**
     * The Realtime Database lives in asia-southeast1 and google-services.json
     * carries no firebase_url, so the URL has to be given explicitly rather
     * than auto-discovered. Re-downloading google-services.json from the
     * console would add it, but naming it here works either way.
     */
    const val RTDB_URL =
        "https://facelock-eldroid-default-rtdb.asia-southeast1.firebasedatabase.app"

    /** Path the ESP32 sketch writes its heartbeat value to. */
    const val RTDB_TEST_PATH = "test/data"

    const val USERS = "users"
    const val LOCKERS = "lockers"
    const val ACCESS_LOGS = "access_logs"
    const val FACE_TEMPLATES = "face_templates"
}
