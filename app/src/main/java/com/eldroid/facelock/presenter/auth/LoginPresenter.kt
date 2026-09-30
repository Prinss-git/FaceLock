package com.eldroid.facelock.presenter.auth

import com.eldroid.facelock.data.model.User
import com.eldroid.facelock.data.repo.AuthRepository
import com.eldroid.facelock.presenter.auth.LoginContract.FieldType
import com.eldroid.facelock.presenter.auth.LoginState.Reason
import com.eldroid.facelock.presenter.base.CoroutinePresenter
import com.eldroid.facelock.util.authMessage
import com.eldroid.facelock.util.validateEmail
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class LoginPresenter(
    private val authRepo: AuthRepository
) : CoroutinePresenter<LoginContract.View>(), LoginContract.Presenter {

    private var state: LoginState = LoginState.Idle
    /** A saved session is checked once per screen, not again after rotation. */
    private var resumeChecked = false

    override fun onViewAttached() {
        view?.render(state)
        val uid = authRepo.currentUid
        if (!resumeChecked && uid != null) {
            resumeChecked = true
            resumeSession(uid)
        }
    }

    /**
     * Restores an existing session on launch.
     *
     * The profile read is bounded: if Firestore never answers — offline, or a
     * rules change has locked this account out — the button used to sit on
     * "Signing in…" forever with no way back. A stale session that cannot load
     * is cleared so the form is usable again.
     */
    private fun resumeSession(uid: String) {
        setState(LoginState.Loading)
        scope.launch {
            val result = withTimeoutOrNull(PROFILE_TIMEOUT_MS) { authRepo.loadProfile(uid) }
            when {
                result == null -> fail(Reason.SESSION_TIMED_OUT)
                // A saved session must not outlive a suspension.
                result.isSuccess && !result.getOrThrow().active -> fail(Reason.SUSPENDED)
                result.isSuccess -> succeed(result.getOrThrow())
                else -> fail(
                    Reason.SESSION_UNREADABLE,
                    result.exceptionOrNull()?.authMessage("")?.takeIf { it.isNotBlank() }
                )
            }
        }
    }

    override fun handleLoginClick() {
        val view = view ?: return
        val email = view.getEmail().trim()
        val password = view.getPassword()

        // Sign-in only checks the shape of the input. It deliberately does not
        // apply the registration password policy — accounts created before the
        // policy tightened must still be able to get in and change it.
        val emailError = validateEmail(email)
        view.showValidationError(FieldType.EMAIL, emailError)
        if (emailError != null) return
        if (password.isEmpty()) {
            view.showValidationError(FieldType.PASSWORD, "Enter your password")
            return
        }
        view.showValidationError(FieldType.PASSWORD, null)

        setState(LoginState.Loading)
        scope.launch {
            authRepo.login(email, password)
                .onSuccess { user ->
                    if (user.active) succeed(user) else fail(Reason.SUSPENDED)
                }
                .onFailure {
                    setState(
                        LoginState.Error(
                            Reason.SIGN_IN_FAILED,
                            it.authMessage("Sign in failed. Check your credentials.")
                        )
                    )
                }
        }
    }

    override fun handleInputChanged(field: FieldType) {
        view?.showValidationError(field, null)
    }

    /** Signs out whatever half-open session there is, then reports why. */
    private fun fail(reason: Reason, detail: String? = null) {
        authRepo.logout()
        setState(LoginState.Error(reason, detail))
    }

    private fun succeed(user: User) {
        state = LoginState.Idle
        view?.navigateHome(user)
    }

    private fun setState(state: LoginState) {
        view?.render(state)
        // An error is said once; after rotation the form is simply ready again.
        this.state = if (state is LoginState.Error) LoginState.Idle else state
    }

    private companion object {
        /** Long enough for a slow network, short enough not to feel stuck. */
        const val PROFILE_TIMEOUT_MS = 12_000L
    }
}
