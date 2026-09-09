package com.eldroid.facelock.ui.auth

import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.eldroid.facelock.R
import com.eldroid.facelock.data.model.Role
import com.eldroid.facelock.data.repo.AuthRepository
import com.eldroid.facelock.databinding.ActivityRegisterBinding
import com.eldroid.facelock.ui.user.FaceEnrollActivity
import com.eldroid.facelock.util.PasswordPolicy
import com.eldroid.facelock.util.SessionManager
import com.eldroid.facelock.util.authMessage
import com.eldroid.facelock.util.render
import com.eldroid.facelock.util.toast
import com.eldroid.facelock.util.validateEmail
import com.eldroid.facelock.util.validateName
import com.eldroid.facelock.util.visible
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch

/**
 * Self-registration always creates a plain USER account.
 * Admin and security roles are granted only by an existing admin.
 */
class RegisterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterBinding
    private val authRepo = AuthRepository()

    /** Errors only appear after the first submit, so typing isn't nagged at. */
    private var submitted = false

    /** Set by [revalidate]; the field the user should be taken back to. */
    private var firstInvalid: TextInputLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnRegister.setOnClickListener { attemptRegister() }

        binding.passwordStrength.root.visible(false)

        // Live feedback: the strength meter always, field errors once submitted.
        binding.etPassword.doAfterTextChanged {
            renderStrength()
            if (submitted) revalidate()
        }
        listOf(
            binding.etFirstName, binding.etLastName,
            binding.etEmail, binding.etConfirm
        ).forEach { field ->
            field.doAfterTextChanged { if (submitted) revalidate() }
        }

        binding.etConfirm.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                attemptRegister()
                true
            } else false
        }
    }

    // --------------------------------------------------------- validation ----

    private data class Form(
        val first: String,
        val last: String,
        val email: String,
        val pass: String,
        val confirm: String
    )

    private fun readForm() = Form(
        first = binding.etFirstName.text.toString().trim(),
        last = binding.etLastName.text.toString().trim(),
        email = binding.etEmail.text.toString().trim(),
        pass = binding.etPassword.text.toString(),
        confirm = binding.etConfirm.text.toString()
    )

    /**
     * Validates every field at once so the user sees all of the problems in one
     * pass instead of fixing them one submit at a time.
     *
     * @return true when the form is clean.
     */
    private fun revalidate(): Boolean {
        val form = readForm()

        val errors = linkedMapOf<TextInputLayout, String?>(
            binding.tilFirstName to validateName(form.first, "First name"),
            binding.tilLastName to validateName(form.last, "Last name"),
            binding.tilEmail to validateEmail(form.email),
            binding.tilPassword to PasswordPolicy.firstError(
                password = form.pass,
                email = form.email,
                names = listOf(form.first, form.last)
            ),
            binding.tilConfirm to when {
                form.confirm.isEmpty() -> "Re-enter your password"
                form.confirm != form.pass -> "Passwords do not match"
                else -> null
            }
        )

        errors.forEach { (layout, message) ->
            layout.error = message
            layout.isErrorEnabled = message != null
        }
        firstInvalid = errors.entries.firstOrNull { it.value != null }?.key
        return firstInvalid == null
    }

    private fun renderStrength() {
        val form = readForm()
        val check = PasswordPolicy.check(
            password = form.pass,
            email = form.email,
            names = listOf(form.first, form.last)
        )
        binding.passwordStrength.render(check, form.pass)
    }

    // ------------------------------------------------------------- submit ----

    private fun attemptRegister() {
        submitted = true
        if (!revalidate()) {
            // Send the user straight to the first problem.
            firstInvalid?.editText?.requestFocus()
            return
        }

        val form = readForm()
        setLoading(true)
        lifecycleScope.launch {
            authRepo.register(form.first, form.last, form.email, form.pass, Role.USER)
                .onSuccess { user ->
                    SessionManager(this@RegisterActivity).apply {
                        role = user.roleEnum
                        fullName = user.fullName
                    }
                    toast("Account created. Let's enroll your face.")
                    startActivity(Intent(this@RegisterActivity, FaceEnrollActivity::class.java))
                    finishAffinity()
                }
                .onFailure { error ->
                    setLoading(false)
                    val message = error.authMessage("Registration failed. Please try again.")
                    // Collisions and weak passwords belong on the field, not a toast.
                    when {
                        message.contains("already exists", ignoreCase = true) ->
                            binding.tilEmail.error = message
                        message.contains("password", ignoreCase = true) ->
                            binding.tilPassword.error = message
                        else -> toast(message)
                    }
                }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.progress.visible(loading)
        binding.btnRegister.isEnabled = !loading
        binding.btnRegister.text =
            if (loading) "Creating account…" else getString(R.string.create_account)
    }
}
