package com.eldroid.facelock.data.model

import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.Exclude

data class Locker(
    @DocumentId val id: String = "",
    val label: String = "",
    /** Display string, e.g. "Main Building · 1F". Kept for older screens. */
    val location: String = "",
    /** Code of the [Building] it stands in; null for lockers made before buildings. */
    val building: String? = null,
    val floor: Int? = null,
    /** The pre-buildings ID (e.g. L-011) this locker was re-created from, if any. */
    val formerId: String? = null,
    val assignedUid: String? = null,
    val assignedName: String? = null,
    val status: String = STATUS_AVAILABLE,
    val lastOpenedAt: Long? = null
) {
    val isAvailable: Boolean get() = assignedUid.isNullOrBlank()

    /** Retired lockers can't be assigned or unlocked until put back in service. */
    @get:Exclude
    val isInService: Boolean get() = status != STATUS_OUT_OF_SERVICE

    /** Free to hand out right now. */
    @get:Exclude
    val isFree: Boolean get() = isAvailable && isInService

    companion object {
        const val STATUS_AVAILABLE = "AVAILABLE"
        const val STATUS_OCCUPIED = "OCCUPIED"
        const val STATUS_LOCKED = "LOCKED"
        const val STATUS_OFFLINE = "OFFLINE"
        const val STATUS_OUT_OF_SERVICE = "OUT_OF_SERVICE"
    }
}
