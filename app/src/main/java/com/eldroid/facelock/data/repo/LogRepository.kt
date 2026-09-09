package com.eldroid.facelock.data.repo

import com.eldroid.facelock.data.model.AccessLog
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class LogRepository {

    private val col get() = FirebaseRefs.db.collection(FirebaseRefs.ACCESS_LOGS)

    /** All access attempts, newest first. Used by the admin/security dashboard. */
    fun observeAllLogs(limit: Long = 200): Flow<List<AccessLog>> = callbackFlow {
        val reg = col
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(limit)
            .addSnapshotListener { snap, err ->
                if (err != null) { close(err); return@addSnapshotListener }
                trySend(snap?.toObjects(AccessLog::class.java).orEmpty())
            }
        awaitClose { reg.remove() }
    }

    /** Only the signed-in user's own history. */
    fun observeLogsForUser(uid: String, limit: Long = 100): Flow<List<AccessLog>> = callbackFlow {
        val reg = col
            .whereEqualTo("uid", uid)
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(limit)
            .addSnapshotListener { snap, err ->
                if (err != null) { close(err); return@addSnapshotListener }
                trySend(snap?.toObjects(AccessLog::class.java).orEmpty())
            }
        awaitClose { reg.remove() }
    }

    /** Failed attempts only, for the security alert feed. */
    fun observeFailedLogs(limit: Long = 100): Flow<List<AccessLog>> = callbackFlow {
        val reg = col
            .whereEqualTo("result", AccessLog.RESULT_DENIED)
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(limit)
            .addSnapshotListener { snap, err ->
                if (err != null) { close(err); return@addSnapshotListener }
                trySend(snap?.toObjects(AccessLog::class.java).orEmpty())
            }
        awaitClose { reg.remove() }
    }

    suspend fun addLog(log: AccessLog): Result<Unit> =
        runCatching { col.add(log).await(); Unit }
}
