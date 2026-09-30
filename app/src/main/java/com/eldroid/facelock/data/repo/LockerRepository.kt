package com.eldroid.facelock.data.repo

import com.eldroid.facelock.data.model.Locker
import com.eldroid.facelock.data.model.User
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

    /**
     * Creates every locker in [lockers], or none of them: if any ID is already
     * taken the whole batch is refused and the error names that ID, so a bulk
     * add can never overwrite an existing locker or stop half-way.
     */
    suspend fun createMany(lockers: List<Locker>): Result<Unit> = runCatching {
        val refs = lockers.map { col.document(it.id) }
        FirebaseRefs.db.runTransaction { tx ->
            val taken = refs.filter { tx.get(it).exists() }.map { it.id }
            if (taken.isNotEmpty()) {
                error("Already in use: ${taken.joinToString()}. Nothing was added.")
            }
            refs.zip(lockers).forEach { (ref, locker) -> tx.set(ref, locker) }
        }.await()
    }

    /**
     * Re-creates a pre-buildings locker under its new ID. Document IDs cannot
     * be renamed, so the old document is copied to [newId] with its holder,
     * status and history fields, the holder's profile is pointed at the new
     * ID, and the old document is deleted — all in one transaction.
     */
    suspend fun migrate(
        old: Locker,
        newId: String,
        buildingCode: String,
        floor: Int,
        location: String
    ): Result<Unit> = runCatching {
        val db = FirebaseRefs.db
        val oldRef = col.document(old.id)
        val newRef = col.document(newId)
        val holderRef = old.assignedUid?.takeIf { it.isNotBlank() }
            ?.let { db.collection(FirebaseRefs.USERS).document(it) }

        db.runTransaction { tx ->
            if (tx.get(newRef).exists()) error("$newId already exists.")
            if (!tx.get(oldRef).exists()) error("${old.id} no longer exists.")
            val holderPointsHere = holderRef?.let {
                tx.get(it).getString("lockerId") == old.id
            } == true

            tx.set(
                newRef,
                old.copy(
                    id = newId,
                    label = newId,
                    building = buildingCode,
                    floor = floor,
                    location = location,
                    // Old access logs still name the old ID; this links them back.
                    formerId = old.formerId ?: old.id
                )
            )
            if (holderPointsHere) tx.update(holderRef!!, "lockerId", newId)
            tx.delete(oldRef)
        }.await()
    }

    /**
     * Gives [locker] to [newHolder], or frees it when [newHolder] is null.
     *
     * One transaction keeps the locker and user documents consistent: the
     * previous holder loses the locker, and a new holder's old locker is
     * released so nobody ends up listed on two lockers. Documents that no
     * longer exist (a deleted user, a removed locker) are skipped rather than
     * failing the whole assignment.
     */
    suspend fun assign(locker: Locker, newHolder: User?): Result<Unit> = runCatching {
        val db = FirebaseRefs.db
        val users = db.collection(FirebaseRefs.USERS)
        val lockerRef = col.document(locker.id)
        val previousUid = locker.assignedUid?.takeIf { it.isNotBlank() && it != newHolder?.uid }
        val releasedLockerId = newHolder?.lockerId?.takeIf { it.isNotBlank() && it != locker.id }

        db.runTransaction { tx ->
            // Firestore requires every read before the first write.
            val previousRef = previousUid?.let { users.document(it) }
            val previousExists = previousRef?.let { tx.get(it).exists() } == true
            val releasedRef = releasedLockerId?.let { col.document(it) }
            val releasedExists = releasedRef?.let { tx.get(it).exists() } == true

            tx.update(lockerRef, holderFields(newHolder))
            if (previousExists) tx.update(previousRef!!, "lockerId", null)
            if (newHolder != null) tx.update(users.document(newHolder.uid), "lockerId", locker.id)
            if (releasedExists) tx.update(releasedRef!!, holderFields(null))
        }.await()
    }

    private fun holderFields(holder: User?): Map<String, Any?> = mapOf(
        "assignedUid" to holder?.uid,
        "assignedName" to holder?.fullName,
        "status" to if (holder == null) Locker.STATUS_AVAILABLE else Locker.STATUS_OCCUPIED
    )

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

    /**
     * Records which old ID each locker used to have ([formerIdsByLocker] is
     * current locker ID → old ID), so access logs naming the old ID resolve
     * to it. One batch: either every link is saved or none is.
     */
    suspend fun setFormerIds(formerIdsByLocker: Map<String, String>): Result<Unit> = runCatching {
        val batch = FirebaseRefs.db.batch()
        formerIdsByLocker.forEach { (lockerId, formerId) ->
            batch.update(col.document(lockerId), "formerId", formerId)
        }
        batch.commit().await()
    }

    /**
     * Deletes [locker] and, if someone still holds it, clears their profile's
     * `lockerId` in the same transaction so nobody is left pointing at nothing.
     */
    suspend fun remove(locker: Locker): Result<Unit> = runCatching {
        val db = FirebaseRefs.db
        val ref = col.document(locker.id)
        val holderRef = locker.assignedUid?.takeIf { it.isNotBlank() }
            ?.let { db.collection(FirebaseRefs.USERS).document(it) }

        db.runTransaction { tx ->
            val holderPointsHere = holderRef?.let {
                tx.get(it).getString("lockerId") == locker.id
            } == true
            if (holderPointsHere) tx.update(holderRef!!, "lockerId", null)
            tx.delete(ref)
        }.await()
    }

    /**
     * Takes a locker out of service or puts it back. Only an unassigned locker
     * can be retired; the check runs inside the transaction so a locker that
     * was assigned a moment ago is not retired with someone's things inside.
     */
    suspend fun setInService(lockerId: String, inService: Boolean): Result<Unit> = runCatching {
        val ref = col.document(lockerId)
        FirebaseRefs.db.runTransaction { tx ->
            val snap = tx.get(ref)
            if (!snap.exists()) error("$lockerId no longer exists.")
            if (!inService && !snap.getString("assignedUid").isNullOrBlank()) {
                error("Unassign $lockerId before taking it out of service.")
            }
            tx.update(
                ref, "status",
                if (inService) Locker.STATUS_AVAILABLE else Locker.STATUS_OUT_OF_SERVICE
            )
        }.await()
    }
}
