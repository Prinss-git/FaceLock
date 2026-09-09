package com.eldroid.facelock.data.model

import com.google.firebase.firestore.DocumentId

data class AccessLog(
    @DocumentId val id: String = "",
    val lockerId: String = "",
    val uid: String? = null,
    val userName: String? = null,
    val result: String = RESULT_DENIED,
    val confidence: Double? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    val granted: Boolean get() = result.equals(RESULT_GRANTED, ignoreCase = true)

    companion object {
        const val RESULT_GRANTED = "GRANTED"
        const val RESULT_DENIED = "DENIED"
    }
}
