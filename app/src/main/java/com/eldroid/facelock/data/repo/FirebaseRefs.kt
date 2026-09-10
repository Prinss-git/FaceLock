package com.eldroid.facelock.data.repo

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage

object FirebaseRefs {
    val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    val storage: FirebaseStorage by lazy { FirebaseStorage.getInstance() }

    const val USERS = "users"
    const val LOCKERS = "lockers"
    const val ACCESS_LOGS = "access_logs"
    const val FACE_TEMPLATES = "face_templates"
}
