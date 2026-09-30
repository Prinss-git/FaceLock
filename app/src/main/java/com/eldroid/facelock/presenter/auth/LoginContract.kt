package com.eldroid.facelock.presenter.auth

import com.eldroid.facelock.data.model.User
import com.eldroid.facelock.presenter.base.BasePresenter

/** Sign-in (lessons 1.2/1.4): the View supplies input, the Presenter decides. */
interface LoginContract {

    interface View {
        fun getEmail(): String
        fun getPassword(): String
        /** [message] null clears the field's error. */
        fun showValidationError(field: FieldType, message: String?)
        fun render(state: LoginState)
        fun navigateHome(user: User)
    }

    interface Presenter : BasePresenter<View> {
        fun handleLoginClick()
        fun handleInputChanged(field: FieldType)
    }

    enum class FieldType { EMAIL, PASSWORD }
}

/** Screen states (lesson 1.2). The View turns an [Error.reason] into words. */
sealed class LoginState {
    object Idle : LoginState()
    object Loading : LoginState()
    data class Error(val reason: Reason, val detail: String? = null) : LoginState()

    enum class Reason {
        /** Wrong credentials, network, and so on; [detail] says which. */
        SIGN_IN_FAILED,
        SUSPENDED,
        /** A saved session couldn't be checked in time. */
        SESSION_TIMED_OUT,
        SESSION_UNREADABLE
    }
}
