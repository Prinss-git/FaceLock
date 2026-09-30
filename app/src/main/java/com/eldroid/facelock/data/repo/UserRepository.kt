package com.eldroid.facelock.data.repo

import com.eldroid.facelock.data.model.Locker
import com.eldroid.facelock.data.model.User
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class UserRepository {

    private val col get() = FirebaseRefs.db.collection(FirebaseRefs.USERS)

    /** Live stream of every registered user, newest first. */
    fun observeUsers(): Flow<List<User>> = callbackFlow {
        val reg = col.orderBy("createdAt").addSnapshotListener { snap, err ->
            if (err != null) { close(err); return@addSnapshotListener }
            trySend(snap?.toObjects(User::class.java).orEmpty())
        }
        awaitClose { reg.remove() }
    }

    fun observeUser(uid: String): Flow<User?> = callbackFlow {
        val reg = col.document(uid).addSnapshotListener { snap, err ->
            if (err != null) { close(err); return@addSnapshotListener }
            trySend(snap?.toObject(User::class.java))
        }
        awaitClose { reg.remove() }
    }

    suspend fun updateUser(uid: String, changes: Map<String, Any?>): Result<Unit> =
        runCatching { col.document(uid).update(changes).await() }

    suspend fun setActive(uid: String, active: Boolean) =
        updateUser(uid, mapOf("active" to active))

    suspend fun markFaceEnrolled(uid: String, enrolled: Boolean) =
        updateUser(uid, mapOf("faceEnrolled" to enrolled))

    /**
     * Removes the profile and frees the user's locker in one transaction, so a
     * locker is never left assigned to someone who no longer exists.
     *
     * The Firebase sign-in account is untouched: deleting another person's
     * login needs the Admin SDK, which is not available without Cloud Functions.
     */
    suspend fun deleteUser(user: User): Result<Unit> = runCatching {
        val db = FirebaseRefs.db
        val lockerRef = user.lockerId?.takeIf { it.isNotBlank() }
            ?.let { db.collection(FirebaseRefs.LOCKERS).document(it) }

        db.runTransaction { tx ->
            val holdsLocker = lockerRef?.let {
                tx.get(it).getString("assignedUid") == user.uid
            } == true

            if (holdsLocker) {
                tx.update(
                    lockerRef!!,
                    mapOf(
                        "assignedUid" to null,
                        "assignedName" to null,
                        "status" to Locker.STATUS_AVAILABLE
                    )
                )
            }
            tx.delete(col.document(user.uid))
        }.await()
    }
}
