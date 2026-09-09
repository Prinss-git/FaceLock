package com.eldroid.facelock.util

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException

/**
 * Firebase surfaces things like "The given password is invalid. [ INVALID_LOGIN_
 * CREDENTIALS ]" straight to the UI. This maps the cases the auth screens can
 * actually hit onto sentences a student or guard can act on, and falls back to
 * the original message for anything unexpected.
 */
fun Throwable.authMessage(fallback: String): String = when (this) {
    is FirebaseAuthWeakPasswordException ->
        reason ?: "That password is too weak. Choose a stronger one."
    is FirebaseAuthUserCollisionException ->
        "An account already exists for this email address."
    is FirebaseAuthInvalidUserException ->
        "No account was found for this email address."
    is FirebaseAuthRecentLoginRequiredException ->
        "For your security, sign in again before changing your password."
    is FirebaseAuthInvalidCredentialsException ->
        "Incorrect email or password."
    is FirebaseNetworkException ->
        "No internet connection. Check your network and try again."
    is FirebaseTooManyRequestsException ->
        "Too many attempts. Wait a few minutes before trying again."
    else -> message ?: fallback
}
