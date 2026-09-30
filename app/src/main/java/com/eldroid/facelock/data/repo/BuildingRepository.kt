package com.eldroid.facelock.data.repo

import com.eldroid.facelock.data.model.Building
import com.eldroid.facelock.data.model.Locker
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class BuildingRepository {

    private val col get() = FirebaseRefs.db.collection(FirebaseRefs.BUILDINGS)
    private val lockers get() = FirebaseRefs.db.collection(FirebaseRefs.LOCKERS)

    fun observeBuildings(): Flow<List<Building>> = callbackFlow {
        val reg = col.orderBy("name").addSnapshotListener { snap, err ->
            if (err != null) { close(err); return@addSnapshotListener }
            trySend(snap?.toObjects(Building::class.java).orEmpty())
        }
        awaitClose { reg.remove() }
    }

    /** Fails instead of overwriting when the code is already in use. */
    suspend fun createBuilding(building: Building): Result<Unit> = runCatching {
        val ref = col.document(building.code)
        FirebaseRefs.db.runTransaction { tx ->
            if (tx.get(ref).exists()) error("Code ${building.code} is already used by another building.")
            tx.set(ref, building)
        }.await()
    }

    /**
     * Renames a building, changes its floor count or its GF naming. Each
     * locker stores its location as display text ("Gymnasium · GF"), so that
     * text is rewritten on [lockersInBuilding] in the same batch.
     */
    suspend fun updateBuilding(building: Building, lockersInBuilding: List<Locker>): Result<Unit> =
        runCatching {
            val batch = FirebaseRefs.db.batch()
            batch.update(
                col.document(building.code),
                mapOf(
                    "name" to building.name,
                    "floors" to building.floors,
                    "groundFloor" to building.groundFloor
                )
            )
            lockersInBuilding.forEach { locker ->
                val floor = locker.floor ?: return@forEach
                batch.update(lockers.document(locker.id), "location", building.locationOf(floor))
            }
            batch.commit().await()
        }

    /** Refuses while any locker still stands in the building. */
    suspend fun deleteBuilding(code: String): Result<Unit> = runCatching {
        val inUse = lockers.whereEqualTo("building", code).limit(1).get().await()
        if (!inUse.isEmpty) error("Move or remove its lockers before deleting this building.")
        col.document(code).delete().await()
    }
}
