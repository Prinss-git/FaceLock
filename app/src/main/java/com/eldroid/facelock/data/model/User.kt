package com.eldroid.facelock.data.model

import com.google.firebase.firestore.DocumentId

data class User(
    @DocumentId val uid: String = "",
    val firstName: String = "",
    val lastName: String = "",
    val fullName: String = "",
    val email: String = "",
    val role: String = "USER",
    val lockerId: String? = null,
    val faceEnrolled: Boolean = false,
    val active: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
) {
    val roleEnum: Role get() = Role.from(role)
}
