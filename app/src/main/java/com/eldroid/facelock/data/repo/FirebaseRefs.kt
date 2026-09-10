package com.eldroid.facelock.data.repo

import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage

object FirebaseRefs {
    val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    val storage: FirebaseStorage by lazy { FirebaseStorage.getInstance() }

    /**
     * Base URL of the deployed Cloud Functions. The project id is read from
     * google-services.json so this survives a project change; only the region
     * is fixed, and it matches the default the functions deploy to because
     * none of them call .region().
     */
    private const val REGION = "us-central1"
    val functionsBase: String
        get() = "https://$REGION-${FirebaseApp.getInstance().options.projectId}.cloudfunctions.net"

    const val USERS = "users"
    const val PASSWORD_RESETS = "password_resets"
    const val LOCKERS = "lockers"
    const val ACCESS_LOGS = "access_logs"
    const val FACE_TEMPLATES = "face_templates"
}
