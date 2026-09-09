package com.eldroid.facelock.data.repo

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

    suspend fun assignLocker(uid: String, lockerId: String?) =
        updateUser(uid, mapOf("lockerId" to lockerId))

    suspend fun markFaceEnrolled(uid: String, enrolled: Boolean) =
        updateUser(uid, mapOf("faceEnrolled" to enrolled))

    suspend fun deleteUser(uid: String): Result<Unit> =
        runCatching { col.document(uid).delete().await() }
}
