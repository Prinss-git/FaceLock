package com.eldroid.facelock.data.repo

import com.eldroid.facelock.data.model.AdminAction
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class AdminActionRepository {

    private val col get() = FirebaseRefs.db.collection(FirebaseRefs.ADMIN_ACTIONS)

    /**
     * Queues the write and returns at once. Awaiting it would hold the caller
     * until the server confirms — indefinitely while offline — and the entry is
     * sent by Firestore's own queue either way.
     */
    fun add(action: AdminAction, onFailure: (Exception) -> Unit) {
        col.add(action).addOnFailureListener(onFailure)
    }

    /** Newest first, from [since] on. Single-field range, so no composite index. */
    fun observeSince(since: Long, limit: Long): Flow<List<AdminAction>> = callbackFlow {
        val reg = col
            .whereGreaterThanOrEqualTo("timestamp", since)
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(limit)
            .addSnapshotListener { snap, err ->
                if (err != null) { close(err); return@addSnapshotListener }
                trySend(snap?.toObjects(AdminAction::class.java).orEmpty())
            }
        awaitClose { reg.remove() }
    }
}
