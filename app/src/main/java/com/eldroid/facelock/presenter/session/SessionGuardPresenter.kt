package com.eldroid.facelock.presenter.session

import com.eldroid.facelock.data.model.Role
import com.eldroid.facelock.data.model.User
import com.eldroid.facelock.data.repo.UserRepository
import com.eldroid.facelock.presenter.base.BasePresenter
import com.eldroid.facelock.presenter.base.CoroutinePresenter
import com.eldroid.facelock.util.catchFirestore
import kotlinx.coroutines.launch

/**
 * Watches the signed-in person's own profile while a home screen is open, so
 * an admin's change takes effect on their phone at once instead of at the
 * next app launch.
 */
interface SessionGuardContract {

    interface View {
        fun signOut(reason: SignOutReason)
        /** The role changed: open the home screen for [role] in place of this one. */
        fun switchHome(role: Role)
        /** Name or locker changed; keep the cached session in step. */
        fun refreshSession(user: User)
    }

    interface Presenter : BasePresenter<View>

    enum class SignOutReason { SUSPENDED, REMOVED }
}

class SessionGuardPresenter(
    private val userRepo: UserRepository,
    private val uid: String,
    /** The role this home screen was opened for. */
    private val screenRole: Role
) : CoroutinePresenter<SessionGuardContract.View>(), SessionGuardContract.Presenter {

    override fun onViewAttached() {
        scope.launch {
            userRepo.observeOwnProfile(uid)
                // A read failure is not proof of anything: stay signed in.
                .catchFirestore("your profile") { }
                .collect { user ->
                    val view = view ?: return@collect
                    when {
                        user == null -> view.signOut(SessionGuardContract.SignOutReason.REMOVED)
                        !user.active -> view.signOut(SessionGuardContract.SignOutReason.SUSPENDED)
                        user.roleEnum != screenRole -> view.switchHome(user.roleEnum)
                        else -> view.refreshSession(user)
                    }
                }
        }
    }
}
