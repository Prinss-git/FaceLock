package com.eldroid.facelock.util

import android.content.Context
import com.eldroid.facelock.data.model.Role

/**
 * Caches the signed-in user's role so the app can route to the right
 * dashboard without a round trip on every launch.
 */
class SessionManager(context: Context) {

    private val prefs =
        context.getSharedPreferences("facelock_session", Context.MODE_PRIVATE)

    var role: Role
        get() = Role.from(prefs.getString(KEY_ROLE, null))
        set(value) = prefs.edit().putString(KEY_ROLE, value.name).apply()

    var fullName: String
        get() = prefs.getString(KEY_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_NAME, value).apply()

    var lockerId: String?
        get() = prefs.getString(KEY_LOCKER, null)
        set(value) = prefs.edit().putString(KEY_LOCKER, value).apply()

    fun clear() = prefs.edit().clear().apply()

    companion object {
        private const val KEY_ROLE = "role"
        private const val KEY_NAME = "name"
        private const val KEY_LOCKER = "locker"
    }
}
