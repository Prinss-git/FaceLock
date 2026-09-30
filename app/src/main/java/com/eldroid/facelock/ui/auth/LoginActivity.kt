package com.eldroid.facelock.ui.auth

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.eldroid.facelock.R
import com.eldroid.facelock.data.model.User
import com.eldroid.facelock.data.repo.AuthRepository
import com.eldroid.facelock.databinding.ActivityLoginBinding
import com.eldroid.facelock.presenter.auth.LoginContract
import com.eldroid.facelock.presenter.auth.LoginContract.FieldType
import com.eldroid.facelock.presenter.auth.LoginPresenter
import com.eldroid.facelock.presenter.auth.LoginState
import com.eldroid.facelock.presenter.base.PresenterHolder
import com.eldroid.facelock.ui.openHome
import com.eldroid.facelock.util.toast
import com.eldroid.facelock.util.visible

/** Sign-in — the View in MVP. [LoginPresenter] validates, signs in and restores sessions. */
class LoginActivity : AppCompatActivity(), LoginContract.View {

    private lateinit var binding: ActivityLoginBinding
    private val holder: PresenterHolder by viewModels()
    private lateinit var presenter: LoginContract.Presenter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Why the person was signed out (suspended, removed), shown once.
        if (savedInstanceState == null) intent.getStringExtra(EXTRA_MESSAGE)?.let { toast(it) }

        presenter = holder.getOrCreate { LoginPresenter(AuthRepository()) }

        binding.btnLogin.setOnClickListener { presenter.handleLoginClick() }
        binding.etEmail.doAfterTextChanged { presenter.handleInputChanged(FieldType.EMAIL) }
        binding.etPassword.doAfterTextChanged { presenter.handleInputChanged(FieldType.PASSWORD) }
        binding.tvRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }
        binding.tvForgot.setOnClickListener { openForgotPassword() }

        // Attaching also restores a saved session (skips straight to the dashboard).
        presenter.attachView(this)
    }

    override fun onDestroy() {
        presenter.detachView()
        super.onDestroy()
    }

    // ------------------------------------------------------------ View ----

    override fun getEmail() = binding.etEmail.text.toString()

    override fun getPassword() = binding.etPassword.text.toString()

    override fun showValidationError(field: FieldType, message: String?) {
        val layout = when (field) {
            FieldType.EMAIL -> binding.tilEmail
            FieldType.PASSWORD -> binding.tilPassword
        }
        layout.error = message
    }

    override fun render(state: LoginState) {
        val loading = state is LoginState.Loading
        binding.progress.visible(loading)
        binding.btnLogin.isEnabled = !loading
        binding.tvRegister.isEnabled = !loading
        binding.btnLogin.setText(if (loading) R.string.signing_in else R.string.sign_in)

        if (state is LoginState.Error) {
            toast(
                when (state.reason) {
                    LoginState.Reason.SIGN_IN_FAILED -> state.detail.orEmpty()
                    LoginState.Reason.SUSPENDED -> getString(R.string.account_suspended)
                    LoginState.Reason.SESSION_TIMED_OUT -> getString(R.string.session_timed_out)
                    LoginState.Reason.SESSION_UNREADABLE ->
                        state.detail ?: getString(R.string.session_unreadable)
                }
            )
        }
    }

    override fun navigateHome(user: User) = openHome(user)

    /** Hands off to the reset screen, carrying whatever email is already typed. */
    private fun openForgotPassword() {
        startActivity(
            Intent(this, ForgotPasswordActivity::class.java)
                .putExtra(ForgotPasswordActivity.EXTRA_EMAIL, getEmail().trim())
        )
    }

    companion object {
        /** A sentence to show once when this screen opens (why you were signed out). */
        const val EXTRA_MESSAGE = "message"
    }
}
