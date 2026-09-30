package com.eldroid.facelock.presenter.auth

import com.eldroid.facelock.data.model.User
import com.eldroid.facelock.presenter.base.BasePresenter

/**
 * Self-registration (lessons 1.2/1.4). Always creates a plain USER account;
 * staff roles are granted only by an admin.
 */
interface RegisterContract {

    interface View {
        fun getForm(): RegisterForm
        /** Every field at once; a null message clears that field. */
        fun showValidationErrors(errors: Map<FieldType, String?>)
        /** Takes the person back to the first field with a problem. */
        fun focusField(field: FieldType)
        fun showProgress(loading: Boolean)
        fun showMessage(message: String)
        /** Account made: on to face enrollment. */
        fun navigateToEnroll(user: User)
    }

    interface Presenter : BasePresenter<View> {
        fun handleRegisterClick()
        /** Live re-check, but only once the form has been submitted. */
        fun handleInputChanged(field: FieldType)
    }

    enum class FieldType { FIRST_NAME, LAST_NAME, EMAIL, PASSWORD, CONFIRM }
}

data class RegisterForm(
    val first: String,
    val last: String,
    val email: String,
    val password: String,
    val confirm: String
)
