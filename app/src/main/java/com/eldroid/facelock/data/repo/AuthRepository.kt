package com.eldroid.facelock.data.repo

import com.eldroid.facelock.data.model.Role
import com.eldroid.facelock.data.model.User
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import kotlinx.coroutines.tasks.await

class AuthRepository {

    val currentUid: String? get() = FirebaseRefs.auth.currentUser?.uid
    val currentEmail: String? get() = FirebaseRefs.auth.currentUser?.email
    val isLoggedIn: Boolean get() = FirebaseRefs.auth.currentUser != null

    /** Signs in and returns the matching Firestore profile. */
    suspend fun login(email: String, password: String): Result<User> = runCatching {
        val result = FirebaseRefs.auth
            .signInWithEmailAndPassword(email.trim(), password)
            .await()
        val uid = result.user?.uid ?: error("Authentication returned no user.")
        loadProfile(uid).getOrThrow()
    }

    /** Creates an auth account plus its Firestore profile document. */
    suspend fun register(
        firstName: String,
        lastName: String,
        email: String,
        password: String,
        role: Role = Role.USER
    ): Result<User> = runCatching {
        val result = FirebaseRefs.auth
            .createUserWithEmailAndPassword(email.trim(), password)
            .await()
        val uid = result.user?.uid ?: error("Registration returned no user.")

        val first = firstName.trim()
        val last = lastName.trim()

        val profile = User(
            uid = uid,
            firstName = first,
            lastName = last,
            fullName = "$first $last",
            email = email.trim(),
            role = role.name,
            faceEnrolled = false,
            active = true
        )
        FirebaseRefs.db.collection(FirebaseRefs.USERS)
            .document(uid)
            .set(profile)
            .await()
        profile
    }

    suspend fun loadProfile(uid: String): Result<User> = runCatching {
        val snap = FirebaseRefs.db.collection(FirebaseRefs.USERS)
            .document(uid)
            .get()
            .await()
        snap.toObject(User::class.java)
            ?: error("No profile found for this account. Contact your administrator.")
    }

    suspend fun sendPasswordReset(email: String): Result<Unit> = runCatching {
        FirebaseRefs.auth.sendPasswordResetEmail(email.trim()).await()
    }

    /**
     * Changes the signed-in user's password.
     *
     * Firebase requires a recent login before [com.google.firebase.auth.FirebaseUser.updatePassword],
     * so the current password is verified by re-authenticating first. That doubles
     * as the "prove it's you" check — a stolen unlocked phone cannot silently
     * take over the account.
     */
    suspend fun changePassword(
        currentPassword: String,
        newPassword: String
    ): Result<Unit> = runCatching {
        val user = FirebaseRefs.auth.currentUser ?: error("You are not signed in.")
        val email = user.email ?: error("This account has no email address on file.")

        if (currentPassword == newPassword) {
            error("Your new password must be different from your current one.")
        }

        val credential = EmailAuthProvider.getCredential(email, currentPassword)
        try {
            user.reauthenticate(credential).await()
        } catch (e: FirebaseAuthInvalidCredentialsException) {
            error("Your current password is incorrect.")
        }
        user.updatePassword(newPassword).await()
    }

    fun logout() = FirebaseRefs.auth.signOut()
}
