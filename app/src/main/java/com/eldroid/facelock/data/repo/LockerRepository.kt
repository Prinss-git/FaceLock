package com.eldroid.facelock.data.repo

import com.eldroid.facelock.data.model.Locker
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class LockerRepository {

    private val col get() = FirebaseRefs.db.collection(FirebaseRefs.LOCKERS)

    fun observeLockers(): Flow<List<Locker>> = callbackFlow {
        val reg = col.orderBy("label").addSnapshotListener { snap, err ->
            if (err != null) { close(err); return@addSnapshotListener }
            trySend(snap?.toObjects(Locker::class.java).orEmpty())
        }
        awaitClose { reg.remove() }
    }

    fun observeLocker(lockerId: String): Flow<Locker?> = callbackFlow {
        val reg = col.document(lockerId).addSnapshotListener { snap, err ->
            if (err != null) { close(err); return@addSnapshotListener }
            trySend(snap?.toObject(Locker::class.java))
        }
        awaitClose { reg.remove() }
    }

    suspend fun createLocker(locker: Locker): Result<Unit> = runCatching {
        col.document(locker.id).set(locker).await()
    }

    /** Assigns a locker to a user and flips its status in one write. */
    suspend fun assign(lockerId: String, uid: String?, name: String?): Result<Unit> =
        runCatching {
            col.document(lockerId).update(
                mapOf(
                    "assignedUid" to uid,
                    "assignedName" to name,
                    "status" to if (uid == null) Locker.STATUS_AVAILABLE else Locker.STATUS_OCCUPIED
                )
            ).await()
        }

    /**
     * Writes a remote-unlock request. The ESP32 listens on this document and
     * drives the relay, then clears the flag.
     */
    suspend fun requestRemoteUnlock(lockerId: String): Result<Unit> = runCatching {
        col.document(lockerId).update(
            mapOf(
                "unlockRequested" to true,
                "unlockRequestedAt" to System.currentTimeMillis()
            )
        ).await()
    }

    suspend fun deleteLocker(lockerId: String): Result<Unit> =
        runCatching { col.document(lockerId).delete().await() }
}
