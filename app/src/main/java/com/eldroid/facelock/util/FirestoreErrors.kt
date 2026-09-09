package com.eldroid.facelock.util

import android.util.Log
import com.eldroid.facelock.data.repo.FirebaseRefs
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch

private const val TAG = "FaceLock/data"

/**
 * Guards a Firestore snapshot flow so a listener failure cannot kill the app.
 *
 * The repositories end their callbackFlow with `close(err)` when a snapshot
 * listener fails. Collecting such a flow rethrows that exception inside the
 * `lifecycleScope.launch` that collects it, and an exception escaping a
 * coroutine takes the whole process down. Signing out hit this every single
 * time: the moment auth is cleared, every rule in firestore.rules stops
 * matching and each open listener is handed a PERMISSION_DENIED.
 *
 * So: a failure after sign-out is expected and ignored, and any other failure
 * is logged and reported to the caller as a sentence a user can act on.
 *
 * Apply this immediately before `collect`, so it covers the whole chain
 * upstream but never swallows a bug thrown inside the collector itself.
 */
fun <T> Flow<T>.catchFirestore(what: String, onError: (String) -> Unit): Flow<T> = catch { e ->
    if (FirebaseRefs.auth.currentUser == null) {
        // Signed out; the listener is torn down on its way out. Not an error.
        Log.d(TAG, "listener for $what ended after sign-out")
        return@catch
    }
    Log.w(TAG, "listener for $what failed", e)
    onError(messageFor(what, e))
}

private fun messageFor(what: String, e: Throwable): String {
    val code = (e as? FirebaseFirestoreException)?.code ?: return "Couldn't load $what."
    return when (code) {
        // Almost always a firestore.rules mismatch rather than a signed-out user.
        FirebaseFirestoreException.Code.PERMISSION_DENIED ->
            "You don't have permission to view $what."
        // Firestore names the index it wants in the Logcat warning above.
        FirebaseFirestoreException.Code.FAILED_PRECONDITION ->
            "$what needs a database index that hasn't been created yet."
        FirebaseFirestoreException.Code.UNAVAILABLE ->
            "Can't reach the server. Check your connection."
        FirebaseFirestoreException.Code.UNAUTHENTICATED ->
            "Your session expired. Sign in again."
        else -> "Couldn't load $what."
    }
}
