package com.eldroid.facelock.data.model

import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.Exclude

/**
 * One entry in the admin activity trail: who changed what, and when.
 * Append-only — firestore.rules allows create but never update or delete.
 */
data class AdminAction(
    @DocumentId val id: String = "",
    val actorUid: String = "",
    val actorName: String = "",
    /** An [AdminActionType] name. */
    val action: String = "",
    /** What was acted on: a locker ID, building code or person's name. */
    val target: String = "",
    val details: String? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    @get:Exclude
    val type: AdminActionType? get() = AdminActionType.entries.firstOrNull { it.name == action }
}

enum class AdminActionType(val label: String) {
    LOCKER_ASSIGNED("Assigned locker"),
    LOCKER_UNASSIGNED("Unassigned locker"),
    LOCKER_UNLOCK_REQUESTED("Requested remote unlock"),
    LOCKER_REMOVED("Removed locker"),
    LOCKER_OUT_OF_SERVICE("Took locker out of service"),
    LOCKER_BACK_IN_SERVICE("Put locker back in service"),
    LOCKERS_ADDED("Added lockers"),
    LOCKERS_MIGRATED("Sorted lockers into buildings"),
    LOCKERS_LINKED("Linked old locker IDs"),
    BUILDING_ADDED("Added building"),
    BUILDING_EDITED("Edited building"),
    BUILDING_DELETED("Deleted building"),
    USER_CREATED("Created account"),
    USER_ROLE_CHANGED("Changed role"),
    USER_SUSPENDED("Suspended account"),
    USER_RESTORED("Restored account"),
    USER_DELETED("Deleted profile")
}
