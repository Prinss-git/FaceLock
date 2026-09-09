package com.eldroid.facelock.ui.auth

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.eldroid.facelock.R
import com.eldroid.facelock.data.model.Role
import com.eldroid.facelock.data.model.User
import com.eldroid.facelock.data.repo.AuthRepository
import com.eldroid.facelock.databinding.ActivityLoginBinding
import com.eldroid.facelock.ui.admin.AdminActivity
import com.eldroid.facelock.ui.user.UserActivity
import com.eldroid.facelock.util.SessionManager
import com.eldroid.facelock.util.authMessage
import com.eldroid.facelock.util.validateEmail
import com.eldroid.facelock.util.toast
import com.eldroid.facelock.util.visible
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val authRepo = AuthRepository()
    private lateinit var session: SessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        session = SessionManager(this)

        // Already signed in -> skip straight to the right dashboard.
        authRepo.currentUid?.let { uid ->
            setLoading(true)
            lifecycleScope.launch {
                authRepo.loadProfile(uid)
                    .onSuccess { route(it) }
                    .onFailure { setLoading(false) }
            }
        }

        binding.btnLogin.setOnClickListener { attemptLogin() }
        binding.tvRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }
        binding.tvForgot.setOnClickListener { openForgotPassword() }
    }

    private fun attemptLogin() {
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString()

        // Sign-in only checks the shape of the input. It deliberately does not
        // apply the registration password policy — accounts created before the
        // policy tightened must still be able to get in and change it.
        val emailError = validateEmail(email)
        if (emailError != null) {
            binding.tilEmail.error = emailError
            return
        }
        if (password.isEmpty()) {
            binding.tilPassword.error = "Enter your password"
            return
        }
        binding.tilEmail.error = null
        binding.tilPassword.error = null

        setLoading(true)
        lifecycleScope.launch {
            authRepo.login(email, password)
                .onSuccess { user ->
                    if (!user.active) {
                        authRepo.logout()
                        setLoading(false)
                        toast("This account has been suspended. Contact your administrator.")
                    } else {
                        route(user)
                    }
                }
                .onFailure {
                    setLoading(false)
                    toast(it.authMessage("Sign in failed. Check your credentials."))
                }
        }
    }

    /** Hands off to the reset screen, carrying whatever email is already typed. */
    private fun openForgotPassword() {
        startActivity(
            Intent(this, ForgotPasswordActivity::class.java)
                .putExtra(
                    ForgotPasswordActivity.EXTRA_EMAIL,
                    binding.etEmail.text.toString().trim()
                )
        )
    }

    /** Role-based routing: admins and security go to the dashboard, users to their locker. */
    private fun route(user: User) {
        session.role = user.roleEnum
        session.fullName = user.fullName
        session.lockerId = user.lockerId

        val target = when (user.roleEnum) {
            Role.ADMIN, Role.SECURITY -> AdminActivity::class.java
            Role.USER -> UserActivity::class.java
        }
        startActivity(Intent(this, target))
        finish()
    }

    private fun setLoading(loading: Boolean) {
        binding.progress.visible(loading)
        binding.btnLogin.isEnabled = !loading
        binding.tvRegister.isEnabled = !loading
        binding.btnLogin.text =
            if (loading) getString(R.string.signing_in) else getString(R.string.sign_in)
    }
}
