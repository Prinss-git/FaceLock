package com.eldroid.facelock.data.model

import com.google.firebase.firestore.DocumentId

/**
 * A member waiting for an admin to reset their password.
 *
 * Written only by the Cloud Functions — clients can read the queue (admins
 * only) but never write to it. The temporary password is never part of this
 * document; it is returned once, to the admin who approves.
 */
data class PasswordResetRequest(
    @DocumentId val id: String = "",
    val uid: String = "",
    val email: String = "",
    val displayName: String? = null,
    val status: String = STATUS_PENDING,
    val requestedAt: Long = System.currentTimeMillis(),
    val handledBy: String? = null,
    val handledByName: String? = null,
    val handledAt: Long? = null
) {
    val isPending: Boolean get() = status == STATUS_PENDING

    /** Falls back to the email when the profile had no name on it. */
    val label: String get() = displayName?.takeIf { it.isNotBlank() } ?: email

    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_REJECTED = "REJECTED"
    }
}
