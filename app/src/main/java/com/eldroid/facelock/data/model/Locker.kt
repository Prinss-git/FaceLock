package com.eldroid.facelock.data.model

import com.google.firebase.firestore.DocumentId

data class Locker(
    @DocumentId val id: String = "",
    val label: String = "",
    val location: String = "",
    val assignedUid: String? = null,
    val assignedName: String? = null,
    val status: String = STATUS_AVAILABLE,
    val lastOpenedAt: Long? = null
) {
    val isAvailable: Boolean get() = assignedUid.isNullOrBlank()

    companion object {
        const val STATUS_AVAILABLE = "AVAILABLE"
        const val STATUS_OCCUPIED = "OCCUPIED"
        const val STATUS_LOCKED = "LOCKED"
        const val STATUS_OFFLINE = "OFFLINE"
    }
}
