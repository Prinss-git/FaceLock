package com.eldroid.facelock.data.repo

import com.eldroid.facelock.data.model.PasswordResetRequest
import com.google.firebase.firestore.Query
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Password resets that do not go through email.
 *
 * Firebase's client SDK can only change the password of a user who is already
 * signed in, so both ends of this flow are Cloud Functions using the Admin
 * SDK. They are plain HTTPS endpoints called with HttpURLConnection rather
 * than callables, which keeps the app free of a new Firebase dependency.
 */
class PasswordResetRepository {

    /**
     * Raises a request from the sign-in screen, where nobody is authenticated.
     *
     * Always succeeds as far as the caller can tell: the backend answers
     * identically whether or not the address belongs to an account, so this
     * cannot be used to discover who has one.
     */
    suspend fun requestReset(email: String): Result<Unit> = runCatching {
        val body = JSONObject().put("email", email.trim())
        val (code, _) = post("requestPasswordReset", body, idToken = null)
        if (code !in 200..299) error("The server could not take your request right now.")
    }

    /** Pending requests, newest first. Readable by admins only, per the rules. */
    fun observePendingRequests(): Flow<List<PasswordResetRequest>> = callbackFlow {
        val reg = FirebaseRefs.db.collection(FirebaseRefs.PASSWORD_RESETS)
            .whereEqualTo("status", PasswordResetRequest.STATUS_PENDING)
            .orderBy("requestedAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snap, err ->
                if (err != null) { close(err); return@addSnapshotListener }
                trySend(snap?.toObjects(PasswordResetRequest::class.java).orEmpty())
            }
        awaitClose { reg.remove() }
    }

    /**
     * Approves a reset and returns the temporary password — the only moment it
     * is ever readable, since the backend does not store it.
     *
     * Pass [requestId] to resolve a queued request, or [uid] to reset someone
     * straight from the user list. Rejecting returns null.
     */
    suspend fun resolve(
        requestId: String? = null,
        uid: String? = null,
        approve: Boolean = true
    ): Result<String?> = runCatching {
        val user = FirebaseRefs.auth.currentUser ?: error("Sign in again to do that.")
        val token = user.getIdToken(false).await().token
            ?: error("Could not verify your session. Sign in again.")

        val body = JSONObject().put("approve", approve)
        requestId?.let { body.put("requestId", it) }
        uid?.let { body.put("uid", it) }

        val (code, payload) = post("resolvePasswordReset", body, idToken = token)
        val json = runCatching { JSONObject(payload) }.getOrNull()

        when (code) {
            in 200..299 -> if (approve) json?.optString("tempPassword")?.takeIf { it.isNotBlank() }
                ?: error("The server did not return a password.") else null
            401 -> error("Your session expired. Sign in again.")
            403 -> error("Only administrators can reset passwords.")
            404 -> error("That request no longer exists.")
            409 -> error("Somebody already handled that request.")
            else -> error(json?.optString("error").orEmpty().ifBlank { "Reset failed ($code)." })
        }
    }

    /**
     * Cheap liveness probe for the endpoints, so the UI can tell "not deployed"
     * apart from "something went wrong". A deployed function answers 405 to a
     * GET; an undeployed one answers 404.
     */
    suspend fun backendDeployed(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL("${FirebaseRefs.functionsBase}/requestPasswordReset")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
            }
            val code = conn.responseCode
            conn.disconnect()
            code != HttpURLConnection.HTTP_NOT_FOUND
        }.getOrDefault(false)
    }

    private suspend fun post(
        endpoint: String,
        body: JSONObject,
        idToken: String?
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        val conn = (URL("${FirebaseRefs.functionsBase}/$endpoint")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("Content-Type", "application/json")
            idToken?.let { setRequestProperty("Authorization", "Bearer $it") }
        }

        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            // Non-2xx bodies arrive on the error stream, and that is where the
            // function puts its {"error": "..."} explanation.
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            code to text
        } finally {
            conn.disconnect()
        }
    }
}
