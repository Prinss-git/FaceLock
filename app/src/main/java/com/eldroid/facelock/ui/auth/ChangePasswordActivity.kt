package com.eldroid.facelock.ui.auth

import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.activity.addCallback
import com.eldroid.facelock.R
import com.eldroid.facelock.data.model.Role
import com.eldroid.facelock.data.repo.AuthRepository
import com.eldroid.facelock.data.repo.UserRepository
import com.eldroid.facelock.ui.admin.AdminActivity
import com.eldroid.facelock.ui.user.UserActivity
import com.eldroid.facelock.databinding.ActivityChangePasswordBinding
import com.eldroid.facelock.util.PasswordPolicy
import com.eldroid.facelock.util.SessionManager
import com.eldroid.facelock.util.authMessage
import com.eldroid.facelock.util.render
import com.eldroid.facelock.util.toast
import com.eldroid.facelock.util.visible
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch

/**
 * In-app password change for the signed-in user.
 *
 * The current password is required — the repository re-authenticates with it
 * before Firebase will accept the update — and the new one must satisfy the
 * same policy the registration screen enforces.
 */
class ChangePasswordActivity : AppCompatActivity() {

    companion object {
        /**
         * Set after an admin-issued reset. The screen then cannot be dismissed
         * and, once done, continues into the app instead of going back.
         */
        const val EXTRA_FORCED = "extra_forced"
    }

    private lateinit var binding: ActivityChangePasswordBinding
    private val authRepo = AuthRepository()
    private val userRepo = UserRepository()

    private val forced by lazy { intent.getBooleanExtra(EXTRA_FORCED, false) }
    private var submitted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChangePasswordBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val email = authRepo.currentEmail
        if (email == null) {
            toast("Sign in again to change your password.")
            finish()
            return
        }
        binding.tvAccountEmail.text = "Signed in as $email"

        if (forced) applyForcedMode() else binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnSave.setOnClickListener { attemptChange() }
        binding.passwordStrength.root.visible(false)

        binding.tvForgotCurrent.setOnClickListener {
            startActivity(
                Intent(this, ForgotPasswordActivity::class.java)
                    .putExtra(ForgotPasswordActivity.EXTRA_EMAIL, email)
            )
        }

        binding.etNew.doAfterTextChanged {
            renderStrength()
            if (submitted) revalidate()
        }
        listOf(binding.etCurrent, binding.etConfirm).forEach { field ->
            field.doAfterTextChanged { if (submitted) revalidate() }
        }

        binding.etConfirm.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                attemptChange()
                true
            } else false
        }
    }

    /**
     * There is no way past this screen except setting a password or signing
     * out, so the toolbar offers sign-out rather than back, and the system
     * back gesture is ignored.
     */
    private fun applyForcedMode() {
        binding.toolbar.setTitle(R.string.forced_change_title)
        binding.toolbar.setNavigationIcon(R.drawable.ic_logout)
        binding.toolbar.navigationContentDescription = getString(R.string.action_sign_out)
        binding.toolbar.setNavigationOnClickListener { signOut() }
        binding.tvAccountEmail.text = getString(R.string.forced_change_body)
        binding.tvForgotCurrent.visible(false)

        onBackPressedDispatcher.addCallback(this) {
            signOut()
        }
    }

    private fun signOut() {
        authRepo.logout()
        SessionManager(this).clear()
        startActivity(Intent(this, LoginActivity::class.java))
        finishAffinity()
    }

    // --------------------------------------------------------- validation ----

    private val current get() = binding.etCurrent.text.toString()
    private val newPass get() = binding.etNew.text.toString()
    private val confirm get() = binding.etConfirm.text.toString()

    /** Own name and email are rejected inside the new password. */
    private val personalNames: List<String> by lazy {
        SessionManager(this).fullName.split(" ").filter { it.isNotBlank() }
    }

    private fun newPasswordError(): String? {
        val policyError = PasswordPolicy.firstError(
            password = newPass,
            email = authRepo.currentEmail.orEmpty(),
            names = personalNames
        )
        return when {
            policyError != null -> policyError
            newPass == current -> "Your new password must be different from your current one."
            else -> null
        }
    }

    private fun revalidate(): Boolean {
        val errors = linkedMapOf<TextInputLayout, String?>(
            binding.tilCurrent to
                if (current.isEmpty()) "Enter your current password" else null,
            binding.tilNew to newPasswordError(),
            binding.tilConfirm to when {
                confirm.isEmpty() -> "Re-enter your new password"
                confirm != newPass -> "Passwords do not match"
                else -> null
            }
        )

        errors.forEach { (layout, message) ->
            layout.error = message
            layout.isErrorEnabled = message != null
        }
        return errors.values.all { it == null }
    }

    private fun renderStrength() {
        val check = PasswordPolicy.check(
            password = newPass,
            email = authRepo.currentEmail.orEmpty(),
            names = personalNames
        )
        binding.passwordStrength.render(check, newPass)
    }

    // ------------------------------------------------------------- submit ----

    private fun attemptChange() {
        submitted = true
        if (!revalidate()) return

        setLoading(true)
        lifecycleScope.launch {
            authRepo.changePassword(current, newPass)
                .onSuccess { onChanged() }
                .onFailure { failure ->
                    setLoading(false)
                    val message = failure.authMessage("Could not update your password.")
                    if (message.contains("current password", ignoreCase = true) ||
                        message.contains("Incorrect email or password", ignoreCase = true)
                    ) {
                        binding.tilCurrent.error = "Your current password is incorrect."
                    } else {
                        toast(message)
                    }
                }
        }
    }

    /**
     * Firebase keeps this device signed in after an update, so there is no need
     * to bounce the user back to the login screen.
     */
    private fun onChanged() {
        setLoading(false)
        // Clear the forced-change flag so the next sign-in goes straight in.
        authRepo.currentUid?.let { uid ->
            lifecycleScope.launch {
                userRepo.updateUser(uid, mapOf("mustChangePassword" to false))
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Password updated")
            .setMessage(
                "Your password has been changed. Other devices signed in to this " +
                    "account will need the new password."
            )
            .setPositiveButton("Done") { _, _ -> if (forced) continueIntoApp() else finish() }
            .setCancelable(false)
            .show()
    }

    /** Forced mode arrives before the dashboard, so it has to open it. */
    private fun continueIntoApp() {
        val target = when (SessionManager(this).role) {
            Role.ADMIN, Role.SECURITY -> AdminActivity::class.java
            Role.USER -> UserActivity::class.java
        }
        startActivity(Intent(this, target))
        finishAffinity()
    }

    private fun setLoading(loading: Boolean) {
        binding.progress.visible(loading)
        binding.btnSave.isEnabled = !loading
        binding.btnSave.text = if (loading) "Updating…" else "Update password"
    }
}
