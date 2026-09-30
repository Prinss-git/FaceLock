package com.eldroid.facelock.presenter.auth

import com.eldroid.facelock.data.model.Role
import com.eldroid.facelock.data.repo.AuthRepository
import com.eldroid.facelock.presenter.auth.RegisterContract.FieldType
import com.eldroid.facelock.presenter.base.CoroutinePresenter
import com.eldroid.facelock.util.PasswordPolicy
import com.eldroid.facelock.util.authMessage
import com.eldroid.facelock.util.validateEmail
import com.eldroid.facelock.util.validateName
import kotlinx.coroutines.launch

class RegisterPresenter(
    private val authRepo: AuthRepository
) : CoroutinePresenter<RegisterContract.View>(), RegisterContract.Presenter {

    /** Errors only appear after the first submit, so typing isn't nagged at. */
    private var submitted = false
    private var loading = false

    override fun onViewAttached() {
        view?.showProgress(loading)
        if (submitted) validate()
    }

    override fun handleInputChanged(field: FieldType) {
        if (submitted) validate()
    }

    override fun handleRegisterClick() {
        val view = view ?: return
        submitted = true
        val firstInvalid = validate()
        if (firstInvalid != null) {
            // Send the person straight to the first problem.
            view.focusField(firstInvalid)
            return
        }

        val form = view.getForm()
        setLoading(true)
        scope.launch {
            authRepo.register(form.first.trim(), form.last.trim(), form.email.trim(), form.password, Role.USER)
                .onSuccess { user -> this@RegisterPresenter.view?.navigateToEnroll(user) }
                .onFailure { error ->
                    setLoading(false)
                    val message = error.authMessage("Registration failed. Please try again.")
                    // Collisions and weak passwords belong on the field, not a toast.
                    when {
                        message.contains("already exists", ignoreCase = true) ->
                            this@RegisterPresenter.view?.showValidationErrors(mapOf(FieldType.EMAIL to message))
                        message.contains("password", ignoreCase = true) ->
                            this@RegisterPresenter.view?.showValidationErrors(mapOf(FieldType.PASSWORD to message))
                        else -> this@RegisterPresenter.view?.showMessage(message)
                    }
                }
        }
    }

    /**
     * Checks every field at once so the person sees all of the problems in
     * one pass instead of fixing them one submit at a time.
     *
     * @return the first field with a problem, or null when the form is clean.
     */
    private fun validate(): FieldType? {
        val view = view ?: return null
        val form = view.getForm()
        val first = form.first.trim()
        val last = form.last.trim()
        val email = form.email.trim()
        val errors = linkedMapOf(
            FieldType.FIRST_NAME to validateName(first, "First name"),
            FieldType.LAST_NAME to validateName(last, "Last name"),
            FieldType.EMAIL to validateEmail(email),
            FieldType.PASSWORD to PasswordPolicy.firstError(
                password = form.password,
                email = email,
                names = listOf(first, last)
            ),
            FieldType.CONFIRM to when {
                form.confirm.isEmpty() -> "Re-enter your password"
                form.confirm != form.password -> "Passwords do not match"
                else -> null
            }
        )
        view.showValidationErrors(errors)
        return errors.entries.firstOrNull { it.value != null }?.key
    }

    private fun setLoading(loading: Boolean) {
        this.loading = loading
        view?.showProgress(loading)
    }
}
