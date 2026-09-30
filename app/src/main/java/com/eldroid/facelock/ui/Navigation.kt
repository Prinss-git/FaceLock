package com.eldroid.facelock.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import com.eldroid.facelock.R
import com.eldroid.facelock.data.model.Role
import com.eldroid.facelock.data.model.User
import com.eldroid.facelock.data.repo.AuthRepository
import com.eldroid.facelock.data.repo.UserRepository
import com.eldroid.facelock.presenter.base.PresenterHolder
import com.eldroid.facelock.presenter.session.SessionGuardContract
import com.eldroid.facelock.presenter.session.SessionGuardContract.SignOutReason
import com.eldroid.facelock.presenter.session.SessionGuardPresenter
import com.eldroid.facelock.ui.admin.AdminActivity
import com.eldroid.facelock.ui.auth.LoginActivity
import com.eldroid.facelock.ui.user.UserActivity
import com.eldroid.facelock.util.SessionManager

/** The home screen for each role: staff share the console, members get their locker. */
fun homeFor(role: Role): Class<out Activity> = when (role) {
    Role.ADMIN, Role.SECURITY -> AdminActivity::class.java
    Role.USER -> UserActivity::class.java
}

/** Caches [user] for routing, then opens their home screen as a fresh task. */
fun Activity.openHome(user: User) {
    SessionManager(this).apply {
        role = user.roleEnum
        fullName = user.fullName
        lockerId = user.lockerId
    }
    startActivity(
        Intent(this, homeFor(user.roleEnum))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    )
    finish()
}

/**
 * Signs out and returns to the login screen, clearing every screen behind it.
 * [message], when given, is shown once on the login screen.
 */
fun signOutTo(context: Context, message: String? = null) {
    AuthRepository().logout()
    SessionManager(context).clear()
    context.startActivity(
        Intent(context, LoginActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .apply { if (message != null) putExtra(LoginActivity.EXTRA_MESSAGE, message) }
    )
}

/**
 * The View half of the session guard, shared by both home screens. Kept in
 * its own ViewModel slot so it lives beside the screen's other presenter.
 */
class SessionGuard(private val activity: FragmentActivity) : SessionGuardContract.View {

    private val presenter: SessionGuardContract.Presenter? =
        AuthRepository().currentUid?.let { uid ->
            ViewModelProvider(activity)[KEY, PresenterHolder::class.java].getOrCreate {
                SessionGuardPresenter(UserRepository(), uid, SessionManager(activity).role)
            }
        }

    fun attach() = presenter?.attachView(this)
    fun detach() = presenter?.detachView()

    override fun signOut(reason: SignOutReason) {
        signOutTo(
            activity,
            activity.getString(
                when (reason) {
                    SignOutReason.SUSPENDED -> R.string.account_suspended
                    SignOutReason.REMOVED -> R.string.account_removed
                }
            )
        )
        activity.finish()
    }

    override fun switchHome(role: Role) {
        SessionManager(activity).role = role
        activity.startActivity(
            Intent(activity, homeFor(role))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(EXTRA_ROLE_CHANGED, true)
        )
        activity.finish()
    }

    override fun refreshSession(user: User) {
        SessionManager(activity).apply {
            fullName = user.fullName
            lockerId = user.lockerId
        }
    }

    companion object {
        private const val KEY = "session_guard"
        /** Set on a home screen opened because the person's role changed. */
        const val EXTRA_ROLE_CHANGED = "role_changed"
    }
}
