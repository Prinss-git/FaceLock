package com.eldroid.facelock.data.repo

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * Realtime Database access, used for talking to the ESP32.
 *
 * The device writes here directly with its database secret, so this path needs
 * no Cloud Functions and works on the free Spark plan — unlike the Firestore
 * side of the app, which is fed by the phone itself.
 */
class RealtimeDbRepository {

    // getInstance() with no argument reads firebase_url from google-services.json,
    // which this project's file does not have. Naming the URL avoids that.
    private val db: FirebaseDatabase by lazy {
        FirebaseDatabase.getInstance(FirebaseRefs.RTDB_URL)
    }

    /** Live value at [path]. Emits null when the key does not exist yet. */
    fun observeInt(path: String): Flow<Int?> = callbackFlow {
        val ref = db.getReference(path)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                // The device may write it as a number or a string; accept both.
                trySend(snapshot.getValue(Int::class.java)
                    ?: snapshot.getValue(String::class.java)?.toIntOrNull())
            }

            override fun onCancelled(error: DatabaseError) {
                close(error.toException())
            }
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    suspend fun setInt(path: String, value: Int): Result<Unit> = runCatching {
        db.getReference(path).setValue(value).await()
    }

    /**
     * Whether the client currently has a socket to the database. Mirrors the
     * built-in `.info/connected` flag, so it reflects the SDK's own view rather
     * than a guess.
     */
    fun observeConnected(): Flow<Boolean> = callbackFlow {
        val ref = db.getReference(".info/connected")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                trySend(snapshot.getValue(Boolean::class.java) ?: false)
            }

            override fun onCancelled(error: DatabaseError) {
                trySend(false)
            }
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }
}
