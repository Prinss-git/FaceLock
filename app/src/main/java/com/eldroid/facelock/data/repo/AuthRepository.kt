package com.eldroid.facelock.data.repo

import android.content.Context
import com.eldroid.facelock.data.model.Role
import com.eldroid.facelock.data.model.User
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
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
        writeProfile(uid, firstName, lastName, email, role)
    }

    /**
     * Admin-side account creation.
     *
     * [register] cannot be used here: createUserWithEmailAndPassword signs the
     * new account in, which would replace the admin's session — and the profile
     * write would then run as the new user, whom the rules only allow to be a
     * USER. So the account is created on a separate FirebaseApp instance, and
     * the profile is written from the admin's own session.
     */
    suspend fun provisionUser(
        context: Context,
        firstName: String,
        lastName: String,
        email: String,
        password: String,
        role: Role
    ): Result<User> = runCatching {
        val provisioningAuth = FirebaseAuth.getInstance(provisioningApp(context))
        val created = provisioningAuth
            .createUserWithEmailAndPassword(email.trim(), password)
            .await()
            .user ?: error("Registration returned no user.")

        try {
            writeProfile(created.uid, firstName, lastName, email, role)
        } catch (e: Exception) {
            // No profile means an account nobody can use; remove it so the
            // email address is free for another attempt.
            runCatching { created.delete().await() }
            throw e
        } finally {
            provisioningAuth.signOut()
        }
    }

    private fun provisioningApp(context: Context): FirebaseApp =
        FirebaseApp.getApps(context).firstOrNull { it.name == PROVISIONING_APP }
            ?: FirebaseApp.initializeApp(
                context.applicationContext,
                FirebaseApp.getInstance().options,
                PROVISIONING_APP
            )

    private suspend fun writeProfile(
        uid: String,
        firstName: String,
        lastName: String,
        email: String,
        role: Role
    ): User {
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
        return profile
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

    private companion object {
        const val PROVISIONING_APP = "provisioning"
    }
}
